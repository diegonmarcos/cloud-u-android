package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailHeader
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sender surface's decisions, EXECUTED rather than looked at: which metadata rows a message
 * earns, how an address is copied, and what counts as a tag.
 */
class MessageMetadataTest {

    private fun header(name: String, value: String) = EmailHeader(name, value)
    private val time: (Long) -> String = { "T$it" }

    // ---- what the sender surface shows, and what it must not ----

    /** THE rule: no row for a field the message does not carry. An empty "Authentication-Results:"
     *  reads as a verdict of nothing, and a reader who learns to see it on ordinary mail will not
     *  notice it on the one message where the verdict matters. */
    @Test fun aMessageCarryingNothingEarnsNoRowsButTheOnesItHas() {
        val rows = messageMetadata(Email(id = "e1"), emptyList(), null, null, time)
        assertTrue("a bare message produced rows out of nothing: ${rows.map { it.label }}", rows.isEmpty())
    }

    @Test fun everyRowItDoesEmitHasAValue() {
        val rows = messageMetadata(
            Email(id = "e1", messageId = listOf("abc@example.com")),
            listOf(
                header("Return-Path", "  "),
                header("Authentication-Results", "mx.example.com; dkim=pass"),
                header("List-Id", ""),
            ),
            receivedAtMillis = 1_000,
            sentAtMillis = null,
            formatTime = time,
        )
        assertTrue("a blank header became a row", rows.none { it.value.isBlank() })
        assertEquals(
            listOf(MetadataLabel.RECEIVED_AT, MetadataLabel.AUTHENTICATION, MetadataLabel.MESSAGE_ID),
            rows.map { it.label },
        )
    }

    /** The sender's clock only earns a row when it disagrees with the server's beyond ordinary
     *  skew. A row that appears on all normal mail is a row nobody reads. */
    @Test fun theSendersClockIsShownOnlyWhenItDisagrees() {
        val agreeing = messageMetadata(
            Email(id = "e1"), emptyList(), 1_000_000, 1_000_000 + CLOCK_SKEW_MS - 1, time,
        )
        assertTrue("a normal clock skew produced a row", agreeing.none { it.label == MetadataLabel.SENT_AT })

        val disagreeing = messageMetadata(
            Email(id = "e1"), emptyList(), 1_000_000, 1_000_000 - 3 * CLOCK_SKEW_MS, time,
        )
        assertTrue(
            "a message dated well before it arrived showed no Sent row",
            disagreeing.any { it.label == MetadataLabel.SENT_AT },
        )
    }

    /** A Reply-To equal to the sender says nothing and must not take a row; one that differs is the
     *  whole trick of a display-name forgery and must. */
    @Test fun replyToIsShownOnlyWhenItIsSomebodyElse() {
        val same = Email(
            id = "e1",
            from = listOf(EmailAddress("Ada", "ada@example.com")),
            replyTo = listOf(EmailAddress(null, "ADA@example.com")),
        )
        assertTrue(
            "a Reply-To that is just the sender took a row",
            messageMetadata(same, emptyList(), null, null, time).none { it.label == MetadataLabel.REPLY_TO },
        )
        val other = same.copy(replyTo = listOf(EmailAddress(null, "list@elsewhere.example")))
        assertTrue(
            "a Reply-To pointing somewhere else was hidden",
            messageMetadata(other, emptyList(), null, null, time).any { it.label == MetadataLabel.REPLY_TO },
        )
    }

    /**
     * The LAST Authentication-Results, not the first. Each hop PREPENDS one, so the first in the
     * list is the outermost relay — on a forwarded message, a machine the reader does not trust,
     * writing its own verdict about its own message. The last is the reader's own server.
     */
    @Test fun theAuthenticationVerdictReadIsTheReceivingServersOwn() {
        val rows = messageMetadata(
            Email(id = "e1"),
            listOf(
                header("Authentication-Results", "relay.attacker.example; dkim=pass (forged)"),
                header("Authentication-Results", "mx.mine.example; dkim=fail"),
            ),
            null, null, time,
        )
        assertEquals(
            "mx.mine.example; dkim=fail",
            rows.first { it.label == MetadataLabel.AUTHENTICATION }.value,
        )
    }

    // ---- copying the address (part two) ----

    @Test fun anAddressWithNoNameIsCopiedBare() {
        assertEquals("ada@example.com", formatAddress(EmailAddress(null, "ada@example.com")))
        assertEquals("ada@example.com", formatAddress(EmailAddress("   ", "ada@example.com")))
    }

    @Test fun anAddressWithANameIsCopiedAsNameAddr() {
        assertEquals("Ada Lovelace <ada@example.com>", formatAddress(EmailAddress("Ada Lovelace", "ada@example.com")))
    }

