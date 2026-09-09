package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `Cc` and `Bcc` recipients survive the cache.
 */
class EmailMapperCopiesTest {

    private val alex = EmailAddress(name = "Alex", email = "alex@example.org")
    private val bob = EmailAddress(name = "Bob", email = "bob@example.org")
    private val carol = EmailAddress(name = "Carol", email = "carol@example.org")
    private val dave = EmailAddress(name = "Dave", email = "dave@example.org")
    private val support = EmailAddress(email = "support@example.org")

    private fun message(
        to: List<EmailAddress> = listOf(bob),
        cc: List<EmailAddress> = listOf(carol),
        bcc: List<EmailAddress> = listOf(dave),
    ) = Email(
        id = "e1",
        subject = "Six o'clock",
        receivedAt = "2026-08-11T10:00:00Z",
        from = listOf(alex),
        replyTo = listOf(support),
        to = to,
        cc = cc,
        bcc = bcc,
        keywords = mapOf("\$seen" to true),
    )

    // --- the round trip, field by field ------------------------------------------------------------

    @Test fun theCcRecipientsAreWrittenToTheRowAndReadBackUnchanged() {
        val entity = message().toEntity("accA", "inbox")

        assertNotNull("the row must carry the Cc, not just the To", entity.ccJson)
        assertEquals(listOf(carol), EmailRecipients.decode(entity.ccJson))
        assertEquals(listOf(carol), entity.toEmail().cc)
        assertEquals("carol@example.org", entity.toEmail().cc.single().email)
    }

    @Test fun theBccRecipientsAreWrittenToTheRowAndReadBackUnchanged() {
        val entity = message().toEntity("accA", "inbox")

        assertNotNull("the row must carry the Bcc, or a draft reopens without its blind copies", entity.bccJson)
        assertEquals(listOf(dave), EmailRecipients.decode(entity.bccJson))
        assertEquals(listOf(dave), entity.toEmail().bcc)
        assertEquals("dave@example.org", entity.toEmail().bcc.single().email)
    }

    /**
     * The four addressing fields land in four separate columns, each holding its OWN list. Pins the
     */
    @Test fun eachAddressingFieldGoesToItsOwnColumnAndNoOther() {
        val entity = message().toEntity("accA", "inbox")

        assertEquals("recipientsJson must hold the To", listOf(bob), EmailRecipients.decode(entity.recipientsJson))
        assertEquals("ccJson must hold the Cc", listOf(carol), EmailRecipients.decode(entity.ccJson))
        assertEquals("bccJson must hold the Bcc", listOf(dave), EmailRecipients.decode(entity.bccJson))
        assertEquals("replyToJson must hold the Reply-To", listOf(support), EmailRecipients.decode(entity.replyToJson))

        assertNotEquals("the Cc column must not be a copy of the To", entity.recipientsJson, entity.ccJson)
        assertNotEquals("the Bcc column must not be a copy of the Cc", entity.ccJson, entity.bccJson)
    }

    /**
     * The one-token slip that leaks an address: reading the Bcc column into the visible `cc`.
     */
    @Test fun aBlindCopyNeverComesBackAsAVisibleCc() {
        val read = message().toEntity("accA", "inbox").toEmail()

        assertEquals(listOf(carol), read.cc)
        assertEquals(listOf(dave), read.bcc)
        assertEquals(
            "dave@example.org was blind-copied; it must not appear in the visible Cc",
            emptyList<String>(),
            read.cc.map { it.email }.filter { it == "dave@example.org" },
        )
        assertEquals(
            "the To must not be served as the Cc either",
            emptyList<String>(),
            read.cc.map { it.email }.filter { it == "bob@example.org" },
        )
    }

    @Test fun severalCopiedRecipientsKeepTheirOrderAndDisplayNames() {
        val entity = message(cc = listOf(carol, support), bcc = listOf(dave, bob)).toEntity("accA", "inbox")

        assertEquals(
            listOf("carol@example.org", "support@example.org"),
            entity.toEmail().cc.map { it.email },
        )
        assertEquals(listOf("Carol", "support@example.org"), entity.toEmail().cc.map { it.display() })
        assertEquals(
            listOf("dave@example.org", "bob@example.org"),
            entity.toEmail().bcc.map { it.email },
        )
    }

    // --- the ordinary message: no copies at all -----------------------------------------------------

    @Test fun aMessageWithoutCopiesStoresNothingAndReadsBackEmpty() {
        val entity = message(cc = emptyList(), bcc = emptyList()).toEntity("accA", "inbox")

        assertNull(entity.ccJson)
        assertNull(entity.bccJson)
        assertEquals(emptyList<EmailAddress>(), entity.toEmail().cc)
        assertEquals(emptyList<EmailAddress>(), entity.toEmail().bcc)
        // …and the To it does have is untouched by the two empty fields.
        assertEquals(listOf(bob), entity.toEmail().to)
    }

    // --- one copy field filled, the other empty -----------------------------------------------------

