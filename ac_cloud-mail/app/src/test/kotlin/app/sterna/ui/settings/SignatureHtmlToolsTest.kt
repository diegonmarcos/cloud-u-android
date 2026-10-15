package app.sterna.ui.settings

import app.sterna.ui.settings.SignatureHtmlTools.Edit
import app.sterna.ui.settings.SignatureHtmlTools.History
import app.sterna.ui.settings.SignatureHtmlTools.Kind
import app.sterna.ui.settings.SignatureHtmlTools.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signature editor's pure half: what the toolbar writes into the SOURCE, what is allowed into it,
 * what the preview is handed, how the source is painted and how undo behaves. The toolbar must only
 * ever produce markup the sanitiser keeps and a recipient's client will draw, and must refuse an
 * address that could carry script.
 */
class SignatureHtmlToolsTest {

    // -- wrapping ------------------------------------------------------------

    @Test fun boldWrapsTheSelectionAndKeepsItSelected() {
        assertEquals(Edit("<b>hello</b> world", 3, 8), SignatureHtmlTools.bold("hello world", 0, 5))
    }

    @Test fun aSecondPressOnTheSameSelectionTakesTheTagsBackOff() {
        assertEquals(Edit("hello world", 0, 5), SignatureHtmlTools.bold("<b>hello</b> world", 3, 8))
    }

    @Test fun withNothingSelectedThePairIsInsertedAndTheCursorSitsBetween() {
        assertEquals(Edit("a<i></i>b", 4, 4), SignatureHtmlTools.italic("ab", 1, 1))
    }

    @Test fun aReversedSelectionAndOutOfRangeOffsetsAreTolerated() {
        assertEquals(Edit("<u>hello</u> world", 3, 8), SignatureHtmlTools.underline("hello world", 5, 0))
        assertEquals(Edit("ab<u></u>", 5, 5), SignatureHtmlTools.underline("ab", 99, 99))
    }

    // -- link ----------------------------------------------------------------

    @Test fun aLinkTakesTheSelectionAsItsLabel() {
        val edit = SignatureHtmlTools.link("see site", 4, 8, "example.com")!!
        assertEquals("see <a href=\"https://example.com\">site</a>", edit.text)
        assertEquals("site", edit.text.substring(edit.start, edit.end))
    }

    @Test fun aLinkWithNothingSelectedIsLabelledByItsAddressEscaped() {
        val edit = SignatureHtmlTools.link("", 0, 0, "https://a.b/x?y=1&z=2")!!
        assertEquals("<a href=\"https://a.b/x?y=1&amp;z=2\">https://a.b/x?y=1&amp;z=2</a>", edit.text)
    }

    @Test fun onlyWebMailAndPhoneAddressesMakeALink() {
        assertEquals("https://example.com", SignatureHtmlTools.safeLinkUrl("  example.com "))
        assertEquals("mailto:a@b.c", SignatureHtmlTools.safeLinkUrl("mailto:a@b.c"))
        assertEquals("tel:+3312", SignatureHtmlTools.safeLinkUrl("tel:+3312"))
        assertNull(SignatureHtmlTools.safeLinkUrl("javascript:alert(1)"))
        assertNull(SignatureHtmlTools.safeLinkUrl("ftp://x.y"))
        assertNull(SignatureHtmlTools.safeLinkUrl("data:text/html,<b>"))
        assertNull(SignatureHtmlTools.safeLinkUrl("localhost"))
        assertNull(SignatureHtmlTools.safeLinkUrl("a b.c"))
        assertNull(SignatureHtmlTools.safeLinkUrl("https://a.b\"onmouseover=x"))
        assertNull(SignatureHtmlTools.safeLinkUrl(""))
        assertNull(SignatureHtmlTools.link("x", 0, 1, "javascript:alert(1)"))
    }

    // -- image ---------------------------------------------------------------

    @Test fun anImageIsInsertedAtTheCursorWithTheSelectionAsAlt() {
        val tag = "<img src=\"http://a.b/c.png\" alt=\"\">"
        assertEquals(Edit(tag, tag.length, tag.length), SignatureHtmlTools.image("", 0, 0, "http://a.b/c.png"))
        val withAlt = SignatureHtmlTools.image("A \"B\"", 0, 5, "https://a.b/c.png")!!
        assertEquals("<img src=\"https://a.b/c.png\" alt=\"A &quot;B&quot;\">", withAlt.text)
    }

    @Test fun anImageAddressMustBeHttpOrHttps() {
        assertNull(SignatureHtmlTools.image("x", 0, 0, "data:image/png;base64,AAA"))
        assertNull(SignatureHtmlTools.image("x", 0, 0, "mailto:a@b.c"))
        assertNull(SignatureHtmlTools.image("x", 0, 0, "javascript:alert(1)"))
        assertEquals("https://a.b/c.png", SignatureHtmlTools.safeImageUrl("a.b/c.png"))
    }

    // -- colour --------------------------------------------------------------

