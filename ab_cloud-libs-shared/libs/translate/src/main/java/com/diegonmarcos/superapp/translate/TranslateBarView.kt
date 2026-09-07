package com.diegonmarcos.superapp.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

/**
 * Editing actions the host IME routes into the bar's own buffer while it is open.
 *
 * The bar cannot host a focusable EditText (see [TranslateInputView]), so the
 * keyboard's existing navigation and clipboard toolbar keys are translated into
 * these by LatinIME instead of being applied to the app's field. Declared here
 * rather than taken from the keyboard's KeyCode so that libs:translate keeps no
 * dependency on libs:keyboard — the mapping lives on the keyboard side.
 */
enum class TranslateEdit {
    LEFT, RIGHT, WORD_LEFT, WORD_RIGHT, LINE_START, LINE_END,
    SELECT_ALL, SELECT_WORD, COPY, CUT, PASTE
}

/**
 * Translate bar, hosted INSIDE the keyboard frame (LatinIME adds it above the
 * suggestion strip; onComputeInsets reserves its height so the host app
 * reflows up):
 *
 *   ┌───────────────────────────────────────────┐
 *   │ [AUTO (EN) ▾]   ⇄   [PT ▾]              ✕  │  language chips (tap = picker, recents first)
 *   │ type here…                                 │  input buffer (keys are routed here by LatinIME)
 *   │ tradução ao vivo                           │  live preview / status line
 *   │ [Insert] [Replace] [Copy] [Clear]          │  actions — Enter = the primary one (setting)
 *   └───────────────────────────────────────────┘
 *
 * Input model: while the bar is open, LatinIME routes printable keys +
 * backspace into [buffer]; nothing touches the app field until the user
 * applies (Insert at cursor / Replace selection-or-field / Copy). The
 * previous "live commit" model (translation written into the field as you
 * type, rewound by length on every change) is kept as an opt-in setting —
 * it breaks as soon as the host app touches its own text.
 *
 * Settings + recent pairs: [TranslatePrefs]. Engine: [Translator].
 * Lives in libs:translate; the cloud-keyboard tree (libs/keyboard) hosts it in LatinIME.
 */
class TranslateBarView(context: Context) : LinearLayout(context) {

    /** Supplies the current InputConnection (the IME's getCurrentInputConnection). */
    fun interface IcProvider { fun get(): InputConnection? }

    companion object {
        private val DEFAULT_LANGS = TranslatePrefs.FALLBACK_LANGS
        /** Pinned above the full alphabetical list in the picker, in this order. */
        private val MOST_USED = listOf("en", "es", "de", "pt", "fr", "ko", "ja")
        private const val DEBOUNCE_MS = 300L
        private const val SLOW_MS = 8000L
        private const val AUTO = Translator.AUTO
    }

    private val bg = 0xF21B1B20.toInt()
    private val chipColor = 0xFF34343F.toInt()
    private val chipPrimary = 0xFF3D5AFE.toInt()
    private val muted = 0xFFB0B0B8.toInt()
    private val hintColor = 0xFF6E6E78.toInt()
    private val selectionColor = 0x803D5AFE.toInt()

    private var icp: IcProvider? = null
    private var onClose: Runnable? = null
    private var keyboardLang = "en"
    private var fromTag = AUTO
    private var toTag = "en"
    private var detectedTag: String? = null
    private var translated: String? = null

    private val buffer = StringBuilder()
    // Caret / selection over `buffer`, in the same units (UTF-16 offsets). Equal =
    // a collapsed caret; unequal = a selection, and selStart is the drag anchor so
    // it may be greater than selEnd — use selLo()/selHi() to read a range.
    private var selStart = 0
    private var selEnd = 0
    private var lastOutput = ""          // live-commit mode: what we've committed to the app field
    private var liveCommit = TranslatePrefs.DEFAULT_LIVE_COMMIT
    private var applyMode = TranslatePrefs.DEFAULT_APPLY_MODE

