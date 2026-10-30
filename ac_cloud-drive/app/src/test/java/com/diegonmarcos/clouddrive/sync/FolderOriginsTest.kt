package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1317 Rename all failed with "the folder is gone" and listed REGISTERED clones as Parentless, because (1) java.io was asked about a
 * store only cld.termux can see, (2) an origin carrying an OLD repository name (GitHub redirects it) was taken as the current name,
 * (3) the lists were not refreshed after a run. The four rows the owner saw on the phone are the fixtures.
 */
class FolderOriginsTest {
    private val sot = Declarations.parseRepoSot("""{"repos":[
        {"name":"cloudy-u-linux","url":"git@github.com:diegonmarcos/cloudy-u-linux.git","private":false,"renamed_from":["cloud-u-linux"]},
        {"name":"cloudy_db_LFS","url":"git@github.com:diegonmarcos/cloudy_db_LFS.git","private":true,"renamed_from":["cloud-data-lfs","cloud_db_LFS"]},
        {"name":"Projects-___","url":"git@github.com:diegonmarcos/Projects-___.git","private":false,"renamed_from":["ffront","front","Projects","Projects___"]},
        {"name":"z-archived_cloud-me-pub-data","url":"git@github.com:diegonmarcos/z-archived_cloud-me-pub-data.git","private":false,"renamed_from":["cloud-me_data-pub","cloud-me-pub-data"]},
        {"name":"cloud-me-pvt-vault","url":"git@github.com:diegonmarcos/cloud-me-pvt-vault.git","private":true,"renamed_from":["cloud-vault","cloud-me_vault"]},
        {"name":"cloud-me-pvt-configs","url":"git@github.com:diegonmarcos/cloud-me-pvt-configs.git","private":true,"renamed_from":["cloud-me_configs"]},
        {"name":"_git-repos-master","url":"git@github.com:diegonmarcos/git-repos-master.git","private":false}]}""")
    private val names = RegistryNames(sot)

    /** folder on the phone, the stale origin it carries, the origin fixed. */
    private val rows = listOf(
        Triple("cloudy-u-linux", "https://github.com/diegonmarcos/cloud-u-linux.git", "https://github.com/diegonmarcos/cloudy-u-linux.git"),
        Triple("cloudy_db_LFS", "git@github.com:diegonmarcos/cloud-data-lfs.git", "git@github.com:diegonmarcos/cloudy_db_LFS.git"),
        Triple("Projects-___", "ssh://git@github.com/diegonmarcos/front", "ssh://git@github.com/diegonmarcos/Projects-___"),
        Triple("z-archived_cloud-me-pub-data", "https://github.com/diegonmarcos/cloud-me_data-pub.git", "https://github.com/diegonmarcos/z-archived_cloud-me-pub-data.git"),
        Triple("cloud-me-pvt-vault", "https://github.com/diegonmarcos/cloud-vault/", "https://github.com/diegonmarcos/cloud-me-pvt-vault"),
        Triple("cloud-me-pvt-configs", "git@github.com:diegonmarcos/Cloud-Me_Configs.git", "git@github.com:diegonmarcos/cloud-me-pvt-configs.git"),
    )

    // ── 2) old names fold to the current registry name ──

    @Test fun `the phone rows are registered clones with a stale origin, not Parentless`() {
        for ((folder, origin, _) in rows) {
            val f = LocalRepos.found(File("/s/git/$folder"), origin, names)
            assertEquals(folder, f.repoName)
            assertTrue("$folder: the origin says an old name", f.stale)
            assertTrue("$folder is not Parentless", LocalRepos.parentless(listOf(f), names).isEmpty())
        }
    }

    @Test fun `without the registry the same rows WERE parentless (the bug)`() {
        val f = LocalRepos.found(File("/s/git/cloudy-u-linux"), rows[0].second)
        assertEquals("cloud-u-linux", f.repoName)
        assertEquals(1, LocalRepos.parentless(listOf(f)).size)
    }

    @Test fun `a folder carrying an old name is still parentless and renames to the CURRENT name`() {
        val f = LocalRepos.found(File("/s/git/cloud-u-linux"), "https://github.com/diegonmarcos/cloud-u-linux.git", names)
        assertEquals(1, LocalRepos.parentless(listOf(f), names).size)
        assertEquals("cloudy-u-linux", f.repoName)
    }

    @Test fun `folder and origin names match case-insensitively through renamed_from`() {
        assertEquals("Projects-___", names.currentName("FRONT"))
        assertTrue(names.isOld("Front"))
        assertFalse(names.isOld("projects-___"))
        assertEquals("cloud-me-pvt-configs", names.currentName("Cloud-Me_Configs"))
    }

    // ── b) rename never targets a retired name ──

