package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxIdRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision behind "3 results" and "at least 3": may this search answer be shown as a TOTAL?
 */
class SearchAnswerIsTotalTest {

    private fun folders(vararg pairs: Pair<String, String?>) = pairs.map { MailboxIdRole(it.first, it.second) }

    /** The witness the three refusals are measured against: covered, finished, under the cap. */
    @Test fun `a search that covered the account, ran to its end and stayed under the cap is a total`() {
        assertTrue(searchAnswerIsTotal(scopeCoversAccount = true, scanComplete = true, found = 3, limit = 50))
    }

    @Test fun `a search that covered a fraction of the account is never a total`() {
        assertFalse(searchAnswerIsTotal(scopeCoversAccount = false, scanComplete = true, found = 3, limit = 50))
        // Above all when it found nothing: THIS is the answer that must not read "No results".
        assertFalse(searchAnswerIsTotal(scopeCoversAccount = false, scanComplete = true, found = 0, limit = 50))
    }

    @Test fun `a search that could not run to its end is never a total`() {
        assertFalse(searchAnswerIsTotal(scopeCoversAccount = true, scanComplete = false, found = 3, limit = 50))
        assertFalse(searchAnswerIsTotal(scopeCoversAccount = true, scanComplete = false, found = 0, limit = 50))
    }

    @Test fun `a search that filled the cap is never a total, one hit under it is`() {
        assertFalse(searchAnswerIsTotal(scopeCoversAccount = true, scanComplete = true, found = 50, limit = 50))
        assertTrue(searchAnswerIsTotal(scopeCoversAccount = true, scanComplete = true, found = 49, limit = 50))
    }

    /**
     * The IMAP entry point answers exactly what it answered when it held the rule itself: it reads
     */
    @Test fun `the IMAP entry point still decides exactly what the shared rule decides`() {
        for (known in listOf(emptyList<String>(), listOf("i"), listOf("i", "a"))) {
            for (walkComplete in listOf(true, false)) {
                for (found in listOf(0, 3, 49, 50)) {
                    assertEquals(
                        "known=$known walkComplete=$walkComplete found=$found",
                        searchAnswerIsTotal(
                            scopeCoversAccount = known.isNotEmpty(),
                            scanComplete = walkComplete,
                            found = found,
                            limit = 50,
                        ),
                        imapSearchComplete(known, walkComplete, found, limit = 50),
                    )
                }
            }
        }
    }

    /**
     * An account with neither Trash nor Junk: nothing to leave out of a search, so the excluded list
     */
    @Test fun `having nothing to exclude is not the same as knowing no folder`() {
        val cached = folders("i" to "inbox", "a" to "archive", "x" to null)
        val excluded = excludedSearchFolderIds(cached)

        assertEquals(emptyList<String>(), excluded)
        assertTrue(
            "the folder list is what says whether the account was covered",
            searchAnswerIsTotal(cached.isNotEmpty(), scanComplete = true, found = 3, limit = 50),
        )
        assertFalse(
            "the derived list cannot say it: this account would never be allowed a total",
            searchAnswerIsTotal(excluded.isNotEmpty(), scanComplete = true, found = 3, limit = 50),
        )
    }

    /** The witness for it: an account that has never synced knows no folder, and both lists agree. */
    @Test fun `an account that knows no folder at all cannot claim a total`() {
        val cached = folders()

        assertEquals(emptyList<String>(), excludedSearchFolderIds(cached))
        assertFalse(searchAnswerIsTotal(cached.isNotEmpty(), scanComplete = true, found = 3, limit = 50))
    }
}
