package com.diegonmarcos.cloudcalc

/**
 * THE ONE INPUT MODEL of the calculator: the text and the selection, the same value the soft keyboard
 * edits and the keypad edits. A keypad press used to append to a String (so "(" typed with the cursor at
 * the start landed at the end, and DEL always ate the last character); now every key is an edit of this
 * value at the cursor. Pure, so the JVM suite holds each rule.
 */
data class Edit(val text: String, val start: Int, val end: Int) {
    init { require(start in 0..text.length && end in 0..text.length) { "selection $start..$end outside 0..${text.length}" } }

    val lo: Int get() = minOf(start, end)
    val hi: Int get() = maxOf(start, end)
    val hasSelection: Boolean get() = start != end
    /** The text before the cursor (or the selection's start). */
    val before: String get() = text.substring(0, lo)
    val after: String get() = text.substring(hi)

    companion object {
        /** [text] with the cursor at its end - what a result or a reused entry becomes. */
        fun at(text: String, cursor: Int = text.length) = Edit(text, cursor.coerceIn(0, text.length), cursor.coerceIn(0, text.length))
        /** A value read from outside (a TextFieldValue), its selection clamped into the text. */
        fun of(text: String, start: Int, end: Int) = Edit(text, start.coerceIn(0, text.length), end.coerceIn(0, text.length))
    }
}

object Editor {
    /** Type [s] at the cursor, replacing any selection; the cursor lands after it. */
    fun insert(e: Edit, s: String): Edit = Edit.at(e.before + s + e.after, e.lo + s.length)

    /** Backspace: the selection if there is one, else the character before the cursor (a surrogate pair is one). */
    fun backspace(e: Edit): Edit {
        if (e.hasSelection) return Edit.at(e.before + e.after, e.lo)
        if (e.lo == 0) return e
        val n = if (e.lo >= 2 && Character.isLowSurrogate(e.text[e.lo - 1]) && Character.isHighSurrogate(e.text[e.lo - 2])) 2 else 1
        return Edit.at(e.text.removeRange(e.lo - n, e.lo), e.lo - n)
    }

    /** A keypad key as an edit: AC clears, DEL is [backspace], = changes nothing, any other key types its text. */
    fun press(e: Edit, key: Declarations.Key): Edit = when (key.action) {
        Declarations.Action.CLEAR -> Edit.at("")
        Declarations.Action.DELETE -> backspace(e)
        Declarations.Action.EVALUATE -> e
        null -> insert(e, key.insert)
    }

    /** Make [s] the whole value (a result after =, a history entry into an empty field), cursor at its end. */
    fun replaceAll(s: String): Edit = Edit.at(s)

    /** Reuse [s] (a history entry): it fills an empty field, else is typed at the cursor like any key. */
    fun reuse(e: Edit, s: String): Edit = if (e.text.isBlank()) replaceAll(s) else insert(e, s)

    /** The word ending at the cursor, for autocomplete (letters, digits, _; it must start with a letter). */
    fun wordBeforeCursor(e: Edit): String = Logic.lastWord(e.before)

    /** Replace the word before the cursor with the completion [name]; the cursor lands after it. */
    fun complete(e: Edit, name: String): Edit {
        val w = wordBeforeCursor(e)
        return Edit.at(e.before.dropLast(w.length) + name + e.after, e.lo - w.length + name.length)
    }
}
