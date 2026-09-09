package app.sterna.ui.inbox

import app.sterna.core.data.mail.deleteDestroysMessage
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [planSelectionDelete] — who is destroyed and who is moved, executed rather than re-derived.
 */
class SelectionDeletePlanTest {

    private fun email(id: String, account: String? = "accA", mailbox: String? = "mbA") =
        Email(id = id, accountId = account, mailboxId = mailbox, subject = "s")

    private fun target(id: String, trusted: Boolean, account: String? = "accA", mailbox: String? = "mbA") =
        SelectionTarget(email(id, account, mailbox), folderTrusted = trusted)

    /** Records what each probe was asked, so "never asked about an untrusted row" is assertable. */
    private class Probes(
        val destroys: Set<String> = emptySet(),
        val trashless: Set<String?> = emptySet(),
    ) {
        val wouldDestroyAsked = mutableListOf<String>()
        val hasTrashAsked = mutableListOf<String>()

        val wouldDestroy: suspend (Email) -> Boolean = { e ->
            wouldDestroyAsked += e.id
            e.id in destroys
        }
        val hasTrash: suspend (Email) -> Boolean = { e ->
            hasTrashAsked += e.id
            e.accountId !in trashless
        }
    }

    @Test
    fun `a trusted row already in the Trash is destroyed, the others move`() = runTest {
        val probes = Probes(destroys = setOf("m2"))
        val plan = planSelectionDelete(
            targets = listOf(target("m1", trusted = true), target("m2", trusted = true)),
            hasTrash = probes.hasTrash,
            wouldDestroy = probes.wouldDestroy,
        )
        assertEquals(listOf("m2"), plan.destroy.map { it.id })
        assertEquals(listOf("m1"), plan.move.map { it.id })
        assertEquals(emptyList<String>(), plan.untreated.map { it.id })
        assertEquals("a trusted row's folder is the one thing worth asking about", listOf("m1", "m2"), probes.wouldDestroyAsked)
        assertEquals("and it needs no account-level probe", emptyList<String>(), probes.hasTrashAsked)
    }

    /**
     * The reported case: results selected out of a search, no local row, folder unusable. They must
     */
    @Test
    fun `an untrusted row goes to the Trash and is never offered to the destroy probe`() = runTest {
        val probes = Probes(destroys = setOf("m1", "m2"))
        val plan = planSelectionDelete(
            targets = listOf(target("m1", trusted = false), target("m2", trusted = false, mailbox = "")),
            hasTrash = probes.hasTrash,
            wouldDestroy = probes.wouldDestroy,
        )
        assertEquals("nothing may be destroyed on an untrusted folder", emptyList<String>(), plan.destroy.map { it.id })
        assertEquals(listOf("m1", "m2"), plan.move.map { it.id })
        assertEquals(emptyList<String>(), plan.untreated.map { it.id })
        assertEquals("the row's own folder must not even be asked about", emptyList<String>(), probes.wouldDestroyAsked)
    }

    /**
     * No Trash: the account-level probe answers no, and the move has nowhere the caller can
     * name. A row we cannot place is then left alone — the caller counts it and says so — instead of being destroyed.
     */
    @Test
    fun `on an account with no Trash an untrusted row is left alone, not destroyed`() = runTest {
        val probes = Probes(destroys = setOf("m1"), trashless = setOf("accA"))
        val plan = planSelectionDelete(
            targets = listOf(target("m1", trusted = false)),
            hasTrash = probes.hasTrash,
            wouldDestroy = probes.wouldDestroy,
        )
        assertEquals(emptyList<String>(), plan.destroy.map { it.id })
        assertEquals(emptyList<String>(), plan.move.map { it.id })
        assertEquals(listOf("m1"), plan.untreated.map { it.id })
    }

