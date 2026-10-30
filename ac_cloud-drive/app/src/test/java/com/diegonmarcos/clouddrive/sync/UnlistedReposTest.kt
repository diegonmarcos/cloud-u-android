package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1255 the Unlisted git folders section: declared roots, the persisted row list and its status keys. #1315 the walk and the
 * registry match live in the ONE inventory (InventoryScriptTest, RepoInventoryPhoneTest); this file keeps the section's own rules.
 */
class UnlistedReposTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }

    @Test fun `root templates expand from the declared placeholders and a placeholder with no value drops its root`() {
        val r = UnlistedRepos.expandRoots(listOf("{store_git}", "{repo_parents}", "{external}/Projects", "{nope}"), mapOf("store_git" to listOf("/s/git"), "repo_parents" to listOf("/p1", "/p2", "/s/git"), "external" to listOf("/sd")))
        assertEquals(listOf("/s/git", "/p1", "/p2", "/sd/Projects"), r.map { it.path })
    }

    @Test fun `the found list round-trips through prefs and its status keys never collide with a repo name`() {
        val l = listOf(UnlistedRepos.Folder("/a b", "https://x/y.git"), UnlistedRepos.Folder("/c", ""))
        assertEquals(l, UnlistedRepos.decode(UnlistedRepos.encode(l)))
        assertTrue(UnlistedRepos.statusKey("/c").startsWith(UnlistedRepos.KEY_PREFIX))
        assertEquals(listOf(l[1]), UnlistedRepos.without(l, setOf("/a b")))
    }

    @Test fun `the scan is declared - roots, bounds and the label`() {
        val page = Declarations.parseGitPage(Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"])
        assertTrue(page.unlisted.roots.isNotEmpty())
        assertTrue(page.unlisted.roots.all { Regex("""\{(store_git|repo_parents|external|files)}.*""").matches(it) })
        assertTrue(page.unlisted.maxDepth in 1..6 && page.unlisted.timeoutSeconds > 0 && page.unlisted.label.isNotBlank())
        assertTrue("#1315 the inventory is declared with it", page.inventory.debris.contains("*.incoming"))
        assertTrue("#1315 the cleanup age and the copy timeout are declared", page.inventory.cleanupMinAgeHours >= 1 && page.copyTimeoutSeconds in 10..120)
    }
}
