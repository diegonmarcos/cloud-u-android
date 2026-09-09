package app.sterna.ui.message

import app.sterna.core.data.text.htmlToText
import app.sterna.core.imap.MimeAttachment
import app.sterna.core.imap.MimeParser
import app.sterna.core.jmap.ContentTooLargeException

/** What the sheet shows of a message/rfc822 attachment. */
data class AttachedMessage(
    val from: String?,
    val to: String?,
    val cc: String?,
    val date: String?,
    val subject: String?,
    /** "" when the message has no text nor html. */
    val body: String,
    val attachmentNames: List<String>,
)

/** Which attachment opens as an attached message rather than being handed to a viewer app. */
internal fun isAttachedMessage(type: String?): Boolean =
    type?.substringBefore(';')?.trim().equals("message/rfc822", ignoreCase = true)

/**
 * Parses the downloaded bytes of a message/rfc822 part. The bytes become MimeParser's faithful byte
 */
internal fun attachedMessageOf(bytes: ByteArray): AttachedMessage {
    val raw = bytes.toString(Charsets.ISO_8859_1)
    val parsed = MimeParser.parseBody(raw)
    if (parsed.tooLarge) {
        throw ContentTooLargeException(
            "attached message is too large",
            raw.length.toLong(),
            MimeParser.MAX_BODY_CHARS.toLong(),
        )
    }
    return AttachedMessage(
        from = MimeParser.decodedHeaderOf(raw, "From"),
        to = MimeParser.decodedHeaderOf(raw, "To"),
        cc = MimeParser.decodedHeaderOf(raw, "Cc"),
        date = MimeParser.decodedHeaderOf(raw, "Date"),
        subject = MimeParser.decodedHeaderOf(raw, "Subject"),
        body = parsed.text ?: parsed.html?.let { htmlToText(it) } ?: "",
        attachmentNames = parsed.attachments.filter { it.isFileAttachment() }.map { it.name },
    )
}

/**
 * The reader's own rule (`Email.fileAttachmentParts`), applied to what [MimeParser] collects.
 */
private fun MimeAttachment.isFileAttachment(): Boolean = when {
    cid != null && type.startsWith("image/") -> false
    type.startsWith("text/calendar") -> false
    type == "application/pgp-encrypted" || type == "application/pgp-signature" -> false
    else -> true
}
