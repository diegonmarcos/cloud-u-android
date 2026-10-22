package com.diegonmarcos.clouddrive.files

import com.diegonmarcos.cloudlib.sharedstore.StorePaths
import com.diegonmarcos.cloudlib.sharedstore.StorePaths.Mode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The terminal-side scripts of a routed change, run with a real sh on a temp tree (what cld.termux executes on the phone). */
class ExecMutationTest {

    private fun tree(): File = Files.createTempDirectory("execmut").toFile()
    private fun sh(script: String): Pair<Int, String> {
        val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); return p.waitFor() to out.trim()
    }

    @Test fun mkdirAndTouchCreateOnceAndRefuseAnExistingName() {
        val d = tree()
        assertEquals(0, sh(ExecMutation.mkdirScript(d.path, "new dir")).first); assertTrue(File(d, "new dir").isDirectory)
        assertEquals(17, sh(ExecMutation.mkdirScript(d.path, "new dir")).first)
        assertEquals(0, sh(ExecMutation.touchScript(d.path, "it's \$1.txt")).first); assertTrue(File(d, "it's \$1.txt").isFile)
        assertEquals(17, sh(ExecMutation.touchScript(d.path, "it's \$1.txt")).first)
    }

    @Test fun renameMovesAndNeverOverwrites() {
        val d = tree(); File(d, "a").writeText("1"); File(d, "b").writeText("2")
        assertEquals(17, sh(ExecMutation.renameScript(File(d, "a").path, "b")).first); assertEquals("2", File(d, "b").readText())
        assertEquals(0, sh(ExecMutation.renameScript(File(d, "a").path, "c c")).first)
        assertFalse(File(d, "a").exists()); assertEquals("1", File(d, "c c").readText())
    }

    @Test fun copyKeepsTheSourceAndPicksUniqueNames() {
        val d = tree(); val src = File(d, "src").apply { mkdirs() }; val dst = File(d, "dst").apply { mkdirs() }
        File(src, "f's \$1.txt").writeText("x"); File(dst, "f's \$1.txt").writeText("old")
        val s = ExecMutation.transferScript(listOf(File(src, "f's \$1.txt").path), dst.path, move = false)
        assertEquals(0, sh(s).first); assertEquals(0, sh(s).first)
        assertEquals("old", File(dst, "f's \$1.txt").readText()); assertEquals("x", File(dst, "f's \$1.txt (1)").readText()); assertTrue(File(dst, "f's \$1.txt (2)").exists())
        assertTrue(File(src, "f's \$1.txt").exists())
    }

    @Test fun moveRemovesTheSourceAndRefusesAFolderIntoItself() {
        val d = tree(); val dst = File(d, "dst").apply { mkdirs() }; val dir = File(d, "dir").apply { mkdirs() }; File(dir, "k").writeText("k")
        assertEquals(0, sh(ExecMutation.transferScript(listOf(dir.path), dst.path, move = true)).first)
        assertFalse(dir.exists()); assertEquals("k", File(dst, "dir/k").readText())
        val self = sh(ExecMutation.transferScript(listOf(dst.path), File(dst, "dir").path, move = true))
        assertEquals(1, self.first); assertTrue(self.second, self.second.contains("into itself")); assertTrue(File(dst, "dir/k").exists())
    }

    @Test fun deleteRemovesFilesAndFoldersAndReportsNothingMissingAsFailure() {
        val d = tree(); val dir = File(d, "x y").apply { mkdirs() }; File(dir, "k").writeText("k"); val f = File(d, "f").apply { writeText("f") }
        assertEquals(0, sh(ExecMutation.deleteScript(listOf(dir.path, f.path, File(d, "gone").path))).first)
        assertFalse(dir.exists()); assertFalse(f.exists())
    }

    @Test fun namesAndPathsThatEscapeAreRefused() {
        assertTrue(ExecMutation.safeName("a b")); assertFalse(ExecMutation.safeName("a/b")); assertFalse(ExecMutation.safeName(".."))
        assertFalse(ExecMutation.safeName("")); assertFalse(ExecMutation.safeName("a\nb"))
        assertTrue(ExecMutation.safePath("/data/user/0/x")); assertFalse(ExecMutation.safePath("/data/../etc")); assertFalse(ExecMutation.safePath("rel"))
    }

    @Test fun onlyAnUnwritableOwnerDirIsRoutedAndOnlyWhenTheOwnerAnswers() {
        val p = StorePaths("cld.termux", "cloud-shared-store", "CloudDrive", "x")
        val dir = "/data/user/0/cld.termux/files/usr"
        assertTrue(StorePaths.mutateViaOwner(p, dir, 0, writable = false, mode = Mode.EXEC))
        assertFalse(StorePaths.mutateViaOwner(p, dir, 0, writable = true, mode = Mode.DIRECT))
        assertFalse(StorePaths.mutateViaOwner(p, dir, 0, writable = false, mode = Mode.NONE))
        assertFalse(StorePaths.mutateViaOwner(p, "/data/user/0/other/files", 0, writable = false, mode = Mode.EXEC))
    }
}