    @Test fun `rename never targets an old name, always the current registry name`() {
        for ((folder, origin, _) in rows) {
            val old = LocalRepos.repoNameOf(origin)
            assertTrue(names.isOld(old))
            val plan = FolderActions.renamePlan("some-other-folder", old, names, false, { null }) { false }
            assertEquals(FolderActions.RenamePlan.Go(folder), plan)
            assertFalse(names.isOld((plan as FolderActions.RenamePlan.Go).name))
        }
        assertEquals("cloudy-u-linux", FolderActions.renameName("cloud-u-linux", names))
    }

    @Test fun `a folder already carrying its registry name is skipped, never renamed to the GitHub name`() {
        // registry name _git-repos-master, GitHub name git-repos-master: either folder is right
        for (folder in listOf("_git-repos-master", "git-repos-master", "GIT-REPOS-MASTER")) {
            val plan = FolderActions.renamePlan(folder, "git-repos-master", names, false, { null }) { false }
            assertTrue("$folder skipped: $plan", plan is FolderActions.RenamePlan.Skip)
        }
        for ((folder, origin, _) in rows) assertTrue(FolderActions.renamePlan(folder, LocalRepos.repoNameOf(origin), names, false, { null }) { false } is FolderActions.RenamePlan.Skip)
        assertTrue(names.carries("_git-repos-master", "git-repos-master"))
    }

    // ── c) Fix origin: scheme, host, owner kept; only the name swaps ──

    @Test fun `fix origin gives the right URL for https, ssh and scp forms`() {
        for ((_, origin, fixed) in rows) assertEquals(fixed, FolderActions.fixedOrigin(origin, names))
    }

    @Test fun `an origin that is current, unknown or not a renamed_from is never rewritten`() {
        assertNull(FolderActions.fixedOrigin("https://github.com/diegonmarcos/cloudy-u-linux.git", names))
        assertNull(FolderActions.fixedOrigin("https://github.com/diegonmarcos/some-fork.git", names))
        assertNull(FolderActions.fixedOrigin("", names))
        assertNull(FolderActions.fixedOrigin("https://github.com/x/front.git?token=1", names))
        assertNull(FolderActions.fixedOrigin("git@github.com:diegonmarcos/git-repos-master.git", names))
    }

    @Test fun `the row shows the old name as old, and the log line names both`() {
        assertEquals("cloud-u-linux (old name of cloudy-u-linux)", FolderActions.originLabel("cloud-u-linux", names))
        assertEquals("cloudy-u-linux", FolderActions.originLabel("cloudy-u-linux", names))
        assertEquals("origin cloud-u-linux → cloudy-u-linux", FolderActions.fixNote(rows[0].second, names))
    }

    @Test fun `the set-url script touches only origin of the one folder and the config rewrite changes only the url`() {
        val s = FolderActions.setOriginScript("/g/cloudy-u-linux", "https://github.com/o/cloudy-u-linux.git") { "'$it'" }
        assertTrue(s.contains("remote set-url origin 'https://github.com/o/cloudy-u-linux.git'"))
        assertFalse(s.contains("*"))
        val cfg = "[core]\n\turl = keep\n[remote \"origin\"]\n\turl = https://github.com/o/cloud-u-linux.git\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n[branch \"main\"]\n"
        val out = LocalRepos.withOrigin(cfg, "https://github.com/o/cloudy-u-linux.git")!!
        assertTrue(out.contains("url = https://github.com/o/cloudy-u-linux.git\n\tfetch = +refs"))
        assertTrue(out.contains("[core]\n\turl = keep"))
        assertNull(LocalRepos.withOrigin("[core]\n", "x"))
    }

    @Test fun `setOrigin rewrites a real config and the clone then leaves Parentless and Stale`() {
        val root = Files.createTempDirectory("origins").toFile()
        val d = File(root, "cloudy-u-linux").apply { File(this, ".git").mkdirs() }
        File(d, ".git/config").writeText("[remote \"origin\"]\n\turl = https://github.com/o/cloud-u-linux.git\n")
        assertTrue(LocalRepos.scan(listOf(root), names).single().stale)
        assertTrue(LocalRepos.setOrigin(d, FolderActions.fixedOrigin(LocalRepos.originOf(d), names)!!))
        val f = LocalRepos.scan(listOf(root), names).single()
        assertFalse(f.stale)
        assertEquals("https://github.com/o/cloudy-u-linux.git", f.origin)
        assertTrue(LocalRepos.parentless(listOf(f), names).isEmpty())
        root.deleteRecursively()
    }

    // ── 1) through cld.termux java.io is never asked ──