    /**
     * A trusted row on that same Trash-less account: it MOVES. The probe below is the shipped
     */
    @Test
    fun `on an account with no Trash a trusted row is moved, not destroyed`() = runTest {
        val plan = planSelectionDelete(
            targets = listOf(target("m1", trusted = true)),
            hasTrash = { false },
            wouldDestroy = { email -> deleteDestroysMessage(trashMailboxId = null, messageMailboxId = email.mailboxId) },
        )
        assertEquals("nothing may be destroyed for want of a Trash", emptyList<String>(), plan.destroy.map { it.id })
        assertEquals(listOf("m1"), plan.move.map { it.id })
        assertEquals(emptyList<String>(), plan.untreated.map { it.id })
    }

    /** And the other half of the same shipped rule, through the same plan: a trusted row that IS
     *  in the Trash is destroyed — otherwise a delete inside the bin moves it onto itself. */
    @Test
    fun `a trusted row sitting in the Trash is destroyed, by the shipped rule`() = runTest {
        val plan = planSelectionDelete(
            targets = listOf(target("m1", trusted = true, mailbox = "mbTrash")),
            hasTrash = { true },
            wouldDestroy = { email -> deleteDestroysMessage(trashMailboxId = "mbTrash", messageMailboxId = email.mailboxId) },
        )
        assertEquals(listOf("m1"), plan.destroy.map { it.id })
        assertEquals(emptyList<String>(), plan.move.map { it.id })
    }

    /**
     * A mixed selection, and the same id in both trust states — the two rows a merge can produce
     */
    @Test
    fun `the same id trusted and untrusted lands in both legs, in either order`() = runTest {
        val trustedFirst = planSelectionDelete(
            targets = listOf(target("m1", trusted = true), target("m1", trusted = false)),
            hasTrash = Probes().hasTrash,
            wouldDestroy = Probes(destroys = setOf("m1")).wouldDestroy,
        )
        assertEquals("trusted copy destroyed", listOf("m1"), trustedFirst.destroy.map { it.id })
        assertEquals("untrusted copy moved", listOf("m1"), trustedFirst.move.map { it.id })

        val untrustedFirst = planSelectionDelete(
            targets = listOf(target("m1", trusted = false), target("m1", trusted = true)),
            hasTrash = Probes().hasTrash,
            wouldDestroy = Probes(destroys = setOf("m1")).wouldDestroy,
        )
        assertEquals("trusted copy destroyed", listOf("m1"), untrustedFirst.destroy.map { it.id })
        assertEquals("untrusted copy moved", listOf("m1"), untrustedFirst.move.map { it.id })
    }

    /**
     * The Trash question is about the ACCOUNT, so it is asked once per account. Asked per message
     */
    @Test
    fun `the Trash is asked about once per account, not once per message`() = runTest {
        val probes = Probes()
        val plan = planSelectionDelete(
            targets = listOf(
                target("m1", trusted = false),
                target("m2", trusted = false),
                target("m3", trusted = false, account = "accB"),
            ),
            hasTrash = probes.hasTrash,
            wouldDestroy = probes.wouldDestroy,
        )
        assertEquals(listOf("m1", "m2", "m3"), plan.move.map { it.id })
        assertEquals(
            "one probe per account, not one per message",
            listOf("m1", "m3"),
            probes.hasTrashAsked,
        )
    }

    /** Two accounts, one with a Trash and one without: neither answer may leak into the other. */
    @Test
    fun `each account answers for its own Trash`() = runTest {
        val probes = Probes(trashless = setOf("accB"))
        val plan = planSelectionDelete(
            targets = listOf(
                target("m1", trusted = false, account = "accA"),
                target("m2", trusted = false, account = "accB"),
            ),
            hasTrash = probes.hasTrash,
            wouldDestroy = probes.wouldDestroy,
        )
        assertEquals(listOf("m1"), plan.move.map { it.id })
        assertEquals(listOf("m2"), plan.untreated.map { it.id })
        assertEquals(emptyList<String>(), plan.destroy.map { it.id })
    }
}
