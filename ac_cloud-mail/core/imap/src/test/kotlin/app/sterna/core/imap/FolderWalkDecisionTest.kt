package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three decisions a paginated folder walk takes, EXECUTED on their own: where the window
 */
class FolderWalkDecisionTest {

    // -- where the window starts -------------------------------------------------------------------

    @Test fun `the window is the newest limit messages of the folder`() {
        assertEquals(951, folderWindowLowest(exists = 1000, limit = 50))
        assertEquals(1, folderWindowLowest(exists = 1000, limit = 1000))
        assertEquals(1000, folderWindowLowest(exists = 1000, limit = 1))
    }

    @Test fun `a window larger than the folder is the whole folder, and does not wrap`() {
        // The arithmetic the cartography checked by hand: `exists - Int.MAX_VALUE + 1` is a large
        // negative number, not an overflow, and the floor turns it into 1.
        assertEquals(1, folderWindowLowest(exists = 40, limit = 1000))
        assertEquals(1, folderWindowLowest(exists = 1000, limit = Int.MAX_VALUE))
        assertEquals(1, folderWindowLowest(exists = 0, limit = 50))
    }

    // -- how big a page may be ---------------------------------------------------------------------

    @Test fun `the page size is a few hundred, which is the whole point of paginating`() {
        // Not a tautology, a BOUND. The shipped page size is the one number that can un-do this
        // walk without changing a line of its logic: raise it past a window and every window is
        assertTrue("IMAP_FOLDER_PAGE = $IMAP_FOLDER_PAGE is no longer a page", IMAP_FOLDER_PAGE in 50..500)
        assertTrue(
            "one page must be smaller than the largest window the app offers (1 000), or the " +
                "largest window is a single request again",
            IMAP_FOLDER_PAGE < 1000,
        )
    }

    // -- which range comes next --------------------------------------------------------------------

    /** The whole walk as a list, so the sequence can be read at a glance. */
    private fun pages(exists: Int, limit: Int, pageSize: Int): List<IntRange> {
        val lowest = folderWindowLowest(exists, limit)
        val out = mutableListOf<IntRange>()
        var previous: IntRange? = null
        while (true) {
            val next = nextFolderPage(lowest, exists, previous, pageSize) ?: break
            out += next
            previous = next
            check(out.size < 100) { "the walk did not terminate: $out" }
        }
        return out
    }

    @Test fun `a thousand-message window is walked newest first, in pages`() {
        assertEquals(
            listOf(801..1000, 601..800, 401..600, 201..400, 1..200),
            pages(exists = 1000, limit = 1000, pageSize = 200),
        )
    }

    @Test fun `the last page stops at the window, not at the bottom of the folder`() {
        // 300 of a 1 000-message folder: the walk must not run on to sequence 1.
        assertEquals(listOf(801..1000, 701..800), pages(exists = 1000, limit = 300, pageSize = 200))
    }

    @Test fun `a folder smaller than one page is a single request`() {
        assertEquals(listOf(1..40), pages(exists = 40, limit = 1000, pageSize = 200))
        assertEquals(listOf(1..50), pages(exists = 50, limit = 50, pageSize = 200))
    }

    @Test fun `an empty folder is walked with no request at all`() {
        assertEquals(emptyList<IntRange>(), pages(exists = 0, limit = 50, pageSize = 200))
        assertNull(nextFolderPage(lowest = 1, highest = 0, previous = null, pageSize = 200))
    }

    @Test fun `a page size of zero asks for nothing rather than looping`() {
        assertNull(nextFolderPage(lowest = 1, highest = 100, previous = null, pageSize = 0))
        assertNull(nextFolderPage(lowest = 1, highest = 100, previous = null, pageSize = -5))
    }

    @Test fun `the pages of a walk touch, with no gap and no overlap`() {
        // A gap is a message never read; an overlap is a wasted request. Both are silent.
        val walk = pages(exists = 953, limit = 953, pageSize = 100)
        walk.zipWithNext().forEach { (upper, lower) ->
            assertEquals("a gap or an overlap between $upper and $lower", upper.first - 1, lower.last)
        }
        assertEquals(953, walk.sumOf { it.count() })
        assertEquals(1, walk.last().first)
    }

    // -- what a renumbering really costs ----------------------------------------------------------

