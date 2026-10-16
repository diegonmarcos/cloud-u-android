package app.sterna.ui.settings

import app.sterna.ui.settings.SignatureHtmlTools.Edit
import app.sterna.ui.settings.SignatureHtmlTools.Kind
import app.sterna.ui.settings.SignatureHtmlTools.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureEditingTest {

    /** The field after typing [c] at [at] in [text]. */
    private fun typed(text: String, at: Int, c: String): Pair<Edit, Edit> =
        Edit(text, at, at) to Edit(text.substring(0, at) + c + text.substring(at), at + c.length, at + c.length)

    private fun after(text: String, at: Int, c: String): Edit? {
        val (b, a) = typed(text, at, c)
        return SignatureEditing.afterTyping(b, a)
    }

    // -- highlighting (the editor paints exactly these spans) ----------------------

    @Test fun commentsTagsAttributesValuesAndEntitiesAreToldApart() {
        val src = "<!-- c --><a href=\"u\">x&amp;y</a>"
        val kinds = SignatureHtmlTools.highlight(src).map { it.kind }
        assertEquals(
            listOf(Kind.COMMENT, Kind.TAG, Kind.ATTRIBUTE, Kind.VALUE, Kind.TAG, Kind.ENTITY, Kind.TAG, Kind.TAG),
            kinds,
        )
        assertEquals(Span(0, 10, Kind.COMMENT), SignatureHtmlTools.highlight(src).first())
    }

    @Test fun anUnterminatedCommentOrStringPaintsToTheEndWithoutThrowing() {
        assertEquals(Span(2, 8, Kind.COMMENT), SignatureHtmlTools.highlight("a <!-- b").single())
        val spans = SignatureHtmlTools.highlight("<a href=\"x")
        assertEquals(Span(8, 10, Kind.VALUE), spans.last())
    }

    // -- auto-indent / auto-close ---------------------------------------------

    @Test fun enterKeepsTheIndentOfThePreviousLine() {
        val e = after("  <b>x</b>", 10, "\n")!!
        assertEquals("  <b>x</b>\n  ", e.text)
        assertEquals(13, e.start)
    }

    @Test fun enterAfterAnOpeningTagIndentsOneLevelDeeper() {
        val e = after("<div>", 5, "\n")!!
        assertEquals("<div>\n  ", e.text)
    }

    @Test fun enterBetweenOpenAndCloseOpensABlock() {
        val e = after("<div></div>", 5, "\n")!!
        assertEquals("<div>\n  \n</div>", e.text)
        assertEquals(8, e.start)
    }

    @Test fun enterAfterAVoidTagDoesNotIndent() {
        assertEquals("<br>\n", after("<br>", 4, "\n")!!.text)
    }

    @Test fun typingTheEndOfAnOpeningTagAddsItsClosingTag() {
        val e = after("<div", 4, ">")!!
        assertEquals("<div></div>", e.text)
        assertEquals(5, e.start)
        assertEquals("<a href=\"x\"></a>", after("<a href=\"x\"", 11, ">")!!.text)
    }

    @Test fun noClosingTagForVoidClosingSelfClosedOrAlreadyClosed() {
        assertNull(after("<br", 3, ">"))
        assertNull(after("</div", 5, ">"))
        assertNull(after("<img src=\"x\" /", 14, ">"))
        assertNull(after("a ", 2, ">"))
        // already closed right after the caret: nothing is added twice
    }

    @Test fun aQuoteInsideATagIsPairedAndTypedOver() {
        val paired = after("<a href=", 8, "\"")!!
        assertEquals("<a href=\"\"", paired.text)
        assertEquals(9, paired.start)
        // typing the closing quote steps over the one that was inserted
        val over = after("<a href=\"u\"", 10, "\"")!!
        assertEquals("<a href=\"u\"", over.text)
        assertEquals(11, over.start)
    }

    @Test fun anApostropheInTextIsLeftAlone() {
        assertNull(after("don", 3, "'"))
        assertNull(after("<b>don", 6, "'"))
    }

    @Test fun pasteAndMultiCharacterChangesAreNotTouched() {
        val (b, a) = typed("<div", 4, ">>")
        assertNull(SignatureEditing.afterTyping(b, a))
        assertNull(SignatureEditing.afterTyping(Edit("ab", 1, 2), Edit("a>", 2, 2)))
    }

    // -- tab ---------------------------------------------------------------------

    @Test fun tabInsertsTwoSpaces() {
        val e = SignatureEditing.tab("ab", 1, 1)
        assertEquals("a  b", e.text)
        assertEquals(3, e.start)
    }

    @Test fun tabOverSeveralLinesIndentsEachAndShiftTabUndoesIt() {
        val src = "a\nb\nc"
        val indented = SignatureEditing.tab(src, 0, 5)
        assertEquals("  a\n  b\n  c", indented.text)
        val back = SignatureEditing.tab(indented.text, indented.start, indented.end, outdent = true)
        assertEquals(src, back.text)
    }

    @Test fun shiftTabOnOneLineRemovesUpToTwoSpaces() {
        assertEquals("x", SignatureEditing.tab("  x", 3, 3, outdent = true).text)
        assertEquals("x", SignatureEditing.tab("x", 1, 1, outdent = true).text)
    }

    // -- toolbar inserts ----------------------------------------------------------

    @Test fun fontSizeAndAlignWrapTheSelection() {
        val size = SignatureEditing.fontSize("Hi", 0, 2, 18)!!
        assertEquals("<span style=\"font-size:18px\">Hi</span>", size.text)
        assertNull(SignatureEditing.fontSize("Hi", 0, 2, 200))
        assertEquals("<div style=\"text-align:center\">Hi</div>", SignatureEditing.align("Hi", 0, 2, SignatureEditing.Align.CENTER).text)
        assertEquals("<s>Hi</s>", SignatureEditing.strike("Hi", 0, 2).text)
    }

    @Test fun tableUsesInlineStylesAndPutsTheCaretInTheFirstCell() {
        val e = SignatureEditing.table("", 0, 0, 2, 3)!!
        assertEquals(6, Regex("<td ").findAll(e.text).count())
        assertEquals(2, Regex("<tr>").findAll(e.text).count())
        assertTrue(e.text.startsWith("<table"))
        assertTrue(e.text.substring(0, e.start).endsWith(">"))
        assertTrue(e.text.substring(e.start).startsWith("</td>"))
        assertTrue(SignatureValidator.validate(e.text).none { it.severity == SignatureValidator.Severity.ERROR })
        assertNull(SignatureEditing.table("", 0, 0, 0, 3))
    }

    @Test fun dividerAndSocialInsertValidMarkup() {
        val d = SignatureEditing.divider("ab", 1, 1)
        assertTrue(d.text.startsWith("a<hr style="))
        val s = SignatureEditing.social("", 0, 0, listOf(SignatureEditing.Social("GitHub", "github.com/x"), SignatureEditing.Social("Bad", "javascript:alert(1)")))!!
        assertTrue(s.text, s.text.contains("href=\"https://github.com/x\""))
        assertTrue(!s.text.contains("javascript"))
        assertNull(SignatureEditing.social("", 0, 0, listOf(SignatureEditing.Social("X", ""))))
        assertTrue(SignatureValidator.validate(s.text).isEmpty())
    }

    // -- find ----------------------------------------------------------------------

    @Test fun findIsCaseInsensitiveByDefaultAndStepsWithWrap() {
        val m = SignatureEditing.find("<B>b</b> b", "b")
        assertEquals(listOf(1, 3, 6, 9), m)
        assertEquals(listOf(3, 6, 9), SignatureEditing.find("<B>b</b> b", "b", matchCase = true))
        assertEquals(3, SignatureEditing.step(m, 2, forward = true))
        assertEquals(1, SignatureEditing.step(m, 10, forward = true))
        assertEquals(9, SignatureEditing.step(m, 1, forward = false))
        assertNull(SignatureEditing.step(emptyList(), 0, true))
        assertEquals(emptyList<Int>(), SignatureEditing.find("abc", ""))
        assertEquals("xbc", SignatureEditing.replaceAll("abc", "a", "x"))
    }

    // -- import ----------------------------------------------------------------------

    @Test fun aWholePageIsReducedToItsBodyAndAFragmentIsOnlyTrimmed() {
        val page = "<!DOCTYPE html><html><head><title>t</title></head><BODY class=\"x\">\n<b>Hi</b>\n</body></html>"
        assertEquals("<b>Hi</b>", SignatureEditing.importFragment(page))
        assertEquals("<b>Hi</b>", SignatureEditing.importFragment("\uFEFF  <b>Hi</b>\n"))
        assertEquals("<b>Hi</b>", SignatureEditing.importFragment("<html><b>Hi</b></html>"))
        assertEquals("plain", SignatureEditing.importFragment("plain"))
    }
}
