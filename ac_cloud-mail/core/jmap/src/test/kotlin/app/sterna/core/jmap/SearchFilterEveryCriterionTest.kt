package app.sterna.core.jmap

import app.sterna.core.jmap.model.SearchQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every criterion the search panel offers, one by one and then crossed, on the JMAP side.
 */
class SearchFilterEveryCriterionTest {

    /** 2026-06-01T00:00:00Z — the start bound the picker hands over. */
    private val june1 = 1_780_272_000_000L

    /** 2026-06-15T23:59:59Z — the end bound (end of the picked day). */
    private val june15End = 1_781_567_999_000L

    private fun filter(query: SearchQuery) = searchFilter(query).toString()

    /** The conditions of an AND/OR filter; a single-condition filter has none of its own. */
    private fun conditions(query: SearchQuery): List<JsonObject> =
        (searchFilter(query)["conditions"] as? JsonArray)?.map { it as JsonObject } ?: emptyList()

    // ---- one criterion at a time ----

    @Test fun `free text alone becomes a text condition`() {
        assertEquals("""{"text":"invoice"}""", filter(SearchQuery(text = "invoice")))
    }

    @Test fun `a sender alone becomes a from condition`() {
        assertEquals("""{"from":"alex@masto.top"}""", filter(SearchQuery(from = "alex@masto.top")))
    }

    @Test fun `a subject alone becomes a subject condition`() {
        assertEquals("""{"subject":"facture"}""", filter(SearchQuery(subject = "facture")))
    }

    /** The one criterion that is TWO header tests: an address in copy was received all the same. */
    @Test fun `a recipient alone becomes an OR over to and cc`() {
        assertEquals(
            """{"operator":"OR","conditions":[{"to":"team@masto.top"},{"cc":"team@masto.top"}]}""",
            filter(SearchQuery(recipient = "team@masto.top")),
        )
    }

    @Test fun `an attachment tick alone becomes a hasAttachment condition`() {
        assertEquals("""{"hasAttachment":true}""", filter(SearchQuery(hasAttachment = true)))
    }

    @Test fun `a flagged tick alone becomes a hasKeyword condition`() {
        assertEquals("""{"hasKeyword":"${'$'}flagged"}""", filter(SearchQuery(flagged = true)))
    }

    @Test fun `a start date alone becomes an after bound in UTC`() {
        assertEquals("""{"after":"2026-06-01T00:00:00Z"}""", filter(SearchQuery(afterMillis = june1)))
    }

    @Test fun `an end date alone becomes a before bound in UTC`() {
        assertEquals("""{"before":"2026-06-15T23:59:59Z"}""", filter(SearchQuery(beforeMillis = june15End)))
    }

    // ---- the witness: a criterion left alone contributes nothing ----

    /**
     * THE witness for all seven at once. Without it every assertion above passes just as well on a
     */
    @Test fun `criteria left at their default put no condition in the filter`() {
        val onlyText = filter(SearchQuery(text = "invoice"))

        assertEquals("""{"text":"invoice"}""", onlyText)
        listOf("from", "to", "cc", "subject", "hasAttachment", "hasKeyword", "after", "before")
            .forEach { property ->
                assertFalse("an untouched criterion emitted a $property condition", onlyText.contains(property))
            }
    }

    /** Dropping one box must cost exactly one condition — not zero (inert), not two (coupled). */
    @Test fun `each criterion is worth exactly one condition of the AND`() {
        val full = SearchQuery(
            text = "invoice",
            from = "alex@masto.top",
            recipient = "team@masto.top",
            subject = "facture",
            hasAttachment = true,
            flagged = true,
            afterMillis = june1,
            beforeMillis = june15End,
        )
        assertEquals(8, conditions(full).size)

        assertEquals(7, conditions(full.copy(flagged = false)).size)
        assertEquals(7, conditions(full.copy(hasAttachment = false)).size)
        assertEquals(7, conditions(full.copy(recipient = "")).size)
        assertEquals(7, conditions(full.copy(afterMillis = null)).size)
        assertEquals(7, conditions(full.copy(beforeMillis = null)).size)
        assertEquals(7, conditions(full.copy(text = "")).size)
        assertEquals(7, conditions(full.copy(from = "")).size)
        assertEquals(7, conditions(full.copy(subject = "")).size)
    }

    // ---- crossed criteria ----

    /**
     * The crossing the flagged filter had never been through: starred AND from Alex AND since June.
     */
    @Test fun `flagged crossed with a date and a sender narrows the query rather than widening it`() {
        val crossed = SearchQuery(from = "alex@masto.top", flagged = true, afterMillis = june1)

        assertEquals(
            """{"operator":"AND","conditions":[{"from":"alex@masto.top"},{"hasKeyword":"${'$'}flagged"},""" +
                """{"after":"2026-06-01T00:00:00Z"}]}""",
            filter(crossed),
        )
        assertFalse("""an OR must not appear outside the recipient's""", filter(crossed).contains("OR"))
    }

    /** The witness for the crossing: unticking the star leaves the other two exactly as they were. */
    @Test fun `unticking the star leaves the date and the sender untouched`() {
        val withoutStar = filter(SearchQuery(from = "alex@masto.top", flagged = false, afterMillis = june1))

        assertEquals(
            """{"operator":"AND","conditions":[{"from":"alex@masto.top"},{"after":"2026-06-01T00:00:00Z"}]}""",
            withoutStar,
        )
        assertFalse(withoutStar.contains("hasKeyword"))
    }

    /** The date range crossed with the star: a window, still ANDed, still one condition each. */
    @Test fun `a date range crossed with the star stays a single AND of three conditions`() {
        assertEquals(
            """{"operator":"AND","conditions":[{"hasKeyword":"${'$'}flagged"},""" +
                """{"after":"2026-06-01T00:00:00Z"},{"before":"2026-06-15T23:59:59Z"}]}""",
            filter(SearchQuery(flagged = true, afterMillis = june1, beforeMillis = june15End)),
        )
    }

    /**
     * Only the recipient nests an OR. Any other criterion growing one would widen the search
     * silently — the filter still parses, the server still answers, and the answer is wrong.
     */
    @Test fun `the recipient is the only criterion that nests an OR`() {
        val withoutRecipient = SearchQuery(
            text = "invoice",
            from = "alex@masto.top",
            subject = "facture",
            hasAttachment = true,
            flagged = true,
            afterMillis = june1,
            beforeMillis = june15End,
        )
        assertFalse(filter(withoutRecipient).contains("OR"))

        val withRecipient = filter(withoutRecipient.copy(recipient = "team@masto.top"))
        assertEquals(1, Regex("\"operator\":\"OR\"").findAll(withRecipient).count())
        assertTrue(withRecipient.contains("""{"to":"team@masto.top"},{"cc":"team@masto.top"}"""))
    }
}
