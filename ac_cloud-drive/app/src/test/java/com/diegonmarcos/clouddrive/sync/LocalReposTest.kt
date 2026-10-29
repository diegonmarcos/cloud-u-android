package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1149 Local Repos Parentless: detection (folder name vs origin basename), rename, rename-all and the references that follow. */
class LocalReposTest {

    private fun tmp(): File = Files.createTempDirectory("parentless").toFile()

    /** A folder holding a `.git` whose config names [origin] (or no remote when null). */
    private fun repo(root: File, folder: String, origin: String?): File {
        val d = File(root, folder)
        File(d, ".git").mkdirs()
        File(d, ".git/config").writeText(
            "[core]\n\trepositoryformatversion = 0\n" + if (origin == null) "" else "[remote \"origin\"]\n\turl = $origin\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n[branch \"main\"]\n\tremote = origin\n",
        )
        return d
    }

    // ── the repository name an origin URL ends in ──────────────────────────────────────────

    @Test fun repoNameIsTheBasenameWithoutTheGitSuffix() {
        assertEquals("cloud-drive", LocalRepos.repoNameOf("https://github.com/diegonmarcos/cloud-drive.git"))
        assertEquals("cloud-drive", LocalRepos.repoNameOf("https://github.com/diegonmarcos/cloud-drive"))
        assertEquals("cloud-drive", LocalRepos.repoNameOf("https://github.com/diegonmarcos/cloud-drive/"))
        assertEquals("cloud-drive", LocalRepos.repoNameOf("https://github.com/diegonmarcos/cloud-drive.git/"))
        assertEquals("cloud-drive", LocalRepos.repoNameOf("git@github.com:diegonmarcos/cloud-drive.git"))
        assertEquals("cloud-drive", LocalRepos.repoNameOf("ssh://git@host:2222/owner/cloud-drive.git"))
        assertEquals("Cloud-Drive", LocalRepos.repoNameOf("https://x/o/Cloud-Drive.GIT"))
        assertEquals("repo.github.io", LocalRepos.repoNameOf("https://github.com/o/repo.github.io.git"))
        assertEquals("a", LocalRepos.repoNameOf("/data/mirrors/a.git"))
        assertEquals("", LocalRepos.repoNameOf(""))
        assertEquals("", LocalRepos.repoNameOf("   "))
    }

    // ── detection ───────────────────────────────────────────────────────────────────────────

    @Test fun aFolderIsParentlessWhenItsNameIsNotTheOriginsRepoName() {
        assertTrue(LocalRepos.isParentless("my-copy", "cloud-drive"))
        assertFalse(LocalRepos.isParentless("cloud-drive", "cloud-drive"))
        assertFalse("no origin, nothing to name it after", LocalRepos.isParentless("anything", ""))
    }

    @Test fun theGitSuffixIsNotPartOfTheName() {
        // A folder called cloud-drive.git against origin ...cloud-drive.git is parentless (the suffix is not the repo name), the repo name never carries it.
        assertEquals("cloud-drive", LocalRepos.repoNameOf("https://h/o/cloud-drive.git"))
        assertTrue(LocalRepos.isParentless("cloud-drive.git", LocalRepos.repoNameOf("https://h/o/cloud-drive.git")))
        assertFalse(LocalRepos.isParentless("cloud-drive", LocalRepos.repoNameOf("https://h/o/cloud-drive.git")))
    }

    @Test fun aCaseOnlyDifferenceIsNotParentless() {
        // GitHub names are case-insensitive and shared storage is a case-insensitive volume: no rename is offered.
        assertFalse(LocalRepos.isParentless("Cloud-Drive", "cloud-drive"))
        assertFalse(LocalRepos.isParentless("cloud-drive", "CLOUD-DRIVE"))
        assertTrue(LocalRepos.isParentless("cloud-drive2", "cloud-drive"))
    }

    @Test fun scanFindsOnlyFoldersWithAGitAndReadsTheirOrigin() {
        val root = tmp()
        repo(root, "cloud-drive", "https://github.com/o/cloud-drive.git")
        repo(root, "my-copy", "git@github.com:o/cloud-notes.git")
        repo(root, "no-remote", null)
        File(root, "plain-folder").mkdirs()
        File(root, "a-file.txt").writeText("x")
        val found = LocalRepos.scan(listOf(root))
        assertEquals(listOf("cloud-drive", "my-copy", "no-remote"), found.map { it.dir.name })
        assertEquals(listOf("cloud-drive", "cloud-notes", ""), found.map { it.repoName })
        assertEquals(listOf("my-copy"), LocalRepos.parentless(found).map { it.dir.name })
        assertEquals("cloud-notes", LocalRepos.parentless(found).single().target.name)
    }

