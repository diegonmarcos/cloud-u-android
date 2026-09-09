package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [planSelectionSnooze] — who can be snoozed at all, executed rather than re-derived.
 */
class SelectionSnoozePlanTest {

    /**
     * A fully filled row — every optional field of [Email] the partition could be tempted to read
     */
    private fun target(id: String, trusted: Boolean, account: String? = "accA", mailbox: String? = "mbA") =
        SelectionTarget(
            Email(
                id = id,
                accountId = account,
                blobId = "blob-$id",
                mailboxId = mailbox,
                mailboxIds = mapOf((mailbox ?: "mbA") to true),
                threadId = "t-$id",
                subject = "s",
                preview = "p",
                receivedAt = "2026-08-08T10:00:00Z",
                from = listOf(EmailAddress(name = "A", email = "a@example.test")),
                hasAttachment = true,
                keywords = mapOf("\$seen" to true),
            ),
            folderTrusted = trusted,
        )

    /**
     * The same row stripped to nothing but its ids. Paired with [target] in the mixed case below,
     */
    private fun sparse(id: String, trusted: Boolean) =
        SelectionTarget(Email(id = id), folderTrusted = trusted)

    /**
     * The reported case, interleaved: the cached rows are snoozed, the rows drawn by the search are
     */
    @Test
    fun `a mixed selection snoozes the cached rows and refuses the rest`() {
        val plan = planSelectionSnooze(
            listOf(
                target("m1", trusted = true),
                target("m2", trusted = false),
                sparse("m3", trusted = true),
                target("m4", trusted = false, mailbox = ""),
            ),
        )
        assertEquals(listOf("m1", "m3"), plan.snooze.map { it.email.id })
        assertEquals(listOf(true, true), plan.snooze.map { it.folderTrusted })
        assertEquals(listOf("m2", "m4"), plan.refused.map { it.email.id })
        assertEquals(listOf(false, false), plan.refused.map { it.folderTrusted })
    }

    /** Nothing may be refused when every row has a local one: this is the everyday selection. */
    @Test
    fun `a fully cached selection is snoozed whole`() {
        val plan = planSelectionSnooze(
            listOf(target("m1", trusted = true), target("m2", trusted = true, account = "accB")),
        )
        assertEquals(listOf("m1", "m2"), plan.snooze.map { it.email.id })
        assertEquals(emptyList<String>(), plan.refused.map { it.email.id })
    }

    /** And the reported selection in full: nothing is written at all, everything comes back refused. */
    @Test
    fun `a selection with no cached row at all snoozes nothing`() {
        val plan = planSelectionSnooze(
            listOf(target("m1", trusted = false), target("m2", trusted = false, account = "accB")),
        )
        assertEquals(emptyList<String>(), plan.snooze.map { it.email.id })
        assertEquals(listOf("m1", "m2"), plan.refused.map { it.email.id })
    }

    @Test
    fun `an empty selection plans nothing on either side`() {
        val plan = planSelectionSnooze(emptyList())
        assertEquals(emptyList<SelectionTarget>(), plan.snooze)
        assertEquals(emptyList<SelectionTarget>(), plan.refused)
    }

    /**
     * The same id in both trust states — the two rows a merge of the cache and the search snapshot
     */
    @Test
    fun `the same id trusted and untrusted lands in both parts, in either order`() {
        val trustedFirst = planSelectionSnooze(
            listOf(target("m1", trusted = true), target("m1", trusted = false)),
        )
        assertEquals(listOf("m1"), trustedFirst.snooze.map { it.email.id })
        assertEquals(listOf("m1"), trustedFirst.refused.map { it.email.id })
        assertEquals(listOf(true), trustedFirst.snooze.map { it.folderTrusted })
        assertEquals(listOf(false), trustedFirst.refused.map { it.folderTrusted })

        val untrustedFirst = planSelectionSnooze(
            listOf(target("m1", trusted = false), target("m1", trusted = true)),
        )
        assertEquals(listOf("m1"), untrustedFirst.snooze.map { it.email.id })
        assertEquals(listOf("m1"), untrustedFirst.refused.map { it.email.id })
        assertEquals(listOf(true), untrustedFirst.snooze.map { it.folderTrusted })
        assertEquals(listOf(false), untrustedFirst.refused.map { it.folderTrusted })
    }

    /** No row may be dropped between the two parts: what goes in comes out, once, somewhere. */
    @Test
    fun `every target ends up in exactly one part`() {
        val targets = listOf(
            target("m1", trusted = true),
            target("m2", trusted = false),
            target("m3", trusted = true),
            target("m4", trusted = false),
            target("m5", trusted = true),
        )
        val plan = planSelectionSnooze(targets)
        assertEquals(
            listOf("m1", "m3", "m5", "m2", "m4"),
            (plan.snooze + plan.refused).map { it.email.id },
        )
        assertEquals(targets.size, plan.snooze.size + plan.refused.size)
    }
}
