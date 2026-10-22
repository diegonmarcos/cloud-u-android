// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.autocomplete

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.autofill.AutocompleteContract.Row as AcRow
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.panel.PanelLayoutParams
import helium314.keyboard.keyboard.panel.drawableBackground
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.autocomplete.AutocompleteEngine
import helium314.keyboard.latin.autocomplete.AutocompletePage
import helium314.keyboard.latin.autocomplete.AutocompleteStore
import helium314.keyboard.latin.autocomplete.AutocompleteSync
import helium314.keyboard.latin.autocomplete.AutocompleteUnlock
import helium314.keyboard.latin.autocomplete.AutocompleteUnlockActivity
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.stateColor
import helium314.keyboard.latin.utils.toKitPalette

/**
 * #1103 The Autocomplete panel: its own view over its own engine ([AutocompleteStore], filled by [AutocompleteSync]
 * from Cloud Account), with its own first-row sub-page tabs ([AutocompletePage]: IDs, Addresses, URLs, Secrets).
 * Clipboard and Autocomplete share the visual structure only: the strip that carries the close key, the bottom row and
 * the key-shaped row background; no state, no data and no link points from one to the other. Tap pastes, long-press
 * copies; the Secrets page is masked until the screen lock has been confirmed.
 */
@SuppressLint("CustomViewStyleable")
class AutocompleteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : KitImeComposeView(context, attrs), View.OnClickListener {

    private class Look(
        val typeface: Typeface?,
        val textColor: Int,
        val textSizePx: Float,
        val layout: PanelLayoutParams,
        val keyboardWidth: Int,
        val keyboardHeight: Int,
        val padLeft: Int,
        val padRight: Int,
    )

    private class BottomRow(val editorInfo: EditorInfo, val listener: KeyboardActionListener)

    private val keyBackgroundId: Int
    private var look by mutableStateOf<Look?>(null)
    private var page by mutableStateOf(AutocompletePage.IDS)
    private var rows by mutableStateOf<List<AcRow>>(emptyList())
    /** The string shown instead of an empty page: why there is nothing (no account, no grant) or that there is nothing yet. */
    private var emptyReason by mutableIntStateOf(R.string.autocomplete_empty)
    private var opening by mutableIntStateOf(0)
    /** Bumped when the unlock state may have changed, so masked rows are drawn again. */
    private var unlockTick by mutableIntStateOf(0)
    private var bottomRow by mutableStateOf<BottomRow?>(null)
    private var bottomRowView: MainKeyboardView? = null
    private var bottomRowConfigured: BottomRow? = null
    private var closeKey: ImageButton? = null

    lateinit var keyboardActionListener: KeyboardActionListener

    init {
        @SuppressLint("UseKtx") // the style lookup does not work through the ktx helper
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView,
            R.attr.clipboardHistoryViewStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
        fitsSystemWindows = true
    }

    override fun palette(): KitPalette {
        opening // read: a new opening re-reads the theme's colours
        return Settings.getValues().mColors.toKitPalette()
    }

