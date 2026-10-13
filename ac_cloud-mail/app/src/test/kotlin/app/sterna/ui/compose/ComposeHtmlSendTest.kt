package app.sterna.ui.compose

import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the composer hands the send as HTML: inline pictures by Content-ID, a reply's quote kept as HTML. */
class ComposeHtmlSendTest {

    @Test fun `an inline picture's marker becomes a cid image in the html`() {
        val body = RichBody.plain("Look:\n[image: cat.png]\nnice")
        val html = htmlBodyWithSignature(body, "", "", false, null, mapOf("[image: cat.png]" to "img1@cloud-mail"))
        assertTrue(html, html.contains("<img src=\"cid:img1@cloud-mail\" alt=\"cat.png\" style=\"max-width:100%\">"))
        assertFalse(html, html.contains("[image: cat.png]"))
    }

    @Test fun `a picture marker the user deleted writes no image`() {
        val html = htmlBodyWithSignature(RichBody.plain("no picture"), "", "", false, null, mapOf("[image: cat.png]" to "x@y"))
        assertFalse(html.contains("<img"))
    }

    @Test fun `markers are unique per picture`() {
        val first = inlineImageMarker("cat.png", emptyList())
        assertEquals("[image: cat.png]", first)
        assertEquals("[image: cat.png (2)]", inlineImageMarker("cat.png", listOf(first)))
    }

    @Test fun `a reply whose own text is bold still sends the original as an html blockquote`() {
        val attribution = "On Mon, 5 Oct 2026, Ana wrote:"
        val block = "$attribution\n> Offer"
        val quote = QuoteForHtml(block, buildQuoteHtml(attribution, "<p style=\"color:#0b57d0\"><b>Offer</b></p>", "Offer"))
        val body = RichBody("Deal.\n\n$block", mapOf(Inline.BOLD to listOf(Span(0, 4))))
        val html = htmlBodyWithSignature(body, "", "", false, quote)
        assertTrue(html, html.startsWith("<b>Deal</b>.<br><br><div>On Mon, 5 Oct 2026, Ana wrote:</div><blockquote type=\"cite\""))
        assertTrue("the original's formatting and inline css survive", html.contains("<b>Offer</b>") && html.contains("color:#0b57d0"))
    }
}
