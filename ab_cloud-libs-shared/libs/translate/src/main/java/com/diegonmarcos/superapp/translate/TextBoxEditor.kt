package com.diegonmarcos.superapp.translate

import android.icu.text.BreakIterator
import kotlin.math.abs

/**
 * A text box the keyboard hosts inside its OWN window, and therefore has to edit by hand.
 *
 * An input method's window is not focusable — that is precisely what stops it stealing
 * focus from the application it is typing into — so no view inside it holds a real
 * editing focus, and none of the platform's caret, selection, autocorrect or gesture
 * handling ever runs for one of these boxes. Every bar that shows one routes keys into
 * it itself.
 *
 * This interface exists so that there is exactly ONE question LatinIME asks — "who owns
 * editing right now" — and exactly one place to answer it. Before it, key events were
 * intercepted and gestures were not, because gestures had been written to call the host
 * application's InputConnection directly; the space bar's cursor slide therefore moved a
 * caret in the document BEHIND the bar, invisibly, while the user was looking at the box.
 * A feature added to the keyboard's editing pipeline can now miss these boxes only by
 * failing to ask this one question.
 */
interface ImeTextBox {
    /** True while this box, rather than the host application's field, owns the keys. */
    fun consumesKeys(): Boolean

    fun appendCodePoint(cp: Int)

    fun backspace()

    /**
     * A navigation or clipboard key routed here. ALWAYS consumes: these keys reach the
     * box only because the box owns editing, so letting one fall through applies it to
     * the field behind the bar — and for CUT or SELECT_ALL that destroys text no bar
     * ever owned. An empty box means the action does nothing, not that somebody else
     * should do it instead.
     */
    fun onEdit(action: TranslateEdit): Boolean

    /**
     * A slide gesture — the space bar's cursor slide, the backspace swipe — moving the
     * caret [steps] GRAPHEMES, extending the selection instead of collapsing it when
     * [select] is set. Steps, not characters: that is the unit the gesture layer already
     * counts in, and it is the unit a caret is allowed to move in.
     */
    fun moveCaret(steps: Int, select: Boolean)
}

/**
 * The one caret/selection buffer behind every [ImeTextBox]. Extracted from the two
 * hand-rolled copies that used to live in the translate bar and the enhance bar, which
 * is the follow-up the re-sync engine named and did not attempt.
 *
 * WHERE THE CODEPOINT DISCIPLINE USED TO STOP, AND WHAT THAT BROKE. Both copies knew
 * about surrogate pairs in exactly two places — the backspace and the one-step caret
 * move — and nowhere else. Everything that fed an offset INTO those two functions was
 * plain UTF-16 arithmetic: the offset a touch resolved to, the index a whitespace scan
 * stopped at, the ends of a drag selection. So the discipline protected the two
 * operations that already had it and nothing upstream of them, and an offset landing
 * between a high and a low surrogate — half of `😶` — went straight into the buffer as a
 * caret, a selection edge or a cut point. Half a surrogate pair is not a character: it
 * renders as a replacement glyph and it is not equal to the text it was taken from.
 *
 * And a surrogate pair is only the easy case. A flag is two regional indicators, a
 * skin-toned emoji is a base plus a modifier, `☺️` is a codepoint plus a variation
 * selector, and a family is several people joined by zero-width joiners. Each is one
 * character to the person typing and several codepoints to the machine, so codepoint
 * arithmetic is still the wrong unit for a caret even after the surrogates are handled.
 *
 * The unit is the grapheme cluster, and the platform already segments them — the same
 * BreakIterator the keyboard's own pipeline uses to convert a swipe into a character
 * count. This class does not scan text itself; it snaps.
 *
 * THE RULE, and it is the whole design: an offset this class COMPUTES from its own edit
 * is exact and used as-is; an offset arriving from OUTSIDE — a touch, a whitespace scan,
 * a caller's arithmetic — is snapped to a grapheme boundary on the way in. There is one
 * function for that ([boundaryAt]) and every entry point goes through it.
 *
 * Main thread only: BreakIterator is not thread safe, and every caller is a view.
 */
class TextBoxEditor {

    private val buffer = StringBuilder()

    /** Drag anchor. May be GREATER than [selEnd]; read ranges through [selLo]/[selHi]. */
    var selStart = 0
        private set
    var selEnd = 0
        private set

    private val breaks: BreakIterator = BreakIterator.getCharacterInstance()
    // Null means the iterator is looking at text the buffer no longer holds. Re-set
    // lazily rather than on every mutation: a burst of typing then costs one setText.
    private var breaksText: String? = null

