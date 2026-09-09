package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Runs the drawer's memory of the current view — [encodeSelection] and [restoreSelection] — as the
 */
class SelectionMemoryTest {

    // -- what gets written ------------------------------------------------------------------------

    @Test
    fun `each of the three views encodes to its own string, account id first`() {
        assertEquals("acc-1\nf\nINBOX", encodeSelection("acc-1", Sel.Folder("INBOX")))
        assertEquals("acc-1\nu\n", encodeSelection("acc-1", Sel.Unified))
        assertEquals("acc-1\nn\n", encodeSelection("acc-1", Sel.Unread))
    }

    /**
     * The account id travels WITH the selection, never a bare folder id: two accounts on the same
     */
    @Test
    fun `two accounts encode the same folder id to two different strings`() {
        assertEquals("acc-1\nf\n5", encodeSelection("acc-1", Sel.Folder("5")))
        assertEquals("acc-2\nf\n5", encodeSelection("acc-2", Sel.Folder("5")))
    }

    @Test
    fun `there is nothing to remember without an account`() {
        assertNull(encodeSelection(null, Sel.Folder("INBOX")))
        assertNull(encodeSelection(null, Sel.Unified))
        assertNull(encodeSelection(null, Sel.Unread))
    }

    /**
     * An unknown folder id is not a memory. Storing `Folder(null)` would reopen on a list with no
     */
    @Test
    fun `a selection with no folder id is not remembered`() {
        assertNull(encodeSelection("acc-1", Sel.Folder(null)))
    }

    // -- what gets read back ----------------------------------------------------------------------

    @Test
    fun `the three views survive the round trip`() {
        assertEquals(Sel.Folder("INBOX"), restoreSelection(encodeSelection("acc-1", Sel.Folder("INBOX")), "acc-1", false))
        assertEquals(Sel.Unified, restoreSelection(encodeSelection("acc-1", Sel.Unified), "acc-1", true))
        assertEquals(Sel.Unread, restoreSelection(encodeSelection("acc-1", Sel.Unread), "acc-1", false))
    }

    /**
     * Only the separator is special: dots and slashes are ordinary characters in an IMAP mailbox
     * path and must come back untouched.
     */
    @Test
    fun `a real IMAP folder path survives the round trip intact`() {
        val stored = encodeSelection("acc-1", Sel.Folder("INBOX.Projets/2026"))
        assertEquals("acc-1\nf\nINBOX.Projets/2026", stored)
        assertEquals(Sel.Folder("INBOX.Projets/2026"), restoreSelection(stored, "acc-1", false))
    }

    /**
     * A folder id CAN carry the separator, and the answer is to FORGET the view, not to escape
     */
    @Test
    fun `a folder id carrying the separator is refused, not escaped`() {
        val stored = encodeSelection("acc-1", Sel.Folder("INBOX.A\nB"))
        assertNull(
            "a remembered folder id holding the separator must be forgotten, not escaped and " +
                "reopened. Restored: ${restoreSelection(stored, "acc-1", true)}",
            restoreSelection(stored, "acc-1", true),
        )
        assertEquals("acc-1\nf\nINBOX.A\nB", stored)
    }

    @Test
    fun `nothing stored means the caller keeps today's behaviour`() {
        assertNull(restoreSelection(null, "acc-1", true))
        assertNull(restoreSelection("", "acc-1", true))
    }

    @Test
    fun `no current account means nothing to restore onto`() {
        assertNull(restoreSelection("acc-1\nf\nINBOX", null, true))
        assertNull(restoreSelection("acc-1\nu\n", null, true))
        assertNull(restoreSelection("acc-1\nn\n", null, true))
    }

    /**
     * The central guard: the memory belongs to ONE account. Deleted account, account switched
     */
    @Test
    fun `a memory of another account is refused, in all three forms`() {
        assertNull(restoreSelection("acc-2\nf\nINBOX", "acc-1", true))
        assertNull(restoreSelection("acc-2\nu\n", "acc-1", true))
        assertNull(restoreSelection("acc-2\nn\n", "acc-1", true))
    }

