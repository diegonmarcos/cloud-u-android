package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [selectionUndoEntries] — who may be put back, executed rather than re-derived.
 */
class SelectionUndoTest {

    private fun email(id: String, account: String? = "accA", mailbox: String? = "mbA") =
        Email(id = id, accountId = account, mailboxId = mailbox, subject = "s")

    private fun trusted(id: String, mailbox: String? = "mbA") =
        UndoCandidate(SelectionTarget(email(id, mailbox = mailbox), folderTrusted = true), "trash")

    private fun untrusted(id: String, mailbox: String? = "mbA") =
        UndoCandidate(SelectionTarget(email(id, mailbox = mailbox), folderTrusted = false), "trash")

    @Test
    fun `trusted rows are put back into the folder they came from`() {
        val out = selectionUndoEntries(listOf(trusted("m1"), trusted("m2", mailbox = "mbB")))
        assertEquals(
            listOf(
                UndoEntry("m1", "accA", "mbA", "trash"),
                UndoEntry("m2", "accA", "mbB", "trash"),
            ),
            out,
        )
    }

    @Test
    fun `a snooze carries no destination, and that is not a reason to drop the entry`() {
        val candidate = UndoCandidate(SelectionTarget(email("m1"), folderTrusted = true), null)
        assertEquals(listOf(UndoEntry("m1", "accA", "mbA", null)), selectionUndoEntries(listOf(candidate)))
    }

    @Test
    fun `an untrusted row never becomes an Undo entry`() {
        assertEquals(emptyList<UndoEntry>(), selectionUndoEntries(listOf(untrusted("m1"))))
    }

    /**
     * The rule that is easy to get wrong and expensive to get wrong: dropping only the untrusted
     */
    @Test
    fun `one untrusted row going through withholds the whole batch's Undo`() {
        assertEquals(
            "trusted first",
            emptyList<UndoEntry>(),
            selectionUndoEntries(listOf(trusted("m1"), untrusted("m2"))),
        )
        assertEquals(
            "untrusted first",
            emptyList<UndoEntry>(),
            selectionUndoEntries(listOf(untrusted("m2"), trusted("m1"))),
        )
    }

    /**
     * The same id twice, trusted and not — the two rows a merge can hand over for one message.
     */
    @Test
    fun `the same id trusted and untrusted withholds the Undo in either order`() {
        assertEquals(
            "trusted copy first",
            emptyList<UndoEntry>(),
            selectionUndoEntries(listOf(trusted("m1"), untrusted("m1"))),
        )
        assertEquals(
            "untrusted copy first",
            emptyList<UndoEntry>(),
            selectionUndoEntries(listOf(untrusted("m1"), trusted("m1"))),
        )
    }

    /**
     * `email.mailboxId?.let { }` lets `""` through, and an Undo to an empty folder id is a move to
     */
    @Test
    fun `an empty or absent source folder yields no entry, and does not sink the others`() {
        val out = selectionUndoEntries(
            listOf(trusted("m1", mailbox = ""), trusted("m2", mailbox = null), trusted("m3")),
        )
        assertEquals(listOf(UndoEntry("m3", "accA", "mbA", "trash")), out)
    }

    @Test
    fun `nothing written means nothing to offer`() {
        assertEquals(emptyList<UndoEntry>(), selectionUndoEntries(emptyList()))
    }

    // -- the success banner's count ------------------------------------------------------------

    /** Any Int stands in for a plurals id: the decision never resolves it, it only carries it. */
    private val label = 4711

    @Test
    fun `three messages written are announced as three`() {
        val wrote = listOf(trusted("m1"), trusted("m2"), trusted("m3"))
        val entries = selectionUndoEntries(wrote)
        assertEquals("all three are undoable here", 3, entries.size)
        assertEquals(SelectionBanner(4711, 3), selectionBanner(label, wrote, entries))
    }

    /** The number is written out even at one: in ru/pl the CLDR `one` form also covers 21 and 101. */
    @Test
    fun `one message written still carries its count`() {
        val wrote = listOf(trusted("m1"))
        assertEquals(SelectionBanner(4711, 1), selectionBanner(label, wrote, selectionUndoEntries(wrote)))
    }

    /**
     * The confusion this exists to kill: ten messages left the folder, only four of them can be
     */
    @Test
    fun `the count is what was written, not what can be undone`() {
        val wrote = (1..4).map { trusted("m$it") } + (5..10).map { trusted("m$it", mailbox = "") }
        val entries = selectionUndoEntries(wrote)
        assertEquals("six rows name no folder, so only four are undoable", 4, entries.size)
        assertEquals(SelectionBanner(4711, 10), selectionBanner(label, wrote, entries))
    }

    /**
     * And when the whole batch's Undo is withheld (one untrusted row), there is no banner at all:
     * a success bar with no Undo button on it is a bar the user cannot take back.
     */
    @Test
    fun `no Undo entries means no banner, however much was written`() {
        val wrote = (1..9).map { trusted("m$it") } + untrusted("m10")
        val entries = selectionUndoEntries(wrote)
        assertEquals("one untrusted row withholds them all", 0, entries.size)
        assertNull("nine messages written, but nothing to put back", selectionBanner(label, wrote, entries))
    }

    /** No label, no banner: this is the mixed destroy+move delete, whose Undo is the held-back one. */
    @Test
    fun `a batch with no label announces nothing`() {
        val wrote = listOf(trusted("m1"), trusted("m2"))
        assertNull("no label, no banner", selectionBanner(null, wrote, selectionUndoEntries(wrote)))
    }

    @Test
    fun `nothing written, nothing announced`() {
        assertNull("nothing written", selectionBanner(label, emptyList(), emptyList()))
    }
}