    // One level of undo of a whole-buffer replacement; see [replaceAll].
    private var undoText: String? = null
    private var undoCaret = 0

    val text: String get() = buffer.toString()
    val length: Int get() = buffer.length
    val isEmpty: Boolean get() = buffer.isEmpty()
    val isNotEmpty: Boolean get() = buffer.isNotEmpty()

    fun hasSelection() = selStart != selEnd
    fun selLo() = minOf(selStart, selEnd)
    fun selHi() = maxOf(selStart, selEnd)

    /** The selection, or the whole box when there is none — what Copy and Cut act on. */
    fun selectedText(): String =
        if (hasSelection()) buffer.substring(selLo(), selHi()) else buffer.toString()

    fun canUndo() = undoText != null

    // ── grapheme boundaries: the one place text is measured ──────────────────

    private fun iterator(): BreakIterator {
        if (breaksText == null) {
            breaksText = buffer.toString()
            breaks.setText(breaksText)
        }
        return breaks
    }

    private fun invalidate() { breaksText = null }

    /**
     * Snap an offset from outside back onto a grapheme boundary, towards the start.
     *
     * Towards the start because this is where a CARET is being placed, and a caret that
     * has to move to become legal should move to the beginning of the character it is
     * standing in rather than skip over it.
     */
    fun boundaryAt(at: Int): Int {
        val p = at.coerceIn(0, buffer.length)
        if (p == 0 || p == buffer.length) return p
        val bi = iterator()
        if (bi.isBoundary(p)) return p
        val back = bi.preceding(p)
        return if (back == BreakIterator.DONE) 0 else back
    }

    /** Snap towards the END instead — for the far end of a selection; see [select]. */
    private fun boundaryAfter(at: Int): Int {
        val p = at.coerceIn(0, buffer.length)
        if (p == 0 || p == buffer.length) return p
        val bi = iterator()
        if (bi.isBoundary(p)) return p
        val fwd = bi.following(p)
        return if (fwd == BreakIterator.DONE) buffer.length else fwd
    }

    /** The boundary strictly after [at], or the end of the text. */
    fun nextBoundary(at: Int): Int {
        val p = at.coerceIn(0, buffer.length)
        if (p >= buffer.length) return buffer.length
        val n = iterator().following(p)
        return if (n == BreakIterator.DONE) buffer.length else n
    }

    /** The boundary strictly before [at], or the start of the text. */
    fun prevBoundary(at: Int): Int {
        val p = at.coerceIn(0, buffer.length)
        if (p <= 0) return 0
        val n = iterator().preceding(p)
        return if (n == BreakIterator.DONE) 0 else n
    }

    // ── caret and selection ──────────────────────────────────────────────────

    /** An offset this class computed from its own edit: exact, clamped, never snapped. */
    private fun setCaretExact(at: Int) {
        val p = at.coerceIn(0, buffer.length)
        selStart = p
        selEnd = p
    }

    fun setCaret(at: Int) = setCaretExact(boundaryAt(at))

    /**
     * A selection from [anchor] to [extent], both from outside.
     *
     * The moving end snaps AWAY from the anchor: a drag that stops halfway across an
     * emoji takes the whole emoji rather than half of it, because half of it is not
     * something the user can copy, cut, or read.
     */
    fun select(anchor: Int, extent: Int) {
        val a = boundaryAt(anchor)
        val raw = extent.coerceIn(0, buffer.length)
        selStart = a
        selEnd = if (raw >= a) boundaryAfter(raw) else boundaryAt(raw)
    }

    fun selectAll() {
        selStart = 0
        selEnd = buffer.length
    }

    /**
     * Move the caret [steps] whole graphemes, extending the selection when [select].
     *
     * Collapsing a selection costs the first step, the way it does in every text field:
     * pressing left with a range showing puts the caret at its left edge and does not
     * also move off it.
     */
    fun moveCaret(steps: Int, select: Boolean) {
        if (steps == 0) return
        val back = steps < 0
        var p = when {
            select -> selEnd
            !hasSelection() -> selStart
            back -> selLo()
            else -> selHi()
        }
        var remaining = abs(steps)
        if (!select && hasSelection()) remaining--
        repeat(remaining) { p = if (back) prevBoundary(p) else nextBoundary(p) }
        if (select) selEnd = p else setCaretExact(p)
    }

    /** One grapheme left ([dir] < 0) or right — the arrow keys. */
    fun stepCaret(dir: Int) = moveCaret(if (dir < 0) -1 else 1, select = false)

