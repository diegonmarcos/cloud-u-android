package app.sterna.ui.search

import app.sterna.core.jmap.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folded criteria panel must still SAY what it is searching for. A fold that hid "from Alex,
 */
class SearchCriteriaTest {

    @Test fun `a query with no criteria summarises to nothing`() {
        assertTrue(searchSummary(SearchQuery()).isEmpty())
    }

    @Test fun `free text alone adds nothing to the summary`() {
        // Its own field stays on screen folded or not; repeating it would be noise.
        assertTrue(searchSummary(SearchQuery(text = "invoice")).isEmpty())
    }

    @Test fun `every advanced criterion shows, in the order the panel lists them`() {
        val summary = searchSummary(
            SearchQuery(
                text = "invoice",
                from = "alex",
                recipient = "jordan",
                subject = "quote",
                hasAttachment = true,
                flagged = true,
                afterMillis = 1_000L,
                beforeMillis = 2_000L,
            ),
        )
        assertEquals(
            listOf(
                SearchCriterion.FROM,
                SearchCriterion.RECIPIENT,
                SearchCriterion.SUBJECT,
                SearchCriterion.ATTACHMENT,
                SearchCriterion.FLAGGED,
                SearchCriterion.AFTER,
                SearchCriterion.BEFORE,
            ),
            summary.map { it.criterion },
        )
        assertEquals("alex", summary.first { it.criterion == SearchCriterion.FROM }.text)
        assertEquals(1_000L, summary.first { it.criterion == SearchCriterion.AFTER }.millis)
        assertEquals(2_000L, summary.first { it.criterion == SearchCriterion.BEFORE }.millis)
    }

    @Test fun `a criterion holding only spaces is not a criterion`() {
        assertTrue(searchSummary(SearchQuery(from = "   ", subject = "\t")).isEmpty())
    }

    @Test fun `a typed value keeps its own spacing trimmed`() {
        val summary = searchSummary(SearchQuery(from = "  alex@example.org  "))
        assertEquals("alex@example.org", summary.single().text)
    }

    @Test fun `an unchecked attachment switch is not a criterion`() {
        assertTrue(searchSummary(SearchQuery(hasAttachment = false)).isEmpty())
    }

    // ---- the flagged filter: the only way left to gather starred mail (1.4.5 unpinned it) ----

    @Test fun `the flagged switch shows in the summary, so an active filter is never invisible`() {
        // A filter the folded panel doesn't mention is exactly the WYSIWYG break #111 removed
        // from the list: results narrowed by something the screen never names.
        assertEquals(
            listOf(SearchCriterion.FLAGGED),
            searchSummary(SearchQuery(flagged = true)).map { it.criterion },
        )
    }

    @Test fun `an unchecked flagged switch is not a criterion`() {
        assertTrue(searchSummary(SearchQuery(flagged = false)).isEmpty())
    }

    @Test fun `flagged alone is a real search, not an empty one`() {
        // Without this, ticking the box and pressing Search would do nothing at all: run() and
        // the repository both bail out on isEmpty().
        assertFalse(SearchQuery(flagged = true).isEmpty())
        assertTrue(SearchQuery(flagged = false).isEmpty())
    }

    @Test fun `a flagged-only search folds the panel like any other`() {
        assertFalse(SearchForm(query = SearchQuery(flagged = true)).afterSearch().expanded)
    }

    // ---- the fold rule ----

    @Test fun `the panel starts open`() {
        assertTrue(SearchForm().expanded)
    }

    @Test fun `running a search folds the panel away`() {
        val form = SearchForm(query = SearchQuery(text = "invoice"))
        assertFalse(form.afterSearch().expanded)
    }

    @Test fun `an empty query searches nothing, so it folds nothing`() {
        assertTrue(SearchForm(query = SearchQuery()).afterSearch().expanded)
        // Even from an already folded panel: there is nothing to look at underneath.
        assertTrue(SearchForm(query = SearchQuery(), expanded = false).afterSearch().expanded)
    }

    @Test fun `the toggle opens a folded panel and folds an open one`() {
        assertTrue(SearchForm(expanded = false).toggled().expanded)
        assertFalse(SearchForm(expanded = true).toggled().expanded)
    }

    @Test fun `folding never touches the criteria`() {
        val query = SearchQuery(text = "invoice", from = "alex", hasAttachment = true)
        assertEquals(query, SearchForm(query = query).afterSearch().query)
        assertEquals(query, SearchForm(query = query).toggled().query)
    }
}
