package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The composer's quote, heading and text-colour styles: their HTML, their plain text, their draft round trip. */
class RichBodyQuoteHeadingColorTest {

    private val body = RichBody(
        "Title\nquoted one\nquoted two\nred words",
        mapOf(Inline.RED to listOf(Span(28, 31)), Inline.BOLD to listOf(Span(28, 31))),
        listOf(Block(BlockKind.HEADING, 0..0), Block(BlockKind.QUOTE, 1..2)),
    )

    @Test fun `the html carries a heading, a blockquote and a coloured span`() {
        val html = toHtml(body)
        assertEquals(
            "<header><h2>Title</h2></header><br><blockquote><div>quoted one</div><div>quoted two</div></blockquote><br>" +
                "<b><span style=\"color:#d93025\">red</span></b> words",
            html,
        )
    }

    @Test fun `a draft stores that html and reopens as the very same body`() {
        val stored = draftHtmlToSave(body)
        assertNotNull(stored)
        assertEquals(body, richBodyFrom(stored) { "unused" })
        assertFalse(draftHtmlIsLossy(stored))
    }

    @Test fun `the plain alternative spells the quote out and drops the heading markup`() {
        assertEquals("Title\n> quoted one\n> quoted two\nred words", toPlainText(body))
    }

    @Test fun `one colour per character - choosing another replaces it`() {
        val red = withTextColor(RichBody.plain("abc"), Span(0, 3), Inline.RED)
        val blue = withTextColor(red, Span(1, 2), Inline.BLUE)
        assertEquals(listOf(Span(0, 1), Span(2, 3)), blue.ranges[Inline.RED])
        assertEquals(listOf(Span(1, 2)), blue.ranges[Inline.BLUE])
        val none = withTextColor(blue, Span(0, 3), null)
        assertTrue(none.isPlain)
    }

    @Test fun `a span with another colour or any other attribute is still refused`() {
        assertNull(fromHtml("<span style=\"color:#123456\">x</span>"))
        assertNull(fromHtml("<span onclick=\"x\">x</span>"))
        assertNull(fromHtml("<blockquote><li>x</li></blockquote>"))
    }
}