    // ── words ────────────────────────────────────────────────────────────────

    fun wordStart(at: Int): Int {
        var i = at.coerceIn(0, buffer.length)
        while (i > 0 && buffer[i - 1].isWhitespace()) i--
        while (i > 0 && !buffer[i - 1].isWhitespace()) i--
        return boundaryAt(i)
    }

    fun wordEnd(at: Int): Int {
        var i = at.coerceIn(0, buffer.length)
        while (i < buffer.length && buffer[i].isWhitespace()) i++
        while (i < buffer.length && !buffer[i].isWhitespace()) i++
        return boundaryAfter(i)
    }

    /**
     * Select the word under [at] — a long press, or the select-word key.
     *
     * A press landing exactly ON a space used to select the words on BOTH sides of it:
     * the two scans each skipped the whitespace first and then ran outwards, so the
     * start came from the word before the space and the end from the word after it, and
     * a long press between two words selected both. A space belongs to the word it
     * follows; with nothing before it, to the word that follows it; and between two
     * spaces there is no word at all, so the run of spaces is what gets selected.
     */
    fun selectWordAt(at: Int) {
        if (buffer.isEmpty()) return
        val p = at.coerceIn(0, buffer.length)
        val inside = when {
            p < buffer.length && !buffer[p].isWhitespace() -> p
            p > 0 && !buffer[p - 1].isWhitespace() -> p - 1
            else -> { selectWhitespaceRun(p); return }
        }
        selStart = wordStart(inside + 1)
        selEnd = wordEnd(inside)
    }

    private fun selectWhitespaceRun(at: Int) {
        var lo = at.coerceIn(0, buffer.length)
        var hi = lo
        while (lo > 0 && buffer[lo - 1].isWhitespace()) lo--
        while (hi < buffer.length && buffer[hi].isWhitespace()) hi++
        selStart = lo
        selEnd = hi
    }

    // ── edits ────────────────────────────────────────────────────────────────

    /**
     * Replace the selection — or insert at the caret — with [s]; the caret lands after it.
     *
     * The caret is set exactly, not snapped: the user just put that text there, so the
     * position after it is authoritative even when what follows would otherwise combine
     * with it into one cluster. Snapping here would walk the caret back over the
     * character that was just typed.
     */
    fun insert(s: String) {
        if (s.isEmpty()) return
        val lo = selLo()
        buffer.replace(lo, selHi(), s)
        invalidate()
        setCaretExact(lo + s.length)
    }

    /** Drop the selected range; false when there was none. */
    fun deleteSelection(): Boolean {
        if (!hasSelection()) return false
        val lo = selLo()
        buffer.delete(lo, selHi())
        invalidate()
        setCaretExact(lo)
        return true
    }

    /**
     * Backspace: the selection if there is one, otherwise the whole grapheme before the
     * caret — one flag, one skin-toned face, one emoji-plus-variation-selector, gone in
     * one press, rather than a press that leaves half a character behind.
     */
    fun deleteBackward() {
        if (deleteSelection()) return
        val at = selLo()
        if (at <= 0) return
        val from = prevBoundary(at)
        buffer.delete(from, at)
        invalidate()
        setCaretExact(from)
    }

    /**
     * Replace the WHOLE buffer — a rewrite, a summary, a generated text landing in the
     * box — remembering what was there so [undo] can give it back.
     *
     * This is the one loss worth insuring against in a box like this: the user types or
     * edits something, presses the button, and a model's output arrives on top of it.
     *
     * ponytail: one level of undo, not a stack. Per-keystroke undo is the upgrade path
     * if it is ever asked for; it is not what "get my text back" means here.
     */
    fun replaceAll(s: String) {
        if (buffer.isNotEmpty()) {
            undoText = buffer.toString()
            undoCaret = selStart
        }
        buffer.setLength(0)
        buffer.append(s)
        invalidate()
        setCaretExact(buffer.length)
    }

    /** Empty the box, remembering it: Clear by accident is the other way text is lost. */
    fun clear() = replaceAll("")

    fun undo(): Boolean {
        val previous = undoText ?: return false
        undoText = null
        buffer.setLength(0)
        buffer.append(previous)
        invalidate()
        setCaretExact(undoCaret)
        return true
    }

    /** A fresh session: nothing in the box, and nothing behind it worth restoring. */
    fun reset() {
        buffer.setLength(0)
        invalidate()
        setCaretExact(0)
        undoText = null
    }
}
