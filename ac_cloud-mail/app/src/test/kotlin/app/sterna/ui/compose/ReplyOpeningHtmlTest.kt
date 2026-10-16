package app.sterna.ui.compose

import app.sterna.core.data.text.BLOCK_FORWARD
import app.sterna.core.data.text.BLOCK_QUOTE
import app.sterna.core.data.text.BLOCK_SIGNATURE
import app.sterna.core.data.text.composeOpeningHtml
import app.sterna.core.data.text.editorBlock
import app.sterna.core.data.text.htmlForWire
import app.sterna.core.data.text.signatureEditorBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reply, a reply-all and a forward OPEN with, executed: the original quoted as HTML in a
 * blockquote under the "On <date>, <name> wrote:" line, the signature above it, a forward's header
 * block and body inside the editor - and all of it surviving to the wire.
 */
class ReplyOpeningHtmlTest {
    private val attribution = "On Mon 5 Oct, Ana wrote:"

    private fun reply(originalHtml: String?, originalText: String): String {
        val quote = editorBlock(BLOCK_QUOTE, buildQuoteHtml(attribution, originalHtml, originalText))
        val sig = signatureEditorBlock("<b>Alex</b>", "Alex", delimiter = true)
        return composeOpeningHtml("", sig, quote, signatureBelowQuote = false)
    }

    @Test fun `a reply to an html mail opens with its html in a blockquote under the attribution`() {
        val html = reply("<p>Offer <b>attached</b></p><script>x()</script>", "Offer attached")
        assertTrue(html, html.startsWith("<div><br></div>"))
        val sig = html.indexOf("data-sterna=\"$BLOCK_SIGNATURE\"")
        val quote = html.indexOf("data-sterna=\"$BLOCK_QUOTE\"")
        assertTrue("signature above the quote: $html", sig in 0 until quote)
        assertTrue(html, "<div>$attribution</div><blockquote type=\"cite\"" in html)
        assertTrue(html, "<b>attached</b>" in html)
        assertFalse("sanitised: $html", "<script" in html)
    }

    @Test fun `a reply to a text-only mail quotes it as escaped html with its line breaks`() {
        val html = reply(null, "a < b\nsecond line")
        assertTrue(html, "<blockquote type=\"cite\"" in html)
        assertTrue(html, "a &lt; b<br>second line" in html)
    }

    @Test fun `what the editor opened with leaves as html with the quote intact`() {
        val wire = htmlForWire(reply("<p>Offer</p>", "Offer"))
        assertTrue(wire, "<blockquote" in wire && "Offer" in wire)
        assertTrue(wire, "$attribution" in wire)
        assertFalse(wire, "data-sterna" in wire)
    }

    @Test fun `a forward carries the header block and the original html inside the body`() {
        val blocks = buildForwardedBlocks(
            from = "Ana <ana@example.org>", subject = "Offer", date = "Mon 5 Oct", to = "me@example.org",
            originalText = "Offer", originalHtml = "<p>The <i>offer</i></p>",
        )
        val html = composeOpeningHtml("", "", editorBlock(BLOCK_FORWARD, blocks.html), false)
        assertTrue(html, "---------- Forwarded message ----------" in html)
        assertTrue(html, "From: Ana" in html && "Subject: Offer" in html)
        assertTrue(html, "<i>offer</i>" in html)
        val wire = htmlForWire(html)
        assertTrue(wire, "Forwarded message" in wire && "<i>offer</i>" in wire)
    }
}
