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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Editing actions the host IME routes into a bar's own text box while it is open.
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
 * Input model: while the bar is open, LatinIME routes printable keys, backspace,
 * the editing keys and the caret-slide GESTURES into [editor] — the shared
 * [TextBoxEditor] the enhance bar's box uses too; nothing touches the app field
 * until the user applies (Insert at cursor / Replace selection-or-field / Copy).
 *
 * Live commit (on by default) additionally streams the translation into the app
 * field as you type. Two buffers then exist — [editor] and the host's field — and
 * [Output] is the rule that keeps them from fighting: the bar's buffer is always
 * the user's, the bar owns exactly ONE span of the host field, it owns that span
 * as a composing region so the platform tracks it, and it gives the span up the
 * moment the host says otherwise. [onHostOutputDropped] carries the argument for
 * why that arrangement cannot loop.
 *
 * EMOJI ACROSS THE ROUND TRIP: THEY PASS THROUGH, AND THAT IS A DECISION.
 * A translation provider may drop, duplicate, reorder or re-render the emoji in a
 * sentence, because emoji are not language and nothing in a translation model is
 * asked to preserve them. So the text coming back differs from the text that went
 * out in ways that have nothing to do with translation quality. Three answers were
 * possible and two lose:
 *
 *   - Treat an emoji-only difference as "the source changed" and re-sync. This is
 *     the one that produces the flaky box: it makes a difference the provider will
 *     reintroduce on the very next request into a reason to make another request.
 *     There is deliberately NO comparison anywhere here between what was sent and
 *     what came back — [onChanged]'s only equality test is source against source,
 *     to drop a reply that a later keystroke has already made stale.
 *   - Extract the emoji before sending and re-insert them after. The known failure
 *     is that the placeholder tokens get translated or mangled themselves, and
 *     there is a second one specific to translation: a target language with a
 *     different word order has no defined position to put an emoji back into. It
 *     would also mean changing what the translation call sends, which is not this
 *     layer's to change.
 *   - Pass them through untouched, and make the box arithmetic correct enough that
 *     an emoji in the SOURCE survives being typed, moved past and deleted. That is
 *     what [TextBoxEditor] is for, and it is what this bar does.
 *
 * Settings + recent pairs: [TranslatePrefs]. Engine: [Translator].
 * Lives in libs:translate; the cloud-keyboard tree (libs/keyboard) hosts it in LatinIME.
 */
class TranslateBarView(context: Context) : LinearLayout(context), ImeTextBox {

    /** Supplies the current InputConnection (the IME's getCurrentInputConnection). */
    fun interface IcProvider { fun get(): InputConnection? }

