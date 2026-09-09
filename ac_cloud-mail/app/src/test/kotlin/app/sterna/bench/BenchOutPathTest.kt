package app.sterna.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXECUTES [isBenchOutPathAllowed] against pinned arguments — it does not read the rule, it runs it.
 */
class BenchOutPathTest {

    @Test
    fun theBenchOutFileIsAccepted() {
        assertEquals(true, isBenchOutPathAllowed("/data/local/tmp/bench.out"))
        assertEquals(true, isBenchOutPathAllowed("/data/local/tmp/bench.json"))
        // A subdirectory of the allowed directory is still the allowed directory.
        assertEquals(true, isBenchOutPathAllowed("/data/local/tmp/sterna/run-42.out"))
    }

    @Test
    fun nothingToWriteIsRefused() {
        assertEquals(false, isBenchOutPathAllowed(null))
        assertEquals(false, isBenchOutPathAllowed(""))
        assertEquals(false, isBenchOutPathAllowed("   "))
    }

    @Test
    fun aFileOutsideTheBenchDirectoryIsRefused() {
        // The one that motivated the guard: the test app's own private data, writable by its uid,
        // and writeText truncates whatever is there.
        assertEquals(false, isBenchOutPathAllowed("/data/data/app.sterna.test/files/x"))
        assertEquals(false, isBenchOutPathAllowed("/sdcard/x.json"))
        assertEquals(false, isBenchOutPathAllowed("/data/local/tmpfoo/x.out"))
        // Relative: resolved against the process's cwd, which is not /data/local/tmp.
        assertEquals(false, isBenchOutPathAllowed("data/local/tmp/bench.out"))
    }

    @Test
    fun theDirectoryItselfIsNotAFileToWrite() {
        assertEquals(false, isBenchOutPathAllowed("/data/local/tmp"))
        assertEquals(false, isBenchOutPathAllowed("/data/local/tmp/"))
        assertEquals(false, isBenchOutPathAllowed("/data/local/tmp/sterna/"))
    }

    @Test
    fun aTraversalOutOfTheBenchDirectoryIsRefused() {
        val traversal = "/data/local/tmp/../../data/data/app.sterna.test/x"
        // The trap, stated as an assertion so it cannot be argued away: this path DOES start with
        // the allowed prefix, so a startsWith-only rule hands the app's own data to any caller.
        assertTrue("this case only bites if it really carries the prefix", traversal.startsWith("/data/local/tmp/"))
        assertEquals(false, isBenchOutPathAllowed(traversal))
        assertEquals(false, isBenchOutPathAllowed("/data/local/tmp/../tmp/bench.out"))
        assertEquals(false, isBenchOutPathAllowed("/data/local/tmp/./bench.out"))
        // Nothing is normalised, so a doubled separator is refused rather than resolved.
        assertEquals(false, isBenchOutPathAllowed("/data/local/tmp//bench.out"))
    }
}
