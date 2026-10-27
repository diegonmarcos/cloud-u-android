package com.diegonmarcos.cloudkgstore

import com.diegonmarcos.cloudsearch.core.Cache
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Web pages run Cloud Search's declared sources and parsers through the linked core; here against the saved real responses of
 * Cloud Search's own suite (read where they live), so a change of its declaration or parsers is felt in this app's suite too.
 */
class WebPagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Net : Http {
        val calls = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
            calls += url
            return when {
                "arbeitnow" in url -> Http.Response(200, Data.searchFixture("arbeitnow.json"))
                "arbeitsagentur" in url -> Http.Response(200, Data.searchFixture("ba-jobsuche.json"))
                "tagesschau.de" in url -> Http.Response(200, Data.searchFixture("tagesschau-verbraucher.xml"))
                "news.google.com/rss" in url -> Http.Response(200, Data.searchFixture("google-news-technology.xml"))
                "openverse" in url -> Http.Response(200, Data.searchFixture("openverse-images.json"))
                else -> Http.Response(404, "")
            }
        }
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int) = Http.Response(405, "")
    }

    private val cfg = SearchConfig.parse(Data.searchJson.toString())
    private val net = Net()
    private fun engine() = SearchEngine(cfg, net, Cache(tmp.root), { 1_000_000L })

    @Test fun theJobsPageListsRealListingsAndSaysWhatItDidNotRead() {
        val r = engine().query("jobs", "", "berlin")
        assertTrue(r.listings.isNotEmpty())
        val st = r.statuses.associateBy { it.id }
        assertEquals(SearchEngine.State.OK, st.getValue("ba-jobsuche").state)
        assertEquals("a link source is opened, not read", SearchEngine.State.LINK, st.getValue("stepstone").state)
        assertTrue("a link carries the site's own search", st.getValue("stepstone").link!!.startsWith("https://"))
        assertTrue(r.listings.all { it.title.isNotBlank() })
    }

    @Test fun theMediaPageReadsTheImageSource() {
        val r = engine().query("image", "cat", null)
        assertTrue(r.listings.isNotEmpty())
        assertTrue(net.calls.any { "openverse" in it })
    }

    @Test fun theNewsPageReadsTheDeclaredFeedsAndAnUnreachableOneIsAnErrorStatus() {
        val page = cfg.newsPages.first { it.id == "rss" }
        val ok = engine().feedOf(page.feeds.first())
        assertTrue(ok.items.isNotEmpty())
        assertEquals(SearchEngine.State.OK, ok.statuses.single().state)
        val gnews = cfg.newsPages.first { it.id == "gnews" }.feeds.first()
        assertTrue(engine().feedOf(gnews).items.isNotEmpty())
        val youtube = cfg.newsPages.first { it.id == "youtube" }.feeds.first()
        val down = engine().feedOf(youtube)
        assertTrue(down.items.isEmpty())
        assertEquals("a feed that does not answer says so", SearchEngine.State.ERROR, down.statuses.single().state)
    }

    @Test fun everyVerticalThePagesNameCanBeQueriedWithoutError() {
        val kg = KgConfig.parse(Data.kgText)
        for (p in kg.pages.values.filter { it.kind == KgConfig.KIND_VERTICALS }) for (v in p.verticals) {
            val r = engine().query(v, "", cfg.defaultCity)
            assertEquals(v, r.vertical)
            assertEquals("every declared source of $v is reported", cfg.vertical(v)!!.sources.size, r.statuses.size)
        }
    }
}