    @Test fun `the exec path never pre-checks through java io`() {
        assertFalse(FolderActions.goneByIo(true) { error("java.io was asked about a cld.termux store") })
        assertTrue(FolderActions.goneByIo(false) { false })
        assertFalse(FolderActions.goneByIo(false) { true })
        val plan = FolderActions.renamePlan("x", "cloud-u-linux", names, true, { setOf() }) { error("java.io exists() on the exec path") }
        assertEquals(FolderActions.RenamePlan.Go("cloudy-u-linux"), plan)
    }

    @Test fun `the conflict check goes through cld.termux and a taken name becomes CONFLICT`() {
        var asked: List<String>? = null
        val plan = FolderActions.renamePlan("old", "cloud-u-linux", names, true, { asked = it; setOf("cloudy-u-linux", "cloudy-u-linux___CONFLICT") }) { error("java.io") }
        assertEquals(FolderActions.RenamePlan.Go("cloudy-u-linux___CONFLICT2"), plan)
        assertTrue(asked!!.containsAll(listOf("cloudy-u-linux", "cloudy-u-linux___CONFLICT", "cloudy-u-linux___CONFLICT99")))
        val s = FolderActions.takenScript("/g/git", asked!!) { "'$it'" }
        assertTrue(s.contains("[ -e '/g/git'/") && s.contains("'cloudy-u-linux___CONFLICT2'"))
        assertTrue("a failed probe refuses instead of risking an overwrite", FolderActions.renamePlan("old", "cloud-u-linux", names, true, { null }) { false } is FolderActions.RenamePlan.Fail)
    }

    @Test fun `on the direct path the conflict check is java io`() {
        val plan = FolderActions.renamePlan("old", "cloud-u-linux", names, false, { error("termux") }) { it == "cloudy-u-linux" }
        assertEquals(FolderActions.RenamePlan.Go("cloudy-u-linux___CONFLICT"), plan)
    }

    @Test fun `batched origin read parses one line per clone`() {
        val out = "/g/a\thttps://github.com/o/a.git\n/g/b\t\n"
        assertEquals(mapOf("/g/a" to "https://github.com/o/a.git", "/g/b" to ""), FolderActions.parseOrigins(out))
        assertTrue(FolderActions.originsScript(listOf("/g/a", "/g/b")) { "'$it'" }.contains("remote get-url origin"))
    }

    // ── 3) lists refresh after a run ──

    @Test fun `rows that moved or were deleted leave the unlisted list at once, the rest stay`() {
        val l = listOf(UnlistedRepos.Folder("/s/a", "o1"), UnlistedRepos.Folder("/s/b", "o2"), UnlistedRepos.Folder("/s/c", ""))
        assertEquals(listOf("/s/b"), UnlistedRepos.without(l, setOf("/s/a", "/s/c")).map { it.path })
        assertEquals(l, UnlistedRepos.without(l, emptySet()))
    }

    // ── source: scan reads the folder's own clone, once ──

    @Test fun `a symlinked folder, a git file pointing outside and a clone reached from two roots are not extra rows`() {
        val base = Files.createTempDirectory("scan").toFile()
        val a = File(base, "a").apply { mkdirs() }; val b = File(base, "b").apply { mkdirs() }
        fun clone(parent: File, n: String, url: String) = File(parent, n).apply { File(this, ".git").mkdirs(); File(this, ".git/config").writeText("[remote \"origin\"]\n\turl = $url\n") }
        val real = clone(a, "real", "https://github.com/o/real.git")
        Files.createSymbolicLink(File(b, "real-link").toPath(), real.toPath())
        Files.createSymbolicLink(File(b, "alias").toPath(), a.toPath())
        val sub = File(a, "sub").apply { mkdirs() }; File(sub, ".git").writeText("gitdir: ${File(real, ".git").absolutePath}\n")
        val inside = File(a, "inside").apply { mkdirs() }; File(inside, ".gitdata").mkdirs(); File(inside, ".git").writeText("gitdir: .gitdata\n")
        val found = LocalRepos.scan(listOf(a, b, File(base, "a/../a")), names)
        assertEquals(listOf("inside", "real"), found.map { it.dir.name }.sorted().filter { it != "alias" })
        assertTrue("sub (gitdir elsewhere) is not read as its own", found.none { it.dir.name == "sub" })
        assertTrue("the symlink is not a second row", found.none { it.dir.name == "real-link" })
        base.deleteRecursively()
    }

    // ── the declaration ──

    @Test fun `the new kind is declared in build json and known to the app`() {
        assertTrue(Declarations.FOLDER_REPOINT in Declarations.FOLDER_KINDS)
        assertEquals("repoint_origin", Declarations.FOLDER_REPOINT)
        val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
        val bj = File(root, "build.json").readText()
        assertTrue(bj.contains("\"kind\": \"repoint_origin\"") && bj.contains("\"stale_origin\": { \"auto_fix\": true }") && bj.contains("_doc_stale_origin"))
    }
}
