package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1356 The Git table's three size columns. Main (depth 1) is the default branch's tip as a depth-1 checkout, Total (history)
 * the forge's packed size of every commit (KiB), On disk the bytes of the clone folder on the phone. Main can never exceed
 * Total; the forge's KiB are bytes * 1024. test-drive-git-size-columns.sh breaks each rule once and proves this goes red.
 */
class RepoSizeColumnsTest {
    private fun stats(treeBytes: Long, totalKb: Long) =
        RepoRemoteStats.Stats(sizeTotalKb = totalKb, sizeMainBytes = treeBytes, branches = 1, isPrivate = false, defaultBranch = "main", fetchedAtMs = 1)

    @Test fun `total is the forge's KiB in bytes`() {
        assertEquals(2048L, RepoTable.totalBytes(2))
        assertEquals(1_048_576L, RepoTable.totalBytes(1024))
    }

    @Test fun `main never exceeds total, even when the uncompressed tip tree is larger than the packed history`() {
        // the owner's case: 5 MB of raw blobs at the tip, a 1000 KiB pack for the whole history
        val r = RepoTable.rowOf("big-tree", null, false, null, stats(treeBytes = 5_000_000, totalKb = 1000))
        assertEquals(1_024_000L, r.sizeTotalBytes)
        assertTrue("main ${r.sizeMainBytes} > total ${r.sizeTotalBytes}", r.sizeMainBytes!! <= r.sizeTotalBytes!!)
        assertEquals(1_024_000L, r.sizeMainBytes)
    }

    @Test fun `a small tip keeps its own size`() {
        val r = RepoTable.rowOf("small", null, false, null, stats(treeBytes = 1000, totalKb = 2))
        assertEquals(1000L, r.sizeMainBytes); assertEquals(2048L, r.sizeTotalBytes)
    }

    @Test fun `main is at most total over a sweep of sizes`() {
        for (tree in listOf(0L, 1L, 1023L, 1024L, 1025L, 10_000_000L)) for (kb in listOf(0L, 1L, 2L, 9766L)) {
            val r = RepoTable.rowOf("r", null, false, null, stats(tree, kb))
            assertTrue("tree=$tree kb=$kb", r.sizeMainBytes!! <= r.sizeTotalBytes!!)
            assertEquals(kb * 1024, r.sizeTotalBytes)
        }
    }

    @Test fun `on disk is the clone's bytes, the dash when it is not cloned`() {
        assertEquals(4096L, RepoTable.rowOf("c", null, true, null, null, diskBytes = 4096).sizeDiskBytes)
        val notCloned = RepoTable.rowOf("n", null, false, null, null, diskBytes = 4096)
        assertNull(notCloned.sizeDiskBytes)
        assertEquals("—", RepoTable.size(notCloned.sizeDiskBytes))
        assertNull(RepoTable.rowOf("n", null, false, null, null).sizeMainBytes)
    }
}
