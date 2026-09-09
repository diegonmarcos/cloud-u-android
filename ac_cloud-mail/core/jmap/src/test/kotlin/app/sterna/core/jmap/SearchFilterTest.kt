package app.sterna.core.jmap

import app.sterna.core.jmap.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `Email/query` filter (RFC 8621 §4.4.1). The combination is what can go wrong: the
 */
class SearchFilterTest {

    @Test
    fun `a single criterion is emitted without an AND wrapper`() {
        assertEquals("""{"text":"invoice"}""", searchFilter(SearchQuery(text = "invoice")).toString())
    }

    @Test
    fun `recipient alone is an OR over to and cc`() {
        assertEquals(
            """{"operator":"OR","conditions":[{"to":"team@masto.top"},{"cc":"team@masto.top"}]}""",
            searchFilter(SearchQuery(recipient = "team@masto.top")).toString(),
        )
    }

    @Test
    fun `recipient nests inside the AND rather than flattening into it`() {
        assertEquals(
            """{"operator":"AND","conditions":[{"from":"alex@masto.top"},""" +
                """{"operator":"OR","conditions":[{"to":"team@masto.top"},{"cc":"team@masto.top"}]}]}""",
            searchFilter(SearchQuery(from = "alex@masto.top", recipient = "team@masto.top")).toString(),
        )
    }

    @Test
    fun `a full query keeps every criterion, dates as UTCDate`() {
        val filter = searchFilter(
            SearchQuery(
                text = "invoice",
                from = "alex@masto.top",
                recipient = "team@masto.top",
                subject = "facture",
                hasAttachment = true,
                flagged = true,
                afterMillis = 1_780_272_000_000L,
                beforeMillis = 1_781_567_999_000L,
            ),
        )
        assertEquals(
            """{"operator":"AND","conditions":[{"text":"invoice"},{"from":"alex@masto.top"},""" +
                """{"operator":"OR","conditions":[{"to":"team@masto.top"},{"cc":"team@masto.top"}]},""" +
                """{"subject":"facture"},{"hasAttachment":true},{"hasKeyword":"${'$'}flagged"},""" +
                """{"after":"2026-06-01T00:00:00Z"},{"before":"2026-06-15T23:59:59Z"}]}""",
            filter.toString(),
        )
    }

    // ---- the flagged filter ----

    /**
     * The exact JSON, keyword included: `$flagged` is the JMAP name of IMAP's `\Flagged`
     */
    @Test
    fun `flagged alone becomes a hasKeyword condition on the flagged keyword`() {
        assertEquals(
            """{"hasKeyword":"${'$'}flagged"}""",
            searchFilter(SearchQuery(flagged = true)).toString(),
        )
    }

    /** The witness: unticked, the condition must not be there at all. */
    @Test
    fun `an unticked flagged switch adds no condition`() {
        val filter = searchFilter(SearchQuery(text = "invoice", flagged = false)).toString()
        assertEquals("""{"text":"invoice"}""", filter)
        assertEquals(false, filter.contains("hasKeyword"))
    }

    @Test
    fun `flagged ANDs with the other criteria rather than widening them`() {
        assertEquals(
            """{"operator":"AND","conditions":[{"from":"alex@masto.top"},{"hasKeyword":"${'$'}flagged"}]}""",
            searchFilter(SearchQuery(from = "alex@masto.top", flagged = true)).toString(),
        )
    }

    @Test
    fun `a flagged-only search is not an empty query`() {
        // isEmpty() is what stops the repository before it ever builds a filter; a "flagged only"
        // search must get past it.
        assertEquals(false, SearchQuery(flagged = true).isEmpty())
    }

    @Test
    fun `blank criteria contribute nothing`() {
        assertEquals(
            """{"text":"invoice"}""",
            searchFilter(SearchQuery(text = " invoice ", from = "  ", recipient = "", subject = " ")).toString(),
        )
    }

    @Test
    fun `excluded mailboxes become an inMailboxOtherThan condition of the AND`() {
        assertEquals(
            """{"operator":"AND","conditions":[{"text":"invoice"},""" +
                """{"inMailboxOtherThan":["trash1","junk1"]}]}""",
            searchFilter(SearchQuery(text = "invoice"), excludeMailboxIds = listOf("trash1", "junk1")).toString(),
        )
    }

    @Test
    fun `no excluded mailboxes leave the filter unchanged`() {
        assertEquals(
            """{"text":"invoice"}""",
            searchFilter(SearchQuery(text = "invoice"), excludeMailboxIds = emptyList()).toString(),
        )
    }

    @Test
    fun `an empty query is caught upstream, not here`() {
        // Guarded by SearchQuery.isEmpty() in the repository: an empty filter matches everything.
        assertEquals(true, SearchQuery().isEmpty())
        assertEquals(false, SearchQuery(recipient = "team@masto.top").isEmpty())
    }
}
