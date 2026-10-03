package com.diegonmarcos.clouddrive

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #606/#629 the one-time migration of stray root clones, exercised against a real temp store so
 * the JVM runner proves the exact logic the phone runs (StoreMigration is pure java.io.File).
 *
 * #629 asserts the behaviour the owner's device actually needed: BOTH copies exist and BOTH are
 * partially complete, in either direction. The COMPLETE copy must win wherever it starts, the
 * incomplete one must be deleted only after the survivor verifies, and a pair of husks must lose
 * nothing. Each of those is one test, so a mutation that deletes the complete copy or that leaves
 * a stray forever reddens this suite.
 */
class StoreMigrationTest {

    private fun tmp(): File = Files.createTempDirectory("store-migration").toFile()

    /** A COMPLETE clone: a `.git` with a resolvable HEAD, and a populated worktree. */
    private fun clone(dir: File, content: String = dir.name) {
        dir.mkdirs()
        File(dir, ".git/refs/heads").mkdirs()
        File(dir, ".git/HEAD").writeText("ref: refs/heads/main\n")
        File(dir, ".git/refs/heads/main").writeText("0123456789abcdef0123456789abcdef01234567\n")
        File(dir, "README.md").writeText(content)
    }

    /** A HUSK: `.git` exists but HEAD resolves to nothing and there is no worktree. */
    private fun husk(dir: File) {
        dir.mkdirs()
        File(dir, ".git").mkdirs()
        File(dir, ".git/HEAD").writeText("ref: refs/heads/main\n")
    }

    private val declared = setOf("cloud", "cloud-infra", "cloud-u-android", "cloud-u-linux")

    /** The completeness ladder is what every decision rests on, so it is asserted directly. */
    @Test fun completenessScoresTheThreeThingsThatMakeACopyUsable() {
        val root = tmp()
        val plain = File(root, "plain").apply { mkdirs() }
        assertEquals(0, StoreMigration.completeness(plain))
        val h = File(root, "husk").also { husk(it) }
        assertEquals(1, StoreMigration.completeness(h))
        File(h, ".git/refs/heads").mkdirs()
        File(h, ".git/refs/heads/main").writeText("0123456789abcdef0123456789abcdef01234567\n")
        assertEquals(2, StoreMigration.completeness(h))
        val good = File(root, "good").also { clone(it) }
        assertEquals(StoreMigration.COMPLETE, StoreMigration.completeness(good))
        assertTrue(StoreMigration.isComplete(good))
        assertFalse(StoreMigration.isComplete(h))
    }

    /** A stray clone at the store root, a declared repo, is moved under git_subdir; content survives. */
    @Test fun straysAtRootAreMovedIntoTheGitFolder() {
        val root = tmp()
        clone(File(root, "cloud"))
        val moves = StoreMigration.migrate(root, "git", declared)
        assertEquals(1, moves.size)
        assertTrue("the stray must be reported moved: " + moves.first().decision, moves.first().moved)
        assertFalse("the old root clone must be gone", File(root, "cloud").exists())
        val dest = File(root, "git/cloud")
        assertTrue("the clone must now live under git/", File(dest, ".git").exists())
        assertEquals("cloud", File(dest, "README.md").readText())
    }

    /** A repository already under git_subdir is not a stray and is never touched (idempotent). */
    @Test fun alreadyMigratedRepoIsLeftAlone() {
        val root = tmp()
        clone(File(root, "git/cloud"))
        val moves = StoreMigration.migrate(root, "git", declared)
        assertTrue("nothing at the root ⇒ nothing to move", moves.isEmpty())
        assertTrue(File(root, "git/cloud/.git").exists())
        assertTrue(StoreMigration.migrate(root, "git", declared).isEmpty())
    }

