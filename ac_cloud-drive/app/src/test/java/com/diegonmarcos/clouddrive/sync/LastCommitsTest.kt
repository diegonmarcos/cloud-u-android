package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** #1256 the Last commit column: time unit, key canonicalisation, pushed_at fallback, first-open fill. */
class LastCommitsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun engineTimeIsSecondsAndBecomesMs() {
        assertEquals(1_761_696_000_000L, LastCommits.msOf(1_761_696_000L))
        assertNull(LastCommits.msOf(0)); assertNull(LastCommits.msOf(-5)); assertNull(LastCommits.msOf(null))
    }

    @Test fun keyResolvesSymlinksAndDotSegments() {
        val real = tmp.newFolder("store", "repo")
        val link = File(tmp.root, "link").toPath().also { Files.createSymbolicLink(it, real.toPath()) }.toFile()
        assertEquals(LastCommits.keyOf(real.path), LastCommits.keyOf(link.path))
        assertEquals(LastCommits.keyOf(real.path), LastCommits.keyOf(File(real, "../repo").path))
    }

    @Test fun notClonedFallsBackToPushedAt() {
        val r = RepoTable.rowOf("n", false, false, null, RepoRemoteStats.Stats(1, 1, 1, false, "main", 1, pushedAtMs = 777))
        assertEquals(777L, r.lastCommitMs)
    }

    @Test fun clonedUnreadFallsBackToPushedAtAndReadWins() {
        assertEquals(777L, RepoTable.lastCommit(true, null, 777))
        assertEquals(555L, RepoTable.lastCommit(true, 555, 777))
        assertNull(RepoTable.lastCommit(false, null, 0))
    }

    @Test fun firstOpenFillsOnlyAnEmptyCache() {
        assertTrue(LastCommits.needsFirstFill(emptyMap(), 3))
        assertFalse(LastCommits.needsFirstFill(mapOf("/a" to 1L), 3))
        assertFalse(LastCommits.needsFirstFill(emptyMap(), 0))
        assertTrue(LastCommits.needsRemoteFill(listOf(RepoRemoteStats.Stats(1, 1, 1, false, "main", 1))))
        assertFalse(LastCommits.needsRemoteFill(listOf(RepoRemoteStats.Stats(1, 1, 1, false, "main", 1, pushedAtMs = 9))))
    }

    @Test fun renderDashOnlyWhenUnknown() {
        assertEquals(RepoTable.DASH, RepoTable.age(null, 0)); assertEquals(RepoTable.DASH, RepoTable.age(0, 0))
        assertEquals("2d", RepoTable.age(1_000L, 1_000L + 2 * 86_400_000L))
    }
}
