package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every HTML message — a new one, a reply, a forward — leaves as multipart/alternative: text/plain first,
 * text/html last. The earlier version of this test passed nowhere: OutgoingMime still wrote a single
 * text/html part, and core:imap's JVM tests were not run by CI, so nobody saw it red.
 */
class ReplyIsMultipartAlternativeTest {
    private fun msg(html: String?, body: String, attachments: List<OutgoingAttachment> = emptyList()) = OutgoingMessage(
        from = "Me <me@example.org>", to = listOf("ana@example.org"), subject = "Re: Offer",
        body = body, html = html, inReplyTo = "<a@x>", references = "<a@x>",
        messageId = "m1@example.org", dateMillis = 1_750_000_000_000L, attachments = attachments,
    )

    @Test fun `a reply carries a text part and an html part with the blockquote`() {
        val mime = OutgoingMime.build(
            msg(
                html = "Thanks.<br><br><div>On Monday, Ana wrote:</div><blockquote type=\"cite\"><h1>Offer</h1></blockquote>",
                body = "Thanks.\n\nOn Monday, Ana wrote:\n> Offer",
            ),
        )
        assertTrue(mime, mime.contains("Content-Type: multipart/alternative; boundary="))
        val text = mime.indexOf("Content-Type: text/plain")
        val html = mime.indexOf("Content-Type: text/html")
        assertTrue("both parts, text first", text in 0 until html)
        val parts = MimeTestParts.decodedLeaves(mime)
        assertTrue(parts.getValue("text/html").contains("<blockquote type=\"cite\"><h1>Offer</h1></blockquote>"))
        assertTrue("the fallback keeps the quote as lines", parts.getValue("text/plain").contains("> Offer"))
    }

    @Test fun `a new message with formatting is alternative, the plain half derived when none is given`() {
        val mime = OutgoingMime.build(msg(html = "<b>Hello</b> <i>you</i><ul><li>one</li><li>two</li></ul>", body = ""))
        val parts = MimeTestParts.decodedLeaves(mime)
        assertEquals("<b>Hello</b> <i>you</i><ul><li>one</li><li>two</li></ul>", parts.getValue("text/html"))
        val plain = parts.getValue("text/plain")
        assertTrue(plain, plain.startsWith("Hello you"))
        assertTrue(plain, plain.contains("- one") && plain.contains("- two"))
    }

    @Test fun `inline images go in a related inside the alternative, and files wrap it in a mixed`() {
        val img = OutgoingAttachment("dot.png", "image/png", byteArrayOf(1, 2, 3), cid = "img1@sterna", inline = true)
        val file = OutgoingAttachment("a.pdf", "application/pdf", byteArrayOf(4, 5), cid = null, inline = false)
        val mime = OutgoingMime.build(msg(html = "<p>Look</p><img src=\"cid:img1@sterna\">", body = "Look", attachments = listOf(img, file)))
        val mixed = mime.indexOf("Content-Type: multipart/mixed")
        val alt = mime.indexOf("Content-Type: multipart/alternative")
        val related = mime.indexOf("Content-Type: multipart/related; type=\"text/html\"")
        val html = mime.indexOf("Content-Type: text/html")
        val cid = mime.indexOf("Content-ID: <img1@sterna>")
        val pdf = mime.indexOf("Content-Type: application/pdf")
        assertTrue(mime, mixed in 0 until alt && alt < related && related < html && html < cid && cid < pdf)
        assertTrue(mime.contains("Content-Disposition: inline; filename=\"dot.png\""))
        // The text half is NOT inside the related: it precedes it.
        assertTrue(mime.indexOf("Content-Type: text/plain") in alt until related)
    }

    @Test fun `a plain message without html stays one text part`() {
        val mime = OutgoingMime.build(msg(html = null, body = "hi"))
        assertTrue(!mime.contains("multipart/"))
        assertTrue(mime.contains("Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\naGk="))
    }
}

/** Test-only: every leaf part's Content-Type (bare) to its base64-decoded UTF-8 content. */
internal object MimeTestParts {
    fun decodedLeaves(mime: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val leaf = Regex("Content-Type: (text/[a-z]+)[^\r\n]*\r\nContent-Transfer-Encoding: base64\r\n\r\n([A-Za-z0-9+/=\r\n]+?)(?:\r\n--|$)")
        for (m in leaf.findAll(mime)) {
            out[m.groupValues[1]] = String(java.util.Base64.getMimeDecoder().decode(m.groupValues[2]), Charsets.UTF_8)
        }
        return out
    }
}