    @Test fun `a renumbering makes the walk RE-READ, never skip — except at the floor`() {
        // The arithmetic behind the guard, stated as a fact about the ranges rather than as
        // prose. `k` messages are expunged while the walk is between its first and second page:
        // every message above them slides DOWN by k positions.
        val exists = 1000
        val limit = 500
        val k = 3
        val lowest = folderWindowLowest(exists, limit) // 501
        val first = nextFolderPage(lowest, exists, null, 200)!! // 801..1000
        val second = nextFolderPage(lowest, exists, first, 200)!! // 601..800

        // A message that sat at position p before the expunges sits at p - k after them. The
        // second range therefore covers what USED to be at 601+k .. 800+k — which overlaps the
        // first range's floor (801) instead of leaving a hole under it.
        assertTrue(
            "the shift opened a gap between the pages: nothing covers old position ${second.last + 1}",
            second.last + k >= first.first - 1,
        )

        // The floor is the whole story: `lowest` was frozen from the SELECT, so the messages that
        // were at 501..503 now sit at 498..500 and NO range of this walk reaches them.
        val bottom = generateSequence(first) { nextFolderPage(lowest, exists, it, 200) }.last()
        assertEquals(lowest, bottom.first)
        assertTrue(
            "old positions $lowest..${lowest + k - 1} are inside the window the user asked for, " +
                "and after $k expunges they sit below $lowest where this walk never looks",
            lowest - k < bottom.first,
        )
    }

    @Test fun `the All window cannot lose its floor, whatever the folder does`() {
        // The corollary that matters for the next volet: with `limit >= exists` the floor is 1,
        // and nothing can slide below 1. The window this pagination exists for is the one where a
        // renumbering costs nothing at all.
        assertEquals(1, folderWindowLowest(exists = 1000, limit = 1000))
        assertEquals(1, folderWindowLowest(exists = 1000, limit = Int.MAX_VALUE))
        assertEquals(1, folderWindowLowest(exists = 3, limit = 1000))
        // ...and the count windows, which the guard is really for, do have a floor above 1.
        assertTrue(folderWindowLowest(exists = 1000, limit = 50) > 1)
        assertTrue(folderWindowLowest(exists = 1000, limit = 500) > 1)
    }

    // -- did the folder move? --------------------------------------------------------------------

    private fun line(vararg tokens: Any?): List<Any?> = tokens.toList()

    @Test fun `an EXPUNGE means the folder moved`() {
        // The one that costs mail if it is missed: everything above sequence 3 has just been
        // renumbered, so the next page covers a slice one message off from the one meant.
        assertTrue(folderMoved(listOf(line("*", "3", "EXPUNGE")), startedWith = 1000))
    }

    @Test fun `a QRESYNC VANISHED means the folder moved`() {
        assertTrue(folderMoved(listOf(line("*", "VANISHED", listOf("EARLIER"), "41:116")), startedWith = 1000))
    }

    @Test fun `an EXISTS that differs from the count the walk started on means the folder moved`() {
        assertTrue(folderMoved(listOf(line("*", "999", "EXISTS")), startedWith = 1000))
        assertTrue(folderMoved(listOf(line("*", "1001", "EXISTS")), startedWith = 1000))
    }

    @Test fun `an EXISTS that will not parse counts as movement`() {
        // The conservative direction: an answer we cannot read is not an answer that the folder
        // held still.
        assertTrue(folderMoved(listOf(line("*", "many", "EXISTS")), startedWith = 1000))
        assertTrue(folderMoved(listOf(line("*", null, "EXISTS")), startedWith = 1000))
    }

    @Test fun `the same EXISTS is not movement, and neither is an ordinary FETCH`() {
        // The witness for every assertion above: without this, "moved" could simply be always
        // true and the whole table would still pass — and a walk that never reconciles never
        // trims the cache to the window.
        assertFalse(folderMoved(listOf(line("*", "1000", "EXISTS")), startedWith = 1000))
        assertFalse(
            folderMoved(
                listOf(
                    line("*", "1000", "EXISTS"),
                    line("*", "17", "FETCH", listOf("UID", "17")),
                    line("*", "OK", "[UIDNEXT 1001]"),
                ),
                startedWith = 1000,
            ),
        )
        assertFalse(folderMoved(emptyList(), startedWith = 1000))
    }

    @Test fun `one moved line among many is enough`() {
        assertTrue(
            folderMoved(
                listOf(
                    line("*", "17", "FETCH", listOf("UID", "17")),
                    line("*", "3", "EXPUNGE"),
                    line("*", "18", "FETCH", listOf("UID", "18")),
                ),
                startedWith = 1000,
            ),
        )
    }
}