    /**
     * THE cloud-u-linux CASE, measured on the device: the ROOT copy is complete and the git/ copy is
     * an empty husk. The complete copy must win — a migration that "never overwrites an existing
     * clone" strands it at the root forever.
     */
    @Test fun theCompleteRootCopyReplacesAnIncompleteGitCopy() {
        val root = tmp()
        clone(File(root, "cloud-u-linux"), content = "KEEP")
        husk(File(root, "git/cloud-u-linux"))
        val moves = StoreMigration.migrate(root, "git", declared)
        assertEquals(1, moves.size)
        assertTrue("the complete root copy must be moved: " + moves.first().decision, moves.first().moved)
        assertFalse("the stray must not survive its own migration", File(root, "cloud-u-linux").exists())
        assertEquals("KEEP", File(root, "git/cloud-u-linux/README.md").readText())
        assertTrue("the survivor must verify complete", StoreMigration.isComplete(File(root, "git/cloud-u-linux")))
        assertFalse("the parked husk must be gone", File(root, "git/cloud-u-linux.incomplete").exists())
    }

    /**
     * THE cloud / cloud-infra CASE, the reverse: git/ is complete and the root copy is the husk. The
     * complete copy is kept untouched and the redundant stray is REMOVED — left in place it is the
     * duplicate the user sees forever.
     */
    @Test fun theRedundantRootHuskIsRemovedWhenGitIsComplete() {
        val root = tmp()
        husk(File(root, "cloud"))
        clone(File(root, "git/cloud"), content = "KEEP")
        val moves = StoreMigration.migrate(root, "git", declared)
        assertEquals(1, moves.size)
        assertFalse(moves.first().moved)
        assertTrue("the redundant stray must be reported removed: " + moves.first().decision, moves.first().removed)
        assertFalse("the redundant root husk must be gone", File(root, "cloud").exists())
        assertEquals("the complete copy must be untouched", "KEEP", File(root, "git/cloud/README.md").readText())
    }

    /** Two husks: nothing is complete, so NOTHING is deleted — a migration never destroys an only copy. */
    @Test fun neitherCopyCompleteMeansNothingIsDeleted() {
        val root = tmp()
        husk(File(root, "cloud"))
        husk(File(root, "git/cloud"))
        val moves = StoreMigration.migrate(root, "git", declared)
        assertEquals(1, moves.size)
        assertFalse(moves.first().moved)
        assertFalse("no copy may be deleted while neither is complete", moves.first().removed)
        assertTrue(File(root, "cloud/.git").exists())
        assertTrue(File(root, "git/cloud/.git").exists())
        assertTrue("the decision must say why", moves.first().decision.contains("NEITHER"))
    }

    /** Two complete copies: git/ is proven good, so the root duplicate goes and the survivor is intact. */
    @Test fun bothCompleteKeepsTheGitCopyAndDropsTheDuplicate() {
        val root = tmp()
        clone(File(root, "cloud"), content = "STRAY")
        clone(File(root, "git/cloud"), content = "KEEP")
        val moves = StoreMigration.migrate(root, "git", declared)
        assertEquals(1, moves.size)
        assertTrue(moves.first().removed)
        assertFalse(File(root, "cloud").exists())
        assertEquals("KEEP", File(root, "git/cloud/README.md").readText())
    }

    /** Only DECLARED names migrate, and a folder that is not a clone is never moved and never deleted. */
    @Test fun undeclaredFoldersAndPlainDirsAreIgnored() {
        val root = tmp()
        clone(File(root, "my-photos"))            // a clone, but not a declared repo name
        File(root, "cloud").apply { mkdirs(); File(this, "notes.txt").writeText("x") } // declared name, not a clone
        val moves = StoreMigration.migrate(root, "git", declared)
        assertTrue("neither is a stray to migrate: " + moves.map { it.decision }, moves.isEmpty())
        assertTrue(File(root, "my-photos/.git").exists())
        assertTrue("the user's own folder must survive", File(root, "cloud/notes.txt").exists())
        assertFalse(File(root, "git/cloud").exists())
    }

    /** A user folder sharing a declared name is never deleted even when git/ holds the real clone. */
    @Test fun aPlainUserFolderIsNeverDeletedForTheCompleteClone() {
        val root = tmp()
        File(root, "cloud").apply { mkdirs(); File(this, "notes.txt").writeText("mine") }
        clone(File(root, "git/cloud"))
        val moves = StoreMigration.migrate(root, "git", declared)
        assertEquals(1, moves.size)
        assertFalse(moves.first().removed)
        assertTrue(File(root, "cloud/notes.txt").exists())
    }

