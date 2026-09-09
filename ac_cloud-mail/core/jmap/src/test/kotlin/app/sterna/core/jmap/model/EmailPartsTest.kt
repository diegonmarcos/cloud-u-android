package app.sterna.core.jmap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure partitioning of attachments into inline images vs. downloadable files, both transports. */
class EmailPartsTest {
    private fun email(parts: List<EmailBodyPart>) = Email(id = "1", attachments = parts)

    @Test
    fun plainTextInHtmlBodyIsNotTreatedAsHtml() {
        // RFC 8621: a plain-text-only message gets its text/plain part listed in BOTH textBody
        // and htmlBody. htmlContent() must NOT return it as HTML (issue #4: would collapse the
        // paragraph breaks); textContent() should still return it for the <pre> render path.
        val part = EmailBodyPart(partId = "0", type = "text/plain")
        val e = Email(
            id = "1",
            textBody = listOf(part),
            htmlBody = listOf(part),
            bodyValues = mapOf("0" to EmailBodyValue(value = "Para one.\n\nPara two.")),
        )
        assertNull("plain text in htmlBody is not HTML", e.htmlContent())
        assertEquals("Para one.\n\nPara two.", e.textContent())
    }

    @Test
    fun genuineHtmlPartIsHtmlContent() {
        val html = EmailBodyPart(partId = "1", type = "text/html")
        val text = EmailBodyPart(partId = "0", type = "text/plain")
        val e = Email(
            id = "1",
            textBody = listOf(text),
            htmlBody = listOf(html),
            bodyValues = mapOf(
                "1" to EmailBodyValue(value = "<p>Hi</p>"),
                "0" to EmailBodyValue(value = "Hi"),
            ),
        )
        assertEquals("<p>Hi</p>", e.htmlContent())
        assertEquals("Hi", e.textContent())
    }

    @Test
    fun jmapInlineImageIsInlineNotFile() {
        val inline = EmailBodyPart(blobId = "b1", cid = "logo@x", type = "image/png")
        val file = EmailBodyPart(blobId = "b2", name = "doc.pdf", type = "application/pdf")
        val e = email(listOf(inline, file))
        assertEquals(listOf(inline), e.inlineImageParts())
        assertEquals(listOf(file), e.fileAttachmentParts())
    }

    @Test
    fun imapInlineImageByPartIdIsInlineNotFile() {
        // IMAP parts carry a partId (the MIME section) and no blobId; a Content-ID + image type
        // marks them inline.
        val inline = EmailBodyPart(partId = "2", cid = "logo@x", type = "image/png", disposition = "inline")
        val file = EmailBodyPart(partId = "3", name = "doc.pdf", type = "application/pdf")
        val e = email(listOf(inline, file))
        assertEquals(listOf(inline), e.inlineImageParts())
        assertEquals(listOf(file), e.fileAttachmentParts())
        assertFalse("inline image is not a file row", e.fileAttachmentParts().contains(inline))
    }

    @Test
    fun imageWithoutCidIsAFileNotInline() {
        val noCid = EmailBodyPart(partId = "2", name = "photo.png", type = "image/png")
        val e = email(listOf(noCid))
        assertTrue(e.inlineImageParts().isEmpty())
        assertEquals(listOf(noCid), e.fileAttachmentParts())
    }
}
