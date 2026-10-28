package com.diegonmarcos.clouddrive

import com.diegonmarcos.clouddrive.sync.RepoSotLive
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The stale-list refresh: the baked registry is a snapshot, a newer live one replaces it, renamed clones fold into their new name, archived repos are recognised. */
class RepoSotLiveTest {
    private lateinit var dir: File
    @Before fun setUp() { dir = Files.createTempDirectory("sot-live").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private val v1 = """{"_schema":"cloud-repos/v1","renames":[{"from":"cloud-infra","to":"cloudy-t-infra"}],"repos":[{"name":"cloudy-t-infra","url":"git@github.com:diegonmarcos/cloudy-t-infra.git","private":false,"renamed_from":["cloud-t-infra"]},{"name":"z-archived_old","url":"git@github.com:diegonmarcos/z-archived_old.git","private":false}]}"""
    private val v2 = v1.replace("z-archived_old", "z-archived_older")
    private val url = "https://example.invalid/repos.json"
    private class Fake(val answers: ArrayDeque<RepoSotLive.Response>) : RepoSotLive.Fetcher {
        val etagsSent = mutableListOf<String?>()
        override fun get(url: String, etag: String?): RepoSotLive.Response { etagsSent += etag; return answers.removeFirst() }
    }

    @Test fun firstOpenFetchesAndCaches_thenAFreshCacheIsNotAskedAgain_thenAnEtagMatchMovesNothing() {
        val f = Fake(ArrayDeque(listOf(RepoSotLive.Response(200, v1, "\"e1\""), RepoSotLive.Response(304, null, null))))
        assertNull(RepoSotLive.cached(dir))
        assertEquals(RepoSotLive.Outcome.UPDATED, RepoSotLive.refresh(dir, url, f, minIntervalMs = 1_000, now = 10_000))
        assertEquals(v1, RepoSotLive.cached(dir))
        assertEquals(RepoSotLive.Outcome.SKIPPED_FRESH, RepoSotLive.refresh(dir, url, f, 1_000, now = 10_500))
        assertEquals("no request while the cache is younger than the interval", 1, f.etagsSent.size)
        assertEquals(RepoSotLive.Outcome.NOT_MODIFIED, RepoSotLive.refresh(dir, url, f, 1_000, now = 20_000))
        assertEquals(listOf(null, "\"e1\""), f.etagsSent)
        assertEquals(v1, RepoSotLive.cached(dir))
    }

    @Test fun aNewerLiveRegistryReplacesTheCache_andABadAnswerNeverDoes() {
        val f = Fake(ArrayDeque(listOf(
            RepoSotLive.Response(200, v1, "\"e1\""), RepoSotLive.Response(200, v2, "\"e2\""),
            RepoSotLive.Response(200, "<html>proxy login</html>", "\"x\""), RepoSotLive.Response(500, null, null),
        )))
        RepoSotLive.refresh(dir, url, f, 0, now = 1)
        assertEquals(RepoSotLive.Outcome.UPDATED, RepoSotLive.refresh(dir, url, f, 0, now = 2))
        assertEquals(v2, RepoSotLive.cached(dir))
        assertEquals(RepoSotLive.Outcome.FAILED, RepoSotLive.refresh(dir, url, f, 0, now = 3))
        assertEquals(RepoSotLive.Outcome.FAILED, RepoSotLive.refresh(dir, url, f, 0, now = 4))
        assertEquals("the good cache survives", v2, RepoSotLive.cached(dir))
        assertEquals(RepoSotLive.Outcome.FAILED, RepoSotLive.refresh(dir, url, { _, _ -> error("offline") }, 0, now = 5))
        assertEquals(RepoSotLive.Outcome.FAILED, RepoSotLive.refresh(dir, "", f, 0, now = 6))
    }

    @Test fun theLiveObjectParsesLikeTheBakedArray_renamesFoldIntoTheNewName_archivedIsRecognised() {
        val sot = Declarations.parseRepoSot(v1)
        assertEquals(listOf("cloudy-t-infra", "z-archived_old"), sot.repos.map { it.github })
        assertEquals("a clone folder under an old name is the renamed repository", "cloudy-t-infra", sot.canonical("cloud-infra"))
        assertEquals("cloudy-t-infra", sot.canonical("cloud-t-infra"))
        assertEquals("a current name is itself", "cloudy-t-infra", sot.canonical("cloudy-t-infra"))
        assertEquals("an unknown name is itself", "my-photos", sot.canonical("my-photos"))
        assertTrue(sot.isArchived("z-archived_old")); assertFalse(sot.isArchived("cloudy-t-infra"))
        val baked = Declarations.parseRepoSot("""[{"name":"a","github":"a","private":false,"pending":false,"renamed_from":["old-a"],"lfs":true}]""")
        assertEquals("a", baked.canonical("old-a")); assertEquals(true, baked.lfsOf("a"))
        assertNotNull(sot.byGithub("cloudy-t-infra"))
    }

    @Test fun renameChainsAreFollowedToTheCurrentName() {
        val sot = Declarations.parseRepoSot("""{"renames":[{"from":"a","to":"b"},{"from":"b","to":"c"}],"repos":[{"name":"c","url":"git@github.com:o/c.git"}]}""")
        assertEquals("c", sot.canonical("a")); assertEquals("c", sot.canonical("b"))
    }

    @Test fun theBakedRegistryOfThisBuildListsNoOldName() {
        val f = File("data/repos.sot.json").takeIf { it.isFile } ?: File("../data/repos.sot.json")
        val sot = Declarations.parseRepoSot(f.readText())
        for (old in listOf("cloud-infra", "cloud-u-android", "cloud-___")) assertTrue("$old is not a current repository", sot.repos.none { it.github == old })
        assertEquals("cloudy-t-infra", sot.canonical("cloud-infra"))
    }
}
