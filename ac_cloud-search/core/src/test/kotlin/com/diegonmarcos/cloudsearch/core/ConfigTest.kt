package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** This app's build.json through the parser the phone uses, and the cross-references it refuses to break. */
class ConfigTest {
    @Test fun theShippedDeclarationParses() {
        val c = Fixtures.cfg
        assertEquals(listOf("house", "jobs", "search", "groceries", "things", "commercial", "business", "suppliers", "services", "image", "video", "movies", "books", "electronics", "clothing"), c.verticals.map { it.id })
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
        assertTrue(problemsAfter { it.getJSONArray("verticals").getJSONObject(3).put("sources", JSONArray()) }.contains("listing but no source"))
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

    /** #1197 the News feed pages, the Engines page, the media sources and the things pages are declared and cross-referenced. */
    @Test fun newsEnginesMediaAndThingsPagesAreDeclared() {
        val c = Fixtures.cfg
        assertEquals(listOf("youtube", "gnews", "rss"), c.newsPages.map { it.id })
        assertEquals(SearchConfig.FORMAT_ATOM, c.feeds.getValue("yt-google-developers").format)
        assertEquals(SearchConfig.FORMAT_RSS, c.feeds.getValue("gn-technology").format)
        assertEquals(SearchConfig.FORMAT_RSS, c.feeds.getValue("tagesschau-wirtschaft").format)
        assertTrue(c.newsPage("youtube")!!.feeds.all { c.feeds.getValue(it).url.startsWith("https://www.youtube.com/feeds/videos.xml?channel_id=UC") })
        assertTrue(c.newsPage("gnews")!!.feeds.all { c.feeds.getValue(it).url.startsWith("https://news.google.com/rss/") })
        assertNull(c.newsPage("ghost"))
        assertTrue(c.majorEngines.map { it.id }.containsAll(listOf("google", "bing", "duckduckgo", "brave", "qwant", "kagi", "perplexity")))
        assertEquals("ai", c.majorEngines.first { it.id == "perplexity" }.kind)
        assertEquals("electronics", c.vertical("electronics")!!.thingsCategory)
        assertEquals("clothes", c.vertical("clothing")!!.thingsCategory)
        assertEquals("", c.vertical("things")!!.thingsCategory)
        for (id in listOf("image", "video", "movies", "books")) assertTrue("$id reads real api sources", c.vertical(id)!!.sources.all { c.sources.getValue(it).fetches })
    }

    @Test fun brokenNewsEngineAndThingsDeclarationsAreRefused() {
        assertTrue(problemsAfter { it.getJSONArray("news_pages").getJSONObject(0).getJSONArray("feeds").put("ghost") }.contains("news page youtube names feed ghost"))
        assertTrue(problemsAfter { it.getJSONArray("news_pages").getJSONObject(0).put("feeds", JSONArray()) }.contains("news page youtube names no feed"))
        assertTrue(problemsAfter { it.getJSONArray("news_pages").put(it.getJSONArray("news_pages").getJSONObject(0)) }.contains("duplicate news page ids"))
        assertTrue(problemsAfter { it.getJSONObject("feeds").getJSONObject("yt-ted").put("format", "json") }.contains("feed yt-ted format json"))
        assertTrue(problemsAfter { it.getJSONArray("engines_major").getJSONObject(0).put("url", "https://example.org/") }.contains("major engine google needs an https url with {q}"))
        assertTrue(problemsAfter { it.getJSONArray("engines_major").getJSONObject(0).put("url", "http://example.org/?q={q}") }.contains("major engine google needs an https url with {q}"))
        assertTrue(problemsAfter { it.getJSONArray("engines_major").getJSONObject(0).put("kind", "oracle") }.contains("major engine google kind oracle"))
        assertTrue(problemsAfter { it.getJSONArray("engines_major").put(it.getJSONArray("engines_major").getJSONObject(0)) }.contains("duplicate major engine ids"))
        val v = { j: org.json.JSONObject -> j.getJSONArray("verticals").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { o -> o.getString("id") == "electronics" } } }
        assertTrue(problemsAfter { v(it).put("things_category", "ghost") }.contains("vertical electronics things_category ghost"))
    }
}