    /**
     * The commonest shape of a hidden copy: somebody in blind copy, NOBODY in visible copy.
     */
    @Test fun aBlindCopyAloneDoesNotFallBackIntoTheVisibleCc() {
        val entity = message(cc = emptyList(), bcc = listOf(dave)).toEntity("accA", "inbox")

        assertNull("nobody was in visible copy, so the Cc column must stay NULL", entity.ccJson)
        assertNotNull("dave@example.org was blind-copied and must be stored", entity.bccJson)
        assertEquals(listOf(dave), EmailRecipients.decode(entity.bccJson))

        val read = entity.toEmail()
        assertEquals(
            "an absent Cc must read back EMPTY, never as the blind copies",
            emptyList<EmailAddress>(),
            read.cc,
        )
        assertEquals(
            "dave@example.org must appear in the Bcc and nowhere else",
            emptyList<String>(),
            read.cc.map { it.email }.filter { it == "dave@example.org" },
        )
        assertEquals(listOf(dave), read.bcc)
    }

    /** The mirror case: a visible copy and no blind copy at all. */
    @Test fun aVisibleCopyAloneDoesNotFallBackIntoTheBcc() {
        val entity = message(cc = listOf(carol), bcc = emptyList()).toEntity("accA", "inbox")

        assertNotNull("carol@example.org was in visible copy and must be stored", entity.ccJson)
        assertNull("nobody was blind-copied, so the Bcc column must stay NULL", entity.bccJson)

        val read = entity.toEmail()
        assertEquals(listOf(carol), read.cc)
        assertEquals(
            "an absent Bcc must read back EMPTY, never as the visible copies",
            emptyList<EmailAddress>(),
            read.bcc,
        )
    }

    /** A row cached before v21: both columns are NULL and must decode, not throw. */
    @Test fun aRowFromBeforeTheColumnsExistedDecodesToNobodyInCopy() {
        val legacy = EmailEntity(
            id = "old", accountId = "accA", mailboxId = "inbox", threadId = null,
            subject = "Old", preview = null, receivedAt = null,
            fromName = "Alex", fromEmail = "alex@example.org",
            seen = true, flagged = false, hasAttachment = false, sortKey = 1,
        )

        assertNull(legacy.ccJson)
        assertNull(legacy.bccJson)
        assertEquals(emptyList<EmailAddress>(), legacy.toEmail().cc)
        assertEquals(emptyList<EmailAddress>(), legacy.toEmail().bcc)
    }

    @Test fun aCorruptColumnDegradesToNoCopiesInsteadOfThrowing() {
        val entity = message().toEntity("accA", "inbox").copy(ccJson = "{not json", bccJson = "[")

        assertEquals(emptyList<EmailAddress>(), entity.toEmail().cc)
        assertEquals(emptyList<EmailAddress>(), entity.toEmail().bcc)
        // The neighbouring column is unaffected: one corrupt field does not empty the address book.
        assertEquals(listOf(bob), entity.toEmail().to)
    }

    // --- the cold-cache scenario ---------------------------------------------------------------------

    @Test fun aRowRebuiltFromTheColumnsAloneStillKnowsWhoWasInCopy() {
        // Exactly what a reopened draft / a reply-all sees after process death: every in-memory
        // memo is gone, the stored columns are all that is left.
        val stored = message().toEntity("accA", "inbox")
        val fromCacheOnly = EmailEntity(
            id = stored.id, accountId = stored.accountId, mailboxId = stored.mailboxId,
            threadId = stored.threadId, subject = stored.subject, preview = stored.preview,
            receivedAt = stored.receivedAt, fromName = stored.fromName, fromEmail = stored.fromEmail,
            seen = stored.seen, flagged = stored.flagged, hasAttachment = stored.hasAttachment,
            sortKey = stored.sortKey, recipientsJson = stored.recipientsJson,
            replyToJson = stored.replyToJson, ccJson = stored.ccJson, bccJson = stored.bccJson,
        )

        assertEquals(listOf(bob), fromCacheOnly.toEmail().to)
        assertEquals(listOf(carol), fromCacheOnly.toEmail().cc)
        assertEquals(listOf(dave), fromCacheOnly.toEmail().bcc)
    }

    @Test fun awkwardDisplayNamesSurviveTheColumns() {
        val awkwardCc = EmailAddress(name = "O'Hara, \"Bo\" <b>", email = "b+tag@example.org")
        val awkwardBcc = EmailAddress(name = "Ünn \\ Ötz", email = "u@example.org")

        val entity = message(cc = listOf(awkwardCc), bcc = listOf(awkwardBcc)).toEntity("accA", "inbox")

        assertEquals(listOf(awkwardCc), entity.toEmail().cc)
        assertEquals(listOf(awkwardBcc), entity.toEmail().bcc)
    }

    /**
     * The addressee typed but not yet resolved: a server can hand a draft's recipient back with an
     */
    @Test fun aCopiedRecipientWithNoAddressYetIsStillStored() {
        val typed = EmailAddress(name = "cc-typed-but-unresolved", email = "")

        val entity = message(cc = listOf(typed)).toEntity("accA", "inbox")

        assertNotNull(entity.ccJson)
        assertEquals(listOf(typed), entity.toEmail().cc)
    }
}
