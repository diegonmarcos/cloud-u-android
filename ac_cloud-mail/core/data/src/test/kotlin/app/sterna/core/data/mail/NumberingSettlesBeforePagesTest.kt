package app.sterna.core.data.mail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codeberg #99, re-opened by writing pages as they land, and closed again: a folder whose
 */
class NumberingSettlesBeforePagesTest {

    private val id = "imap:acc-1:INBOX:7"

    /**
     * What the user has in front of them: the rows the list shows, and the body cache an open would
     */
    private class Screen {
        /** A body cached under the OLD numbering, for the id the new page is about to show. */
        val bodies = linkedMapOf("imap:acc-1:INBOX:7" to "the message that used to be UID 7")
        val rows = mutableListOf<String>()
        var openedWhenFirstPageLanded: String? = null
        var pagesWhenNumberingSettled: Int = -1
    }

    /** The shipped order, driven with the two collaborators recorded. */
    private fun run(screen: Screen, pages: List<List<String>>): List<String> = runBlocking {
        withNumberingSettled(
            settle = {
                // What `reconcileNumbering` does on a renumbered folder: drop the caches keyed by
                // the old UIDs (`UidValidityStore.invalidate`).
                screen.pagesWhenNumberingSettled = screen.rows.size
                screen.bodies.clear()
            },
            walk = {
                pages.forEach { page ->
                    screen.rows += page
                    if (screen.rows.size == page.size) {
                        // The user opens the first row the instant it appears.
                        screen.openedWhenFirstPageLanded = screen.bodies[id]
                    }
                }
                screen.rows.toList()
            },
        )
    }

    @Test fun `a body cached under the old numbering is gone before the first page is visible`() {
        // The test of the reserve. With the settle after the walk, the row of the NEW message 7
        // is on screen for the length of the walk while the body of the OLD message 7 is still in
        // the cache — and opening it renders the wrong message under the right header.
        val screen = Screen()

        run(screen, pages = listOf(listOf(id, "imap:acc-1:INBOX:8"), listOf("imap:acc-1:INBOX:5")))

        assertNull(
            "a body cached under the previous UIDVALIDITY was still readable while the new page " +
                "was on screen — Codeberg #99, under the right header",
            screen.openedWhenFirstPageLanded,
        )
        assertEquals("the numbering settled after pages had already been written", 0, screen.pagesWhenNumberingSettled)
    }

    @Test fun `the walk still runs, and its answer still comes back`() {
        // The witness: settling first must not become "settle instead of walking". A refusal here
        // would fail the whole inbox refresh over one renumbered folder, which is the guard
        // `loadFolder` exists to keep — it compares and records, it never rejects.
        val screen = Screen()

        val walked = run(screen, pages = listOf(listOf(id), listOf("imap:acc-1:INBOX:5")))

        assertEquals(listOf(id, "imap:acc-1:INBOX:5"), walked)
        assertEquals(listOf(id, "imap:acc-1:INBOX:5"), screen.rows)
        assertTrue("the body cache was not dropped at all", screen.bodies.isEmpty())
    }
}
