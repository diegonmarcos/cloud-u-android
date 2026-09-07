// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import com.diegonmarcos.superapp.translate.TranslateInputView
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * SuperApp addition — the Text Enhancements bar, hosted inside the keyboard frame
 * exactly like [LatinIME.toggleTranslateBar]'s translate bar (added above the
 * suggestion strip, its height subtracted in onComputeInsets):
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │ [Clarity ▾] [Formal ▾] [One paragraph ▾] [English ▾] ✕│  the Text Enhancements options
 *   │ what will be rewritten (from the field, dim)          │  source — read-only
 *   │ the rewrite, editable                                 │  output — keys are routed here
 *   │ status                                                │
 *   │ [Generate] [Copy] [Paste] [Replace] [Clear]           │
 *   └──────────────────────────────────────────────────────┘
 *
 * Tapping the ENHANCE toolbar key opens this; long-pressing it keeps the old
 * one-shot behaviour (rewrite in place, no bar). Nothing here touches the app's
 * field until Paste or Replace: Generate only fills the output box, so a rewrite
 * can be read and corrected before it lands.
 *
 * The option chips write the very same preferences as Settings → Text
 * Enhancements, so the bar and that screen can never disagree, and the prompt is
 * built by the one [AiRouter.enhanceStyle] both paths use.
 *
 * ponytail: the caret/selection buffer below is a trimmed second copy of the one
 * in TranslateBarView — the IME window cannot host a focusable EditText (see
 * [TranslateInputView]), so every bar has to own its text by hand. Hoist it into
 * a shared widget if a fourth bar ever needs it.
 */
class EnhanceBarView(context: Context) : LinearLayout(context) {

    /** Supplies the IME's current rich connection; null while no field is attached. */
    fun interface ConnectionProvider { fun get(): RichInputConnection? }

    companion object {
        private const val TAG = "EnhanceBar"
    }

    // Same palette as the translate bar — the two panels are one feature to the eye.
    private val bg = 0xF21B1B20.toInt()
    private val chipColor = 0xFF34343F.toInt()
    private val chipPrimary = 0xFF3D5AFE.toInt()
    private val muted = 0xFFB0B0B8.toInt()
    private val hintColor = 0xFF6E6E78.toInt()
    private val selectionColor = 0x803D5AFE.toInt()

    private var provider: ConnectionProvider? = null
    private var onClose: Runnable? = null

    /** The field text this session rewrites, and where it sits; re-read after every apply. */
    private var target: TextEnhancer.Target? = null

    private val buffer = StringBuilder()
    // Caret / selection over `buffer` in UTF-16 offsets; equal = collapsed caret,
    // and selStart is the drag anchor, so read ranges through selLo()/selHi().
    private var selStart = 0
    private var selEnd = 0
    private var busy = false
    private var applyWhenReady = false

    /**
     * Where the keys go. False — the default, and the state the bar opens in — means
     * the keyboard still types into the app's own field, which is the whole point:
     * you write and edit there, the bar only reads from it. True means the user
     * tapped the output box to touch up a rewrite, so the keys are routed here until
     * they tap the source line (or Generate) to hand them back.
     */
    private var editingOutput = false

    private val sourceView: TextView
    private val outputView: TranslateInputView
    private val statusView: TextView
    private val options: List<Option>

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger()

    /** One option chip, backed by a registry list from build.json::keyboard_ai and its preference. */
    private inner class Option(val key: String, val entries: List<AiRouter.Style>, val fallback: String) {
        val chip: TextView = chip("") { showOptionMenu(this) }
        fun current(): AiRouter.Style? {
            val id = context.prefs().getString(key, fallback) ?: fallback
            return entries.firstOrNull { it.id == id } ?: entries.firstOrNull { it.id == fallback }
        }
        fun set(id: String) {
            context.prefs().edit { putString(key, id) }
            render()
        }
        fun render() { chip.text = current()?.label ?: "—" }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(8))
        setBackgroundColor(bg)

