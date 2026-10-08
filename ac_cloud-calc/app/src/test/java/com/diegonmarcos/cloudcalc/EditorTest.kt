package com.diegonmarcos.cloudcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The one input model: every key and the soft keyboard edit text AND selection together. */
class EditorTest {
    private val k = { label: String, insert: String -> Declarations.Key(label, insert) }
    private fun e(text: String, start: Int = text.length, end: Int = start) = Edit.of(text, start, end)
    /** The value as text with the cursor drawn as | (a selection as |sel|). */
    private fun Edit.show() = before + "|" + (if (hasSelection) text.substring(lo, hi) + "|" else "") + after

    @Test fun `a key inserts at the start, the middle and the end, and the cursor follows`() {
        assertEquals("(|2+2", Editor.insert(e("2+2", 0), "(").show())
        assertEquals("2+(|2", Editor.insert(e("2+2", 2), "(").show())
        assertEquals("2+2(|", Editor.insert(e("2+2"), "(").show())
        assertEquals("sqrt(|2+2", Editor.press(e("2+2", 0), k("√", "sqrt(")).show())
    }

    @Test fun `a selection is replaced by what is typed, then the cursor sits after it`() {
        assertEquals("600/4|", Editor.insert(e("600/3", 4, 5), "4").show())
        assertEquals("(|", Editor.insert(e("600/3", 0, 5), "(").show())
        assertEquals("600/4|", Editor.insert(e("600/3", 5, 4), "4").show())   // a backwards selection is the same selection
        assertEquals("6|3", Editor.insert(e("600/3", 1, 4), "").show())
    }

    @Test fun `backspace deletes the selection, else the character before the cursor`() {
        assertEquals("15|3", Editor.backspace(e("1523", 2, 3)).show())
        assertEquals("1|3", Editor.backspace(e("123", 2)).show())
        assertEquals("|123", Editor.backspace(e("123", 0)).show())
        assertEquals("12|", Editor.backspace(e("123")).show())
        assertEquals("|3", Editor.backspace(e("123", 0, 2)).show())
        assertEquals("a|", Editor.backspace(e("a😀")).show())        // an emoji is one character
        assertEquals("", Editor.press(e("12+3", 2), k("AC", "AC")).text)
        assertEquals("1|+3", Editor.press(e("12+3", 2), k("DEL", "DEL")).show())
        assertEquals(e("12+3", 1), Editor.press(e("12+3", 1), k("=", "=")))
    }

    @Test fun `parentheses and function openers go where the cursor is`() {
        assertEquals("(|600/3", Editor.press(e("600/3", 0), k("(", "(")).show())
        assertEquals("600/(|3", Editor.press(e("600/3", 4), k("(", "(")).show())
        assertEquals("(600/3)|", Editor.press(e("(600/3"), k(")", ")")).show())
        assertEquals("(600)|/3", Editor.press(e("(600/3", 4), k(")", ")")).show())
        assertEquals("sin(|", Editor.press(Edit.at(""), k("sin", "sin(")).show())
    }

    @Test fun `editing after a result evaluates the edited text, 600 over 3 then 600 over 4`() {
        // = puts the result in the field with the cursor at its end ...
        val afterEquals = Editor.replaceAll("200")
        assertEquals(3, afterEquals.start)
        // ... and an edit of the original expression is just another edit of the value.
        val edited = Editor.insert(e("600/3", 4, 5), "4")
        assertEquals("600/4", edited.text)
        assertFalse(edited.hasSelection)
        assertEquals("600/4+|", Editor.insert(edited, "+").show())
    }

    @Test fun `a reused history entry fills an empty field and is typed at the cursor otherwise, then editing goes on`() {
        assertEquals("6*7|", Editor.reuse(Edit.at(""), "6*7").show())
        assertEquals("1+6*7|+2", Editor.reuse(e("1++2", 2), "6*7").show())
        assertEquals("(|2", Editor.reuse(e("2", 0), "(").show())
        val reused = Editor.reuse(Edit.at(""), "6*7")
        assertEquals("6*8|", Editor.insert(Editor.backspace(reused), "8").show())
    }

    @Test fun `autocomplete replaces the word before the cursor, not the last word of the text`() {
        assertEquals("sq", Editor.wordBeforeCursor(e("2 + sq", 6)))
        assertEquals("sqrt|(", Editor.complete(e("sq(", 2), "sqrt").show())
        assertEquals("2 + sqrt|", Editor.complete(e("2 + sq"), "sqrt").show())
        assertEquals("", Editor.wordBeforeCursor(e("2 + 3", 5)))
    }
}
