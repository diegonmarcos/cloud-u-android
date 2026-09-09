package app.sterna.ui.search

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The search list spans every account and deduplicates by (accountId, id), so the same JMAP id
 */
class SearchResultKeyTest {

    private fun email(accountId: String?, id: String) = Email(id = id, accountId = accountId)

    @Test fun `the same id in two accounts yields two keys`() {
        val results = listOf(email("acct-a", "M42"), email("acct-b", "M42"))
        assertEquals(2, results.map(::searchResultKey).toSet().size)
    }

    @Test fun `two ids in one account yield two keys`() {
        assertNotEquals(
            searchResultKey(email("acct-a", "M42")),
            searchResultKey(email("acct-a", "M43")),
        )
    }

    @Test fun `the same row always yields the same key`() {
        assertEquals(
            searchResultKey(email("acct-a", "M42")),
            searchResultKey(email("acct-a", "M42")),
        )
    }

    @Test fun `a result without an account still has a key`() {
        // accountId is null for messages parsed straight from a JMAP response; the single-account
        // search path stamps it, but the key must not blow up if one slips through.
        assertNotEquals(
            searchResultKey(email(null, "M42")),
            searchResultKey(email("acct-a", "M42")),
        )
    }
}