    @Test fun scanFollowsAGitFileAndIgnoresDuplicateRoots() {
        val root = tmp()
        val real = File(root, "modules/sub").apply { mkdirs() }
        File(real, "config").writeText("[remote \"origin\"]\n\turl = https://h/o/lib.git\n")
        val wt = File(root, "checkout").apply { mkdirs() }
        File(wt, ".git").writeText("gitdir: ${real.absolutePath}\n")
        val found = LocalRepos.scan(listOf(root, root))
        assertEquals(listOf("checkout"), found.map { it.dir.name })
        assertEquals("lib", found.single().repoName)
    }

    @Test fun onlyTheOriginRemoteCounts() {
        val cfg = "[remote \"upstream\"]\n\turl = https://h/o/other.git\n[remote \"origin\"]\n\turl = https://h/o/mine.git\n"
        assertEquals("https://h/o/mine.git", LocalRepos.originFromConfig(cfg))
        assertEquals("", LocalRepos.originFromConfig("[remote \"upstream\"]\n\turl = https://h/o/other.git\n"))
    }

    // ── rename ──────────────────────────────────────────────────────────────────────────────

    @Test fun renameMovesTheFolderAndKeepsItsContent() {
        val root = tmp()
        val d = repo(root, "my-copy", "https://h/o/cloud-notes.git")
        File(d, "a.txt").writeText("kept")
        val p = LocalRepos.parentless(LocalRepos.scan(listOf(root))).single()
        val out = LocalRepos.rename(p)
        assertTrue(out is LocalRepos.Outcome.Renamed)
        assertFalse(d.exists())
        assertEquals("kept", File(root, "cloud-notes/a.txt").readText())
        assertTrue("it is no longer parentless", LocalRepos.parentless(LocalRepos.scan(listOf(root))).isEmpty())
    }

    @Test fun renameToATakenNameBecomesConflictAndTouchesTheOtherFolderNot() {
        val root = tmp()
        val d = repo(root, "my-copy", "https://h/o/cloud-notes.git")
        val clash = File(root, "cloud-notes").apply { mkdirs() }
        File(clash, "mine.txt").writeText("do not overwrite")
        val out = LocalRepos.rename(LocalRepos.parentless(LocalRepos.scan(listOf(root))).single())
        assertTrue(out is LocalRepos.Outcome.Renamed && out.conflict)
        assertEquals("cloud-notes___CONFLICT", (out as LocalRepos.Outcome.Renamed).to.name)
        assertFalse(d.exists())
        assertTrue(File(root, "cloud-notes___CONFLICT/.git").isDirectory)
        assertEquals("do not overwrite", File(clash, "mine.txt").readText())
        assertEquals(listOf("mine.txt"), clash.list()!!.toList())
    }

    @Test fun renameCountsConflictsUpAndAPlainFileCountsAsTaken() {
        val root = tmp()
        File(root, "cloud-notes").writeText("file")
        File(root, "cloud-notes___CONFLICT").mkdirs()
        repo(root, "my-copy", "https://h/o/cloud-notes.git")
        val out = LocalRepos.rename(LocalRepos.parentless(LocalRepos.scan(listOf(root))).single { it.dir.name == "my-copy" })
        assertEquals("cloud-notes___CONFLICT2", (out as LocalRepos.Outcome.Renamed).to.name)
        assertEquals("file", File(root, "cloud-notes").readText())
    }

    @Test fun renameRefusesAnUnusableNameAndAMissingFolder() {
        val root = tmp()
        val d = repo(root, "x", "https://h/o/a.git")
        assertTrue(LocalRepos.rename(LocalRepos.Parentless(d, "u", "..")) is LocalRepos.Outcome.Invalid)
        assertTrue(LocalRepos.rename(LocalRepos.Parentless(File(root, "gone"), "u", "name")) is LocalRepos.Outcome.Missing)
        assertTrue(d.isDirectory)
    }

