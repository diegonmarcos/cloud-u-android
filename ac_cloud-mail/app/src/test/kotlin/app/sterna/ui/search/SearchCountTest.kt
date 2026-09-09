package app.sterna.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The header above the search results must never claim a total it can't back (#102).
 */
class SearchCountTest {

    @Test fun `a complete answer with nothing in flight states a firm total`() {
        assertEquals(SearchCount.EXACT, searchCount(complete = true, loading = false))
    }

    @Test fun `local hits shown while the server leg is still in flight say at least`() {
        // The defect: complete is initialised to true on every keystroke (nothing has reported
        // stopping short yet), so completeness ALONE would print a firm total over a partial list.
        assertEquals(SearchCount.AT_LEAST, searchCount(complete = true, loading = true))
    }

    @Test fun `a server cap reached with nothing in flight still says at least`() {
        assertEquals(SearchCount.AT_LEAST, searchCount(complete = false, loading = false))
    }

    @Test fun `an account whose leg failed still says at least once the search settles`() {
        // Same shape as the cap: the dropped account makes the result incomplete. Pinned separately
        // because it is the case the two-pass change must ADD to, never replace.
        val afterFailedAccountLeg = searchCount(complete = false, loading = false)
        assertEquals(SearchCount.AT_LEAST, afterFailedAccountLeg)
    }

    @Test fun `an incomplete answer still loading says at least`() {
        assertEquals(SearchCount.AT_LEAST, searchCount(complete = false, loading = true))
    }

    @Test fun `only the complete and settled case is ever firm`() {
        val firm = listOf(true, false).flatMap { c -> listOf(true, false).map { l -> Triple(c, l, searchCount(c, l)) } }
            .filter { it.third == SearchCount.EXACT }
        assertEquals(listOf(Triple(true, false, SearchCount.EXACT)), firm)
    }

    // ---- an answer is complete only if every leg ran to the end ----

    @Test fun `both legs ran to the end, so the answer is complete`() {
        assertEquals(true, searchComplete(local = true, server = true))
    }

    @Test fun `a local index that fell over is not forgiven by a good server answer`() {
        // The server leg records its verdict LAST, so taking it alone silently wrote off a locked
        // or damaged FTS table — and every hit only that index finds — under a firm total.
        assertEquals(false, searchComplete(local = false, server = true))
    }

    @Test fun `a server leg that stopped short still makes the answer incomplete`() {
        // The witness: the guard that already existed must survive the one just added.
        assertEquals(false, searchComplete(local = true, server = false))
    }

    @Test fun `both legs failing is incomplete, not complete twice over`() {
        assertEquals(false, searchComplete(local = false, server = false))
    }

    // ---- which of the four things the surface shows ----

    @Test fun `rows keep the screen even when a leg is still in flight`() {
        // The spinner must not take back a list the user is already reading; the count carries the
        // "still working" news instead.
        assertEquals(SearchDisplay.RESULTS, searchDisplay(resultCount = 3, loading = true, complete = true))
    }

    @Test fun `no rows yet and still working shows the spinner, not no-results`() {
        assertEquals(SearchDisplay.SPINNER, searchDisplay(resultCount = 0, loading = true, complete = true))
    }

    @Test fun `no rows from a search that stopped short must not claim there are none`() {
        // Offline, a word absent from the local index: local returns nothing, the server leg fails.
        // "No results, try other words" would be an assertion the app never established, and would
        // send the user rewording a query that never actually ran.
        assertEquals(SearchDisplay.INCOMPLETE_EMPTY, searchDisplay(resultCount = 0, loading = false, complete = false))
    }

    @Test fun `no rows from a search that ran to the end is a genuine no-results`() {
        // The witness for the case above: a complete search that found nothing may still say so.
        assertEquals(SearchDisplay.EMPTY, searchDisplay(resultCount = 0, loading = false, complete = true))
    }

    @Test fun `rows with nothing in flight shows the rows`() {
        assertEquals(SearchDisplay.RESULTS, searchDisplay(resultCount = 1, loading = false, complete = true))
    }

    @Test fun `rows are shown whether or not the search stopped short`() {
        // Incompleteness is the count's business, never a reason to withhold the list.
        assertEquals(SearchDisplay.RESULTS, searchDisplay(resultCount = 1, loading = false, complete = false))
    }

    @Test fun `only a search that both ran to the end and found nothing may say no-results`() {
        val saysNothingMatches = listOf(true, false).flatMap { l ->
            listOf(true, false).map { c -> Triple(l, c, searchDisplay(resultCount = 0, loading = l, complete = c)) }
        }.filter { it.third == SearchDisplay.EMPTY }
        assertEquals(listOf(Triple(false, true, SearchDisplay.EMPTY)), saysNothingMatches)
    }
}
