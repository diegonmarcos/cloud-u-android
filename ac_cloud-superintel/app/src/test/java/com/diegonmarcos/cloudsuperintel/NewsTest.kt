package com.diegonmarcos.cloudsuperintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsTest {
    private val cfg = NewsConfig.parse(Data.asset("news.json"))

    /** The shape news-gdelt's GET /news/articles returns (cloud-u-containers user-news_news-gdelt/src/code/server.ts). */
    private val body = """
        {"query":"technology","count":5,"articles":[
          {"url":"https://a.example/1","title":"Chipmaker unveils new AI processor","seendate":"20261024T100000Z","socialimage":"https://a.example/i.jpg","domain":"a.example","language":"English","sourcecountry":"US","tone":1.5},
          {"url":"https://b.example/2","title":"Chipmaker unveils new AI processor today","seendate":"20261024T110000Z","socialimage":"","domain":"b.example","language":"English","sourcecountry":"UK","tone":0},
          {"url":"https://c.example/3","title":"Volcano erupts near coastal town","seendate":"20261023T090000Z","socialimage":"https://c.example/v.jpg","domain":"c.example","language":"English","sourcecountry":"IS","tone":-4},
          {"url":"","title":"No link, dropped","seendate":"","socialimage":"","domain":"d.example"},
          {"url":"https://e.example/5","title":"  ","seendate":"","socialimage":"","domain":"e.example"}
        ]}
    """.trimIndent()

    @Test fun parsesTheServiceShapeAndDropsIncompleteEntries() {
        val a = News.parseArticles(body)
        assertEquals(3, a.size)
        assertEquals("a.example", a[0].domain)
        assertEquals("https://a.example/i.jpg", a[0].image)
    }

    @Test fun emptyOrMissingArticlesGiveNoStoriesNotInventedOnes() {
        assertTrue(News.parseArticles("""{"query":"x","count":0,"articles":[]}""").isEmpty())
        assertTrue(News.parseArticles("""{"query":"x"}""").isEmpty())
        assertTrue(News.stories(emptyList(), cfg.similarity).isEmpty())
    }

    @Test fun sameEventArticlesBecomeOneStoryWithItsSourceCount() {
        val s = News.stories(News.parseArticles(body), cfg.similarity)
        assertEquals(2, s.size)
        val chip = s.first { it.headline.contains("Chipmaker") }
        assertEquals(2, chip.sources)
        assertEquals("the newest article leads", "Chipmaker unveils new AI processor today", chip.headline)
        assertEquals("the first picture found is used", "https://a.example/i.jpg", chip.image)
        assertEquals(1, s.first { it.headline.contains("Volcano") }.sources)
    }

    @Test fun duplicateUrlsCountOnce() {
        val a = News.parseArticles(body)
        assertEquals(2, News.stories(a + a, cfg.similarity).size)
    }

    @Test fun requestUrlUsesTheDeclaredPathAndLimit() {
        val u = News.url("https://api.example/news/", cfg, "finance markets") { it.replace(" ", "+") }
        assertEquals("https://api.example/news/articles?q=finance+markets&limit=${cfg.limit}", u)
    }

    @Test fun topicsComeFromDataAndMatchTheUiPages() {
        assertEquals(listOf("For you", "Tech", "Science", "Finance", "World", "Health", "Sports", "Arts"), cfg.topics.map { it.label })
        assertEquals(cfg.topics.map { it.id }, Data.pages("news"))
    }

    @Test fun forYouMergesTheTopicsItMixes() {
        val forYou = cfg.topics.first { it.id == "foryou" }
        val q = News.queries(forYou, cfg.topics)
        assertTrue(q.isNotEmpty())
        assertEquals(cfg.topics.first { it.id == "tech" }.queries, q.take(cfg.topics.first { it.id == "tech" }.queries.size))
        for (t in cfg.topics.filter { it.mix.isEmpty() }) assertTrue("${t.id} has a query", t.queries.isNotEmpty())
    }

    @Test fun summaryPromptsCarryTheHeadlinesAndForbidAddingFacts() {
        val s = News.stories(News.parseArticles(body), cfg.similarity).first()
        val h = News.headlines(s, cfg.storySourcesMax)
        assertTrue(h.startsWith("1. "))
        assertTrue(cfg.cardPrompt.contains("{{headlines}}") && cfg.storyPrompt.contains("{{headlines}}"))
        assertTrue(cfg.storyPrompt.contains("do not add facts"))
    }
}
