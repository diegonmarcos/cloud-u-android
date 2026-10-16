/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Image
import androidx.core.content.edit
import androidx.core.view.isGone
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripLayout.WordSlot
import helium314.keyboard.latin.utils.InlineAutofillUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.addSecondRowKey
import helium314.keyboard.latin.utils.defaultToolbarPref
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKeyLongClick
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getSecondRowToolbarKeys
import helium314.keyboard.latin.utils.isToolbarKeyActivated
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.removeFirst
import helium314.keyboard.latin.utils.removeSecondRowKey
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange
import helium314.keyboard.latin.utils.stateColor
import helium314.keyboard.latin.utils.toKitPalette
import helium314.keyboard.latin.utils.toolbarKeyDescription
import helium314.keyboard.latin.utils.toolbarKeyIcon
import helium314.keyboard.latin.utils.toolbarKeysOnOwnPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The suggestion strip: an inline-autofill row (only while Vault / the framework has something), a
 * toolbar row of paged keys, and a suggestions row of the three best words (plus the pinned
 * "second row" keys). Compose under the keyboard theme; the public surface LatinIME drives is the
 * one the View strip had.
 *
 * What stays a View is only what a View has to be: the inline-autofill surfaces another process
 * hands over, an external suggestion view (the clipboard chip), and the touch plumbing of the
 * more-suggestions panel, which slides a finger from the strip over the key surface — that is
 * decided in [onInterceptTouchEvent] on this host, exactly where it always was.
 */