    @Test fun aColourIsASixDigitHexInASpanAndNothingElse() {
        assertEquals(Edit("<span style=\"color:#cc0000\">hi</span>", 28, 30), SignatureHtmlTools.colour("hi", 0, 2, "#CC0000"))
        assertNull(SignatureHtmlTools.colour("hi", 0, 2, "red"))
        assertNull(SignatureHtmlTools.colour("hi", 0, 2, "#fff"))
        assertNull(SignatureHtmlTools.colour("hi", 0, 2, "#cc0000;background:url(x)"))
    }

    // -- derived plain text and preview --------------------------------------

    @Test fun thePlainTextIsDerivedWithLinkTargetsSpelledOut() {
        val text = SignatureHtmlTools.plainText("<b>Hi</b><br><a href=\"https://x.y/z\">Site</a>")
        assertTrue(text, text.startsWith("Hi"))
        assertTrue(text, text.contains("Site"))
        assertTrue(text, text.contains("https://x.y/z"))
    }

    @Test fun thePreviewDocumentCarriesOnlySanitisedMarkupUnderAClosedPolicy() {
        val doc = SignatureHtmlTools.previewDocument("<p>ok</p><script>alert(1)</script><img src=\"https://a.b/c.png\" onerror=\"alert(2)\">")
        assertTrue(doc.contains("<p>ok</p>"))
        assertTrue(doc.contains("https://a.b/c.png"))
        assertFalse(doc, doc.contains("alert"))
        assertFalse(doc, doc.contains("onerror"))
        assertTrue(doc.contains("default-src 'none'"))
        assertTrue(doc.contains("img-src https: http: data:"))
    }

    // -- highlighting --------------------------------------------------------

    @Test fun tagsAttributesValuesAndEntitiesArePaintedInOrder() {
        assertEquals(
            listOf(
                Span(0, 2, Kind.TAG),
                Span(3, 7, Kind.ATTRIBUTE),
                Span(8, 11, Kind.VALUE),
                Span(11, 12, Kind.TAG),
                Span(12, 17, Kind.ENTITY),
            ),
            SignatureHtmlTools.highlight("<a href=\"x\">&amp;"),
        )
        assertEquals(listOf(Span(0, 3, Kind.TAG), Span(3, 4, Kind.TAG)), SignatureHtmlTools.highlight("</a>"))
    }

    @Test fun aCommentIsOneSpanAndMarkupInsideItIsNotPainted() {
        assertEquals(listOf(Span(1, 11, Kind.COMMENT)), SignatureHtmlTools.highlight("x<!-- c -->y"))
        assertEquals(listOf(Span(0, 8, Kind.COMMENT)), SignatureHtmlTools.highlight("<!-- <b>"))
    }

    @Test fun plainTextAndHalfTypedMarkupNeverThrowAndSpansStayInRange() {
        val samples = listOf("", "plain text", "a < b", "<", "<b", "<a href=\"x", "<a href=x>", "&", "&amp", "&;", "<<>>", "<a =>", "<!", "</")
        for (s in samples) {
            var end = 0
            for (span in SignatureHtmlTools.highlight(s)) {
                assertTrue("$s: $span", span.start in end..span.end && span.end <= s.length)
                end = span.end
            }
        }
        assertTrue(SignatureHtmlTools.highlight("Alex Rivera, Acme").isEmpty())
        assertTrue(SignatureHtmlTools.highlight("a < b & c").isEmpty())
    }

    // -- history -------------------------------------------------------------

    @Test fun undoAndRedoWalkTheStepsAndANewEditDropsTheRedoBranch() {
        val h = History(Edit("", 0, 0))
        assertFalse(h.canUndo)
        h.record(Edit("a", 1, 1))
        h.record(Edit("ab", 2, 2))
        assertEquals("a", h.undo()!!.text)
        assertEquals("", h.undo()!!.text)
        assertNull(h.undo())
        assertEquals("a", h.redo()!!.text)
        h.record(Edit("ax", 2, 2))
        assertFalse(h.canRedo)
        assertNull(h.redo())
        assertEquals("a", h.undo()!!.text)
    }

    @Test fun aBurstOfTypingIsOneStepAndAToolbarActionIsAStepOfItsOwn() {
        val h = History(Edit("", 0, 0))
        h.record(Edit("a", 1, 1), merge = true)
        h.record(Edit("ab", 2, 2), merge = true)
        h.record(Edit("abc", 3, 3), merge = true)
        h.record(Edit("<b>abc</b>", 3, 6))
        h.record(Edit("<b>abcd</b>", 7, 7), merge = true)
        assertEquals("<b>abc</b>", h.undo()!!.text)
        assertEquals("abc", h.undo()!!.text)
        assertEquals("", h.undo()!!.text)
        assertNull(h.undo())
    }

    @Test fun recordingTheSameTextOnlyUpdatesTheSelection() {
        val h = History(Edit("a", 1, 1))
        h.record(Edit("a", 0, 1))
        assertFalse(h.canUndo)
        assertEquals(Edit("a", 0, 1), h.current)
    }

    @Test fun theHistoryIsBounded() {
        val h = History(Edit("0", 0, 0), limit = 3)
        for (i in 1..5) h.record(Edit("$i", 1, 1))
        assertEquals("4", h.undo()!!.text)
        assertEquals("3", h.undo()!!.text)
        assertNull(h.undo())
    }
}
