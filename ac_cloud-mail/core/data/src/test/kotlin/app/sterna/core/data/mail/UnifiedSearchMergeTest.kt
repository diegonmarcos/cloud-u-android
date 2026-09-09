package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unified search's per-account merge ([mergeAccountSearches]): no account may drop out of the
 * answer without the answer saying so.
 */
class UnifiedSearchMergeTest {

    private fun hit(account: String, id: String, receivedAt: String) =
        Email(id = id, accountId = account, receivedAt = receivedAt)

    /** [count] hits for [account], all newer than [olderThan]. */
    private fun manyRecent(account: String, count: Int) =
        (1..count).map { hit(account, "$account-$it", "2026-07-%02dT10:00:00Z".format((it % 28) + 1)) }

    @Test fun `both accounts are represented, newest first`() {
        val a = MailSearchResult(listOf(hit("accA", "a1", "2026-07-20T10:00:00Z")))
        val b = MailSearchResult(listOf(hit("accB", "b1", "2026-07-21T10:00:00Z")))

        val merged = mergeAccountSearches(listOf(a, b), limit = 10)
        assertEquals(listOf("b1", "a1"), merged.emails.map { it.id })
        assertTrue(merged.complete)
    }

    @Test fun `an account whose mail is all older is not starved by the cap`() {
        // The silent omission: account A alone fills the cap with newer mail, so merging then
        // keeping the newest [limit] left NOTHING of account B — a message the user finds by
        // searching B alone was simply absent from the unified search.
        val a = MailSearchResult(manyRecent("accA", 10))
        val b = MailSearchResult(listOf(hit("accB", "b1", "2020-01-01T10:00:00Z")))

        val merged = mergeAccountSearches(listOf(a, b), limit = 10)
        assertTrue("accB must still be represented", merged.emails.any { it.accountId == "accB" })
        assertEquals(10, merged.emails.size)
        assertFalse("hits were dropped, so the count is a floor", merged.complete)
    }

    @Test fun `the cap is shared fairly between accounts`() {
        val a = MailSearchResult(manyRecent("accA", 20))
        val b = MailSearchResult(manyRecent("accB", 20))

        val merged = mergeAccountSearches(listOf(a, b), limit = 10)
        assertEquals(5, merged.emails.count { it.accountId == "accA" })
        assertEquals(5, merged.emails.count { it.accountId == "accB" })
    }

    @Test fun `an account with few hits does not waste the other's share`() {
        val a = MailSearchResult(manyRecent("accA", 20))
        val b = MailSearchResult(listOf(hit("accB", "b1", "2020-01-01T10:00:00Z")))

        val merged = mergeAccountSearches(listOf(a, b), limit = 10)
        assertEquals(10, merged.emails.size)
        assertEquals(9, merged.emails.count { it.accountId == "accA" })
    }

    @Test fun `the same JMAP id in two accounts is two messages, both listed`() {
        val a = MailSearchResult(listOf(hit("accA", "e1", "2026-07-20T10:00:00Z")))
        val b = MailSearchResult(listOf(hit("accB", "e1", "2026-07-19T10:00:00Z")))

        val merged = mergeAccountSearches(listOf(a, b), limit = 10)
        assertEquals(2, merged.emails.size)
        assertEquals(setOf("accA", "accB"), merged.emails.mapTo(mutableSetOf()) { it.accountId })
    }

    @Test fun `a duplicate within one account is listed once`() {
        val a = MailSearchResult(
            listOf(hit("accA", "e1", "2026-07-20T10:00:00Z"), hit("accA", "e1", "2026-07-20T10:00:00Z")),
        )
        assertEquals(1, mergeAccountSearches(listOf(a, MailSearchResult(emptyList())), limit = 10).emails.size)
    }

    @Test fun `an account that failed makes the answer incomplete`() {
        // What the repository substitutes for an account whose search threw: no hits, not whole.
        val a = MailSearchResult(listOf(hit("accA", "a1", "2026-07-20T10:00:00Z")))
        val failed = MailSearchResult(emptyList(), complete = false)

        val merged = mergeAccountSearches(listOf(a, failed), limit = 10)
        assertEquals(listOf("a1"), merged.emails.map { it.id })
        assertFalse("a dropped account must never pass for the whole answer", merged.complete)
    }

    @Test fun `an account truncated server-side makes the answer incomplete`() {
        val a = MailSearchResult(listOf(hit("accA", "a1", "2026-07-20T10:00:00Z")), complete = false)
        val b = MailSearchResult(listOf(hit("accB", "b1", "2026-07-19T10:00:00Z")))

        assertFalse(mergeAccountSearches(listOf(a, b), limit = 10).complete)
    }

    @Test fun `a whole answer that exactly fills the cap is still whole`() {
        val a = MailSearchResult(manyRecent("accA", 5))
        val b = MailSearchResult(manyRecent("accB", 5))

        val merged = mergeAccountSearches(listOf(a, b), limit = 10)
        assertEquals(10, merged.emails.size)
        assertTrue(merged.complete)
    }

    @Test fun `no accounts answered at all`() {
        assertEquals(emptyList<Email>(), mergeAccountSearches(emptyList(), limit = 10).emails)
    }
}