    @Test fun renameAllRenamesEveryRowAndSendsAClashToConflict() {
        val root = tmp()
        repo(root, "one", "https://h/o/alpha.git")
        repo(root, "two", "https://h/o/beta.git")
        repo(root, "three", "https://h/o/gamma.git")
        File(root, "beta").mkdirs()                      // beta's target is taken
        val rows = LocalRepos.parentless(LocalRepos.scan(listOf(root)))
        assertEquals(listOf("one", "three", "two"), rows.map { it.dir.name })
        val out = LocalRepos.renameAll(rows)
        assertEquals(listOf(false, false, true), out.map { (it as LocalRepos.Outcome.Renamed).conflict })
        assertTrue(File(root, "alpha/.git").isDirectory)
        assertTrue(File(root, "gamma/.git").isDirectory)
        assertTrue("the clashing one went to ___CONFLICT", File(root, "beta___CONFLICT/.git").isDirectory)
        assertFalse(File(root, "beta/.git").exists())
    }

    @Test fun renameAllCannotLetTwoRowsWinOneName() {
        val root = tmp()
        repo(root, "one", "https://h/o/same.git")
        repo(root, "two", "https://h/o/same.git")
        val out = LocalRepos.renameAll(LocalRepos.parentless(LocalRepos.scan(listOf(root))))
        assertEquals(listOf("same", "same___CONFLICT"), out.map { (it as LocalRepos.Outcome.Renamed).to.name })
        assertTrue(File(root, "same/.git").isDirectory)
        assertTrue(File(root, "same___CONFLICT/.git").isDirectory)
    }

    // ── the references that follow a rename ─────────────────────────────────────────────────

    @Test fun repointPathMovesTheFolderAndWhatIsInsideAndNothingElse() {
        assertEquals("/s/git/new", LocalRepos.repointPath("/s/git/old", "/s/git/old", "/s/git/new"))
        assertEquals("/s/git/new/src/a.kt", LocalRepos.repointPath("/s/git/old/src/a.kt", "/s/git/old", "/s/git/new"))
        assertEquals("/s/git/new", LocalRepos.repointPath("/s/git/old/", "/s/git/old", "/s/git/new"))
        assertNull("a sibling that shares the prefix is not inside", LocalRepos.repointPath("/s/git/old-2/x", "/s/git/old", "/s/git/new"))
        assertNull(LocalRepos.repointPath("/elsewhere", "/s/git/old", "/s/git/new"))
    }

    @Test fun theRegistryFollowsTheRenameWithItsIdAndItsCredential() {
        val root = tmp()
        val registry = RepoRegistry(File(root, "repos.json"))
        val oldPath = File(root, "my-copy").absolutePath
        val newPath = File(root, "cloud-notes").absolutePath
        val oldId = RepoRegistry.idFor(oldPath)
        registry.save(listOf(
            ManagedRepo(id = oldId, name = "my-copy", path = oldPath, remoteUrl = "https://h/o/cloud-notes.git"),
            ManagedRepo(id = RepoRegistry.idFor("/other"), name = "other", path = "/other"),
        ))
        val rekeyed = mutableListOf<Pair<String, String>>()
        val moved = LocalRepoRefs.repointRegistry(registry, { a, b -> rekeyed += a to b }, oldPath, newPath)
        assertEquals(1, moved)
        val after = registry.load()
        assertEquals(newPath, after.first().path)
        assertEquals(RepoRegistry.idFor(newPath), after.first().id)
        assertEquals("cloud-notes", after.first().name)
        assertEquals("/other", after.last().path)
        assertEquals(listOf(oldId to RepoRegistry.idFor(newPath)), rekeyed)
        assertEquals("a second pass moves nothing", 0, LocalRepoRefs.repointRegistry(registry, { _, _ -> }, oldPath, newPath))
    }

    @Test fun aNameTheOwnerChoseIsKeptWhenTheFolderIsRenamed() {
        val root = tmp()
        val registry = RepoRegistry(File(root, "repos.json"))
        val oldPath = File(root, "my-copy").absolutePath
        registry.save(listOf(ManagedRepo(id = RepoRegistry.idFor(oldPath), name = "Notes", path = oldPath)))
        LocalRepoRefs.repointRegistry(registry, { _, _ -> }, oldPath, File(root, "cloud-notes").absolutePath)
        assertEquals("Notes", registry.load().single().name)
    }
}
