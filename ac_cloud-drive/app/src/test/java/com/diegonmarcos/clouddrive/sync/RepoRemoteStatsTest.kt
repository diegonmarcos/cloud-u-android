package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1138 the REST facts of the repo table, parsed from recorded bodies by the exact parsers the phone runs. */
class RepoRemoteStatsTest {

    @Test fun `the repository answer gives the total size in KB, the visibility and the default branch`() {
        val r = RepoRemoteStats.parseRepo("""{"name":"x","size":1234,"private":true,"default_branch":"trunk","owner":{"login":"o"}}""")!!
        assertEquals(1234L, r.sizeKb); assertEquals(true, r.isPrivate); assertEquals("trunk", r.defaultBranch)
        assertEquals("main", RepoRemoteStats.parseRepo("""{"size":0}""")!!.defaultBranch)
        assertNull(RepoRemoteStats.parseRepo("""{"message":"Not Found"}"""))
        assertNull(RepoRemoteStats.parseRepo("not json"))
    }

    @Test fun `the depth-1 size is the sum of the blob sizes and nothing else`() {
        val tree = """{"sha":"s","truncated":false,"tree":[
            {"path":"a.txt","type":"blob","size":100},
            {"path":"dir","type":"tree"},
            {"path":"dir/b.bin","type":"blob","size":2500},
            {"path":"sub","type":"commit"}]}"""
        assertEquals(2600L, RepoRemoteStats.parseTreeBytes(tree))
        assertEquals(0L, RepoRemoteStats.parseTreeBytes("""{"tree":[]}"""))
        assertNull(RepoRemoteStats.parseTreeBytes("""{"message":"Git Repository is empty."}"""))
    }

    @Test fun `the last page number comes from the Link header`() {
        val link = "<https://api.example/repos/o/r/branches?per_page=1&page=2>; rel=\"next\", <https://api.example/repos/o/r/branches?per_page=1&page=17>; rel=\"last\""
        assertEquals(17, RepoRemoteStats.lastPage(link))
        assertNull(RepoRemoteStats.lastPage(null))
        assertNull(RepoRemoteStats.lastPage("<https://api.example/x?page=2>; rel=\"next\""))
    }

    @Test fun `the branch count is the last page of a one-per-page listing, or the array size when it is not paginated`() {
        val link = "<https://api.example/b?per_page=1&page=2>; rel=\"next\", <https://api.example/b?per_page=1&page=9>; rel=\"last\""
        assertEquals(9, RepoRemoteStats.branchCount("""[{"name":"main"}]""", link))
        assertEquals(1, RepoRemoteStats.branchCount("""[{"name":"main"}]""", null))
        assertEquals(0, RepoRemoteStats.branchCount("[]", null))
        assertNull(RepoRemoteStats.branchCount("""{"message":"x"}""", null))
    }

    @Test fun `the run feed prints one line per workflow run`() {
        val body = """{"workflow_runs":[
            {"name":"CI","status":"completed","conclusion":"success","head_branch":"main","head_sha":"1234567890","created_at":"2026-10-23T10:00:00Z"},
            {"name":"Ship","status":"in_progress","conclusion":null,"head_branch":"main","head_sha":"abcdef0123","created_at":"2026-10-23T11:00:00Z"}]}"""
        val lines = RepoRemoteStats.parseRuns(body)
        assertEquals("✓ CI · main · 1234567 · 2026-10-23T10:00:00Z", lines[0])
        assertEquals("… Ship · main · abcdef0 · 2026-10-23T11:00:00Z", lines[1])
        assertEquals(emptyList<String>(), RepoRemoteStats.parseRuns("""{"message":"x"}"""))
    }

    @Test fun `the cache round-trips and a damaged one is empty`() {
        val m = mapOf("o/r" to RepoRemoteStats.Stats(12, 3456, 7, true, "main", 99L))
        assertEquals(m, RepoRemoteStats.Cache.decode(RepoRemoteStats.Cache.encode(m)))
        assertEquals(emptyMap<String, RepoRemoteStats.Stats>(), RepoRemoteStats.Cache.decode("{broken"))
        assertEquals(emptyMap<String, RepoRemoteStats.Stats>(), RepoRemoteStats.Cache.decode(null))
    }
}