    /**
     * The one that matters when it is PASTED. "Doe, Jane" unquoted becomes two recipients in the
     * field it lands in, and the second one bounces — silently, on send, to somebody who was
     * copying an address out of a message they were reading.
     */
    @Test fun aNameHoldingAnRfcSpecialIsQuotedRatherThanSplit() {
        assertEquals("\"Doe, Jane\" <jane@example.com>", formatAddress(EmailAddress("Doe, Jane", "jane@example.com")))
        assertEquals("\"Ada @ Work\" <ada@example.com>", formatAddress(EmailAddress("Ada @ Work", "ada@example.com")))
        // A quote inside the name is escaped rather than dropped: the name is still the sender's.
        assertEquals(
            "\"Ada \\\"Countess\\\"\" <ada@example.com>",
            formatAddress(EmailAddress("Ada \"Countess\"", "ada@example.com")),
        )
    }

    // ---- what a tag IS (parts three and five, one answer) ----

    private fun mailbox(id: String, name: String) = Mailbox(id = id, name = name)

    @Test fun bothKindsAreTagsAndTheyAreToldApart() {
        val tags = messageTags(
            mailboxIds = setOf("mbWork", "mbInbox"),
            mailboxes = listOf(mailbox("mbWork", "Work"), mailbox("mbInbox", "Inbox")),
            keywords = mapOf("invoice" to true, "\$seen" to true, "\$flagged" to true),
            currentMailboxId = "mbInbox",
            nameOf = { it.name },
        )
        assertEquals(
            "mailboxes first, then keywords",
            listOf(TagKind.MAILBOX, TagKind.KEYWORD),
            tags.map { it.kind },
        )
        assertEquals(listOf("Work", "invoice"), tags.map { it.label })
    }

    /** `$seen` is the unread state and `$flagged` is the star on the toolbar. Offering either as a
     *  tag would put two controls on one piece of server state. */
    @Test fun systemKeywordsAreNeverTags() {
        val tags = messageTags(
            emptySet(), emptyList(),
            mapOf("\$seen" to true, "\$flagged" to true, "\$draft" to true, "\$answered" to true),
            null, { it.name },
        )
        assertTrue("a \$-prefixed keyword was offered as a tag: ${tags.map { it.id }}", tags.isEmpty())
    }

    /** A keyword set to false is not on the message. */
    @Test fun aClearedKeywordIsNotATag() {
        val tags = messageTags(emptySet(), emptyList(), mapOf("invoice" to false), null, { it.name })
        assertTrue(tags.isEmpty())
    }

    /** A mailbox id the account no longer has is dropped, not drawn raw: an id is not a name, and a
     *  tag nobody can read is not a tag. */
    @Test fun anUnknownMailboxIdIsDroppedRatherThanShownRaw() {
        val tags = messageTags(setOf("mbGone"), emptyList(), emptyMap(), null, { it.name })
        assertTrue(tags.isEmpty())
    }

    /** The chip row under the sender leaves out the folder the message was opened from; the sheet
     *  that EDITS membership passes null and therefore keeps it, because that is the row a user
     *  goes there to remove. */
    @Test fun theCurrentFolderIsExcludedOnlyWhenTheCallerSaysSo() {
        val boxes = listOf(mailbox("mbInbox", "Inbox"), mailbox("mbWork", "Work"))
        val chips = messageTags(setOf("mbInbox", "mbWork"), boxes, emptyMap(), "mbInbox", { it.name })
        assertEquals(listOf("Work"), chips.map { it.label })
        val sheet = messageTags(setOf("mbInbox", "mbWork"), boxes, emptyMap(), null, { it.name })
        assertEquals(listOf("Inbox", "Work"), sheet.map { it.label })
    }

    /** Removing a mailbox can hide a message from every folder the user browses; clearing a keyword
     *  moves nothing. Only the first is worth a confirmation. */
    @Test fun onlyAMailboxRemovalIsWorthConfirming() {
        assertTrue(removalNeedsConfirming(MessageTag(TagKind.MAILBOX, "mbWork", "Work")))
        assertTrue(!removalNeedsConfirming(MessageTag(TagKind.KEYWORD, "invoice", "invoice")))
    }

    // ---- the timestamps ----

    @Test fun anUnparseableDateIsNullRatherThanAWrongTime() {
        assertNull(parseIsoMillis("not a date"))
        assertNull(parseIsoMillis(null))
        assertNull(parseHeaderDateMillis("whenever"))
        assertEquals(0L, parseIsoMillis("1970-01-01T00:00:00Z"))
        assertEquals(0L, parseHeaderDateMillis("Thu, 1 Jan 1970 00:00:00 +0000"))
    }
}
