package com.diegonmarcos.clouddrive

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #606 the one-time migration of stray root clones, exercised against a real temp store so the
 * JVM runner proves the exact logic the phone runs (StoreMigration is pure java.io.File).
 *
 * The two load-bearing behaviours — a stray root clone IS moved into the git folder, and an
 * already-migrated repository is LEFT alone — are asserted on the filesystem, so a mutation to
 * either arm (moving the wrong thing, or clobbering an existing clone) reddens this suite.
 */
class StoreMigrationTest {

    private fun tmp(): File = Files.createTempDirectory("store-migration").toFile()
    private fun clone(dir: File) { dir.mkdirs(); File(dir, ".git").apply { mkdirs() }; File(dir, "README.md").writeText(dir.name) }
    private val declared = setOf("cloud", "cloud-infra", "cloud-u-android")

    /** A stray clone at the store root, a declared repo, is moved under git_subdir; content survives. */
    @Test fun straysAtRootAreMovedIntoTheGitFolder() {
        val root = tmp()
        clone(File(root, "cloud"))
        val moves = StoreMigration.migrate(root, "git", declared)
        assertEquals(1, moves.size)
        assertTrue("the stray must be reported moved", moves.first().moved)
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
        // A second pass is still a no-op — the migration is idempotent.
        assertTrue(StoreMigration.migrate(root, "git", declared).isEmpty())
    }

    /** A stray whose destination is already a clone is LEFT in place, reported not-moved (no clobber). */
    @Test fun strayIsLeftWhenDestinationExists() {
        val root = tmp()
        clone(File(root, "cloud")); File(root, "cloud/README.md").writeText("STRAY")
        clone(File(root, "git/cloud")); File(root, "git/cloud/README.md").writeText("KEEP")
        val moves = StoreMigration.migrate(root, "git", declared)
        assertEquals(1, moves.size)
        assertFalse("a taken destination must not be overwritten", moves.first().moved)
        assertTrue("the stray must be left where it is", File(root, "cloud/.git").exists())
        assertEquals("KEEP", File(root, "git/cloud/README.md").readText())
    }

    /** Only DECLARED names migrate, and only real clones: the user's own folders are never moved. */
    @Test fun undeclaredFoldersAndPlainDirsAreIgnored() {
        val root = tmp()
        clone(File(root, "my-photos"))            // a clone, but not a declared repo name
        File(root, "cloud").apply { mkdirs(); File(this, "notes.txt").writeText("x") } // declared name, but not a clone
        val moves = StoreMigration.migrate(root, "git", declared)
        assertTrue("neither is a stray to migrate", moves.isEmpty())
        assertTrue(File(root, "my-photos/.git").exists())
        assertFalse(File(root, "git/cloud").exists())
    }
}
