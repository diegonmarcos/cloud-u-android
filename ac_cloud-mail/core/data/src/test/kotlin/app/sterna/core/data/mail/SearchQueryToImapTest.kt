package app.sterna.core.data.mail

import app.sterna.core.imap.buildImapSearch
import app.sterna.core.jmap.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joint between the protocol-neutral [SearchQuery] and what IMAP is asked, which had no test
 */
class SearchQueryToImapTest {

    // ---- what the server is asked ----

    @Test fun `the flagged criterion survives the crossing into IMAP criteria`() {
        assertTrue(SearchQuery(flagged = true).toImapCriteria().flagged)
    }

    @Test fun `an unticked switch crosses as false`() {
        assertFalse(SearchQuery(flagged = false).toImapCriteria().flagged)
    }

    /**
     * The end of the chain, from the object the screen owns to the bytes on the socket. This is
     * what actually fails if `flagged = flagged` is dropped from the mapping.
     */
    @Test fun `a flagged query reaches the wire as a FLAGGED key`() {
        assertEquals("FLAGGED", buildImapSearch(SearchQuery(flagged = true).toImapCriteria()).keys)
    }

    /** The witness: no tick, no key — otherwise the assertion above would pass on a constant. */
    @Test fun `an unflagged query puts no FLAGGED key on the wire`() {
        val keys = buildImapSearch(SearchQuery(subject = "facture").toImapCriteria()).keys
        assertEquals("""SUBJECT "facture"""", keys)
        assertFalse(keys.contains("FLAGGED"))
    }

    @Test fun `every other criterion still crosses, so the mapping is not half-wired`() {
        val criteria = SearchQuery(
            text = "invoice",
            from = "alex@masto.top",
            recipient = "team@masto.top",
            subject = "facture",
            flagged = true,
            afterMillis = 1_000L,
            beforeMillis = 2_000L,
        ).toImapCriteria()
        assertEquals("invoice", criteria.text)
        assertEquals("alex@masto.top", criteria.from)
        assertEquals("team@masto.top", criteria.recipient)
        assertEquals("facture", criteria.subject)
        assertTrue(criteria.flagged)
        assertEquals(1_000L, criteria.afterMillis)
        assertEquals(2_000L, criteria.beforeMillis)
    }

    // ---- whether the client has to fetch-and-filter ----

    /**
     * The guarantee the folder-walk test cannot make, because it chooses `requireAttachment`
     */
    @Test fun `a flagged search never asks for a client-side scan`() {
        assertFalse(SearchQuery(flagged = true).requiresLocalScan())
    }

    /** The witness: the attachment filter, which has no IMAP key, still does ask for one. */
    @Test fun `an attachment search does ask for a client-side scan`() {
        assertTrue(SearchQuery(hasAttachment = true).requiresLocalScan())
    }

    @Test fun `flagged does not switch the scan on beside an attachment search either`() {
        // Both ticked: the scan is owed to the attachment alone, and unticking it must switch the
        // walk off even though the flag is still set.
        assertTrue(SearchQuery(hasAttachment = true, flagged = true).requiresLocalScan())
        assertFalse(SearchQuery(hasAttachment = false, flagged = true).requiresLocalScan())
    }

    @Test fun `no criterion at all asks for no scan`() {
        assertFalse(SearchQuery(text = "invoice").requiresLocalScan())
    }
}