    private val fromChip: TextView
    private val toChip: TextView
    private val inputView: TranslateInputView
    private val previewView: TextView
    private val insertBtn: TextView
    private val replaceBtn: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var slow: Runnable? = null

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(8))
        setBackgroundColor(bg)

        // ── Row 1: From  ⇄  To ............................. ✕ ───────────────
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fromChip = chip("") { showLangMenu(fromChip, true) }
        toChip = chip("") { showLangMenu(toChip, false) }
        row.addView(fromChip)
        row.addView(TextView(context).apply {
            text = "  ⇄  "; setTextColor(muted); textSize = 15f
            isClickable = true; setOnClickListener { swap() }
        })
        row.addView(toChip)
        row.addView(View(context), LayoutParams(0, 1, 1f)) // spacer
        row.addView(TextView(context).apply {
            text = "✕"; setTextColor(muted); textSize = 16f
            setPadding(dp(10), dp(2), dp(4), dp(2)); isClickable = true
            setOnClickListener { onClose?.run() }
        })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 2: input buffer ──────────────────────────────────────────────
        inputView = TranslateInputView(context).apply {
            textSize = 16f; setTextColor(Color.WHITE); maxLines = 3; setPadding(0, dp(6), 0, 0)
            isClickable = true
        }
        attachInputTouch()
        addView(inputView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 3: live preview / status ─────────────────────────────────────
        previewView = TextView(context).apply {
            textSize = 15f; setTextColor(muted); maxLines = 3; setPadding(0, dp(4), 0, dp(6))
        }
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 4: actions ───────────────────────────────────────────────────
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        insertBtn = chip("Insert") { apply(TranslatePrefs.APPLY_INSERT) }
        replaceBtn = chip("Replace") { apply(TranslatePrefs.APPLY_REPLACE) }
        actions.addView(insertBtn); actions.gap()
        actions.addView(replaceBtn); actions.gap()
        actions.addView(chip("Paste") { paste() }); actions.gap()
        actions.addView(chip("Copy") { copy() }); actions.gap()
        actions.addView(chip("Clear") { clear() })
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        renderInput(); renderChips(); highlightPrimary()
    }

    /** Wire the bar to the IME. [keyboardLang] = active subtype language (detection fallback + default target). */
    fun bind(provider: IcProvider, keyboardLang: String, onCloseAction: Runnable) {
        icp = provider
        onClose = onCloseAction
        this.keyboardLang = keyboardLang.ifEmpty { "en" }
    }

    /** Called by LatinIME each time the bar is shown — fresh session, settings re-read. */
    fun onShown() {
        buffer.setLength(0); setCaret(0); lastOutput = ""; translated = null; detectedTag = null
        liveCommit = TranslatePrefs.liveCommit(context)
        applyMode = TranslatePrefs.applyMode(context)
        val langs = toLangs()
        // Order of precedence for the opening pair: an explicitly pinned default
        // target wins, then the last pair actually used (translating twice in a row
        // is the common case), then the keyboard's own language.
        val pinned = TranslatePrefs.defaultTarget(context).takeIf { langs.contains(it) }
        val recent = TranslatePrefs.recentPairs(context).firstOrNull { langs.contains(it.second) }
        toTag = pinned ?: recent?.second ?: keyboardLang.takeIf { langs.contains(it) } ?: "en"
        fromTag = when {
            TranslatePrefs.autoDetect(context) -> AUTO
            pinned == null && recent != null && (recent.first == AUTO || langs.contains(recent.first)) -> recent.first
            else -> keyboardLang.takeIf { langs.contains(it) } ?: AUTO
        }
        if (fromTag == toTag) {
            toTag = TranslatePrefs.recentPairs(context).firstOrNull { it.second != fromTag && langs.contains(it.second) }?.second
                ?: DEFAULT_LANGS.firstOrNull { it != fromTag && langs.contains(it) } ?: toTag
        }
        // Live-commit writes straight into the field — Insert/Replace would double it.
        insertBtn.visibility = if (liveCommit) View.GONE else View.VISIBLE
        replaceBtn.visibility = insertBtn.visibility
        highlightPrimary(); renderInput(); renderChips()
        // Say it on open, not after the first keystroke: with no engine every key
        // would otherwise "Translating…" for 300 ms and then fail, one key at a time.
        val client = TranslateEngines.client
        showStatus(when {
            client == null -> "No translate engine registered"
            !client.isConnected() -> Translator.NOT_CONNECTED
            else -> ""
        })
    }

    // ── key routing entry points (called from LatinIME.onEvent) ──────────────
    fun appendCodePoint(cp: Int) {
        if (cp == '\n'.code) { apply(applyMode); return }   // Enter = primary action
        insert(String(Character.toChars(cp)))               // at the caret, replacing any selection
    }

    fun backspace() {
        if (!deleteSelection()) {
            val at = selLo()
            if (at > 0) {
                // drop a surrogate pair as one character
                val from = if (at > 1 && Character.isLowSurrogate(buffer[at - 1]) &&
                    Character.isHighSurrogate(buffer[at - 2])) at - 2 else at - 1
                buffer.delete(from, at)
                setCaret(from)
            }
        }
        onChanged()
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
        onChanged()
    }

    /** Drops the selected range without re-translating; callers follow with onChanged(). */
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

    /**
     * Editing keys the IME hands over while the bar is open (see [TranslateEdit]).
     * Returns false when the buffer is empty and the action would be a no-op, so
     * the caller can fall back to its normal handling.
     */
    fun onEdit(action: TranslateEdit): Boolean {
        if (buffer.isEmpty() && action != TranslateEdit.PASTE) return false
        when (action) {
            TranslateEdit.LEFT -> stepCaret(-1)
            TranslateEdit.RIGHT -> stepCaret(1)
            TranslateEdit.WORD_LEFT -> setCaret(wordStart(selLo()))
            TranslateEdit.WORD_RIGHT -> setCaret(wordEnd(selHi()))
            TranslateEdit.LINE_START -> setCaret(0)
            TranslateEdit.LINE_END -> setCaret(buffer.length)
            TranslateEdit.SELECT_ALL -> select(0, buffer.length)
            TranslateEdit.SELECT_WORD -> selectWordAt(selLo())
            TranslateEdit.COPY -> { copySelection(); return true }
            TranslateEdit.CUT -> { cutSelection(); return true }
            TranslateEdit.PASTE -> { paste(); return true }
        }
        renderInput()
        return true
    }

    private fun selectWordAt(at: Int) {
        if (buffer.isEmpty()) return
        select(wordStart(minOf(at + 1, buffer.length)), wordEnd(at))
    }

    private fun selectedText() = if (hasSelection()) buffer.substring(selLo(), selHi()) else buffer.toString()

    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Paste at the caret, replacing the selection — the "full control to paste" affordance. */
    private fun paste() {
        val clip = clipboard().primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
        if (text.isEmpty()) { toast("Clipboard is empty"); return }
        insert(text)
    }

    private fun copySelection() {
        val text = selectedText()
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("translate", text))
        toast(if (hasSelection()) "Selection copied" else "Copied")
    }

    private fun cutSelection() {
        val text = selectedText()
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("translate", text))
        if (!deleteSelection()) { buffer.setLength(0); setCaret(0) }
        onChanged()
    }

    /**
     * Tap places the caret, drag selects, long-press selects the word and opens the
     * edit menu — the three gestures a real text field gives you, reimplemented
     * because an unfocused TextView provides none of them.
     */
    private fun attachInputTouch() {
        inputView.setOnTouchListener(object : View.OnTouchListener {
            private var anchor = 0
            private var downX = 0f
            private var downY = 0f
            private var dragging = false
            private val longPress = Runnable { selectWordAt(anchor); renderInput(); showEditMenu() }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                if (buffer.isEmpty()) return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        anchor = inputView.offsetAt(e.x, e.y); downX = e.x; downY = e.y; dragging = false
                        setCaret(anchor); renderInput()
                        ui.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val slop = ViewConfiguration.get(context).scaledTouchSlop
                        if (!dragging && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                            dragging = true; ui.removeCallbacks(longPress)
                        }
                        if (dragging) { select(anchor, inputView.offsetAt(e.x, e.y)); renderInput() }
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
        item("Paste") { paste() }
        if (hasSelection()) {
            item("Cut") { cutSelection() }
            item("Copy selection") { copySelection() }
        }
        if (selLo() != 0 || selHi() != buffer.length) item("Select all") { select(0, buffer.length); renderInput() }
        item("Clear") { clear() }
        val popup = ListPopupWindow(context)
        popup.anchorView = inputView
        popup.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
        popup.width = dp(200)
        popup.setOnItemClickListener { _, _, pos, _ -> popup.dismiss(); actions[pos]() }
        popup.show()
    }

    private fun onChanged() {
        renderInput()
        pending?.let { ui.removeCallbacks(it) }
        slow?.let { ui.removeCallbacks(it) }
        translated = null
        val text = buffer.toString()
        if (text.isBlank()) { showStatus(""); if (liveCommit) pushOutput(""); return }
        val job = Runnable {
            showStatus("Translating…")
            val slowJob = Runnable { showStatus("Still translating… first use downloads the language model (needs network once)") }
            slow = slowJob; ui.postDelayed(slowJob, SLOW_MS)
            Translator.liveTranslate(text, fromTag, toTag, keyboardLang) { r ->
                if (buffer.toString() != text) return@liveTranslate   // stale
                slow?.let { ui.removeCallbacks(it) }
                onResult(r)
            }
        }
        pending = job
        ui.postDelayed(job, DEBOUNCE_MS)
    }

    private fun onResult(r: Translator.Result) {
        val out = r.text
        if (out == null) { translated = null; showStatus(r.error ?: "Translate failed"); return }
        translated = out
        val det = r.detected
        if (fromTag == AUTO && det != null && det != "und") { detectedTag = det; renderChips() }
        previewView.text = out
        previewView.setTextColor(Color.WHITE)
        previewView.setTypeface(null, Typeface.NORMAL)
        if (liveCommit) pushOutput(out)
    }

    // ── actions ──────────────────────────────────────────────────────────────
    private fun apply(mode: String) {
        val out = translated
        if (out == null) { if (buffer.isNotEmpty()) toast("Wait for the translation…"); return }
        val ic = icp?.get() ?: return
        if (liveCommit) {
            lastOutput = ""   // already in the field — just end the session
        } else if (mode == TranslatePrefs.APPLY_REPLACE) {
            val hadSelection = !ic.getSelectedText(0).isNullOrEmpty()
            Translator.replaceInField(ic, hadSelection, out)
        } else {
            ic.commitText(out, 1)
        }
        TranslatePrefs.pushRecentPair(context, if (fromTag == AUTO) (detectedTag ?: AUTO) else fromTag, toTag)
        pending?.let { ui.removeCallbacks(it) }
        buffer.setLength(0); setCaret(0); translated = null
        renderInput(); showStatus("")
    }

    private fun copy() {
        val out = translated ?: return
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("translation", out))
        toast("Copied")
    }

    private fun clear() { buffer.setLength(0); setCaret(0); onChanged() }

    private fun swap() {
        val f = if (fromTag == AUTO) (detectedTag ?: return) else fromTag
        fromTag = toTag; toTag = f; detectedTag = null
        renderChips(); onChanged()
    }

    /** Live-commit mode only: replace the previously-committed translation in the app field with [out]. */
    private fun pushOutput(out: String) {
        val ic = icp?.get() ?: return
        ic.beginBatchEdit()
        // Only retract our own previous output. Deleting lastOutput.length blindly
        // eats whatever is actually there if the field moved underneath us (the user
        // tapped elsewhere, the app rewrote the field, autocorrect fired) — and with
        // live commit now on by default that would be everyone's data loss, not an
        // opt-in's. If what precedes the cursor is not what we wrote, leave it alone.
        if (lastOutput.isNotEmpty()) {
            val before = ic.getTextBeforeCursor(lastOutput.length, 0)?.toString()
            if (before == lastOutput) ic.deleteSurroundingText(lastOutput.length, 0)
        }
        if (out.isNotEmpty()) ic.commitText(out, 1)
        lastOutput = out
        ic.endBatchEdit()
    }

    // ── rendering ────────────────────────────────────────────────────────────
    private fun renderInput() {
        if (buffer.isEmpty()) {
            inputView.text = "Type to translate…"
            inputView.setTextColor(hintColor)
            inputView.setTypeface(null, Typeface.ITALIC)
            inputView.caret = -1
            return
        }
        inputView.setTextColor(Color.WHITE)
        inputView.setTypeface(null, Typeface.NORMAL)
        // A copy, not the live StringBuilder: the TextView would otherwise render a
        // buffer that keeps mutating underneath its layout.
        if (hasSelection()) {
            inputView.text = SpannableString(buffer.toString()).apply {
                setSpan(BackgroundColorSpan(selectionColor), selLo(), selHi(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            inputView.caret = -1          // a range and an insertion point are mutually exclusive
        } else {
            inputView.text = buffer.toString()
            inputView.caret = selStart
        }
    }

    private fun showStatus(msg: String) {
        previewView.text = msg
        previewView.setTextColor(hintColor)
        previewView.setTypeface(null, Typeface.ITALIC)
    }

    private fun renderChips() {
        fromChip.text = if (fromTag == AUTO) "AUTO" + (detectedTag?.let { " (${it.uppercase()})" } ?: "") + " ▾"
                        else fromTag.uppercase() + " ▾"
        toChip.text = toTag.uppercase() + " ▾"
    }

    private fun highlightPrimary() {
        insertBtn.background = chipBg(if (applyMode == TranslatePrefs.APPLY_INSERT) chipPrimary else chipColor)
        replaceBtn.background = chipBg(if (applyMode == TranslatePrefs.APPLY_REPLACE) chipPrimary else chipColor)
    }

    // ── language picker ──────────────────────────────────────────────────────
    // Every language the engine can translate — computed fresh on each read: the
    // AIDL client binds asynchronously, so a cached snapshot taken before the bind
    // would freeze the list at the fallback forever.
    private fun toLangs(): List<String> =
        (TranslateEngines.client?.supportedLanguages()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LANGS)
            .sortedBy { Locale(it).displayLanguage }

    /** One picker row. Typed rather than positional so headers can never be off-by-one. */
    private sealed class LangRow {
        class Header(val label: String) : LangRow()
        /** A remembered from→to pair; picking it sets both sides at once. */
        class RecentPair(val from: String, val to: String) : LangRow()
        class Language(val code: String) : LangRow()
    }

    /**
     * Tap a chip → picker, in three sections: Recent pairs (both sides at once),
     * Most used, then every language. Headers are real rows but disabled, and the
     * click handler reads `rows[pos]` instead of doing index arithmetic against the
     * section sizes — the arithmetic is what broke as soon as headers existed.
     */
    private fun showLangMenu(anchor: View, isFrom: Boolean) {
        val langs = toLangs()
        val recents = TranslatePrefs.recentPairs(context)
        val rows = ArrayList<LangRow>()
        if (recents.isNotEmpty()) {
            rows.add(LangRow.Header("Recent"))
            recents.forEach { rows.add(LangRow.RecentPair(it.first, it.second)) }
        }
        rows.add(LangRow.Header("Most used"))
        if (isFrom) rows.add(LangRow.Language(AUTO))
        MOST_USED.filter { langs.contains(it) }.forEach { rows.add(LangRow.Language(it)) }
        rows.add(LangRow.Header("All languages"))
        langs.forEach { rows.add(LangRow.Language(it)) }

        val lpw = ListPopupWindow(context)
        lpw.anchorView = anchor
        lpw.isModal = true
        lpw.width = dp(260)
        lpw.height = dp(320)
        lpw.setAdapter(object : ArrayAdapter<LangRow>(context, android.R.layout.simple_list_item_1, rows) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = rows[position] !is LangRow.Header
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val row = rows[position]
                if (row is LangRow.Header) {
                    v.text = row.label
                    v.setTextColor(muted); v.textSize = 12f
                    v.setTypeface(null, Typeface.BOLD); v.setAllCaps(true)
                } else {
                    v.text = when (row) {
                        is LangRow.RecentPair -> "${langName(row.from)} → ${langName(row.to)}"
                        is LangRow.Language -> if (row.code == AUTO) "Auto-detect" else langName(row.code)
                        else -> ""
                    }
                    v.setTextColor(Color.WHITE); v.textSize = 16f
                    v.setTypeface(null, Typeface.NORMAL); v.setAllCaps(false)
                }
                return v
            }
        })
        lpw.setOnItemClickListener { _, _, pos, _ ->
            when (val row = rows[pos]) {
                is LangRow.Header -> return@setOnItemClickListener   // disabled above; belt and braces
                is LangRow.RecentPair -> { fromTag = row.from; toTag = row.to }
                is LangRow.Language -> if (isFrom) fromTag = row.code else toTag = row.code
            }
            detectedTag = null
            lpw.dismiss(); renderChips(); onChanged()
        }
        lpw.show()
    }

    private fun langName(code: String): String =
        Locale(code).displayLanguage.let { n -> if (n.equals(code, ignoreCase = true)) code.uppercase() else "$n  ($code)" }

    // ── view helpers ─────────────────────────────────────────────────────────
    private fun chip(label: String, onTap: () -> Unit) = TextView(context).apply {
        text = label; setTextColor(Color.WHITE); textSize = 13f
        setPadding(dp(12), dp(3), dp(12), dp(3))
        background = chipBg(chipColor)
        isClickable = true; setOnClickListener { onTap() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }
    private fun chipBg(color: Int) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
    private fun LinearLayout.gap() = addView(View(context), LayoutParams(dp(6), 1))
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
