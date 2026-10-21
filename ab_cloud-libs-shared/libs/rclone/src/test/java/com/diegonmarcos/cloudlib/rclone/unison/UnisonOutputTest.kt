package com.diegonmarcos.cloudlib.rclone.unison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1084 conflict handling, part one: reading a run. The fixtures are REAL output captured from the pinned
 * unison 2.53.8 (ocaml 4.14.2) on 2026-10-21, not hand-written approximations.
 */
class UnisonOutputTest {

    private val conflictRun = """
Unison 2.53.8 (ocaml 4.14.2): Contacting server...
Looking for changes
Reconciling changes
new file <-?-> new file   new.txt  
A            : new file           modified on 2026-10-21 at 19:03:58  size 4         unknown permissions
B            : new file           modified on 2026-10-21 at 19:03:58  size 5         unknown permissions
changed  <-?-> changed    one.txt  
changed  <-?-> deleted    two.txt  

0 items will be synced, 3 skipped
0 B to be synced from A to B
0 B to be synced from B to A
No updates to propagate
Synchronization complete at 19:03:58  (0 items transferred, 3 skipped, 0 failed)
  skipped: new.txt (contents changed on both sides)
  skipped: one.txt (contents changed on both sides)
  skipped: two.txt (conflicting updates)
""".trimStart().lines()

    private val cleanRun = listOf(
        "Starting...           [BGN] Copying one.txt from /a to /b",
        "Starting...            33%  0/2  (2 B of 4 B)  --:-- ETA                                  [END] Copying one.txt",
        "Saving synchronizer state",
        "Synchronization complete at 19:03:58  (2 items transferred, 0 skipped, 0 failed)",
    )

    @Test fun everySkippedItemIsAConflictWithItsReason() {
        val r = UnisonOutput.parse(conflictRun, rc = 1)
        assertEquals(listOf("new.txt", "one.txt", "two.txt"), r.conflicts.map { it.path })
        assertEquals("contents changed on both sides", r.conflicts[0].reason)
        assertEquals("conflicting updates", r.conflicts[2].reason)
        assertEquals(UnisonOutput.Summary(true, 0, 3, 0), r.summary)
        assertFalse("a run with skips is not a clean run", r.ok)
        assertEquals("0 transferred · 3 conflict(s) · 0 failed", r.headline())
    }

    @Test fun aCleanRunHasNoConflictsAndIsOk() {
        val r = UnisonOutput.parse(cleanRun, rc = 0)
        assertTrue(r.ok); assertTrue(r.conflicts.isEmpty()); assertTrue(r.failures.isEmpty())
        assertEquals(2, r.summary?.transferred)
        assertEquals("2 transferred · 0 conflict(s) · 0 failed", r.headline())
    }

    @Test fun failuresAreKeptApartFromConflicts() {
        val r = UnisonOutput.parse(listOf(
            "Synchronization incomplete at 10:00:00  (1 item transferred, 1 skipped, 1 failed)",
            "  skipped: a.txt (contents changed on both sides)",
            "  failed: b.bin (Permission denied)",
        ), rc = 2)
        assertEquals(listOf("a.txt"), r.conflicts.map { it.path })
        assertEquals(listOf(UnisonOutput.Failure("b.bin", "Permission denied")), r.failures)
        assertEquals(false, r.summary?.complete)
    }

    @Test fun aPathWithSpacesAndParenthesesIsReadWhole() {
        val r = UnisonOutput.parse(listOf("  skipped: Photos/Trip (2025)/img 1.jpg (conflicting updates)"), rc = 1)
        assertEquals("Photos/Trip (2025)/img 1.jpg", r.conflicts.single().path)
        assertEquals("conflicting updates", r.conflicts.single().reason)
    }

    @Test fun aFatalErrorIsNamedAndIsNeverOk() {
        val r = UnisonOutput.parse(listOf("Fatal error: Lost connection with the server"), rc = 3, rolledBack = true)
        assertEquals("Lost connection with the server", r.fatal)
        assertFalse(r.ok)
        assertEquals("fatal: Lost connection with the server · archives restored", r.headline())
    }

    @Test fun aCancelledRunSaysSoAndThatTheArchivesWereRestored() {
        assertEquals("cancelled · archives restored", UnisonOutput.parse(emptyList(), rc = -2, rolledBack = true).headline())
    }

    @Test fun aRunThatNeverStartedSaysWhy() {
        val r = UnisonOutput.parse(listOf("Fatal error: the engine is not installed"), rc = -1)
        assertEquals("fatal: the engine is not installed", r.headline())
    }

    @Test fun theProgressTickerIsNoiseInTheLogButRealLinesSurvive() {
        assertNull(UnisonOutput.display(" 50%  1/2  (2 B of 4 B)  --:-- ETA"))
        assertNull(UnisonOutput.display("Starting...           [BGN] Copying one.txt from /a to /b"))
        assertNotNull(UnisonOutput.display("Synchronization complete at 19:03:58  (2 items transferred, 0 skipped, 0 failed)"))
        // a carriage-return redrawn line shows its last segment
        assertEquals("Saving synchronizer state", UnisonOutput.display("100%  2/2  done\rSaving synchronizer state"))
        assertNull(UnisonOutput.display("   "))
    }
}
