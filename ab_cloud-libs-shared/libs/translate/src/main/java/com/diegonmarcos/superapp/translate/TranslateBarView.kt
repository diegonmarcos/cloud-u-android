package com.diegonmarcos.superapp.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

    // No colour of its own: the host hands in the KEYBOARD's palette ([setPalette]).
    // The fixed near-black slab that used to sit here is what made the bar a black box
    // on a light keyboard — see [TranslatePalette].
    private var palette = TranslatePalette.DARK
    /** Ceiling the host sets so the bar never outgrows the keys under it; 0 = none yet. */
    private var maxBarHeight = 0

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
    // Who owns the KEYS — a separate question from who owns the text, and it has to stay
    // separate: Output.LOST is terminal for the session, while the keys can come back to
    // the box the moment the user taps it again. See [KeyHandoff].
    private val keys = KeyHandoff()
    private var liveCommit = TranslatePrefs.DEFAULT_LIVE_COMMIT
    private var applyMode = TranslatePrefs.DEFAULT_APPLY_MODE

    private val fromChip: TextView
    private val toChip: TextView
    private val swapView: TextView
    private val closeView: TextView
    private val inputView: TranslateInputView
    private val inputScroll: CappedScrollView
    private val inputCap: Int
    private val previewView: TextView
    private val insertBtn: TextView
    private val replaceBtn: TextView
    private val actionChips = ArrayList<TextView>()
    private val ui = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var slow: Runnable? = null

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(4), dp(10), dp(8))
        // Opaque from the first frame, even before the host's palette arrives: a bar that
        // draws nothing is the IME window showing through, which is black.
        setBackgroundColor(palette.background)

        // ── Row 1: From  ⇄  To ............................. ✕ ───────────────
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fromChip = chip("") { showLangMenu(fromChip, true) }
        toChip = chip("") { showLangMenu(toChip, false) }
        row.addView(fromChip)
        swapView = TextView(context).apply {
            text = "⇄"; textSize = 18f; gravity = Gravity.CENTER
            minWidth = dp(44); minHeight = dp(40)
            contentDescription = context.getString(R.string.translate_bar_swap)
            isClickable = true; setOnClickListener { swap() }
        }
        row.addView(swapView)
        row.addView(toChip)
        row.addView(View(context), LayoutParams(0, 1, 1f)) // spacer
        // The way back to the keyboard. A real touch target (48dp), not a 16sp glyph
        // with a few dp of padding that a thumb misses and lands on the app behind.
        closeView = TextView(context).apply {
            text = "✕"; textSize = 18f; gravity = Gravity.CENTER
            minWidth = dp(48); minHeight = dp(44)
            contentDescription = context.getString(R.string.translate_bar_close)
            isClickable = true
            setOnClickListener { onClose?.run() }
        }
        row.addView(closeView)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── Row 2: input buffer ──────────────────────────────────────────────
        inputView = TranslateInputView(context).apply {
            textSize = 16f; setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
        }
        attachInputTouch()
        // Capped and SCROLLABLE, not capped by maxLines: the bar must stay short
        // enough to leave the keys visible, but a maxLines cap makes everything past
        // the last line unreachable — the caret walks off the box and the user is
        // editing text they cannot see. INPUT_LINES is a window, not a limit.
        inputScroll = CappedScrollView(context, inputView.lineHeight * INPUT_LINES + dp(12)).apply {
            isFillViewport = true
            addView(inputView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        inputCap = inputScroll.maxHeight   // the window's natural size; onMeasure may shrink it to fit
        addView(inputScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })

        // ── Row 3: the result / status ───────────────────────────────────────
        previewView = TextView(context).apply {
            textSize = 15f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), dp(6), dp(10), dp(6))
            minHeight = dp(34)
        }
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        })

        // ── Row 4: actions ───────────────────────────────────────────────────
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        insertBtn = chip(context.getString(R.string.translate_bar_insert)) { apply(TranslatePrefs.APPLY_INSERT) }
        replaceBtn = chip(context.getString(R.string.translate_bar_replace)) { apply(TranslatePrefs.APPLY_REPLACE) }
        actions.addView(insertBtn); actions.gap()
        actions.addView(replaceBtn); actions.gap()
        actions.addView(chip(context.getString(R.string.translate_bar_paste)) { paste() }.also { actionChips.add(it) }); actions.gap()
        actions.addView(chip(context.getString(R.string.translate_bar_copy)) { copy() }.also { actionChips.add(it) }); actions.gap()
        actions.addView(chip(context.getString(R.string.translate_bar_clear)) { clear() }.also { actionChips.add(it) })
        // Scrolls sideways rather than clipping the last chip on a narrow screen or at a
        // large font scale.
        addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        applyPalette()
        renderInput(); renderChips(); highlightPrimary()
    }

    /**
     * The keyboard theme's colours, from the host. Called on every open and whenever the
     * input view is rebuilt (a day/night switch rebuilds it), so the bar follows the keys.
     */
    fun setPalette(p: TranslatePalette) {
        if (p == palette) return
        palette = p
        applyPalette()
        renderInput(); highlightPrimary()
        showStatusColours()
    }

    /**
     * The tallest the bar may be — the host passes the height of the keys, so the bar
     * never takes more of the screen than the keyboard itself. The input box is the part
     * that gives: it is a scroller already, so shrinking its window loses nothing.
     */
    fun setMaxHeight(px: Int) {
        if (px == maxBarHeight) return
        maxBarHeight = px
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        inputScroll.maxHeight = inputCap
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val cap = maxBarHeight
        if (cap > 0 && measuredHeight > cap) {
            val over = measuredHeight - cap
            inputScroll.maxHeight = maxOf(inputView.lineHeight + dp(12), inputCap - over)
            inputScroll.forceLayout()   // its spec may not change, and the cap did
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }

    private fun applyPalette() {
        val p = palette
        setBackgroundColor(p.background)
        swapView.setTextColor(p.muted)
        closeView.setTextColor(p.text)
        // The boxes are outlined as well as filled: on a near-white or near-black keyboard
        // a surface can only step a little off the background, and the outline is what
        // says where the input ends and the result begins.
        inputScroll.background = boxBg(p.surface, p.outline)
        previewView.background = boxBg(p.surface, p.outline)
        inputView.caretColor = p.caret
        for (c in listOf(fromChip, toChip) + actionChips) {
            c.background = chipBg(p.chip); c.setTextColor(p.onChip)
        }
        // insertBtn / replaceBtn: highlightPrimary() colours them, primary vs not.
    }

    /** Wire the bar to the IME. [keyboardLang] = active subtype language (detection fallback + default target). */
    fun bind(provider: IcProvider, keyboardLang: String, onCloseAction: Runnable) {
        icp = provider
        onClose = onCloseAction
        this.keyboardLang = keyboardLang.ifEmpty { "en" }
    }

    /** Called by LatinIME each time the bar is shown — fresh session, settings re-read. */
    fun onShown() {
        editor.reset(); output = Output.NONE; keys.reset(); translated = null; detectedTag = null
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
            client == null -> context.getString(R.string.translate_no_engine_registered)
            !client.isConnected() -> Translator.notConnected(context)
            else -> ""
        })
    }

    // ── key routing entry points (called from LatinIME.onEvent) ──────────────
    /**
     * The bar's box owns the keys while the bar is open AND the user has not tapped into
     * the host application's own field. That second clause is #355 part 2: the panel keeps
     * the keys while the user is working in the panel, and gives them up the moment they
     * touch the field underneath. [KeyHandoff] is the whole of that decision.
     */
    override fun consumesKeys() = visibility == View.VISIBLE && keys.heldByBox

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
        if (text.isEmpty()) { toast(context.getString(R.string.translate_bar_clipboard_empty)); return }
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
        // onClaim was empty for as long as the bar never gave the keys up. Now that a tap
        // in the host field releases them, the same touch that the enhance bar has always
        // used to TAKE the keys is the way back — symmetric, and no gesture to learn.
        inputView.attachEditing(editor, onClaim = keys::reclaim, onChange = ::renderInput, onMenu = ::showEditMenu)

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
        popup.setBackgroundDrawable(roundBg(palette.surface, 12))
        popup.setAdapter(object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(palette.text) }
        })
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
            showStatus(context.getString(R.string.translate_bar_working))
            val slowJob = Runnable { showStatus(context.getString(R.string.translate_bar_slow)) }
            slow = slowJob; ui.postDelayed(slowJob, SLOW_MS)
            Translator.liveTranslate(context, text, fromTag, toTag, keyboardLang) { r ->
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
        if (out == null) { translated = null; showStatus(r.error ?: context.getString(R.string.translate_failed, context.getString(R.string.translate_reason_unknown))); return }
        translated = out
        showingStatus = false
        val det = r.detected
        if (fromTag == AUTO && det != null && det != "und") { detectedTag = det; renderChips() }
        previewView.text = out
        previewView.setTextColor(palette.text)
        previewView.setTypeface(null, Typeface.NORMAL)
        if (liveCommit) pushOutput(out)
    }

    // ── actions ──────────────────────────────────────────────────────────────
    private fun apply(mode: String) {
        val out = translated
        if (out == null) { if (editor.isNotEmpty) toast(context.getString(R.string.translate_bar_wait)); return }
        // The field is gone (the host closed it, or focus moved) and the bar cannot
        // write anywhere. Saying so is the whole difference between a chip that is
        // busy and a chip that is broken: the translation is still here, above.
        val ic = icp?.get() ?: run { toast(context.getString(R.string.translate_bar_no_field)); return }
        if (output == Output.OWNED) {
            // Already in the field as our composing region — finishing it is the
            // whole apply. Committing again is what produced the second copy.
            releaseOutput(ic, keep = true)
        } else if (mode == TranslatePrefs.APPLY_REPLACE) {
            val hadSelection = !ic.getSelectedText(0).isNullOrEmpty()
            keys.onSelfWrite()      // commitText into the field reports -1, same as a host tap
            Translator.replaceInField(ic, hadSelection, out)
        } else {
            keys.onSelfWrite()
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
        val out = translated ?: run { toast(if (editor.isNotEmpty) context.getString(R.string.translate_bar_wait) else context.getString(R.string.translate_bar_type_first)); return }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("translation", out))
        toast(context.getString(R.string.translate_bar_copied))
    }

    private fun clear() { editor.clear(); onChanged() }

    private fun swap() {
        val f = if (fromTag == AUTO) (detectedTag
            ?: run { toast(context.getString(R.string.translate_bar_no_detection)); return })
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
        // finishComposingText reports a composing span of -1, which is character for
        // character what a host tap reports. Announced so the echo is spent against this
        // write instead of reading as the user tapping into the app — otherwise emptying
        // the box (backspace to blank -> pushOutput("") -> here) would hand the keys back
        // mid-edit, which is the one failure that makes the feature unusable.
        keys.onSelfWrite()
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
        // Is this the host, or the bar's own write coming back? Asked FIRST and asked in
        // one place, because both answers below depend on it. The old code asked it as a
        // side effect of `output != Output.OWNED`, which is only a valid proxy inside
        // live-commit mode — see [KeyHandoff] for why that could not simply be reused.
        //
        // #355 part 2 rides on the true branch: the user tapped into the application's own
        // field, so onSelectionChange has ALREADY handed the keys back. It does that
        // whatever `output` says — with live commit off the bar owns nothing in the field
        // and the tap still has to release — and tapping the bar's box takes them again
        // (onClaim, below). Everything past this point is the older, narrower concern of
        // who owns the TEXT, and is unchanged.
        val wasHeld = keys.heldByBox
        if (!keys.onSelectionChange()) return
        // Only on the TRANSITION. Once the keys are with the app, every word the user
        // commits there lands here again (a committed word is a composing span of -1), and
        // re-laying out the box on each of them would be a redraw per keystroke for a
        // caret that is already gone.
        if (wasHeld) renderInput()
        if (output != Output.OWNED) return

        output = Output.LOST
        // There still has to be a way to get the translation out, and live commit
        // is no longer one of them.
        insertBtn.visibility = View.VISIBLE
        replaceBtn.visibility = View.VISIBLE
        showStatus(context.getString(R.string.translate_bar_taken_over))
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
            inputView.text = context.getString(R.string.translate_bar_hint)
            inputView.setTextColor(palette.hint)
            inputView.setTypeface(null, Typeface.ITALIC)
            inputView.caret = -1
            return
        }
        inputView.setTextColor(palette.text)
        inputView.setTypeface(null, Typeface.NORMAL)
        // A copy, not the live StringBuilder: the TextView would otherwise render a
        // buffer that keeps mutating underneath its layout.
        if (editor.hasSelection()) {
            inputView.text = SpannableString(editor.text).apply {
                setSpan(BackgroundColorSpan(palette.selection), editor.selLo(), editor.selHi(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            inputView.caret = -1          // a range and an insertion point are mutually exclusive
        } else {
            inputView.text = editor.text
            // No caret while the host field holds the keys. A blinking caret in a box that
            // the next keystroke will not reach is the box lying about where typing goes —
            // the enhance bar's output box has drawn it conditionally for the same reason.
            inputView.caret = if (keys.heldByBox) editor.selStart else -1
        }
        // Posted: the layout still describes the text set BEFORE this call, and
        // scrolling against a stale layout lands on the wrong line.
        inputView.post { inputView.revealCaret() }
    }

    /** True while [previewView] holds a status line rather than a translation. */
    private var showingStatus = true

    private fun showStatus(msg: String) {
        showingStatus = true
        previewView.text = msg
        showStatusColours()
    }

    private fun showStatusColours() {
        if (!showingStatus) { previewView.setTextColor(palette.text); return }
        previewView.setTextColor(palette.muted)
        previewView.setTypeface(null, Typeface.ITALIC)
    }

    private fun renderChips() {
        fromChip.text = if (fromTag == AUTO) "AUTO" + (detectedTag?.let { " (${it.uppercase()})" } ?: "") + " ▾"
                        else fromTag.uppercase() + " ▾"
        toChip.text = toTag.uppercase() + " ▾"
    }

    private fun highlightPrimary() {
        val p = palette
        val insertPrimary = applyMode == TranslatePrefs.APPLY_INSERT
        val replacePrimary = applyMode == TranslatePrefs.APPLY_REPLACE
        insertBtn.background = chipBg(if (insertPrimary) p.accent else p.chip)
        insertBtn.setTextColor(if (insertPrimary) p.onAccent else p.onChip)
        replaceBtn.background = chipBg(if (replacePrimary) p.accent else p.chip)
        replaceBtn.setTextColor(if (replacePrimary) p.onAccent else p.onChip)
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
        // The keyboard's colours, not the platform popup's: the rows below set their own
        // text colour, and on a light system popup the old white rows were invisible.
        lpw.setBackgroundDrawable(roundBg(palette.surface, 12))
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
                    v.setTextColor(palette.muted); v.textSize = 12f
                    v.setTypeface(null, Typeface.BOLD); v.setAllCaps(true)
                } else {
                    v.text = when (row) {
                        is LangRow.RecentPair -> "${langName(row.from)} → ${langName(row.to)}"
                        is LangRow.Language -> if (row.code == AUTO) "Auto-detect" else langName(row.code)
                        else -> ""
                    }
                    v.setTextColor(palette.text); v.textSize = 16f
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
        text = label; textSize = 14f; gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        minHeight = dp(36)
        isClickable = true; setOnClickListener { onTap() }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }
    private fun chipBg(color: Int) = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(color) }
    private fun roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply { cornerRadius = dp(radiusDp).toFloat(); setColor(color) }
    private fun boxBg(fill: Int, outline: Int) = roundBg(fill, 10).apply {
        setStroke(dp(1), outline)
    }
    private fun LinearLayout.gap() = addView(View(context), LayoutParams(dp(6), 1))
    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
