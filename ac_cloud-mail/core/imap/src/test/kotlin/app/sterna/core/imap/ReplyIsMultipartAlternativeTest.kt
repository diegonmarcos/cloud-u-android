package app.sterna.core.imap

import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply with a quoted HTML original leaves as multipart/alternative: plain text first, html last. */
class ReplyIsMultipartAlternativeTest {
    @Test fun `the message carries a text part and an html part with the blockquote`() {
        val mime = OutgoingMime.build(
            OutgoingMessage(
                from = "Me <me@example.org>", to = listOf("ana@example.org"), subject = "Re: Offer",
                body = "Thanks.\n\nOn Monday, Ana wrote:\n> Offer",
                html = "Thanks.<br><br><div>On Monday, Ana wrote:</div><blockquote type=\"cite\"><h1>Offer</h1></blockquote>",
                inReplyTo = "<a@x>", references = "<a@x>", messageId = "m1@example.org", dateMillis = 1_750_000_000_000L,
            ),
        )
        assertTrue(mime, mime.contains("multipart/alternative"))
        val text = mime.indexOf("Content-Type: text/plain")
        val html = mime.indexOf("Content-Type: text/html")
        assertTrue("both parts, text first", text in 0 until html)
        assertTrue(mime.contains("<blockquote type=\"cite\">"))
        assertTrue("the fallback keeps the quote as lines", mime.contains("> Offer"))
    }
}
