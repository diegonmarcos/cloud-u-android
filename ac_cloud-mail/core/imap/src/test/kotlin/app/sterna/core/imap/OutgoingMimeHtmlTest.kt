package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MIME builder with an html body: the part is there, the structure is alternative, the text half is a fallback. */
class OutgoingMimeHtmlTest {
    private fun msg(html: String?, body: String = "", attachments: List<OutgoingAttachment> = emptyList()) = OutgoingMessage(
        from = "me@example.org", to = listOf("ana@example.org"), subject = "Re: Offer",
        body = body, html = html, messageId = "m1@example.org", dateMillis = 1_750_000_000_000L,
        attachments = attachments,
    )

    @Test fun `the html part is present and is the last alternative`() {
        val mime = OutgoingMime.build(msg("<p>Hello <b>you</b></p>"))
        assertTrue(mime, mime.contains("Content-Type: multipart/alternative;"))
        assertTrue(mime.indexOf("Content-Type: text/plain") in 0 until mime.indexOf("Content-Type: text/html"))
        assertEquals("<p>Hello <b>you</b></p>", MimeTestParts.decodedLeaves(mime).getValue("text/html"))
    }

    @Test fun `the text half is generated from the html when none is given`() {
        val html = "<p>Thanks.</p><div>On Mon, Ana wrote:</div><blockquote type=\"cite\"><p>Offer</p></blockquote>" +
            "<ul><li>one</li></ul><p>See <a href=\"https://x.org/a\">the page</a></p>"
        val plain = MimeTestParts.decodedLeaves(OutgoingMime.build(msg(html))).getValue("text/plain")
        assertTrue(plain, plain.startsWith("Thanks."))
        assertTrue(plain, "> Offer" in plain)
        assertTrue(plain, "- one" in plain)
        assertTrue(plain, "the page <https://x.org/a>" in plain)
        assertFalse(plain, "<p>" in plain || "<blockquote" in plain)
    }

    @Test fun `a link whose label is its address is not written twice`() {
        val plain = htmlToPlainText("<a href=\"https://x.org\">https://x.org</a>")
        assertEquals("https://x.org", plain)
    }

    @Test fun `an inline picture sits in a related beside the html, a file wraps it all in a mixed`() {
        val img = OutgoingAttachment("dot.png", "image/png", byteArrayOf(1, 2), cid = "i@x", inline = true)
        val pdf = OutgoingAttachment("a.pdf", "application/pdf", byteArrayOf(3), inline = false)
        val mime = OutgoingMime.build(msg("<img src=\"cid:i@x\">", attachments = listOf(img, pdf)))
        val mixed = mime.indexOf("multipart/mixed")
        val alt = mime.indexOf("multipart/alternative")
        val related = mime.indexOf("multipart/related")
        assertTrue(mime, mixed in 0 until alt && alt < related)
        assertTrue(mime.contains("Content-ID: <i@x>"))
    }
}
