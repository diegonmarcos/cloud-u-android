package com.diegonmarcos.cloudsearch.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1184 Research: the News page's declaration and the reader of the news service's answer. */
class ResearchTest {
    private val cfg get() = requireNotNull(Fixtures.cfg.research) { "build.json::search.research is declared" }

    @Test fun theShippedDeclarationNamesTheFleetNewsService() {
        assertEquals("news", cfg.service)
        assertEquals("/articles", cfg.articlesPath)
        assertEquals(listOf("tech", "ai", "science", "finance", "world", "health"), cfg.topics.map { it.id })
    }

    @Test fun theRequestPathIsTheServicesOwn() {
        val t = ResearchConfig.Topic("tech", "Tech", "technology sourcelang:english")
        assertEquals("/articles?q=technology%20sourcelang%3Aenglish&limit=40", cfg.path(t))
    }

    private fun refused(edit: (JSONObject) -> Unit): String {
        val j = Fixtures.searchJson().getJSONObject("research")
        edit(j)
        return runCatching { ResearchConfig.parse(j) }.exceptionOrNull()?.message ?: ""
    }

    @Test fun aBrokenDeclarationIsRefused() {
        assertTrue(refused { it.getJSONObject("news").put("topics", org.json.JSONArray()) }.contains("topics is empty"))
        assertTrue(refused { it.getJSONObject("news").getJSONArray("topics").put(it.getJSONObject("news").getJSONArray("topics").getJSONObject(0)) }.contains("repeats an id"))
        assertTrue(refused { it.getJSONObject("news").put("articles_path", "articles") }.contains("/path"))
        assertTrue(refused { it.getJSONObject("news").put("limit", 0) }.contains("limit"))
        assertTrue(refused { it.getJSONObject("news").put("limit", 251) }.contains("limit"))
    }

    @Test fun articlesAreReadNewestFirstAndOnlyWhatIsReal() {
        val a = ResearchConfig.articles(Fixtures.text("news-articles.json"))
        assertEquals("a non-http url is dropped, a repeated url is dropped", listOf("Second story", "First story"), a.map { it.title })
        assertEquals("2026-10-25 08:10", a[0].seen)
        assertEquals("2026-10-24 10:15", a[1].seen)
        assertEquals("example.org", a[1].domain)
        assertEquals("https://example.org/a", a[1].url)
        assertTrue(ResearchConfig.articles("""{"query":"x","count":0}""").isEmpty())
        assertTrue(ResearchConfig.articles("""{"articles":[{"url":"https://x.example","title":" "}]}""").isEmpty())
    }

    @Test fun aDateThatIsNotGdeltsIsShownAsGiven() {
        assertEquals("yesterday", ResearchConfig.stamp("yesterday"))
        assertEquals("2026-01-02 03:04", ResearchConfig.stamp("20260102T030405Z"))
    }
}
