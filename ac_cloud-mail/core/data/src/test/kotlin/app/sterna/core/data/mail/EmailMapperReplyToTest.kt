package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `Reply-To` addresses survive the cache.
 */
class EmailMapperReplyToTest {

    private val list = EmailAddress(name = "Sterna list", email = "list@lists.example.org")
    private val support = EmailAddress(email = "support@example.org")

    private fun received(replyTo: List<EmailAddress>, to: List<EmailAddress> = emptyList()) = Email(
        id = "e1",
        subject = "Ticket 42 updated",
        receivedAt = "2026-08-11T10:00:00Z",
        from = listOf(EmailAddress(name = "No reply", email = "no-reply@example.org")),
        replyTo = replyTo,
        to = to,
        keywords = mapOf("\$seen" to true),
    )

    // --- the round trip --------------------------------------------------------------------------

    @Test fun theReplyToAddressIsWrittenToTheRowAndReadBackUnchanged() {
        val entity = received(listOf(support)).toEntity("accA", "inbox")

        assertNotNull("the row must carry Reply-To, not just From", entity.replyToJson)
        assertEquals(listOf(support), entity.toEmail().replyTo)
        assertEquals("support@example.org", entity.toEmail().replyTo.single().email)
        // …and it is NOT the From the sender set aside.
        assertEquals("no-reply@example.org", entity.toEmail().from.single().email)
    }

    @Test fun severalReplyToAddressesKeepTheirOrderAndDisplayNames() {
        val entity = received(listOf(list, support)).toEntity("accA", "inbox")

        assertEquals(
            listOf("Sterna list", "support@example.org"),
            entity.toEmail().replyTo.map { it.display() },
        )
        assertEquals(
            listOf("list@lists.example.org", "support@example.org"),
            entity.toEmail().replyTo.map { it.email },
        )
    }

    @Test fun replyToAndTheToRecipientsAreStoredInSeparateColumns() {
        val bob = EmailAddress(name = "Bob", email = "bob@example.org")
        val entity = received(listOf(support), to = listOf(bob)).toEntity("accA", "inbox")

        assertEquals(listOf(support), EmailRecipients.decode(entity.replyToJson))
        assertEquals(listOf(bob), EmailRecipients.decode(entity.recipientsJson))
        assertEquals(listOf(support), entity.toEmail().replyTo)
        assertEquals(listOf(bob), entity.toEmail().to)
    }

    // --- the ordinary message: no Reply-To at all -------------------------------------------------

    @Test fun aMessageWithoutReplyToStoresNothingAndReadsBackEmpty() {
        // The vast majority of mail: nothing to store, and the reply falls back to the sender.
        val entity = received(emptyList()).toEntity("accA", "inbox")

        assertNull(entity.replyToJson)
        assertEquals(emptyList<EmailAddress>(), entity.toEmail().replyTo)
    }

    /** A row cached before v20: the column is NULL and must decode, not throw. */
    @Test fun aRowFromBeforeTheColumnExistedDecodesToNoReplyTo() {
        val legacy = EmailEntity(
            id = "old", accountId = "accA", mailboxId = "inbox", threadId = null,
            subject = "Old", preview = null, receivedAt = null,
            fromName = "Alex", fromEmail = "alex@example.org",
            seen = true, flagged = false, hasAttachment = false, sortKey = 1,
        )

        assertNull(legacy.replyToJson)
        assertEquals(emptyList<EmailAddress>(), legacy.toEmail().replyTo)
    }

    @Test fun aCorruptColumnDegradesToNoReplyToInsteadOfThrowing() {
        val entity = received(listOf(support)).toEntity("accA", "inbox").copy(replyToJson = "{not json")

        assertEquals(emptyList<EmailAddress>(), entity.toEmail().replyTo)
    }

    // --- the cold-cache scenario -------------------------------------------------------------------

    @Test fun aRowRebuiltFromTheColumnsAloneStillKnowsWhereToAnswer() {
        // Exactly what an offline reply / a quick reply sees: process death has emptied every
        // in-memory memo, the stored columns are all that is left.
        val stored = received(listOf(support)).toEntity("accA", "inbox")
        val fromCacheOnly = EmailEntity(
            id = stored.id, accountId = stored.accountId, mailboxId = stored.mailboxId,
            threadId = stored.threadId, subject = stored.subject, preview = stored.preview,
            receivedAt = stored.receivedAt, fromName = stored.fromName, fromEmail = stored.fromEmail,
            seen = stored.seen, flagged = stored.flagged, hasAttachment = stored.hasAttachment,
            sortKey = stored.sortKey, recipientsJson = stored.recipientsJson,
            replyToJson = stored.replyToJson,
        )

        assertEquals(listOf(support), fromCacheOnly.toEmail().replyTo)
    }

    @Test fun awkwardDisplayNamesSurviveTheColumn() {
        val awkward = EmailAddress(name = "O'Hara, \"Bo\" <b>", email = "b+tag@lists.example.org")

        val entity = received(listOf(awkward)).toEntity("accA", "inbox")

        assertEquals(listOf(awkward), entity.toEmail().replyTo)
    }
}