    @Composable
    override fun Body() {
        val look = look ?: return
        val density = LocalDensity.current
        val ctx = LocalContext.current
        val colors = Settings.getValues().mColors
        val textColor = Color(look.textColor)
        val fontFamily = look.typeface?.let { FontFamily(it) }
        val keyBackground = remember(opening) {
            ContextCompat.getDrawable(ctx, keyBackgroundId)?.mutate()?.also { colors.setColor(it, ColorType.KEY_BACKGROUND) }
        }
        fun dp(px: Int): Dp = with(density) { px.toDp() }
        // the Secrets page redraws when the unlock lapses
        LaunchedEffect(page) { if (page.masked) while (true) { kotlinx.coroutines.delay(700); unlockTick++ } }

        Box(Modifier.size(dp(look.keyboardWidth), dp(look.keyboardHeight))) {
            Column(Modifier.fillMaxSize()) {
                Tabs(Color(colors.get(ColorType.KEY_TEXT)), fontFamily)
                Box(Modifier.fillMaxWidth().height(dp(look.layout.listHeight))) {
                    PageRows(look, keyBackground, textColor, fontFamily)
                }
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { MainKeyboardView(it, null).also { view -> bottomRowView = view } },
                    update = { view -> bottomRow?.let { setupBottomRow(view, it) } },
                )
            }
        }
    }

    /** The sub-page tabs: this panel's own first row. */
    @Composable
    private fun Tabs(keyText: Color, fontFamily: FontFamily?) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            AutocompletePage.entries.forEach { p ->
                Text(
                    text = p.title,
                    color = keyText,
                    fontFamily = fontFamily,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .alpha(if (p == page) 1f else 0.5f)
                        .clickable { page = p; unlockTick++ }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    @Composable
    private fun PageRows(look: Look, keyBackground: Drawable?, textColor: Color, fontFamily: FontFamily?) {
        val density = LocalDensity.current
        unlockTick // read: a finished unlock redraws the masks
        val unlocked = unlockedNow()
        val shown = AutocompleteEngine.rows(rows, page)
        Column(Modifier.fillMaxSize().padding(horizontal = with(density) { look.padLeft.toDp() })) {
            if (page.masked) {
                Text(
                    text = stringResource(if (unlocked) R.string.autocomplete_hide else R.string.autocomplete_reveal),
                    color = textColor, fontFamily = fontFamily, fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        if (unlocked) { AutocompleteUnlock.lock(); unlockTick++ } else AutocompleteUnlockActivity.start(context)
                    }.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            if (shown.isEmpty()) {
                Text(stringResource(emptyReason), color = textColor.copy(alpha = 0.6f), fontFamily = fontFamily, fontSize = 13.sp,
                    modifier = Modifier.padding(8.dp))
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { row -> RowItem(row, unlocked, look, keyBackground, textColor, fontFamily) }
            }
        }
    }

    /** One row: its label and its value, masked while it is a secret and the device is locked. Tap pastes, long-press copies. */
    @Composable
    private fun RowItem(row: AcRow, unlocked: Boolean, look: Look, keyBackground: Drawable?, textColor: Color, fontFamily: FontFamily?) {
        val density = LocalDensity.current
        var pressed by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp).drawableBackground(keyBackground, pressed)
                .pointerInput(row) {
                    detectTapGestures(
                        onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                        onTap = { use(row, copy = false) },
                        onLongPress = { use(row, copy = true) },
                    )
                }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val size = with(density) { look.textSizePx.toSp() }
            Text(row.label, color = textColor.copy(alpha = 0.7f), fontFamily = fontFamily, fontSize = size, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.35f))
            Text(AutocompleteEngine.shown(row, unlocked), color = textColor, fontFamily = fontFamily, fontSize = size, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.65f))
        }
    }

    private fun unlockedNow() = AutocompleteUnlock.isUnlocked(SystemClock.elapsedRealtime())

    private fun use(row: AcRow, copy: Boolean) {
        when (AutocompleteEngine.access(row, unlockedNow())) {
            AutocompleteEngine.Access.NEEDS_UNLOCK -> AutocompleteUnlockActivity.start(context)
            AutocompleteEngine.Access.USE -> if (copy) copyToClipboard(row) else paste(row)
        }
    }

    private fun paste(row: AcRow) {
        keyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, true, HapticEvent.KEY_PRESS)
        keyboardActionListener.onTextInput(row.value)
        keyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false)
        if (Settings.getValues().mAlphaAfterClipHistoryEntry)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    /** A secret goes to the clipboard marked sensitive, which keeps it out of the clipboard history (ClipboardHistoryManager). */
    private fun copyToClipboard(row: AcRow) {
        val clip = ClipData.newPlainText(row.label, row.value)
        if (AutocompleteEngine.isSensitive(row) && Build.VERSION.SDK_INT >= 33)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(context, row.label, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun setupBottomRow(view: MainKeyboardView, request: BottomRow) {
        if (bottomRowConfigured === request) return
        bottomRowConfigured = request
        view.setKeyboardActionListener(request.listener)
        PointerTracker.switchTo(view)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, request.editorInfo)
        view.setKeyboard(kls.getKeyboard(KeyboardId.ELEMENT_CLIPBOARD_BOTTOM_ROW))
    }

    /** The strip above the panel carries the close key alone: this panel has no toolbar of its own. */
    private fun rebuildStripKeys() {
        val colors = Settings.getValues().mColors
        val strip = KeyboardSwitcher.getInstance().clipboardStrip
        val stripBackground = colors.stateColor(ColorType.STRIP_BACKGROUND)
        strip.setBackgroundColor(stripBackground)
        (strip.parent as? View)?.setBackgroundColor(stripBackground)
        for (i in strip.childCount - 1 downTo 0) if (strip.getChildAt(i) is ImageButton) strip.removeViewAt(i)
        val button = createToolbarKey(context, ToolbarKey.CLOSE_HISTORY)
        button.layoutParams = LinearLayout.LayoutParams(
            resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width), LinearLayout.LayoutParams.MATCH_PARENT)
        strip.addView(button)
        button.setOnClickListener(this)
        colors.setColor(button, ColorType.TOOL_BAR_KEY)
        colors.setBackground(button, ColorType.STRIP_BACKGROUND)
        closeKey = button
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startAutocomplete(keyVisualAttr: KeyVisualAttributes?, editorInfo: EditorInfo, listener: KeyboardActionListener) {
        keyboardActionListener = listener
        rebuildStripKeys()
        val settings = Settings.getInstance()
        val layout = PanelLayoutParams(context)
        val params = KeyDrawParams()
        params.updateParams(layout.bottomRowKeyboardHeight, keyVisualAttr)
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }
        val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
        val keyboardAttr = context.obtainStyledAttributes(null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
        val leftPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
            keyboardWidth, keyboardWidth, 0f) * settings.current.mSidePaddingScale).toInt()
        val rightPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
            keyboardWidth, keyboardWidth, 0f) * settings.current.mSidePaddingScale).toInt()
        keyboardAttr.recycle()
        look = Look(
            typeface = params.mTypeface,
            textColor = params.mTextColor,
            textSizePx = params.mLabelSize.toFloat(),
            layout = layout,
            keyboardWidth = keyboardWidth,
            keyboardHeight = ResourceUtils.getSecondaryKeyboardHeight(resources, Settings.getValues()),
            padLeft = leftPadding,
            padRight = rightPadding,
        )
        page = AutocompletePage.IDS
        AutocompleteUnlock.lock()
        opening++
        val request = BottomRow(editorInfo, listener)
        bottomRow = request
        bottomRowView?.let { setupBottomRow(it, request) }
        load()
    }

    /** Shows what the store holds at once, then asks Cloud Account (a local call) for newer rows. Off the main thread. */
    private fun load() {
        val app = context.applicationContext
        Thread {
            val store = AutocompleteStore.open(app)
            post { rows = store.all() }
            val outcome = AutocompleteSync.pull(app, store)
            val reason = when (outcome) {
                is AutocompleteSync.Outcome.Synced -> R.string.autocomplete_empty
                AutocompleteSync.Outcome.NoAccount -> R.string.autocomplete_no_account
                is AutocompleteSync.Outcome.Refused -> R.string.autocomplete_no_grant
            }
            post { emptyReason = reason; rows = store.all() }
        }.start()
    }

    /** Shows [p]; also what the CI screenshot job calls to reach each page. */
    fun showPage(p: AutocompletePage) { page = p; unlockTick++ }
    val currentPage: AutocompletePage get() = page
    val rowCount: Int get() = rows.size

    fun stopAutocomplete() {
        AutocompleteUnlock.lock() // the Secrets are masked again whenever the panel closes
        rows = emptyList()
        closeKey?.let { (it.parent as? ViewGroup)?.removeView(it) }
        closeKey = null
    }

    override fun onClick(view: View) {
        if (view.tag != ToolbarKey.CLOSE_HISTORY) return
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        keyboardActionListener.onCodeInput(getCodeForToolbarKey(ToolbarKey.CLOSE_HISTORY), Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }
}