    /**
     * The unified view has no drawer entry on a single-account install (`InboxScreen` posts it
     */
    @Test
    fun `the unified view is only restored where the app offers it`() {
        assertEquals(Sel.Unified, restoreSelection("acc-1\nu\n", "acc-1", true))
        assertNull(restoreSelection("acc-1\nu\n", "acc-1", false))
    }

    /** The per-account unread view means something on an install that has one account. */
    @Test
    fun `the unread view is restored whether or not a second account exists`() {
        assertEquals(Sel.Unread, restoreSelection("acc-1\nn\n", "acc-1", false))
        assertEquals(Sel.Unread, restoreSelection("acc-1\nn\n", "acc-1", true))
    }

    @Test
    fun `a folder memory with no folder id is refused`() {
        assertNull(restoreSelection("acc-1\nf\n", "acc-1", true))
    }

    @Test
    fun `anything that is not three parts is refused`() {
        assertNull(restoreSelection("acc-1\nf", "acc-1", true))
        assertNull(restoreSelection("acc-1", "acc-1", true))
        assertNull(restoreSelection("acc-1\nf\nINBOX\n", "acc-1", true))
        assertNull(restoreSelection("acc-1\nf\nINBOX\nextra", "acc-1", true))
    }

    @Test
    fun `an unknown form is refused`() {
        assertNull(restoreSelection("acc-1\nz\nINBOX", "acc-1", true))
        assertNull(restoreSelection("acc-1\n\nINBOX", "acc-1", true))
        assertNull(restoreSelection("hello", "acc-1", true))
    }

    // -- what a sign-out takes with it (PRIVACY.md: "removed when you remove the account") --------

    /**
     * Pins the string RETURNED, not merely whether it is null: a `prunedView` answering `""`, or
     */
    @Test
    fun `a view naming a removed account is forgotten, a survivor's is kept byte for byte`() {
        assertNull(prunedView("acc-1\nf\nINBOX", setOf("acc-1")))
        assertEquals("acc-2\nf\nINBOX", prunedView("acc-2\nf\nINBOX", setOf("acc-1")))
    }

    @Test
    fun `the unified and the unread memories are pruned on their account too`() {
        assertNull(prunedView("acc-1\nu\n", setOf("acc-1")))
        assertNull(prunedView("acc-1\nn\n", setOf("acc-1")))
        assertEquals("acc-2\nu\n", prunedView("acc-2\nu\n", setOf("acc-1")))
        assertEquals("acc-2\nn\n", prunedView("acc-2\nn\n", setOf("acc-1")))
    }

    /** A sign-out cascades over a login's sub-accounts (#31), so several ids arrive at once. */
    @Test
    fun `any of the removed ids prunes, and only they do`() {
        assertNull(prunedView("acc-2\nf\nINBOX", setOf("acc-1", "acc-2")))
        assertEquals("acc-3\nf\nINBOX", prunedView("acc-3\nf\nINBOX", setOf("acc-1", "acc-2")))
    }

    @Test
    fun `nothing stored gives nothing, and nothing removed changes nothing`() {
        assertNull(prunedView(null, setOf("acc-1")))
        assertNull(prunedView("", setOf("acc-1")))
        assertEquals("acc-1\nf\nINBOX", prunedView("acc-1\nf\nINBOX", emptySet()))
    }

    /**
     * The FIRST field decides, not a well-formed value. A folder id can hold a line feed — an
     */
    @Test
    fun `a four field memory is pruned on its account like any other`() {
        assertNull(prunedView("acc-1\nf\nParents\nDivorce", setOf("acc-1")))
        assertEquals(
            "acc-2\nf\nParents\nDivorce",
            prunedView("acc-2\nf\nParents\nDivorce", setOf("acc-1")),
        )
    }

    /**
     * The removed id is matched as the WHOLE first field, never looked for anywhere in the
     */
    @Test
    fun `a removed id sitting in the payload prunes nothing`() {
        assertEquals("acc-2\nf\nacc-1", prunedView("acc-2\nf\nacc-1", setOf("acc-1")))
    }
}
