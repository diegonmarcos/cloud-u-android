package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [resolveSelectionTargets] — what a bulk action acts on, executed rather than re-derived.
 */
class SelectionTargetsTest {

    private fun email(
        account: String?,
        id: String,
        subject: String = "s",
        mailbox: String? = null,
    ) = Email(id = id, accountId = account, mailboxId = mailbox, subject = subject)

    private val k1 = EmailKey("accA", "m1")
    private val k2 = EmailKey("accA", "m2")
    private val k3 = EmailKey("accA", "m3")

    @Test
    fun `every selected key has a cached row`() {
        val out = resolveSelectionTargets(
            keys = setOf(k1, k2),
            cached = listOf(email("accA", "m1"), email("accA", "m2")),
            displayed = null,
        )
        assertEquals(listOf("m1", "m2"), out.targets.map { it.email.id })
        assertEquals(listOf(true, true), out.targets.map { it.folderTrusted })
        assertEquals(emptySet<EmailKey>(), out.unresolved)
    }

    @Test
    fun `nothing is cached and every row comes off the screen`() {
        // The reported case: a search whose hits never landed in the local table. Before the fix
        // this returned no target at all and the six actions did nothing, quietly.
        val out = resolveSelectionTargets(
            keys = setOf(k1, k2),
            cached = emptyList(),
            displayed = listOf(email("accA", "m1"), email("accA", "m2")),
        )
        assertEquals(listOf("m1", "m2"), out.targets.map { it.email.id })
        assertEquals(listOf(false, false), out.targets.map { it.folderTrusted })
        assertEquals(emptySet<EmailKey>(), out.unresolved)
    }

    @Test
    fun `a mix of cached and displayed rows, walked in the order of the selection`() {
        val out = resolveSelectionTargets(
            keys = setOf(k1, k2, k3),
            // Deliberately out of selection order: the answer follows the keys, not the query.
            cached = listOf(email("accA", "m3"), email("accA", "m1")),
            displayed = listOf(email("accA", "m2")),
        )
        assertEquals(listOf("m1", "m2", "m3"), out.targets.map { it.email.id })
        assertEquals(listOf(true, false, true), out.targets.map { it.folderTrusted })
        assertEquals(emptySet<EmailKey>(), out.unresolved)
    }

    @Test
    fun `the cached row wins over the displayed row of the same key`() {
        val out = resolveSelectionTargets(
            keys = setOf(k1, k2),
            cached = listOf(email("accA", "m1", subject = "from the cache")),
            displayed = listOf(
                email("accA", "m1", subject = "from the index"),
                email("accA", "m2", subject = "screen only"),
            ),
        )
        assertEquals(2, out.targets.size)
        assertEquals("from the cache", out.targets[0].email.subject)
        assertTrue(out.targets[0].folderTrusted)
        assertEquals("screen only", out.targets[1].email.subject)
        assertFalse(out.targets[1].folderTrusted)
    }

    @Test
    fun `two accounts sharing an id each get their own row`() {
        // Ids are numbered per account on the same server, so a lookup by bare id would hand
        // account B's action account A's message.
        val a7 = EmailKey("accA", "7")
        val b7 = EmailKey("accB", "7")
        val out = resolveSelectionTargets(
            keys = setOf(a7, b7),
            cached = listOf(email("accA", "7", subject = "A's seven")),
            displayed = listOf(email("accB", "7", subject = "B's seven")),
        )
        assertEquals(listOf("accA", "accB"), out.targets.map { it.email.accountId })
        assertEquals(listOf("A's seven", "B's seven"), out.targets.map { it.email.subject })
        assertEquals(listOf(true, false), out.targets.map { it.folderTrusted })
    }

    @Test
    fun `two displayed rows sharing an id, only the selected account's is taken`() {
        // The #92 shape on the screen side: `mergeHits` dedupes by (accountId, id), so a unified
        // search keeps BOTH rows numbered 7. Looking the fallback up by bare id would archive or
        // delete account A's message when the user picked account B's result.
        //
        // Both orders, deliberately: an id-keyed map keeps whichever row comes LAST, so a single
        // ordering lets a bare-id lookup answer correctly by luck and the case proves nothing.
        val a7 = email("accA", "7", subject = "A's seven")
        val b7 = email("accB", "7", subject = "B's seven")
        for (screen in listOf(listOf(a7, b7), listOf(b7, a7))) {
            val out = resolveSelectionTargets(
                keys = setOf(EmailKey("accB", "7")),
                cached = emptyList(),
                displayed = screen,
            )
            assertEquals(1, out.targets.size)
            assertEquals("accB", out.targets[0].email.accountId)
            assertEquals("B's seven", out.targets[0].email.subject)
            assertFalse(out.targets[0].folderTrusted)
            assertEquals(emptySet<EmailKey>(), out.unresolved)
        }
    }

    @Test
    fun `an empty selection asks for nothing`() {
        val out = resolveSelectionTargets(
            keys = emptySet(),
            cached = listOf(email("accA", "m1")),
            displayed = listOf(email("accA", "m2")),
        )
        assertEquals(emptyList<SelectionTarget>(), out.targets)
        assertEquals(emptySet<EmailKey>(), out.unresolved)
    }

    @Test
    fun `a search showing no rows is not the same as no search`() {
        // displayed = emptyList(): the search is on screen and has nothing to offer. Every key
        // still has to come back somewhere, so they all land in unresolved.
        val out = resolveSelectionTargets(keys = setOf(k1, k2), cached = emptyList(), displayed = emptyList())
        assertEquals(emptyList<SelectionTarget>(), out.targets)
        assertEquals(setOf(k1, k2), out.unresolved)
    }

    @Test
    fun `a key neither cached nor displayed is unresolved, and no row is invented`() {
        val out = resolveSelectionTargets(
            keys = setOf(k1, k2, k3),
            cached = emptyList(),
            displayed = listOf(email("accA", "m2")),
        )
        assertEquals(listOf("m2"), out.targets.map { it.email.id })
        assertEquals(setOf(k1, k3), out.unresolved)
    }

    @Test
    fun `a displayed row is never trusted for its folder, even with a mailboxId`() {
        // The whole point of the flag: this id is non-empty and still worthless — it is whatever
        // the index held when it was crawled.
        val out = resolveSelectionTargets(
            keys = setOf(k1),
            cached = emptyList(),
            displayed = listOf(email("accA", "m1", mailbox = "mbx-42")),
        )
        assertEquals(1, out.targets.size)
        assertFalse(out.targets[0].folderTrusted)
        // The value is carried through untouched; it is the trust that is withheld, not the data.
        assertEquals("mbx-42", out.targets[0].email.mailboxId)
    }

    @Test
    fun `outside a search there is no displayed list at all`() {
        val out = resolveSelectionTargets(
            keys = setOf(k1, k2),
            cached = listOf(email("accA", "m1", mailbox = "inbox")),
            displayed = null,
        )
        assertEquals(listOf("m1"), out.targets.map { it.email.id })
        assertTrue(out.targets[0].folderTrusted)
        assertEquals(setOf(k2), out.unresolved)
    }

    @Test
    fun `a row that is not selected stays out, from either source`() {
        val out = resolveSelectionTargets(
            keys = setOf(k1, k2),
            cached = listOf(email("accA", "m1"), email("accA", "m9")),
            displayed = listOf(email("accA", "m2"), email("accB", "m8")),
        )
        assertEquals(listOf("m1", "m2"), out.targets.map { it.email.id })
        assertEquals(emptySet<EmailKey>(), out.unresolved)
    }
}
