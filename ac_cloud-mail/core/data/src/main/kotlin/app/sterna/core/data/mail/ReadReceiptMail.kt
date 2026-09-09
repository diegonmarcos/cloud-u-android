package app.sterna.core.data.mail

import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.imap.OutgoingMime
import java.util.Base64

/** Nothing in the RFC 8098 notification is translated: a mail client parses it, not a reader. */

internal data class ReadReceiptMail(
    val to: List<String>,
    val subject: String,
    val body: String,
    val fromName: String?,
    val fromEmail: String?,
    /** The `multipart/report` that replaces the body on the wire; see [readReceiptEntity]. */
    val prebuiltEntity: String,
    /** Always false: the user wrote to nobody, so learning the sender is not hers to be taught. */
    val learnRecipients: Boolean = false,
) {
    fun preview(): ReadReceiptPreview = ReadReceiptPreview(subject = subject, body = body)
}

internal const val READ_RECEIPT_SUBJECT = "Read receipt"

/** The strings [readReceiptMail] puts in the outbox, so screen and wire cannot drift apart. */
data class ReadReceiptPreview(
    val subject: String,
    val body: String,
)

/**
 * [originalSubject] is echoed verbatim from a stranger's message: safe in the header via the RFC
 */
fun readReceiptPreview(originalSubject: String?, finalRecipient: String): ReadReceiptPreview {
    val title = originalSubject?.trim().orEmpty()
    return ReadReceiptPreview(
        subject = if (title.isEmpty()) READ_RECEIPT_SUBJECT else "$READ_RECEIPT_SUBJECT: $title",
        body = buildString {
            append("This is a read receipt.\n\n")
            append(
                if (title.isEmpty()) {
                    "The message you sent to $finalRecipient was displayed."
                } else {
                    "The message you sent to $finalRecipient with the subject \"$title\" was displayed."
                },
            )
            append("\n\nIt says the message was shown on the recipient's device. It does not say it was read.")
        },
    )
}

/** RFC 8098 §3.2.2 `ua-name`. No version, no host name: neither belongs in a stranger's inbox. */
internal const val READ_RECEIPT_UA = "Sterna Mail"

/**
 * The `multipart/report` of RFC 8098 §3. [boundary] is an argument, never drawn here, so these bytes
 */
internal fun readReceiptEntity(
    boundary: String,
    humanText: String,
    finalRecipient: String,
    originalMessageId: String?,
): String {
    // One sanitised spelling for the parameter AND the delimiters, so the two cannot disagree.
    val b = OutgoingMime.headerSafe(boundary).replace("\"", "")
    // REMOVED, not trimmed: headerSafe passes `<` and `>` through, so a value carrying them in
    // the middle would leave `<evil@x>Disposition: …>`, which a strict parser may drop whole.
    val messageId = originalMessageId
        ?.let { OutgoingMime.headerSafe(it) }
        ?.filterNot { it == '<' || it == '>' }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return buildString {
        append("Content-Type: multipart/report; report-type=disposition-notification;\r\n")
        append(" boundary=\"$b\"\r\n")
        append("\r\n")
        append("--$b\r\n")
        append("Content-Type: text/plain; charset=utf-8\r\n")
        append("Content-Transfer-Encoding: base64\r\n")
        append("\r\n")
        append(base64(humanText))
        append("\r\n")
        // Fields only, in the order RFC 8098 §3.1's ABNF requires.
        append("--$b\r\n")
        append("Content-Type: message/disposition-notification\r\n")
        append("\r\n")
        append("Reporting-UA: $READ_RECEIPT_UA\r\n")
        append("Final-Recipient: rfc822; ${OutgoingMime.headerSafe(finalRecipient)}\r\n")
        if (messageId != null) append("Original-Message-ID: <$messageId>\r\n")
        // manual-action: a person tapped this. MDN-sent-manually: nothing here fires on its own.
        append("Disposition: manual-action/MDN-sent-manually; displayed\r\n")
        append("--$b--\r\n")
    }
}

/**
 * Not `…encodeToString(…).replace("\n", "\r\n")`: the separators are already `\r\n`, so that
 * yields a bare CR (RFC 5321 § 2.3.8), and on a SIGNED message the recipient reads "invalid".
 */
private fun base64(text: String): String =
    Base64.getMimeEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

/**
 * Not `identities.first()`: an account can hold several aliases, and answering from the first tells
 * a correspondent that a mailbox they never wrote to had read their mail.
 */
internal fun receiptIdentity(identities: List<StoredIdentity>, deliveredTo: String?): StoredIdentity? =
    deliveredTo?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { addr -> identities.firstOrNull { it.email.equals(addr, ignoreCase = true) } }
        ?: identities.firstOrNull()

/**
 * Null when there is nothing to answer: [ReadReceiptHeader.recipients] yields an empty list for a
 */
internal fun readReceiptMail(
    receiptTo: List<String>,
    originalSubject: String?,
    originalMessageId: String?,
    identityName: String?,
    identityEmail: String?,
    deliveredTo: String?,
    accountAddress: String,
    boundary: String,
): ReadReceiptMail? {
    val to = receiptTo.map { it.trim() }.filter { it.isNotEmpty() }
    if (to.isEmpty()) return null
    val finalRecipient = listOf(deliveredTo, identityEmail, accountAddress)
        .firstOrNull { !it.isNullOrBlank() }?.trim() ?: accountAddress
    val preview = readReceiptPreview(originalSubject, finalRecipient)
    return ReadReceiptMail(
        to = to,
        subject = preview.subject,
        body = preview.body,
        fromName = identityName,
        fromEmail = identityEmail,
        prebuiltEntity = readReceiptEntity(
            boundary = boundary,
            humanText = preview.body,
            finalRecipient = finalRecipient,
            originalMessageId = originalMessageId,
        ),
    )
}
