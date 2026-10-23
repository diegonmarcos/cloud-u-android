package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1138 the grouped actions: their order, that no declared operation is lost, the confirmation gates, and the new git verbs' scripts. */
class RepoActionsTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val page: Declarations.GitPageDecl by lazy {
        val b: JsonObject = Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject
        Declarations.parseGitPage(b["ui"]!!.jsonObject["sync"]!!.jsonObject["git"])
    }

    @Test fun `the groups render in the owner's order, Sync first and Clone settings last`() {
        assertEquals(
            listOf(RepoGroup.SYNC, RepoGroup.STEPS, RepoGroup.METADATA, RepoGroup.GOTO, RepoGroup.WORKFLOW, RepoGroup.DANGEROUS, RepoGroup.REPO_CLEAN, RepoGroup.OTHERS, RepoGroup.CLONE_SETTINGS),
            RepoActions.GROUPS.map { it.group },
        )
    }

    @Test fun `each group holds its actions in order`() {
        fun of(g: RepoGroup) = RepoActions.GROUPS.first { it.group == g }.actions
        assertEquals(listOf("sync_no_winner", "sync_remote_wins", "sync_local_wins"), of(RepoGroup.SYNC))
        assertEquals(listOf("fetch", "pull", "push", "commit", "stats"), of(RepoGroup.STEPS))
        assertEquals(listOf("remote", "metadata", "meta_branch", "meta_sync"), of(RepoGroup.METADATA))
        assertEquals(listOf("open_folder", "web"), of(RepoGroup.GOTO))
        assertEquals(listOf("history", "runs", "actions"), of(RepoGroup.WORKFLOW))
        assertEquals(listOf("force_pull", "force_push"), of(RepoGroup.DANGEROUS))
        assertEquals(listOf("clean_1", "clean_5", "clean_10"), of(RepoGroup.REPO_CLEAN))
        assertEquals(listOf("clone_full", "clone_depth1", "clone_oauth", "clone_ssh"), of(RepoGroup.CLONE_SETTINGS))
    }

    @Test fun `every declared operation sits in exactly one group`() {
        val placed = RepoActions.GROUPS.flatMap { it.actions }
        assertEquals("an action sits in two groups", placed.size, placed.toSet().size)
        for (op in page.ops) assertEquals("declared operation ${op.id} must be in exactly one group", 1, placed.count { it == op.id })
    }

    @Test fun `the declared destructive operations are exactly the Dangerous group`() {
        assertEquals(page.ops.filter { it.destructive }.map { it.id }.sorted(), RepoActions.DANGEROUS.sorted())
    }

    @Test fun `Dangerous and Repo clean ask first, every other action runs on the tap`() {
        for (id in RepoActions.DANGEROUS + RepoActions.REPO_CLEAN) assertEquals("$id must be gated", RepoActions.Route.CONFIRM, RepoActions.route(id))
        for (id in RepoActions.GROUPS.flatMap { it.actions } - (RepoActions.DANGEROUS + RepoActions.REPO_CLEAN).toSet()) assertEquals("$id must not be gated", RepoActions.Route.RUN, RepoActions.route(id))
    }

    @Test fun `repo clean keeps depth 1, 5 or 10 and the sync verbs and the trim are the compound ones`() {
        assertEquals(listOf(1, 5, 10), RepoActions.REPO_CLEAN.map { RepoActions.cleanDepth(it) })
        assertEquals(0, RepoActions.cleanDepth("fetch"))
        for (id in listOf("sync_no_winner", "sync_remote_wins", "sync_local_wins", "clean_1", "clean_5", "clean_10")) assertTrue(id, RepoActions.isCompound(id))
        for (id in listOf("fetch", "force_pull", "force_push", "sync_now")) assertFalse(id, RepoActions.isCompound(id))
    }

    // ── the terminal-root scripts of the verbs the engine has no method for ──
    private val dir = "/root/cloud-shared-store/git/r"
    private fun plan(verb: String, args: JSONObject = JSONObject(), auth: JSONObject? = null) = TermuxGitWire.plan(verb, dir, args, auth)

    @Test fun `repo clean is local only - it fetches to a depth, expires the reflog and collects, and never pushes`() {
        val p = plan("shrink", JSONObject().put("depth", 5)).first!!
        assertTrue(p.script.contains("fetch --depth 5 'origin'"))
        assertTrue(p.script.contains("reflog expire --expire=now --all"))
        assertTrue(p.script.contains("gc --prune=now"))
        assertFalse(p.script.contains("push"))
        assertTrue(p.network)
        for (bad in listOf(0, -1, 1001)) assertNull("depth $bad", plan("shrink", JSONObject().put("depth", bad)).first)
    }

    @Test fun `sync-local-wins pushes with a lease, never a bare force`() {
        val p = plan("force_push_lease").first!!
        assertTrue(p.script.contains("push --force-with-lease 'origin'"))
        assertFalse(p.script.contains("push --force 'origin'"))
    }

    @Test fun `sync-no-winner merges the upstream and aborts a conflicted merge only when a merge is in progress`() {
        val p = plan("merge_upstream", JSONObject().put("name", "n").put("email", "e@x")).first!!
        assertTrue(p.script.contains("fetch 'origin'"))
        assertTrue(p.script.contains("merge --no-edit '@{u}'"))
        assertTrue(p.script.contains("MERGE_HEAD") && p.script.contains("merge --abort"))
        assertFalse("a refused merge must never hard-reset the tree", p.script.contains("reset --hard"))
    }

    @Test fun `sync-remote-wins backs up local changes, untracked included, before the reset`() {
        val p = plan("stash", JSONObject().put("name", "n").put("email", "e@x").put("message", "before remote-wins")).first!!
        assertTrue(p.script.contains("stash push --include-untracked -m 'before remote-wins'"))
        assertFalse(p.network)
    }

    @Test fun `the new verbs answer a GitOpResult and are still quoted and credential-free in the script`() {
        for (v in listOf("force_push_lease", "merge_upstream", "stash", "shrink")) assertTrue(v, v in TermuxGitWire.OPS)
        val auth = JSONObject().put("kind", "https").put("username", "u").put("secret", "SECRET-TOKEN")
        val p = plan("force_push_lease", JSONObject(), auth).first!!
        assertFalse(p.script.contains("SECRET-TOKEN")); assertTrue(p.stdin.startsWith("Authorization: Basic "))
        assertNotNull(plan("merge_upstream").first)
    }
}