        // ── Row 1: the Text Enhancements options .................. ✕ ─────────
        options = listOf(
            Option(Settings.PREF_ENHANCE_STYLE, AiRouter.styles, AiRouter.defaultStyle),
            Option(Settings.PREF_ENHANCE_TONE, AiRouter.tones, AiRouter.defaultTone),
            Option(Settings.PREF_ENHANCE_LENGTH, AiRouter.lengths, AiRouter.defaultLength),
            Option(Settings.PREF_ENHANCE_LANGUAGE, AiRouter.languages, AiRouter.defaultLanguage),
        ).filter { it.entries.isNotEmpty() }
        val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        options.forEachIndexed { i, option ->
            if (i > 0) chips.gap()
            chips.addView(option.chip)
        }
        // Four chips do not fit on a phone in portrait, so they scroll rather than shrink.
        val optionRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        optionRow.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        optionRow.addView(TextView(context).apply {
            text = "✕"; setTextColor(muted); textSize = 16f
            setPadding(dp(10), dp(2), dp(4), dp(2)); isClickable = true
            setOnClickListener { onClose?.run() }
        })
        addView(optionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 2: what will be rewritten ────────────────────────────────────
        // Tapping it is also how the keys go back to the app's field after a
        // detour into the output box — "type over there" is what the row means.
        sourceView = TextView(context).apply {
            textSize = 14f; setTextColor(muted); maxLines = 2; setPadding(0, dp(6), 0, 0)
            isClickable = true
            setOnClickListener { focusField() }
        }
        addView(sourceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 3: the rewrite, editable ─────────────────────────────────────
        outputView = TranslateInputView(context).apply {
            textSize = 16f; setTextColor(Color.WHITE); maxLines = 4; setPadding(0, dp(6), 0, 0)
            isClickable = true
        }
        attachOutputTouch()
        addView(outputView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 4: status ────────────────────────────────────────────────────
        statusView = TextView(context).apply {
            textSize = 13f; setTextColor(hintColor); maxLines = 2; setPadding(0, dp(4), 0, dp(6))
        }
        addView(statusView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 5: actions ───────────────────────────────────────────────────
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(chip(str(R.string.enhance_bar_generate), chipPrimary) { generate(false) }); actions.gap()
        actions.addView(chip(str(R.string.enhance_bar_copy)) { copyOutput() }); actions.gap()
        actions.addView(chip(str(R.string.enhance_bar_paste)) { applyOutput() }); actions.gap()
        actions.addView(chip(str(R.string.enhance_bar_replace)) { generate(true) }); actions.gap()
        actions.addView(chip(str(R.string.enhance_bar_clear)) { clear() })
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        renderOutput()
    }

    /** Wire the bar to the IME. */
    fun bind(connectionProvider: ConnectionProvider, onCloseAction: Runnable) {
        provider = connectionProvider
        onClose = onCloseAction
    }

    /** Called by LatinIME every time the bar is shown — fresh session, preferences re-read. */
    fun onShown() {
        seq.incrementAndGet()
        busy = false; applyWhenReady = false
        editingOutput = false
        buffer.setLength(0); setCaret(0)
        options.forEach { it.render() }
        reloadTarget()
        renderOutput()
        showStatus("")
    }

    /** The source line is what the app's field holds; re-read it after the user types. */
    fun onFieldChanged() {
        if (visibility != VISIBLE || busy) return
        reloadTarget()
    }

    /**
     * True while the output box owns the keys. LatinIME asks before routing anything
     * here, so with the box unfocused every key lands in the app's field as usual.
     */
    fun consumesKeys() = visibility == VISIBLE && editingOutput

    /** Hand the keys back to the app's field and re-read what it now holds. */
    private fun focusField() {
        if (!editingOutput) return
        editingOutput = false
        select(selLo(), selLo())
        reloadTarget()
        renderOutput()
    }

    // ── key routing entry points (called from LatinIME.onEvent) ──────────────
    fun appendCodePoint(cp: Int) {
        // Enter is the action key on a keyboard bar, not a newline: it does what the
        // user came for — apply what is in the box (generating it first if need be).
        if (cp == '\n'.code) { if (buffer.isEmpty()) generate(true) else applyOutput(); return }
        insert(String(Character.toChars(cp)))
    }

    fun backspace() {
        if (!deleteSelection()) {
            val at = selLo()
            if (at > 0) {
                val from = if (at > 1 && Character.isLowSurrogate(buffer[at - 1]) &&
                    Character.isHighSurrogate(buffer[at - 2])) at - 2 else at - 1
                buffer.delete(from, at)
                setCaret(from)
            }
        }
        renderOutput()
    }

    /**
     * Navigation and clipboard toolbar keys, applied to the output box instead of the
     * app's field while the bar is open. Returns false when the key is not one of
     * them — or when the box is empty and the action would do nothing — so LatinIME
     * can fall back to its normal handling.
     */
    fun onEdit(keyCode: Int): Boolean {
        if (buffer.isEmpty() && keyCode != KeyCode.CLIPBOARD_PASTE) return false
        when (keyCode) {
            KeyCode.ARROW_LEFT -> stepCaret(-1)
            KeyCode.ARROW_RIGHT -> stepCaret(1)
            KeyCode.WORD_LEFT -> setCaret(wordStart(selLo()))
            KeyCode.WORD_RIGHT -> setCaret(wordEnd(selHi()))
            // The box is one short buffer, so up/down are its ends.
            KeyCode.ARROW_UP, KeyCode.MOVE_START_OF_LINE -> setCaret(0)
            KeyCode.ARROW_DOWN, KeyCode.MOVE_END_OF_LINE -> setCaret(buffer.length)
            KeyCode.CLIPBOARD_SELECT_ALL -> select(0, buffer.length)
            KeyCode.CLIPBOARD_SELECT_WORD -> selectWordAt(selLo())
            KeyCode.CLIPBOARD_COPY -> { copySelection(); return true }
            KeyCode.CLIPBOARD_CUT -> { cutSelection(); return true }
            KeyCode.CLIPBOARD_PASTE -> { pasteIntoBox(); return true }
            else -> return false
        }
        renderOutput()
        return true
    }

    // ── the four actions ─────────────────────────────────────────────────────

    /** Ask the model for a rewrite of the source; [thenApply] = the Replace button. */
    private fun generate(thenApply: Boolean) {
        // Read the field NOW. The snapshot taken when the bar opened is stale by
        // definition — the user types after opening it, which is the normal way to
        // use this, and enhancing what the field held before that is never right.
        editingOutput = false
        reloadTarget()
        val t = target
        if (t == null || t.text.isBlank()) { showStatus(str(R.string.enhance_bar_no_source)); return }
        if (busy) return
        busy = true
        applyWhenReady = thenApply
        val id = seq.incrementAndGet()
        val style = AiRouter.enhanceStyle(context)
        showStatus(context.getString(R.string.enhance_in_progress, AiRouter.provider(context).label))
        io.execute {
            val result = runCatching { AiRouter.complete(context, style.prompt, t.text) }
            ui.post {
                if (id != seq.get()) return@post   // a newer run, or the bar was reopened
                busy = false
                result.onSuccess { out ->
                    buffer.setLength(0); buffer.append(out); setCaret(buffer.length)
                    renderOutput(); showStatus("")
                    if (applyWhenReady) applyOutput()
                }.onFailure { e ->
                    Log.e(TAG, "generate failed", e)
                    showStatus(when (e) {
                        is AiRouter.NoTokenException -> context.getString(R.string.ai_no_token, e.provider.label)
                        else -> context.getString(R.string.enhance_failed, e.message ?: e.javaClass.simpleName)
                    })
                }
                applyWhenReady = false
            }
        }
    }

    /** Write the box into the field over the text it was made from. */
    private fun applyOutput() {
        val text = buffer.toString()
        if (text.isBlank()) { showStatus(str(R.string.enhance_bar_generate_first)); return }
        val t = target ?: return
        val connection = provider?.get() ?: return
        // The field moved under us (the user typed, or the host app rewrote it) — the
        // stored range no longer describes what is on screen, so applying would corrupt it.
        if (!t.sameAs(TextEnhancer.target(context, connection))) {
            showStatus(str(R.string.enhance_stale)); reloadTarget(); return
        }
        if (TextEnhancer.apply(context, connection, t, text)) {
            showStatus(str(R.string.enhance_bar_replaced))
            editingOutput = false   // the text lives in the field now — type there
            reloadTarget()   // the field is now the rewrite: enhancing again starts from it
            renderOutput()
        } else {
            showStatus(str(R.string.enhance_no_cursor))
        }
    }

    private fun copyOutput() {
        val text = buffer.toString()
        if (text.isBlank()) { showStatus(str(R.string.enhance_bar_generate_first)); return }
        clipboard().setPrimaryClip(ClipData.newPlainText("enhance", text))
        toast(str(R.string.enhance_bar_copied))
    }

    private fun clear() {
        buffer.setLength(0); setCaret(0); renderOutput(); showStatus("")
    }

    /** Re-read what the ENHANCE key would rewrite right now and show it. */
    private fun reloadTarget() {
        val connection = provider?.get()
        target = if (connection == null) null else TextEnhancer.target(context, connection)
        val text = target?.text.orEmpty()
        if (text.isBlank()) {
            sourceView.text = str(R.string.enhance_bar_no_source)
            sourceView.setTypeface(null, Typeface.ITALIC)
        } else {
            sourceView.text = text
            sourceView.setTypeface(null, Typeface.NORMAL)
        }
    }

    // ── caret + selection over `buffer` ──────────────────────────────────────
    private fun hasSelection() = selStart != selEnd
    private fun selLo() = minOf(selStart, selEnd)
    private fun selHi() = maxOf(selStart, selEnd)

    private fun setCaret(at: Int) {
        val p = at.coerceIn(0, buffer.length); selStart = p; selEnd = p
    }

    private fun select(anchor: Int, extent: Int) {
        selStart = anchor.coerceIn(0, buffer.length); selEnd = extent.coerceIn(0, buffer.length)
    }

    /** Replace the selection — or insert at the caret — with [text]; the caret lands after it. */
    private fun insert(text: String) {
        if (text.isEmpty()) return
        val lo = selLo()
        buffer.replace(lo, selHi(), text)
        setCaret(lo + text.length)
        renderOutput()
    }

    private fun deleteSelection(): Boolean {
        if (!hasSelection()) return false
        val lo = selLo()
        buffer.delete(lo, selHi())
        setCaret(lo)
        return true
    }

    /** Step one whole code point, so a caret never lands between a surrogate pair. */
    private fun stepCaret(dir: Int) {
        if (hasSelection()) { setCaret(if (dir < 0) selLo() else selHi()); return }
        val at = selStart
        val to = if (dir < 0) {
            if (at > 1 && Character.isLowSurrogate(buffer[at - 1]) &&
                Character.isHighSurrogate(buffer[at - 2])) at - 2 else at - 1
        } else {
            if (at < buffer.length - 1 && Character.isHighSurrogate(buffer[at]) &&
                Character.isLowSurrogate(buffer[at + 1])) at + 2 else at + 1
        }
        setCaret(to)
    }

    private fun wordStart(at: Int): Int {
        var i = at.coerceIn(0, buffer.length)
        while (i > 0 && buffer[i - 1].isWhitespace()) i--
        while (i > 0 && !buffer[i - 1].isWhitespace()) i--
        return i
    }

    private fun wordEnd(at: Int): Int {
        var i = at.coerceIn(0, buffer.length)
        while (i < buffer.length && buffer[i].isWhitespace()) i++
        while (i < buffer.length && !buffer[i].isWhitespace()) i++
        return i
    }

    private fun selectWordAt(at: Int) {
        if (buffer.isEmpty()) return
        select(wordStart(minOf(at + 1, buffer.length)), wordEnd(at))
    }

    private fun selectedText() = if (hasSelection()) buffer.substring(selLo(), selHi()) else buffer.toString()

    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun pasteIntoBox() {
        val clip = clipboard().primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
        if (text.isEmpty()) { showStatus(str(R.string.enhance_bar_clipboard_empty)); return }
        insert(text)
    }

    private fun copySelection() {
        val text = selectedText()
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("enhance", text))
        toast(str(R.string.enhance_bar_copied))
    }

    private fun cutSelection() {
        val text = selectedText()
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("enhance", text))
        if (!deleteSelection()) { buffer.setLength(0); setCaret(0) }
        renderOutput()
    }

    /**
     * Tap places the caret, drag selects, long-press selects the word and opens the
     * edit menu — the three gestures a real text field gives you, reimplemented
     * because an unfocused TextView provides none of them.
     */
    private fun attachOutputTouch() {
        outputView.setOnTouchListener(object : View.OnTouchListener {
            private var anchor = 0
            private var downX = 0f
            private var downY = 0f
            private var dragging = false
            private val longPress = Runnable { selectWordAt(anchor); renderOutput(); showEditMenu() }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                if (buffer.isEmpty()) return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Touching the box is what claims the keys; until then they
                        // belong to the app's field.
                        editingOutput = true
                        anchor = outputView.offsetAt(e.x, e.y); downX = e.x; downY = e.y; dragging = false
                        setCaret(anchor); renderOutput()
                        ui.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val slop = ViewConfiguration.get(context).scaledTouchSlop
                        if (!dragging && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                            dragging = true; ui.removeCallbacks(longPress)
                        }
                        if (dragging) { select(anchor, outputView.offsetAt(e.x, e.y)); renderOutput() }
                    }
                    MotionEvent.ACTION_UP -> { ui.removeCallbacks(longPress); v.performClick() }
                    MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(longPress)
                }
                return true
            }
        })
    }

    private fun showEditMenu() {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(label: String, run: () -> Unit) { labels.add(label); actions.add(run) }
        item(str(R.string.enhance_bar_menu_paste)) { pasteIntoBox() }
        if (hasSelection()) {
            item(str(R.string.enhance_bar_menu_cut)) { cutSelection() }
            item(str(R.string.enhance_bar_menu_copy)) { copySelection() }
        }
        if (selLo() != 0 || selHi() != buffer.length)
            item(str(R.string.enhance_bar_menu_select_all)) { select(0, buffer.length); renderOutput() }
        item(str(R.string.enhance_bar_clear)) { clear() }
        val popup = ListPopupWindow(context)
        popup.anchorView = outputView
        popup.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
        popup.width = dp(200)
        popup.setOnItemClickListener { _, _, pos, _ -> popup.dismiss(); actions[pos]() }
        popup.show()
    }

    /** Tap a chip → the entries of that registry list, current one first in the list order. */
    private fun showOptionMenu(option: Option) {
        val entries = option.entries
        val popup = ListPopupWindow(context)
        popup.anchorView = option.chip
        popup.isModal = true
        popup.width = dp(260)
        popup.height = dp(320)
        popup.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, entries.map { it.label }))
        popup.setOnItemClickListener { _, _, pos, _ ->
            popup.dismiss()
            option.set(entries[pos].id)
        }
        popup.show()
    }

    // ── rendering ────────────────────────────────────────────────────────────
    private fun renderOutput() {
        if (buffer.isEmpty()) {
            outputView.text = str(R.string.enhance_bar_output_hint)
            outputView.setTextColor(hintColor)
            outputView.setTypeface(null, Typeface.ITALIC)
            outputView.caret = -1
            return
        }
        outputView.setTextColor(Color.WHITE)
        outputView.setTypeface(null, Typeface.NORMAL)
        if (hasSelection()) {
            outputView.text = SpannableString(buffer.toString()).apply {
                setSpan(BackgroundColorSpan(selectionColor), selLo(), selHi(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            outputView.caret = -1
        } else {
            outputView.text = buffer.toString()
            // The caret is the only thing that says where the keys are going, so it
            // is drawn only while the box actually has them.
            outputView.caret = if (editingOutput) selStart else -1
        }
    }

    private fun showStatus(msg: String) {
        statusView.text = msg
        statusView.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    // ── view helpers ─────────────────────────────────────────────────────────
    private fun chip(label: String, color: Int = chipColor, onTap: () -> Unit) = TextView(context).apply {
        text = label; setTextColor(Color.WHITE); textSize = 13f
        setPadding(dp(12), dp(3), dp(12), dp(3))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
        isClickable = true; setOnClickListener { onTap() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }
    private fun LinearLayout.gap() = addView(View(context), LayoutParams(dp(6), 1))
    private fun str(id: Int): String = context.getString(id)
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
