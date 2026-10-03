package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** This app's build.json through the parser the phone uses, and the cross-references it refuses to break. */
class ConfigTest {
    @Test fun theShippedDeclarationParses() {
        val c = Fixtures.cfg
        assertEquals(listOf("house", "jobs", "search", "groceries", "things"), c.verticals.map { it.id })
        assertEquals("search", c.defaultVertical)
        assertEquals("berlin", c.city(null).id)
        assertEquals("berlin", c.city("atlantis").id)
        assertTrue(c.problems().isEmpty())
        assertEquals(listOf("google", "duckduckgo", "brave", "qwant"), c.engines.map { it.id })
        assertEquals("bird", c.engines[1].icon)
        assertEquals("engine_duckduckgo", c.engines[1].accent)
        assertEquals("calculator", c.calculators.getValue("payslip").icon)
        assertEquals("result", c.calculators.getValue("payslip").outputs.last().tone)
    }

    @Test fun everySourceIsFetchedLinkedOrExplained() {
        for (s in Fixtures.cfg.sources.values) {
            assertTrue("${s.id}: an api source that is enabled names a parser", !s.fetches || s.parser.isNotBlank())
            assertTrue("${s.id}: a source that is not fetched says why", s.fetches || s.why.isNotBlank())
        }
    }

    private fun problemsAfter(edit: (org.json.JSONObject) -> Unit): String {
        val j = Fixtures.searchJson()
        edit(j)
        return runCatching { SearchConfig.parse(j) }.exceptionOrNull()?.message ?: ""
    }

    @Test fun danglingReferencesAreRefused() {
        assertTrue(problemsAfter { it.put("default_vertical", "nope") }.contains("default_vertical nope"))
        assertTrue(problemsAfter { it.put("default_city", "nope") }.contains("default_city nope"))
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(0).getJSONArray("sources").put("ghost") }.contains("names source ghost"))
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(0).getJSONArray("subpages").put("ghost") }.contains("names subpage ghost"))
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(0).getJSONArray("feeds").put("ghost") }.contains("names feed ghost"))
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(0).getJSONArray("calculators").put("ghost") }.contains("names calculator ghost"))
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(0).put("subpages", JSONArray()) }.contains("has no subpage"))
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(0).put("calculators", JSONArray()) }.contains("calculators page but no calculator"))
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(0).put("feeds", JSONArray()) }.contains("feed page but no feed"))
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(4).put("sources", JSONArray()) }.contains("listing but no source"))
        assertTrue(problemsAfter { it.getJSONArray("verticals").put(it.getJSONArray("verticals").getJSONObject(0)) }.contains("duplicate vertical ids"))
    }

    @Test fun sourcesMustBeHonest() {
        assertTrue(problemsAfter { it.getJSONObject("sources").getJSONObject("rewe").remove("why") }.contains("rewe is not fetched but does not say why"))
        assertTrue(problemsAfter { it.getJSONObject("sources").getJSONObject("arbeitnow").remove("parser") }.contains("arbeitnow is enabled but names no parser"))
        assertTrue(problemsAfter { it.getJSONObject("sources").getJSONObject("arbeitnow").remove("url") }.contains("arbeitnow is enabled but has no url"))
        assertTrue(problemsAfter { it.getJSONObject("sources").getJSONObject("rewe").put("kind", "scrape") }.contains("neither api nor link"))
    }

    @Test fun chipsAndChoicesAreWellFormed() {
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(1).getJSONArray("chips").getJSONObject(0).put("tag", "x") }.contains("exactly one of flag or tag"))
        assertTrue(problemsAfter { c -> c.getJSONObject("calculators").getJSONObject("payslip").getJSONArray("fields").getJSONObject(1).put("default", 9) }.contains("default is not one of its options"))
        assertTrue(problemsAfter { c -> c.getJSONObject("calculators").getJSONObject("max_rent").put("outputs", JSONArray()) }.contains("declares no output"))
        assertTrue(problemsAfter { c -> c.getJSONObject("calculators").getJSONObject("max_rent").getJSONArray("outputs").getJSONObject(0).put("tone", "loud") }.contains("output max_rent tone loud"))
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(1).remove("chart_color") }.contains("vertical jobs has an analysis chart but no chart_color"))
    }
}
