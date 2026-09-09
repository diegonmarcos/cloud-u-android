package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Select all" must take the list the user is looking at (#126).
 */
class SelectAllKeysTest {
    private val folder = listOf(EmailKey("acct-A", "f1"), EmailKey("acct-A", "f2"), EmailKey("acct-A", "f3"))
    private val hits = listOf(EmailKey("acct-A", "f2"), EmailKey("acct-B", "x9"))

    private fun keys(
        searching: Boolean,
        query: String,
        results: List<EmailKey>?,
        loading: Boolean = false,
        complete: Boolean = true,
    ) = selectAllKeys(searching, query, results, loading, complete, folder)

    @Test
    fun `outside a search, select all is the folder`() {
        assertEquals(folder.toSet(), keys(searching = false, query = "", results = null))
    }

    @Test
    fun `a search bar left open over the navigation list is still the folder`() {
        // The screen shows the paged list whenever the query is blank, whatever the bar's state —
        // and results left over from a query since cleared must not be selected under it.
        assertEquals(folder.toSet(), keys(searching = true, query = "   ", results = hits))
    }

    @Test
    fun `a search that found nothing selects the folder, not nothing`() {
        // The screen is showing "no results", not a result list: there is no displayed list to take.
        assertEquals(folder.toSet(), keys(searching = true, query = "invoice", results = emptyList()))
    }

    @Test
    fun `a search that has never run selects the folder`() {
        assertEquals(folder.toSet(), keys(searching = true, query = "invoice", results = null))
    }

    @Test
    fun `a search showing results selects exactly those results`() {
        assertEquals(hits.toSet(), keys(searching = true, query = "invoice", results = hits))
    }

    @Test
    fun `results still being added are the ones on screen`() {
        // Both legs union in as they answer; the screen keeps showing the rows it has while the
        // second one runs (SearchDisplay.RESULTS wins over the spinner). Select all takes those.
        assertEquals(
            hits.toSet(),
            keys(searching = true, query = "invoice", results = hits, loading = true, complete = false),
        )
    }

    @Test
    fun `no folder key leaks into a selection made over results`() {
        // The defect itself, stated as a set difference: f1 and f3 are in the folder and not on
        // screen, and deleting them is what emptied the reporter's folder.
        val selected = keys(searching = true, query = "invoice", results = hits)
        assertEquals(emptySet<EmailKey>(), selected - hits.toSet())
        assertEquals(2, selected.size)
    }

}