@SuppressLint("InflateParams")
class SuggestionStripView(context: Context, attrs: AttributeSet?, defStyle: Int) :
    KitImeComposeView(context, attrs), OnSharedPreferenceChangeListener {

    /** Construct a [SuggestionStripView] for showing suggestions to be picked by the user. */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.suggestionStripViewStyle)

    interface Listener {
        fun pickSuggestionManually(word: SuggestedWordInfo?)
        fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)
        fun removeSuggestion(word: String?)
        fun removeExternalSuggestions()
        fun onSwipeDownOnToolbar()
    }

    /** One key of a toolbar row: what it is, the icon it draws (coloured by the theme) and its spoken name. */
    private class ToolbarKeyItem(val key: ToolbarKey, val icon: Drawable, val description: String)

    /** What takes the words' place in the suggestions row. */
    private sealed interface External {
        /** A view of someone else's (the clipboard chip), optionally followed by a close key. */
        class Hosted(val view: View, val closeButton: Boolean) : External

        /** The Cloud Account autofill chips of an empty field: text and what a tap does. */
        class Chips(val chips: List<Pair<String, () -> Unit>>) : External
    }

    private val style = SuggestionStripLayout.Style(context, attrs, defStyle)
    private val wordTextSizePx = context.resources.getDimension(R.dimen.config_suggestion_text_size)
    private val wordPaddingPx = 2 * context.resources.getDimensionPixelSize(R.dimen.config_suggestion_text_horizontal_padding)
    private val measurePaint = TextPaint().apply {
        isAntiAlias = true
        textSize = wordTextSizePx
        typeface = KeyboardTypeface.customTypeface() ?: Typeface.DEFAULT
    }

    // toolbar rows and their state (the first row pages, the second sits beside the words)
    private val toolbar = mutableStateListOf<ToolbarKeyItem>()
    private val secondRowKeys = mutableStateListOf<ToolbarKeyItem>()
    private var toolbarRowVisible by mutableStateOf(false)
    private var activationTick by mutableIntStateOf(0)
    private var direction = 1 // 1 if LTR, -1 if RTL

    // the suggestions row
    private var suggestedWords = SuggestedWords.getEmptyInstance()
    private var wordLayout by mutableStateOf(SuggestionStripLayout.Layout.EMPTY)
    private var stripWidthPx by mutableIntStateOf(0)
    private var startIndexOfMoreSuggestions = 0
    private var external by mutableStateOf<External?>(null)
    private var removeIconIndex by mutableIntStateOf(-1) // the word that shows its remove icon after a long press
    private var isExternalSuggestionVisible = false // Required to disable the more suggestions if other suggestions are visible
    // Autofill rows (a0_docs/eng-specs/autofill-3-tier.md): row 1 = Vault / inline autofill only, row 2 =
    // the keyboard's own (the suggestions row). SuggestionRows decides which show; nothing mixes them.
    private var inlineView by mutableStateOf<View?>(null)
    private var ownRowSuppressed by mutableStateOf(false)
    /** The toolbar row is always on (the two-row strip); kept so the slide-up gesture's old guard reads the same. */
    private var toolbarContainerVisible = true
    /**
     * The layout the toolbar row was last built for; [ensureToolbar] compares against it.
     * Declared ABOVE the init block that builds the row: Kotlin runs initialisers in text
     * order, so a declaration below it would reset this to null right after the build.
     */
    private var toolbarBuiltFor: ToolbarStatus.Layout? = null
    private var ready = false // the View's own constructor can reach onVisibilityChanged before the fields above exist

    private val enabledToolKeyBackground
        get() = Brush.radialGradient(
            listOf(Color(Settings.getValues().mColors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND) or -0x1000000), Color.Transparent),
            radius = context.resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) / 2.1f,
        )

    init {
        // the strip style's background drawable belongs to the View strip; the Compose strip paints its own
        background = null
        DEBUG_SUGGESTIONS = context.prefs().getBoolean(DebugSettings.PREF_SHOW_SUGGESTION_INFOS, Defaults.PREF_SHOW_SUGGESTION_INFOS)

        val mToolbarMode = if (isGone) ToolbarMode.HIDDEN else Settings.getValues().mToolbarMode
        if (mToolbarMode == ToolbarMode.TOOLBAR_KEYS) {
            setToolbarVisibility(true)
        }
        buildToolbar(mToolbarMode, !isGone, "init")
        ready = true
    }

    private fun toolbarLayout(mode: ToolbarMode) = context.prefs().let {
        ToolbarStatus.Layout(mode, getEnabledToolbarKeys(it), getSecondRowToolbarKeys(it))
    }

    /**
     * Icons the first row actually puts on screen when the strip is shown: a key counts only
     * if its row is visible and it holds a drawable with a size. Not the strip's OWN visibility -
     * it is legitimately GONE while the emoji or clipboard view is up, and counting that as
     * "nothing drawn" would rebuild a healthy row every time.
     */
    private fun drawnToolbarIcons(): Int {
        if (!toolbarRowVisible) return 0
        return toolbar.count { it.icon.intrinsicWidth > 0 && it.icon.intrinsicHeight > 0 }
    }

    /**
     * Rebuilds the toolbar row when it no longer matches the settings or draws nothing it
     * should (#776). Called from LatinIME on every onStartInputView and whenever the strip is
     * re-attached, i.e. on exactly the paths that used to leave a stale row standing. Every
     * rebuild is logged at WARN with its reason, so the guard can never hide the bug.
     */
    fun ensureToolbar(trigger: String) {
        val reason = measureToolbar(trigger) ?: return
        Log.w(TAG, "toolbar rebuild on $trigger: $reason (locked=${Settings.getValues().mIsLocked})")
        buildToolbar(Settings.getValues().mToolbarMode, true, "$trigger: $reason")
    }

    /**
     * Measures the row and records what [ensureToolbar] would do, WITHOUT doing it - the debug
     * API reads through this, so asking about an empty row never repairs it out of sight.
     */
    fun measureToolbar(trigger: String): String? {
        val now = toolbarLayout(Settings.getValues().mToolbarMode)
        val drawn = drawnToolbarIcons()
        val reason = ToolbarStatus.rebuildReason(toolbarBuiltFor, now, drawn)
        ToolbarStatus.checked(trigger, now.expected, drawn, reason)
        return reason
    }

    private fun buildToolbar(mode: ToolbarMode, withSecondRow: Boolean, reason: String) {
        val dropped = ArrayList<String>()
        toolbar.clear()
        secondRowKeys.clear()

        // toolbar keys setup
        //
        // Every mode but HIDDEN, because the two-row redesign changed what a
        // ToolbarMode means. Upstream's modes chose WHICH SINGLE ROW won, so
        // gating the toolbar on TOOLBAR_KEYS/EXPANDABLE was right there. Here
        // the toolbar has its own permanent row beside the suggestions, and
        // under SUGGESTION_STRIP that gate built zero keys, so the row
        // collapsed - taking Translate and Enhance with it, which is why
        // those bars looked broken when in fact they were never reachable.
        if (mode != ToolbarMode.HIDDEN) {
            buildRow(toolbar, getEnabledToolbarKeys(context.prefs()), dropped)
            if (toolbar.isEmpty()) {
                // A toolbar the user did not hide that builds nothing is a stored layout
                // that cannot be shown (every key disabled, unknown, or unbuildable). The
                // strip is rebuilt from that same stored layout on every restart, so left
                // alone it stays broken forever - nothing else ever rewrites the layout
                // pref except a TOOLBAR_LAYOUT_REVISION bump. Restoring the shipped row
                // here, and storing it, makes it a one-time event.
                Log.w(TAG, "toolbar built 0 keys from the stored layout, restoring the default row")
                context.prefs().edit { putString(Settings.PREF_TOOLBAR_KEYS, defaultToolbarPref) }
                buildRow(toolbar, getEnabledToolbarKeys(context.prefs()), dropped)
            }
        }
        // No mSuggestionStripHiddenPerUserSettings check. That flag means "the
        // SUGGESTIONS row is hidden" and is set for TOOLBAR_KEYS mode
        // (SettingsValues.java) - so in exactly the mode that shows the
        // toolbar, this was skipped and the second row stayed empty.
        // SETTINGS is its only member, which is why the Config icon went
        // missing with nothing else obviously wrong. An EMPTY second row is
        // not healed: unpinning every key is a choice the long-press offers.
        if (withSecondRow) {
            buildRow(secondRowKeys, getSecondRowToolbarKeys(context.prefs()), dropped)
        }

        // SuperApp two-row strip: the toolbar occupies its own dedicated row (always visible
        // alongside the suggestions row). Collapse that row only when there are no toolbar keys
        // to show (e.g. ToolbarMode.HIDDEN) so we don't reserve an empty row.
        toolbarRowVisible = toolbar.isNotEmpty()
        if (toolbar.isNotEmpty()) setToolbarVisibility(true)

        val layout = toolbarLayout(mode)
        toolbarBuiltFor = layout
        val built = toolbar.map { it.key.name }
        val drawn = drawnToolbarIcons()
        ToolbarStatus.built(this, reason, layout, built, dropped, drawn)
        Log.i(TAG, "toolbar built ($reason): mode=$mode expected=${layout.expected} built=${built.size} drawn=$drawn " +
            "second=${secondRowKeys.size} dropped=$dropped locked=${Settings.getValues().mIsLocked}")

        updateKeys()
    }

    private lateinit var listener: Listener
    private val moreSuggestionsView = MoreSuggestionsView(context).apply {
        val slidingListener = object : SimpleOnGestureListener() {
            override fun onScroll(down: MotionEvent?, me: MotionEvent, deltaX: Float, deltaY: Float): Boolean {
                if (down == null) return false
                val dy = me.y - down.y
                val dx = me.x - down.x

                if (Settings.getValues().mToolbarSwipeDownToHide && dy > 50.dpToPx(resources) && abs(dy) > abs(dx)) {
                    listener.onSwipeDownOnToolbar()
                    return true
                }

                return if (!isExternalSuggestionVisible && !toolbarContainerVisible && deltaY > 0 && dy < (-10).dpToPx(resources)) showMoreSuggestions()
                else false
            }
        }
        gestureDetector = GestureDetector(context, slidingListener)
    }

    // public stuff

    val isShowingMoreSuggestionPanel get() = moreSuggestionsView.isShowingInParent

    /** A connection back to the input method. */
    fun setListener(newListener: Listener, inputView: View) {
        listener = newListener
        moreSuggestionsView.listener = newListener
        moreSuggestionsView.mainKeyboardView = inputView.requireViewById(R.id.keyboard_view)
    }

    fun setRtl(isRtlLanguage: Boolean) {
        val newLayoutDirection: Int
        if (!Settings.getValues().mVarToolbarDirection)
            newLayoutDirection = LAYOUT_DIRECTION_LOCALE
        else {
            newLayoutDirection = if (isRtlLanguage) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
            direction = if (isRtlLanguage) -1 else 1
        }
        layoutDirection = newLayoutDirection
    }

    fun setToolbarVisibility(toolbarVisible: Boolean) {
        // SuperApp two-row strip: the toolbar and the suggestions live on SEPARATE rows, so they
        // are BOTH shown at once instead of mutually toggling. The toolbar row stays visible
        // regardless of the requested value (so auto-show / auto-hide-toolbar callers in LatinIME
        // become no-ops for the toolbar), and the suggestions row is always visible too. The
        // toolbar row itself is collapsed only when there are no toolbar keys (HIDDEN mode).
        toolbarContainerVisible = true
    }

    fun setSuggestions(suggestions: SuggestedWords, isRtlLanguage: Boolean) {
        clear()
        setRtl(isRtlLanguage)
        suggestedWords = suggestions
        relayout()
        isExternalSuggestionVisible = false
        updateKeys()
    }

    fun setExternalSuggestionView(view: View?, addCloseButton: Boolean) {
        clear()
        isExternalSuggestionVisible = true
        external = view?.let { External.Hosted(it, addCloseButton) }

        if (Settings.getValues().mAutoHideToolbar) setToolbarVisibility(false)
    }

    /**
     * Row 1: the inline autofill suggestions the framework handed over (Cloud Vault's logins, codes,
     * cards), in their own horizontally scrolling row with the key glyph in front. null = none: the
     * row collapses to zero height. Never touches row 2.
     */
    fun setInlineSuggestions(view: View?) {
        inlineView = view
    }

    val hasInlineSuggestions get() = inlineView != null

    /**
     * Row 2 off (Suppression Mode: password, one-time code, card, no-personalised-learning fields):
     * the keyboard shows none of its own chips, so only row 1 — Vault's — can show.
     */
    fun setOwnCandidatesSuppressed(suppressed: Boolean) {
        ownRowSuppressed = suppressed
        if (suppressed) { wordLayout = SuggestionStripLayout.Layout.EMPTY; external = null; dismissMoreSuggestionsPanel() }
    }

    /**
     * Row 2, Normal mode: the Cloud Account SOT chips for this field (a profile switcher, values, snippets),
     * styled like word suggestions, scrolling on their own. Each chip carries its own tap action.
     */
    fun setAutofillCandidates(chips: List<Pair<String, () -> Unit>>) {
        if (ownRowSuppressed || chips.isEmpty()) return
        clear()
        isExternalSuggestionVisible = true
        external = External.Chips(chips)
        if (Settings.getValues().mAutoHideToolbar) setToolbarVisibility(false)
    }

    fun setMoreSuggestionsHeight(remainingHeight: Int) {
        style.setMoreSuggestionsHeight(remainingHeight)
    }

    fun dismissMoreSuggestionsPanel() {
        moreSuggestionsView.dismissPopupKeysPanel()
    }

    // overrides: necessarily public, but not used from outside

    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(key) { activationTick++ }
        if (key == Settings.PREF_ALWAYS_INCOGNITO_MODE)
            GlobalScope.launch { delay(10); withContext(Dispatchers.Main) { updateKeys() } }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // workaround for a bug with inline suggestions views that just keep showing up otherwise, https://github.com/HeliBorg/HeliBoard/pull/386
        // Same for row 1: inline suggestions are surfaces owned by the autofill service's process.
        if (ready && changedView === this) inlineView?.visibility = if (visibility == VISIBLE) VISIBLE else GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissMoreSuggestionsPanel()
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        // Don't populate accessibility event with suggested words and voice key.
        return true
    }

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        // Detecting sliding up finger to show MoreSuggestionsView.
        return moreSuggestionsView.shouldInterceptTouchEvent(motionEvent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
        moreSuggestionsView.touchEvent(motionEvent)
        return true
    }

    // ---- what a tap, a long press and a pref change do ------------------------------------------------

    private fun feedback(event: HapticEvent) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, event)
    }

    private fun onToolbarKeyClick(item: ToolbarKeyItem) {
        feedback(HapticEvent.KEY_PRESS)
        val code = getCodeForToolbarKey(item.key)
        if (code != KeyCode.UNSPECIFIED) {
            Log.d(TAG, "click toolbar key ${item.key}")
            listener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
        }
    }

    private fun onToolbarKeyLongClick(item: ToolbarKeyItem, inSecondRow: Boolean) {
        AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
        val tag = item.key
        if (!Settings.getValues().mQuickPinToolbarKeys || inSecondRow) {
            val longClickCode = getCodeForToolbarKeyLongClick(tag)
            if (longClickCode != KeyCode.UNSPECIFIED) {
                listener.onCodeInput(longClickCode, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
            }
        } else {
            val pinned = secondRowKeys.firstOrNull { it.key == tag }
            if (pinned == null) {
                addKeyToSecondRowKeys(tag)
                addSecondRowKey(context.prefs(), tag)
            } else {
                removeSecondRowKey(context.prefs(), tag)
                secondRowKeys.remove(pinned)
            }
        }
    }

    private fun onWordClick(slot: WordSlot) {
        feedback(HapticEvent.KEY_PRESS)
        // the index of the word in the suggested words is what the View strip kept as the word's tag
        val index = slot.indexInSuggestedWords
        if (index >= suggestedWords.size()) return
        listener.pickSuggestionManually(suggestedWords.getInfo(index))
    }

    private fun onWordLongClick(slot: WordSlot) {
        feedback(HapticEvent.KEY_LONG_PRESS)
        onLongClickSuggestion(slot)
    }

    private fun onLongClickSuggestion(slot: WordSlot) {
        var showIcon = true
        val index = slot.indexInSuggestedWords
        if (index < suggestedWords.size()) {
            val type = suggestedWords.getInfo(index).mSourceDict
            if (type == Dictionary.DICTIONARY_USER_TYPED || type == Dictionary.DICTIONARY_HARDCODED)
                showIcon = false
        }
        removeIconIndex = if (showIcon) index else -1
        if (DebugFlags.DEBUG_ENABLED && (isShowingMoreSuggestionPanel || !showMoreSuggestions())) {
            showSourceDict(slot)
            return
        }
        showMoreSuggestions()
    }

    private fun showMoreSuggestions(): Boolean {
        if (suggestedWords.size() <= startIndexOfMoreSuggestions) {
            return false
        }
        return moreSuggestionsView.show(suggestedWords, startIndexOfMoreSuggestions, this, style)
    }

    private fun showSourceDict(slot: WordSlot) {
        val index = slot.indexInSuggestedWords
        if (index >= suggestedWords.size()) return
        val info = suggestedWords.getInfo(index)
        if (info.word != slot.label) return

        val text = info.mSourceDict.mDictType + ":" + info.mSourceDict.mLocale
        if (isShowingMoreSuggestionPanel) {
            moreSuggestionsView.dismissPopupKeysPanel()
        }
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    private fun removeSuggestion(slot: WordSlot) {
        val word = slot.label
        listener.removeSuggestion(word)
        moreSuggestionsView.dismissPopupKeysPanel()
        // show suggestions, but without the removed word
        val suggestedWordInfos = ArrayList<SuggestedWordInfo>()
        for (i in 0..<suggestedWords.size()) {
            val info = suggestedWords.getInfo(i)
            if (info.word != word) suggestedWordInfos.add(info)
        }
        suggestedWords.mRawSuggestions?.removeFirst { it.word == word }

        val newSuggestedWords = SuggestedWords(
            suggestedWordInfos, suggestedWords.mRawSuggestions, suggestedWords.typedWordInfo, suggestedWords.mTypedWordValid,
            suggestedWords.mWillAutoCorrect, suggestedWords.mIsObsoleteSuggestions, suggestedWords.mInputStyle, suggestedWords.mSequenceNumber
        )
        setSuggestions(newSuggestedWords, direction != 1)

        // Show the toolbar if no suggestions are left and the "Auto show toolbar" setting is enabled
        if (this.suggestedWords.isEmpty && Settings.getValues().mAutoShowToolbar) {
            setToolbarVisibility(true)
        }
    }

    private fun clear() {
        external = null
        removeIconIndex = -1
        dismissMoreSuggestionsPanel()
    }

    /** Decides what the row shows for the current suggested words, at the width the row has now. */
    private fun relayout() {
        wordLayout = SuggestionStripLayout.layout(
            style, suggestedWords, stripWidthPx, wordPaddingPx, style.dividerWidth, measurePaint, DEBUG_SUGGESTIONS
        )
        startIndexOfMoreSuggestions = wordLayout.startIndexOfMoreSuggestions
    }

    fun updateVoiceKey() {
        // SuperApp: always show the VOICE toolbar key. Upstream hides it when no
        // voice-input IME is installed (mShowsVoiceInputKey -> InputAttributes ->
        // !isShortcutImeReady), which looked like a "missing icon". We keep the
        // key visible; tapping launches the system voice input (a voice IME such
        // as Sayboard/FUTO must be installed for it to actually transcribe).
        // Nothing to do: the key is part of the row whenever it is enabled.
    }

    private fun updateKeys() {
        updateVoiceKey()
        // SuperApp two-row strip: the toolbar is fixed on its own always-on row, so there is no
        // expand/collapse arrow to re-evaluate here.
        isExternalSuggestionVisible = false
    }

    private fun addKeyToSecondRowKeys(secondRowKey: ToolbarKey) {
        val original = toolbar.firstOrNull { it.key == secondRowKey } ?: return
        // its own icon instance: a drawable carries the state (activated or not) it is drawn in
        val icon = toolbarKeyIcon(context, secondRowKey) ?: return
        Settings.getValues().mColors.setColor(icon, ColorType.TOOL_BAR_KEY)
        secondRowKeys.add(ToolbarKeyItem(secondRowKey, icon, original.description))
    }

    /**
     * Builds [keys] into [row] one at a time, so a key that cannot be built is the only thing
     * lost — the try/catch is what keeps one bad entry from aborting init() and with it the
     * whole strip. A key without an icon is treated as unbuildable on purpose: a null drawable
     * would draw a blank square that still fires on tap — worse than an absent key, and
     * invisible to any try/catch.
     */
    private fun buildRow(row: MutableList<ToolbarKeyItem>, keys: List<ToolbarKey>, dropped: MutableList<String>) {
        val colors = Settings.getValues().mColors
        for (key in keys) {
            try {
                val icon = toolbarKeyIcon(context, key) ?: throw IllegalStateException("the icon set has no icon named ${key.name}")
                colors.setColor(icon, ColorType.TOOL_BAR_KEY)
                row.add(ToolbarKeyItem(key, icon, toolbarKeyDescription(context, key)))
            } catch (t: Throwable) {
                dropped.add("${key.name}: ${t.message}")
                Log.e(TAG, "toolbar key $key dropped, it cannot be built", t)
            }
        }
    }

    // ---- drawing --------------------------------------------------------------------------------------

    @Composable
    override fun Body() {
        val colors = Settings.getValues().mColors
        val stripHeight = dimensionResource(R.dimen.config_suggestions_strip_height)
        val pressedColor = Color(remember(colors) { colors.stateColor(ColorType.STRIP_BACKGROUND, pressed = true) })
        val background = Color(remember(colors) { colors.stateColor(ColorType.STRIP_BACKGROUND) })
        Column(Modifier.fillMaxWidth().background(background)) {
            inlineView?.let { InlineRow(it, stripHeight) }
            if (toolbarRowVisible) {
                Row(Modifier.fillMaxWidth().height(stripHeight)) { ToolbarRow(pressedColor) }
            }
            if (!ownRowSuppressed) {
                Row(Modifier.fillMaxWidth().height(stripHeight)) {
                    Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged {
                        if (it.width != stripWidthPx) {
                            stripWidthPx = it.width
                            relayout()
                        }
                    }, contentAlignment = Alignment.Center) { SuggestionsRow(pressedColor) }
                    secondRowKeys.forEach { ToolbarKeyCell(it, true, pressedColor) }
                }
            }
        }
    }

    @Composable
    private fun InlineRow(view: View, height: androidx.compose.ui.unit.Dp) {
        Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_autofill_key), null,
                Modifier.padding(start = 6.dp, end = 4.dp), alpha = 0.6f)
            HostedView(view, Modifier.weight(1f).fillMaxHeight(), fill = true)
        }
    }

    /** Someone else's [view] inside this composition; it is taken out again when the composable leaves. */
    @Composable
    private fun HostedView(view: View, modifier: Modifier, fill: Boolean) {
        AndroidView(
            factory = { InlineAutofillUtils.newHost(it) },
            update = { InlineAutofillUtils.setHostedView(it, view, fill) },
            onRelease = { InlineAutofillUtils.setHostedView(it, null, fill) },
            modifier = modifier,
        )
    }

    @Composable
    private fun ToolbarRow(pressedColor: Color) {
        val density = LocalDensity.current
        val keyWidth = dimensionResource(R.dimen.config_suggestions_strip_edge_key_width)
        val iconPx = with(density) { keyWidth.roundToPx() }
        val minFling = remember { android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat() }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val page = ToolbarPaging.pageWidth(constraints.maxWidth, iconPx)
            val keys = toolbar.map { it.key }
            val gaps = remember(keys, page, iconPx) { ToolbarPaging.gaps(keys, toolbarKeysOnOwnPage, iconPx, page) }
            val scroll = rememberScrollState()
            val fling = remember(scroll, page) { PagedToolbarFling(scroll, { page }, minFling) }
            Row(Modifier.fillMaxHeight().horizontalScroll(scroll, flingBehavior = fling)) {
                toolbar.forEachIndexed { i, item ->
                    if (gaps.getOrElse(i) { 0 } > 0) Spacer(Modifier.width(with(density) { gaps[i].toDp() }))
                    ToolbarKeyCell(item, false, pressedColor)
                }
            }
        }
    }

    @Composable
    private fun ToolbarKeyCell(item: ToolbarKeyItem, inSecondRow: Boolean, pressedColor: Color) {
        val density = LocalDensity.current
        val keyWidth = dimensionResource(R.dimen.config_suggestions_strip_edge_key_width)
        val tick = activationTick // a toggle changed: the icon is redrawn in its new state
        val activated = remember(tick, item) { isToolbarKeyActivated(context, item.key) }
        // a key pinned to the second row is marked in the first (when pinning is on)
        val pinned = !inSecondRow && Settings.getValues().mQuickPinToolbarKeys && secondRowKeys.any { it.key == item.key }
        Box(
            Modifier.width(keyWidth).fillMaxHeight()
                .then(if (pinned) Modifier.background(enabledToolKeyBackground) else Modifier)
                .stripPress(pressedColor, RoundedCornerShape(8.dp), item,
                    onTap = { onToolbarKeyClick(item) }, onLongPress = { onToolbarKeyLongClick(item, inSecondRow) })
                .semantics { contentDescription = item.description },
            contentAlignment = Alignment.Center,
        ) {
            val w = item.icon.intrinsicWidth
            val h = item.icon.intrinsicHeight
            Canvas(Modifier.width(with(density) { w.toDp() }).height(with(density) { h.toDp() })) {
                drawIntoCanvas {
                    item.icon.setState(if (activated) ACTIVATED else UNACTIVATED)
                    item.icon.setBounds(0, 0, w, h)
                    item.icon.draw(it.nativeCanvas)
                }
            }
        }
    }

    @Composable
    private fun SuggestionsRow(pressedColor: Color) {
        val ext = external
        if (ext != null) {
            when (ext) {
                is External.Hosted -> Row(Modifier.fillMaxSize()) {
                    HostedView(ext.view, Modifier.weight(1f).fillMaxHeight(), fill = false)
                    if (ext.closeButton) CloseButton(pressedColor)
                }
                is External.Chips -> ChipsRow(ext.chips, pressedColor)
            }
            return
        }
        val current = wordLayout
        if (current.slots.isEmpty()) return
        if (current.singleCenter) {
            val position = style.centerPositionInStrip
            WordCell(current.slots.getOrNull(position), position, current, Modifier.fillMaxSize(), pressedColor)
            return
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            current.slots.forEachIndexed { position, slot ->
                if (position != 0) WordDivider()
                val weight = if (current.punctuation) 1f
                    else SuggestionStripLayout.suggestionWeight(position, style.centerPositionInStrip, style.centerSuggestionWeight, style.suggestionsCountInStrip)
                WordCell(slot, position, current, Modifier.weight(weight).fillMaxHeight(), pressedColor)
            }
        }
    }

    @Composable
    private fun CloseButton(pressedColor: Color) {
        val key = remember {
            val icon = toolbarKeyIcon(context, ToolbarKey.CLOSE_HISTORY)
            icon?.let {
                Settings.getValues().mColors.setColor(it, ColorType.TOOL_BAR_KEY)
                ToolbarKeyItem(ToolbarKey.CLOSE_HISTORY, it, toolbarKeyDescription(context, ToolbarKey.CLOSE_HISTORY))
            }
        } ?: return
        val density = LocalDensity.current
        val keyWidth = dimensionResource(R.dimen.config_suggestions_strip_edge_key_width)
        Box(
            Modifier.width(keyWidth).fillMaxHeight()
                .stripPress(pressedColor, RoundedCornerShape(8.dp), key,
                    onTap = { feedback(HapticEvent.KEY_PRESS); listener.removeExternalSuggestions() },
                    onLongPress = { feedback(HapticEvent.KEY_LONG_PRESS) })
                .semantics { contentDescription = key.description },
            contentAlignment = Alignment.Center,
        ) {
            val w = key.icon.intrinsicWidth
            val h = key.icon.intrinsicHeight
            Canvas(Modifier.width(with(density) { w.toDp() }).height(with(density) { h.toDp() })) {
                drawIntoCanvas {
                    key.icon.setState(ACTIVATED)
                    key.icon.setBounds(0, 0, w, h)
                    key.icon.draw(it.nativeCanvas)
                }
            }
        }
    }

    @Composable
    private fun WordDivider() {
        val divider = style.dividerDrawable ?: return
        val density = LocalDensity.current
        val w = divider.intrinsicWidth
        val h = divider.intrinsicHeight
        Canvas(Modifier.width(with(density) { w.toDp() }).height(with(density) { h.toDp() })) {
            drawIntoCanvas {
                divider.setBounds(0, 0, w, h)
                divider.draw(it.nativeCanvas)
            }
        }
    }

    @Composable
    private fun ChipsRow(chips: List<Pair<String, () -> Unit>>, pressedColor: Color) {
        val textColor = Color(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
        val textSize = with(LocalDensity.current) { wordTextSizePx.toSp() }
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            chips.forEach { (text, onTap) ->
                val label = text.replace('\n', ' ')
                Box(
                    Modifier.fillMaxHeight()
                        .stripPress(pressedColor, RoundedCornerShape(8.dp), text, onTap = { feedback(HapticEvent.KEY_PRESS); onTap() })
                        .padding(horizontal = 12.dp)
                        .semantics { contentDescription = text },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = textColor, fontSize = textSize, fontFamily = KeyboardTypeface.customFontFamily(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun WordCell(slot: WordSlot?, position: Int, current: SuggestionStripLayout.Layout, modifier: Modifier, pressedColor: Color) {
        val density = LocalDensity.current
        val fitWidth = if (current.singleCenter) stripWidthPx - wordPaddingPx
            else SuggestionStripLayout.suggestionWidth(
                position, stripWidthPx, wordPaddingPx, style.dividerWidth,
                style.centerPositionInStrip, style.centerSuggestionWeight, style.suggestionsCountInStrip
            )
        val empty = stringResource(R.string.spoken_empty_suggestion)
        if (slot == null || slot.label.isEmpty()) {
            Box(modifier.semantics { contentDescription = empty })
            return
        }
        val fitted = remember(slot, fitWidth) {
            if (current.punctuation) SuggestionStripLayout.FittedText(slot.label, 1.0f)
            else SuggestionStripLayout.fit(slot.label, slot.bold, fitWidth, measurePaint)
        }
        val showsRemoveIcon = removeIconIndex == slot.indexInSuggestedWords
        val textSize = with(density) { wordTextSizePx.toSp() }
        val removeIcon = remember(showsRemoveIcon) {
            if (!showsRemoveIcon) null
            else KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_BIN, context)?.also {
                Settings.getValues().mColors.setColor(it, ColorType.REMOVE_SUGGESTION_ICON)
            }
        }
        Box(
            modifier
                .stripPress(pressedColor, RoundedCornerShape(8.dp), slot to showsRemoveIcon,
                    onTap = { offset ->
                        val iconZone = (removeIcon?.intrinsicWidth ?: 0) + wordPaddingPx / 2
                        if (removeIcon != null && offset.x < iconZone) removeSuggestion(slot) else onWordClick(slot)
                    },
                    onLongPress = { onWordLongClick(slot) })
                .semantics { contentDescription = slot.label },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (removeIcon != null) {
                    val w = removeIcon.intrinsicWidth
                    val h = removeIcon.intrinsicHeight
                    Canvas(Modifier.width(with(density) { w.toDp() }).height(with(density) { h.toDp() })) {
                        drawIntoCanvas {
                            removeIcon.setBounds(0, 0, w, h)
                            removeIcon.draw(it.nativeCanvas)
                        }
                    }
                }
                Text(
                    fitted.text,
                    color = Color(slot.color),
                    fontSize = textSize,
                    fontFamily = KeyboardTypeface.customFontFamily(),
                    fontWeight = if (slot.bold) FontWeight.Bold else null,
                    textDecoration = if (slot.underline) TextDecoration.Underline else null,
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (showsRemoveIcon) TextOverflow.Ellipsis else TextOverflow.Clip,
                    style = TextStyle(textGeometricTransform = TextGeometricTransform(scaleX = fitted.scaleX)),
                )
            }
            // a hint that there are more suggestions than the row holds, under the centre word
            if (position == style.centerPositionInStrip && current.moreAvailable && !current.punctuation) {
                Text(
                    SuggestionStripLayout.MORE_SUGGESTIONS_HINT,
                    color = Color(style.colorMoreSuggestionsHint),
                    fontSize = with(density) { context.resources.getDimension(R.dimen.config_more_suggestions_hint_text_size).toSp() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                    maxLines = 1,
                )
            }
            slot.debugInfo?.let {
                Text(it, color = Color(Settings.getValues().mColors.get(ColorType.KEY_TEXT)),
                    fontSize = with(density) { (DEBUG_INFO_TEXT_SIZE_IN_DIP.dp).toSp() },
                    modifier = Modifier.align(Alignment.TopEnd), maxLines = 1)
            }
        }
    }

    companion object {
        @JvmField
        var DEBUG_SUGGESTIONS = false
        private const val DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.5f
        private val ACTIVATED = intArrayOf(android.R.attr.state_activated)
        private val UNACTIVATED = intArrayOf()
        private val TAG = SuggestionStripView::class.java.simpleName
    }
}
