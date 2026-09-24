package app.sterna.core.data.mail

import app.sterna.core.data.db.ConversationRow
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.imap.ImapAddress
import app.sterna.core.imap.ImapMessage
import app.sterna.core.imap.MimeAttachment
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #514 / #518: the attachment chips on a message-list row are drawn from
 * `row.email.fileAttachmentParts()` (EmailListItem.kt). This walks a fetched message down the
 * road the paged inbox actually takes -- fetch model -> cache row -> the conversation query's
 * [ConversationRow] -> [toInboxRow] -- and asks that same expression what the row would draw.
 *
 * The expected parts are DERIVED from the input message by the same classifier, never written
 * here as literals, and each case first proves it is not vacuous (the input has files to lose).
 * Drop the `attachments` line from [toEmail], or `attachmentsJson` from either `toEntity`, and
 * the row gets zero chips: this goes red.
 */
class ListRowAttachmentChipsReachTheRowTest {

    private fun ConversationRow.chips() = toInboxRow().email.fileAttachmentParts()

    private fun rowOf(entity: EmailEntity) =
        ConversationRow(email = entity, threadCount = 1, threadTotal = 1, threadUnread = 0)

    @Test fun aJmapMessagesFilesReachTheListRowAsChips() {
        val fetched = Email(
            id = "e1",
            subject = "Invoice",
            receivedAt = "2026-09-24T10:00:00Z",
            from = listOf(EmailAddress(name = "Alex", email = "alex@example.org")),
            hasAttachment = true,
            attachments = listOf(
                EmailBodyPart(blobId = "b1", partId = "2", size = 1234, type = "application/pdf",
                    name = "invoice.pdf", disposition = "attachment"),
                EmailBodyPart(blobId = "b2", partId = "3", size = 99, type = "text/csv",
                    name = "lines.csv", disposition = "attachment"),
            ),
        )
        val expected = fetched.fileAttachmentParts()
        assertTrue("fixture must carry files, or equality below proves nothing", expected.isNotEmpty())

        val chips = rowOf(fetched.toEntity("accA", "inbox")).chips()

        assertEquals("the list row must be handed every file the fetch saw", expected, chips)
    }

    @Test fun anImapMessagesFilesReachTheListRowAsChips() {
        val parts = listOf(
            MimeAttachment(section = "2", name = "photo.jpg", type = "image/jpeg", size = 4096, encoding = "base64"),
            MimeAttachment(section = "3", name = "notes.txt", type = "text/plain", size = 12, encoding = "7bit"),
        )
        val fetched = ImapMessage(
            uid = 7L,
            subject = "Holiday",
            fromName = "Alex",
            fromEmail = "alex@example.org",
            to = listOf(ImapAddress(name = "Me", email = "me@example.org")),
            dateMillis = 1_780_000_000_000L,
            seen = false,
            flagged = false,
            answered = false,
            hasAttachment = true,
            messageId = "<7@example.org>",
            inReplyTo = null,
            attachments = parts,
        )

        val chips = rowOf(fetched.toEntity("accA", "INBOX", 42L, preview = null)).chips()

        // Named and addressed by what BODYSTRUCTURE said: the section is what a tap downloads.
        assertEquals(parts.map { it.name }, chips.map { it.name })
        assertEquals(parts.map { it.section }, chips.map { it.partId })
    }
}
