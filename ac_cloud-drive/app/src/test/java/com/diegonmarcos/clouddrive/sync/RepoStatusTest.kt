package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.sync.RepoStatus.ErrorKind
import com.diegonmarcos.clouddrive.sync.RepoStatus.Kind
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The row light and the STATUS block: the real status shows, every "?" has a named reason, an error becomes a sentence, the unpushed list is the newest [ahead] commits. */
class RepoStatusTest {
    private fun g(
        branch: String? = "main", upstream: String? = "origin/main", ahead: Int = 0, behind: Int = 0, changed: Int = 0, conflicts: Int = 0,
        untracked: Int = 0, error: String? = null, gone: Boolean = false, read: Boolean = true, fetched: Long = 0,
    ) = GitSyncCoordinator.Glance(branch, upstream, ahead, behind, changed, conflicts, error = error, gone = gone, read = read, untracked = untracked, fetchedAtMs = fetched)

    @Test fun `a status that was read decides the kind, with the reader's precedence`() {
        assertEquals(Kind.CLEAN, RepoStatus.kindOf(true, g()))
        assertEquals(Kind.AHEAD, RepoStatus.kindOf(true, g(ahead = 2)))
        assertEquals(Kind.BEHIND, RepoStatus.kindOf(true, g(ahead = 2, behind = 1)))
        assertEquals(Kind.DIRTY, RepoStatus.kindOf(true, g(changed = 1, behind = 3)))
        assertEquals(Kind.CONFLICT, RepoStatus.kindOf(true, g(changed = 1, conflicts = 1)))
    }

    @Test fun `a cloned repository that was never synced still shows its real status, not a question mark`() {
        // The old light read "never synced" (lastSync == 0) as UNKNOWN for every read repository.
        assertEquals(StatusLight.State.ON, RepoStatus.light(RepoStatus.kindOf(true, g())))
        assertEquals(StatusLight.State.OFF, RepoStatus.light(RepoStatus.kindOf(true, g(changed = 2))))
    }

    @Test fun `a question mark only ever means not cloned, not read yet or folder missing, and a failed read is a red ring`() {
        assertEquals(Kind.NOT_CLONED, RepoStatus.kindOf(false, g()))
        assertEquals(Kind.NOT_CLONED, RepoStatus.kindOf(false, null))
        assertEquals(Kind.READING, RepoStatus.kindOf(true, null))
        assertEquals(Kind.READING, RepoStatus.kindOf(true, g(read = false)))
        assertEquals(Kind.MISSING, RepoStatus.kindOf(true, g(gone = true)))
        assertEquals(Kind.ERROR, RepoStatus.kindOf(true, g(error = "boom")))
        assertEquals(StatusLight.State.UNKNOWN, RepoStatus.light(Kind.NOT_CLONED))
        assertEquals(StatusLight.State.UNKNOWN, RepoStatus.light(Kind.READING))
        assertEquals(StatusLight.State.UNVERIFIABLE, RepoStatus.light(Kind.MISSING))
        assertEquals(StatusLight.State.OFF, RepoStatus.light(Kind.ERROR))
        assertEquals(StatusLight.State.OFF, RepoStatus.light(Kind.CONFLICT))
    }

    @Test fun `a probe error maps to a human class`() {
        assertEquals(ErrorKind.TIMEOUT, RepoStatus.errorKind("timeout after 20s"))
        assertEquals(ErrorKind.NOT_A_REPOSITORY, RepoStatus.errorKind("Not a git repository: /x"))
        assertEquals(ErrorKind.PERMISSION, RepoStatus.errorKind("open failed: EACCES (Permission denied)"))
        assertEquals(ErrorKind.AUTH, RepoStatus.errorKind("HTTP 403 from remote"))
        assertEquals(ErrorKind.ENGINE, RepoStatus.errorKind("no coordinator has been built yet, so there is no Context to reach the git sync engine with"))
        assertEquals(ErrorKind.OTHER, RepoStatus.errorKind("weird"))
        assertEquals(ErrorKind.OTHER, RepoStatus.errorKind(null))
    }

    @Test fun `the status block of a read clone counts dirty, ahead, behind, unpushed and the last fetch`() {
        val b = RepoStatus.block(true, g(ahead = 3, behind = 1, changed = 5, conflicts = 1, untracked = 2, fetched = 1234L))
        assertEquals(Kind.CONFLICT, b.kind)
        assertEquals("main", b.branch); assertEquals("origin/main", b.upstream)
        assertEquals(3, b.modified); assertEquals(2, b.untracked); assertEquals(1, b.conflicts); assertEquals(6, b.dirty)
        assertEquals(3, b.ahead); assertEquals(1, b.behind); assertEquals(3, b.unpushed)
        assertEquals(1234L, b.lastFetchMs)
        assertNull(b.error)
    }

    @Test fun `with no upstream the remote counts are unknown, and a never-fetched clone has no fetch time`() {
        val b = RepoStatus.block(true, g(upstream = null, ahead = 4))
        assertNull(b.ahead); assertNull(b.behind); assertNull(b.unpushed); assertNull(b.lastFetchMs)
    }

    @Test fun `a repository that is not cloned or whose read failed has no counts, and the failure carries a message class`() {
        val nc = RepoStatus.block(false, null)
        assertEquals(false, nc.cloned); assertNull(nc.dirty); assertNull(nc.branch); assertNull(nc.error)
        val err = RepoStatus.block(true, g(error = "timeout after 20s"))
        assertEquals(Kind.ERROR, err.kind); assertEquals(ErrorKind.TIMEOUT, err.error); assertEquals("timeout after 20s", err.errorDetail)
        assertNull(err.dirty); assertNull(err.unpushed)
    }

    private fun commit(n: Int) = GitCommitInfo(sha = "abcdef0123456789$n", summary = "subject $n", message = "subject $n", author = "a", email = "a@x", time = 1000L - n, parents = emptyList())

    @Test fun `the unpushed list is the newest ahead-many commits`() {
        val log = (1..5).map(::commit)
        assertEquals(listOf("subject 1", "subject 2"), RepoStatus.unpushed(log, 2).map { it.summary })
        assertEquals(5, RepoStatus.unpushed(log, 99).size)
        assertTrue(RepoStatus.unpushed(log, 0).isEmpty())
        assertTrue(RepoStatus.unpushed(log, null).isEmpty())
        assertTrue(RepoStatus.unpushed(log, -3).isEmpty())
        assertEquals("abcdef01", RepoStatus.unpushed(log, 1).single().shortSha)
    }

    @Test fun `the action sits in the Goto group and is a plain reader`() {
        assertTrue(RepoActions.UNPUSHED in RepoActions.GROUPS.first { it.group == RepoGroup.GOTO }.actions)
        assertEquals(RepoActions.Route.RUN, RepoActions.route(RepoActions.UNPUSHED))
    }
}
