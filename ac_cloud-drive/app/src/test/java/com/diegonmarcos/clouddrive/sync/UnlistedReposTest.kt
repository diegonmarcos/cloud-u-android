package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1255 the Unlisted git folders: declared roots, a bounded walk, registry match by origin (renames folded in), no remote = unlisted. */
class UnlistedReposTest {
    private val canonical: (String) -> String = { if (it == "old-name") "listed" else it }
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }

    private fun repo(parent: File, name: String, origin: String?): File = File(parent, name).apply {
        File(this, ".git").mkdirs()
        File(this, ".git/config").writeText("[core]\n\tbare = false\n" + (origin?.let { "[remote \"origin\"]\n\turl = $it\n" } ?: ""))
    }

    @Test fun `the walk finds repositories under the roots, never enters one, skips hidden and Android, and is bounded`() {
        val t = Files.createTempDirectory("unl").toFile()
        repo(t, "listed-clone", "https://github.com/o/listed.git")
        repo(File(t, "deep/er"), "nested", null)
        repo(File(t, ".hidden"), "h", null)
        repo(File(t, "Android/data"), "a", null)
        repo(File(t, "listed-clone"), "inner", null) // inside a repository: never reached
        val all = UnlistedRepos.walk(listOf(t), maxDepth = 3, maxDirs = 1000, deadlineMs = Long.MAX_VALUE)
        assertEquals(setOf("listed-clone", "nested"), all.map { File(it.path).name }.toSet())
        assertEquals("depth 1 does not reach deep/er/nested", setOf("listed-clone"), UnlistedRepos.walk(listOf(t), 1, 1000, Long.MAX_VALUE).map { File(it.path).name }.toSet())
        assertTrue("an expired deadline walks nothing", UnlistedRepos.walk(listOf(t), 3, 1000, deadlineMs = 0).isEmpty())
        t.deleteRecursively()
    }

    @Test fun `unlisted = origin names no registry repository (old names count as listed) or there is no remote`() {
        val found = listOf(
            UnlistedRepos.Folder("/a", "https://github.com/o/listed.git"),
            UnlistedRepos.Folder("/b", "git@github.com:o/old-name.git"),
            UnlistedRepos.Folder("/c", "https://example.org/x/stranger.git"),
            UnlistedRepos.Folder("/d", ""),
        )
        assertEquals(listOf("/c", "/d"), UnlistedRepos.unlisted(found, setOf("listed"), canonical).map { it.path })
    }

    @Test fun `root templates expand from the declared placeholders and a placeholder with no value drops its root`() {
        val r = UnlistedRepos.expandRoots(listOf("{store_git}", "{repo_parents}", "{external}/Projects", "{nope}"), mapOf("store_git" to listOf("/s/git"), "repo_parents" to listOf("/p1", "/p2", "/s/git"), "external" to listOf("/sd")))
        assertEquals(listOf("/s/git", "/p1", "/p2", "/sd/Projects"), r.map { it.path })
    }

    @Test fun `the found list round-trips through prefs and its status keys never collide with a repo name`() {
        val l = listOf(UnlistedRepos.Folder("/a b", "https://x/y.git"), UnlistedRepos.Folder("/c", ""))
        assertEquals(l, UnlistedRepos.decode(UnlistedRepos.encode(l)))
        assertTrue(UnlistedRepos.statusKey("/c").startsWith(UnlistedRepos.KEY_PREFIX))
    }

    @Test fun `the scan is declared - roots, bounds and the label`() {
        val page = Declarations.parseGitPage(Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"])
        assertTrue(page.unlisted.roots.isNotEmpty())
        assertTrue(page.unlisted.roots.all { Regex("""\{(store_git|repo_parents|external|files)}.*""").matches(it) })
        assertTrue(page.unlisted.maxDepth in 1..6 && page.unlisted.timeoutSeconds > 0 && page.unlisted.label.isNotBlank())
    }
}
