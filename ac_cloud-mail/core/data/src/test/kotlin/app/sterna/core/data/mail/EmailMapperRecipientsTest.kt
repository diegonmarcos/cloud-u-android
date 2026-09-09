package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `To:` recipients survive the cache (#63).
 */
class EmailMapperRecipientsTest {

    private val bob = EmailAddress(name = "Bob", email = "bob@example.org")
    private val carol = EmailAddress(email = "carol@example.org")

    private fun sent(to: List<EmailAddress>) = Email(
        id = "e1",
        subject = "Re: lunch",
        receivedAt = "2026-07-27T10:00:00Z",
        from = listOf(EmailAddress(name = "Alex", email = "alex@example.org")),
        to = to,
        keywords = mapOf("\$seen" to true),
    )

    // --- the round trip ------------------------------------------------------------------------

    @Test fun recipientsAreWrittenToTheRowAndReadBack() {
        val entity = sent(listOf(bob, carol)).toEntity("accA", "sent")

        assertNotNull("the row must carry the recipients, not a memo", entity.recipientsJson)
        assertEquals(listOf(bob, carol), entity.toEmail().to)
    }

    @Test fun orderAndDisplayNamesAreKept() {
        // The list joins every recipient, so both the order and the optional display name matter.
        val entity = sent(listOf(carol, bob)).toEntity("accA", "sent")

        assertEquals(listOf("carol@example.org", "Bob"), entity.toEmail().to.map { it.display() })
    }

    @Test fun aMessageWithoutRecipientsStoresNothingAndFallsBackAsBefore() {
        // A draft still being written has no addressee (#69): nothing to store, and the row keeps
        // the pre-v17 behaviour of showing its sender.
        val entity = sent(emptyList()).toEntity("accA", "drafts")

        assertNull(entity.recipientsJson)
        assertEquals(emptyList<EmailAddress>(), entity.toEmail().to)
    }

    /** A row cached before v17: the column is NULL and must not blow up. */
    @Test fun aRowFromBeforeTheColumnExistedDecodesToNoRecipients() {
        val legacy = EmailEntity(
            id = "old", accountId = "accA", mailboxId = "sent", threadId = null,
            subject = "Old", preview = null, receivedAt = null,
            fromName = "Alex", fromEmail = "alex@example.org",
            seen = true, flagged = false, hasAttachment = false, sortKey = 1,
        )

        assertNull(legacy.recipientsJson)
        assertEquals(emptyList<EmailAddress>(), legacy.toEmail().to)
    }

    @Test fun aCorruptColumnDegradesToNoRecipientsInsteadOfThrowing() {
        val entity = sent(listOf(bob)).toEntity("accA", "sent").copy(recipientsJson = "{not json")

        assertEquals(emptyList<EmailAddress>(), entity.toEmail().to)
    }

    // --- the #63 scenario ----------------------------------------------------------------------

    @Test fun aCachedSelfSentRowCarriesItsRecipientWithNoServerCopy() {
        // Exactly what the cold cache does: an Email is mapped to a row, the row is all that
        // survives (process death empties every in-memory memo), and the row alone is rendered.
        val stored = sent(listOf(bob)).toEntity("accA", "sent")
        val fromCacheOnly = EmailEntity(
            id = stored.id, accountId = stored.accountId, mailboxId = stored.mailboxId,
            threadId = stored.threadId, subject = stored.subject, preview = stored.preview,
            receivedAt = stored.receivedAt, fromName = stored.fromName, fromEmail = stored.fromEmail,
            seen = stored.seen, flagged = stored.flagged, hasAttachment = stored.hasAttachment,
            sortKey = stored.sortKey, recipientsJson = stored.recipientsJson,
        )

        val rendered = fromCacheOnly.toEmail()
        assertTrue("the row must be able to render \"To: …\" on its own", rendered.to.isNotEmpty())
        assertEquals("Bob", rendered.to.joinToString { it.display() })
    }

    // --- the codec -----------------------------------------------------------------------------

    @Test fun theCodecIsTotalInBothDirections() {
        assertNull(EmailRecipients.encode(emptyList()))
        assertEquals(emptyList<EmailAddress>(), EmailRecipients.decode(null))
        assertEquals(emptyList<EmailAddress>(), EmailRecipients.decode(""))
        assertEquals(emptyList<EmailAddress>(), EmailRecipients.decode("   "))
        assertEquals(emptyList<EmailAddress>(), EmailRecipients.decode("[[["))
        assertEquals(listOf(bob), EmailRecipients.decode(EmailRecipients.encode(listOf(bob))))
    }

    @Test fun addressesWithAwkwardCharactersSurviveEncoding() {
        val awkward = EmailAddress(name = "O'Hara, \"Bo\" <b>", email = "b+tag@example.org")

        assertEquals(listOf(awkward), EmailRecipients.decode(EmailRecipients.encode(listOf(awkward))))
    }
}
