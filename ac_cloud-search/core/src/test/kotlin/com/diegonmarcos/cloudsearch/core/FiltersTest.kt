package com.diegonmarcos.cloudsearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    private val jobs = Fixtures.cfg.vertical("jobs")!!
    private val now = 10 * Filters.DAY_MS

    private fun l(id: String, price: Double? = null, date: Long? = null, verified: Boolean = false, remote: Boolean? = null,
                  tags: List<String> = emptyList(), location: String? = null) =
        Listing(id, "s", "title $id", "sub", price, null, null, null, null, date, verified, location, remote, tags)

    private val a = l("a", price = 30.0, date = 9 * Filters.DAY_MS, verified = true, remote = true, tags = listOf("Full-time"))
    private val b = l("b", price = 10.0, date = 1 * Filters.DAY_MS, location = "Berlin")
    private val c = l("c", date = 5 * Filters.DAY_MS, remote = false, tags = listOf("full-time"))
    private val all = listOf(a, b, c)

    private fun ids(f: Filters) = f.apply(all, jobs, 7, now).map { it.id }

    @Test fun relevanceKeepsSourceOrder() = assertEquals(listOf("a", "b", "c"), ids(Filters()))
    @Test fun recentIsWithinTheDeclaredDays() = assertEquals(listOf("a", "c"), ids(Filters(recent = true)))
    @Test fun verifiedOnly() = assertEquals(listOf("a"), ids(Filters(verified = true)))
    @Test fun remoteChip() = assertEquals(listOf("a"), ids(Filters(chips = setOf("remote"))))
    @Test fun tagChipIgnoresCase() = assertEquals(listOf("a", "c"), ids(Filters(chips = setOf("fulltime"))))
    @Test fun pricedChip() = assertEquals(listOf("a", "b"), ids(Filters(chips = setOf("paid"))))
    @Test fun priceBoundsDropUnpriced() = assertEquals(listOf("a"), ids(Filters(min = 20.0)))
    @Test fun maxBound() = assertEquals(listOf("b"), ids(Filters(max = 20.0)))
    @Test fun newestFirst() = assertEquals(listOf("a", "c", "b"), ids(Filters(sort = Filters.Sort.NEWEST)))
    @Test fun priceAscNullsLast() = assertEquals(listOf("b", "a", "c"), ids(Filters(sort = Filters.Sort.PRICE_ASC)))
    @Test fun priceDescNullsLast() = assertEquals(listOf("a", "b", "c"), ids(Filters(sort = Filters.Sort.PRICE_DESC)))

    @Test fun sortIdsRoundTrip() {
        for (s in Filters.Sort.entries) assertEquals(s, Filters.Sort.of(s.id))
        assertEquals(Filters.Sort.RELEVANCE, Filters.Sort.of("nope"))
    }

    @Test fun textMatchNeedsEveryWord() {
        assertTrue(Filters.textMatch(a, "TITLE a"))
        assertTrue(Filters.textMatch(a, "full-time"))
        assertFalse(Filters.textMatch(a, "title zebra"))
        assertTrue(Filters.textMatch(b, "berlin"))
    }

    @Test fun cityMatchUsesAliasesAndKeepsRemote() {
        val munich = Fixtures.cfg.city("munich")
        assertTrue(Filters.cityMatch(l("x", location = "Munich"), munich))
        assertTrue(Filters.cityMatch(l("x", location = "München, Bayern"), munich))
        assertFalse(Filters.cityMatch(l("x", location = "Berlin"), munich))
        assertTrue(Filters.cityMatch(l("x", location = "Berlin", remote = true), munich))
        assertFalse(Filters.cityMatch(l("x"), munich))
    }

    @Test fun everyDeclaredChipFlagIsKnown() {
        for (v in Fixtures.cfg.verticals) for (chip in v.chips)
            assertTrue("chip ${v.id}/${chip.id}", chip.tag.isNotBlank() || chip.flag in Filters.FLAGS)
    }
}
