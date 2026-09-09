package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides WHAT an "Empty trash" may destroy (Codeberg #99), checked without a
 */
class TrashPurgeSnapshotTest {

    private fun rows(ids: List<String>, cap: Int = TrashPurge.SNAPSHOT_MAX) =
        TrashPurge.snapshotRows("p1", "accA", "trash", ids, now = 1_000L, cap = cap)

    @Test fun keepsExactlyTheIdsPresentAtConfirmation() {
        assertEquals(listOf("m1", "m2", "m3"), rows(listOf("m1", "m2", "m3")).map { it.emailId })
    }

    @Test fun aMessageThatArrivesAfterTheConfirmationIsNotInTheSnapshot() {
        // The user confirms with m1/m2 in Trash, then files m3 there during the undo window.
        val snapshot = rows(listOf("m1", "m2"))
        val trashNow = listOf("m1", "m2", "m3")

        // The purge destroys the snapshot, not the folder: m3 survives. Re-reading the folder
        // (the old behaviour) would have destroyed it — that is the bug.
        assertEquals(listOf("m1", "m2"), snapshot.map { it.emailId })
        assertTrue("m3" in trashNow)
        assertTrue("m3" !in snapshot.map { it.emailId })
    }

    @Test fun emptyTrashSnapshotsNothingAndDestroysNothing() {
        assertEquals(emptyList<String>(), rows(emptyList()).map { it.emailId })
    }

    @Test fun idsAreDeduplicatedAndBlanksDropped() {
        assertEquals(listOf("m1", "m2"), rows(listOf("m1", "m1", "", "  ", "m2", "m1")).map { it.emailId })
    }

    @Test fun theCapTruncatesTheSnapshotInsteadOfWideningIt() {
        val ids = (1..10).map { "m$it" }

        // Past the cap the surplus is simply not on the list: it survives the purge and a second
        // Empty trash clears it. Going back to "everything in the folder at execution time" to
        // catch up is exactly what destroyed unconfirmed mail.
        assertEquals(listOf("m1", "m2", "m3"), rows(ids, cap = 3).map { it.emailId })
    }

    @Test fun capOfZeroSnapshotsNothing() {
        assertEquals(emptyList<String>(), rows(listOf("m1", "m2"), cap = 0).map { it.emailId })
    }

    @Test fun everyIdCarriesItsAccountFolderPurgeAndTime() {
        val row = rows(listOf("m1")).single()

        // An email id is unique only inside its account (issue #31): the snapshot stores the
        // account with it so the destroy can never be resolved against a sibling account.
        assertEquals("accA", row.accountId)
        assertEquals("trash", row.mailboxId)
        assertEquals("p1", row.purgeId)
        assertEquals(1_000L, row.createdAt)
    }

    @Test fun aFullSnapshotIsDrainedByTheWaveBudget() {
        // The purge destroys the snapshot in waves; the budget must cover a full one, so a
        // 10 000-message Trash cannot leave a remainder the worker never gets to.
        assertTrue(TrashPurge.MAX_WAVES * TrashPurge.DESTROY_WAVE >= TrashPurge.SNAPSHOT_MAX)
    }
}