    companion object {
        private val DEFAULT_LANGS = TranslatePrefs.FALLBACK_LANGS
        /** Pinned above the full alphabetical list in the picker, in this order. */
        private val MOST_USED = listOf("en", "es", "de", "pt", "fr", "ko", "ja")
        /** Height of the input box, in lines; it scrolls past this rather than clipping. */
        private const val INPUT_LINES = 3
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

    /**
     * Who owns the translation the bar has put into the host app's field.
     *
     * The bar's own [editor] is never in question — nothing outside this class
     * writes to it, so the SOURCE text is always the user's. What needs an owner is
     * the OUTPUT: the span of the host field holding the last translation, which the
     * bar has to be able to revise on the next keystroke without either deleting
     * words it never wrote or leaving a second copy of its own behind.
     */
    private enum class Output {
        /** Nothing of the bar's is in the field, or what was there is the app's for good. */
        NONE,

        /**
         * The translation is in the field as a COMPOSING region. That is the
         * platform's own primitive for "provisional text I intend to revise": it
         * tracks the span across the host app's edits, [InputConnection.setComposingText]
         * replaces exactly it, and the host leaves it alone instead of running its
         * own autocorrect over half a translation.
         */
        OWNED,

        /**
         * The host dropped the composing region — the user tapped into the app, or
         * the app rewrote its own field. What the bar wrote is the app's text now,
         * and revising it would mean guessing; both guesses are wrong. Deleting eats
         * words the bar never wrote. Appending leaves the duplicate the owner
         * reported. So the bar stops writing and hands back Insert/Replace.
         *
         * Terminal for the session: nothing moves out of LOST except a fresh
         * [onShown]. That is what bounds the re-sync engine — see [onHostOutputDropped].
         */
        LOST,
    }

    // The user's source text, its caret and its selection — the ONE buffer, shared
    // with the enhance bar's box rather than copied into it. Every grapheme question
    // (where a caret may stand, what one backspace removes, where a word ends) is
    // answered in there, so a fix made for this bar is a fix the other bar already has.
    private val editor = TextBoxEditor()
    private var output = Output.NONE     // live-commit mode: who owns the text in the app field
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
            textSize = 16f; setTextColor(Color.WHITE); setPadding(0, dp(6), 0, 0)
            isClickable = true
        }
        attachInputTouch()
        // Capped and SCROLLABLE, not capped by maxLines: the bar must stay short
        // enough to leave the keys visible, but a maxLines cap makes everything past
        // the last line unreachable — the caret walks off the box and the user is
        // editing text they cannot see. INPUT_LINES is a window, not a limit.
        addView(CappedScrollView(context, inputView.lineHeight * INPUT_LINES + dp(6)).apply {
            isFillViewport = true
            addView(inputView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
        editor.reset(); output = Output.NONE; translated = null; detectedTag = null
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
    /** The bar's box owns the keys for as long as the bar is open — it IS the bar. */
    override fun consumesKeys() = visibility == View.VISIBLE

    override fun appendCodePoint(cp: Int) {
        if (cp == '\n'.code) { apply(applyMode); return }   // Enter = primary action
        editor.insert(String(Character.toChars(cp)))        // at the caret, replacing any selection
        onChanged()
    }

    override fun backspace() {
        editor.deleteBackward()
        onChanged()
    }

    /**
     * The space bar's cursor slide and the backspace swipe, landing in the box instead
     * of in the application behind it. Nothing is re-translated: this moves a caret.
     */
    override fun moveCaret(steps: Int, select: Boolean) {
        editor.moveCaret(steps, select)
        renderInput()
    }

    /**
     * Editing keys the IME hands over while the bar is open (see [TranslateEdit]).
     *
     * ALWAYS consumed, and the return type is kept only so the caller reads as a
     * chain. These keys are routed here solely because the bar is open, so letting
     * one fall through applies it to the app's field BEHIND the bar — invisible to
     * a user who is looking at the bar, and for CUT or SELECT_ALL destructive of
     * text the bar never owned. An empty buffer means the action does nothing; it
     * does not mean somebody else should do it instead.
     */
    override fun onEdit(action: TranslateEdit): Boolean {
        when (action) {
            TranslateEdit.LEFT -> editor.stepCaret(-1)
            TranslateEdit.RIGHT -> editor.stepCaret(1)
            TranslateEdit.WORD_LEFT -> editor.setCaret(editor.wordStart(editor.selLo()))
            TranslateEdit.WORD_RIGHT -> editor.setCaret(editor.wordEnd(editor.selHi()))
            TranslateEdit.LINE_START -> editor.setCaret(0)
            TranslateEdit.LINE_END -> editor.setCaret(editor.length)
            TranslateEdit.SELECT_ALL -> editor.selectAll()
            TranslateEdit.SELECT_WORD -> editor.selectWordAt(editor.selLo())
            TranslateEdit.COPY -> copySelection()
            TranslateEdit.CUT -> cutSelection()
            TranslateEdit.PASTE -> paste()
        }
        renderInput()
        return true
    }

    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /** Paste at the caret, replacing the selection — the "full control to paste" affordance. */
    private fun paste() {
        val clip = clipboard().primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
        if (text.isEmpty()) { toast("Clipboard is empty"); return }
        editor.insert(text)
        onChanged()
    }

    private fun copySelection() {
        val text = editor.selectedText()
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("translate", text))
        toast(if (editor.hasSelection()) "Selection copied" else "Copied")
    }

    private fun cutSelection() {
        val text = editor.selectedText()
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("translate", text))
        // No selection means cut EVERYTHING, which is the destructive one — so it goes
        // through clear() and is undoable, rather than emptying the buffer in place.
        if (!editor.deleteSelection()) editor.clear()
        onChanged()
    }

    private fun attachInputTouch() =
        inputView.attachEditing(editor, onClaim = {}, onChange = ::renderInput, onMenu = ::showEditMenu)

    private fun showEditMenu() {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun item(label: String, run: () -> Unit) { labels.add(label); actions.add(run) }
        item("Paste") { paste() }
        if (editor.hasSelection()) {
            item("Cut") { cutSelection() }
            item("Copy selection") { copySelection() }
        }
        if (editor.selLo() != 0 || editor.selHi() != editor.length)
            item("Select all") { editor.selectAll(); renderInput() }
        // Only offered when there is something to put back, so the menu never promises
        // an undo that does nothing.
        if (editor.canUndo()) item("Undo") { editor.undo(); onChanged() }
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
        val text = editor.text
        if (text.isBlank()) { showStatus(""); if (liveCommit) pushOutput(""); return }
        val job = Runnable {
            showStatus("Translating…")
            val slowJob = Runnable { showStatus("Still translating… first use downloads the language model (needs network once)") }
            slow = slowJob; ui.postDelayed(slowJob, SLOW_MS)
            Translator.liveTranslate(text, fromTag, toTag, keyboardLang) { r ->
                if (editor.text != text) return@liveTranslate   // stale
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
        if (out == null) { if (editor.isNotEmpty) toast("Wait for the translation…"); return }
        // The field is gone (the host closed it, or focus moved) and the bar cannot
        // write anywhere. Saying so is the whole difference between a chip that is
        // busy and a chip that is broken: the translation is still here, above.
        val ic = icp?.get() ?: run { toast("No text field to write into — tap where you want it first"); return }
        if (output == Output.OWNED) {
            // Already in the field as our composing region — finishing it is the
            // whole apply. Committing again is what produced the second copy.
            releaseOutput(ic, keep = true)
        } else if (mode == TranslatePrefs.APPLY_REPLACE) {
            val hadSelection = !ic.getSelectedText(0).isNullOrEmpty()
            Translator.replaceInField(ic, hadSelection, out)
        } else {
            ic.commitText(out, 1)
        }
        TranslatePrefs.pushRecentPair(context, if (fromTag == AUTO) (detectedTag ?: AUTO) else fromTag, toTag)
        pending?.let { ui.removeCallbacks(it) }
        // Undoable: the source the user typed is gone from the box the moment they
        // apply, and Undo in the long-press menu is how they get it back.
        editor.clear(); translated = null
        renderInput(); showStatus("")
    }

    private fun copy() {
        val out = translated ?: run { toast(if (editor.isNotEmpty) "Wait for the translation…" else "Type something to translate first"); return }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("translation", out))
        toast("Copied")
    }

    private fun clear() { editor.clear(); onChanged() }

    private fun swap() {
        val f = if (fromTag == AUTO) (detectedTag
            ?: run { toast("No language detected yet — type something, or pick one instead of Auto"); return })
            else fromTag
        fromTag = toTag; toTag = f; detectedTag = null
        renderChips(); onChanged()
    }

    /**
     * Live-commit mode: put [out] in the field as the bar's composing region,
     * replacing whatever the bar put there before.
     *
     * One InputConnection call, because the composing region IS the platform's
     * re-sync primitive — it survives the host reflowing its own text, and
     * setComposingText replaces it atomically with no character count to get wrong.
     *
     * The version this replaces retracted by length and then committed, and when it
     * could not CONFIRM the retract it committed anyway: the old translation stayed
     * and a new one landed after it. That is the text appearing twice, and then
     * three times, that the owner reported.
     */
    private fun pushOutput(out: String) {
        if (output == Output.LOST) return      // not the bar's to revise any more
        val ic = icp?.get() ?: return
        if (out.isEmpty()) { releaseOutput(ic, keep = false); return }
        output = Output.OWNED
        ic.setComposingText(out, 1)
    }

    /**
     * End the composing region. [keep] true leaves the text in the field as the app's
     * own — the user applied it, or the bar is closing on top of it; false takes it
     * back out, which is what an emptied buffer means.
     */
    private fun releaseOutput(ic: InputConnection, keep: Boolean) {
        if (output != Output.OWNED) { output = Output.NONE; return }
        // NONE *before* the calls, and this order is load-bearing. finishComposingText
        // makes the host report a composing span of -1, which is the same signal a host
        // takeover sends; releasing while still OWNED would have the bar read its own
        // hand-over as the app stealing the text and go LOST for no reason.
        output = Output.NONE
        ic.beginBatchEdit()
        if (!keep) ic.setComposingText("", 1)
        ic.finishComposingText()
        ic.endBatchEdit()
    }

    /**
     * The host field reported that the bar's composing region is gone — LatinIME
     * passes a composing span of -1 for exactly this. The user tapped into the app,
     * or the app rewrote its own text; either way what the bar wrote is the app's now.
     *
     * WHY THE UPDATE LOOP TERMINATES. This handler is the only thing in the bar that
     * reacts to the host field, and it neither writes to the field nor touches
     * [editor] — so it cannot cause the update it is reacting to, which is the loop a
     * naive listener falls into. The bar's own writes report a span of >= 0 and never
     * reach the body at all; its own [releaseOutput] does report -1, but sets NONE
     * first, so the guard below returns. That leaves a host-originated drop as the
     * only way in, and OWNED -> LOST as the only transition: one-way, and terminal
     * until the next [onShown]. The handler therefore does work at most ONCE per
     * session, and after it nothing writes to the field at all.
     */
    fun onHostOutputDropped() {
        if (output != Output.OWNED) return
        output = Output.LOST
        // There still has to be a way to get the translation out, and live commit
        // is no longer one of them.
        insertBtn.visibility = View.VISIBLE
        replaceBtn.visibility = View.VISIBLE
        showStatus("The app took that text over — use Insert or Replace")
    }

    /**
     * The bar is closing. A composing region left behind would leave the host app's
     * text provisional and underlined for a field nothing is watching any more, so it
     * is handed over as the app's own on the way out.
     */
    fun onHidden() {
        pending?.let { ui.removeCallbacks(it) }
        slow?.let { ui.removeCallbacks(it) }
        icp?.get()?.let { releaseOutput(it, keep = true) }
    }

    // ── rendering ────────────────────────────────────────────────────────────
    private fun renderInput() {
        if (editor.isEmpty) {
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
        if (editor.hasSelection()) {
            inputView.text = SpannableString(editor.text).apply {
                setSpan(BackgroundColorSpan(selectionColor), editor.selLo(), editor.selHi(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            inputView.caret = -1          // a range and an insertion point are mutually exclusive
        } else {
            inputView.text = editor.text
            inputView.caret = editor.selStart
        }
        // Posted: the layout still describes the text set BEFORE this call, and
        // scrolling against a stale layout lands on the wrong line.
        inputView.post { inputView.revealCaret() }
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