    /** #731 an upstream rename: a complete git/ffront moves to git/front, the old dir is gone, content survives. */
    @Test fun aRenamedRepositoryMovesToItsNewName() {
        val git = File(tmp(), "git")
        clone(File(git, "ffront"), "FRONT")
        val moves = StoreMigration.migrateRenames(git, mapOf("ffront" to "front"))
        assertEquals(1, moves.size)
        assertEquals("front", moves.single().name)
        assertTrue(moves.single().moved)
        assertFalse("the stray old-name dir must not be left beside the new one", File(git, "ffront").exists())
        assertEquals("FRONT", File(git, "front/README.md").readText())
    }

    /** #731 a fresh clone already at the new name wins; the redundant old-name clone is removed. */
    @Test fun aRenamedStrayBesideACompleteNewCloneIsRemoved() {
        val git = File(tmp(), "git")
        clone(File(git, "ffront"), "OLD")
        clone(File(git, "front"), "NEW")
        val moves = StoreMigration.migrateRenames(git, mapOf("ffront" to "front"))
        assertTrue(moves.single().removed)
        assertFalse(File(git, "ffront").exists())
        assertEquals("NEW", File(git, "front/README.md").readText())
    }

    /** #731 the shipped manifest declares the rename, so the device migration is actually armed. */
    @Test fun theManifestDeclaresTheFrontRename() {
        val data = listOf(File("data"), File("../data"), File("ac_cloud-drive/data")).first { File(it, "drive-git-repos.json").isFile }
        val family = Declarations.parseGitFamily(File(data, "drive-git-repos.json").readText())
        assertEquals("front", family.renames["ffront"])
        assertTrue(family.repos.none { it.name == "ffront" })
    }

    /**
     * #821 STORE DIRS == DECLARED REPOS. The device's git/ as the code history leaves it: every
     * declared repository cloned, plus the two names the manifest no longer declares — git/ffront
     * (renamed upstream to front, #731) and git/front-diegonmarcos (a seed entry for a repository
     * GitHub never had, dropped by 81249a55; here both its shapes: an empty leftover, and the site
     * cloned under its farm name). After the manifest's own migration the store holds EXACTLY the
     * declared set and reconciliation reports nothing.
     */
    @Test fun afterTheMigrationTheStoreHoldsExactlyTheDeclaredRepositories() {
        val data = listOf(File("data"), File("../data"), File("ac_cloud-drive/data")).first { File(it, "drive-git-repos.json").isFile }
        val family = Declarations.parseGitFamily(File(data, "drive-git-repos.json").readText())
        val declaredNames = family.repos.map { it.name }.toSet()
        for (leftover in listOf("empty", "clone")) {
            val git = File(tmp(), "git")
            declaredNames.forEach { clone(File(git, it)) }
            File(git, "ffront").let { clone(it) }
            File(git, "front-diegonmarcos").let { if (leftover == "clone") clone(it) else it.mkdirs() }
            assertEquals(listOf("ffront", "front-diegonmarcos"), StoreMigration.undeclared(git, declaredNames))
            StoreMigration.migrateRenames(git, family.renames)
            assertEquals("leftover=$leftover", emptyList<String>(), StoreMigration.undeclared(git, declaredNames))
            assertEquals(declaredNames, git.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet())
        }
    }

    /** #821 an empty old-name folder goes; a NON-empty non-clone of the old name is a user's and stays. */
    @Test fun onlyAnEmptyOldNameFolderIsRemoved() {
        val git = File(tmp(), "git")
        clone(File(git, "front"))
        File(git, "ffront").mkdirs()
        assertTrue(StoreMigration.migrateRenames(git, mapOf("ffront" to "front")).single().removed)
        assertFalse(File(git, "ffront").exists())
        File(git, "ffront").mkdirs(); File(git, "ffront/mine.txt").writeText("x")
        StoreMigration.migrateRenames(git, mapOf("ffront" to "front"))
        assertTrue(File(git, "ffront/mine.txt").exists())
    }
}
