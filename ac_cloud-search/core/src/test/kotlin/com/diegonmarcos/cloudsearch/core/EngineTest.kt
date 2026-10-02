package com.diegonmarcos.cloudsearch.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

class EngineTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Answers each declared URL by the source it belongs to, from the saved responses; records every call. */
    private class FakeHttp(var down: Boolean = false) : Http {
        val calls = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
            calls += url
            if (down) throw java.io.IOException("offline")
            return when {
                "arbeitsagentur" in url -> Http.Response(200, Fixtures.text("ba-jobsuche.json"))
                "arbeitnow" in url -> Http.Response(200, Fixtures.text("arbeitnow.json"))
                "prices.openfoodfacts" in url -> Http.Response(200, Fixtures.text("open-prices.json"))
                "search.openfoodfacts" in url -> Http.Response(200, Fixtures.text("off-search.json"))
                "tagesschau" in url -> Http.Response(200, Fixtures.text("tagesschau-verbraucher.xml"))
                else -> Http.Response(404, "")
            }
        }
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int) = Http.Response(405, "")
    }

    private var now = 1_000_000L
    private val http = FakeHttp()
    private fun engine(cfg: SearchConfig = Fixtures.cfg) = SearchEngine(cfg, http, Cache(tmp.root), { now })

    @Test fun jobsQueryMergesApiSourcesAndReportsEveryOther() {
        val r = engine().query("jobs", "", "berlin")
        val st = r.statuses.associateBy { it.id }
        assertEquals(SearchEngine.State.OK, st.getValue("ba-jobsuche").state)
        assertEquals(4, st.getValue("ba-jobsuche").count)
        // Arbeitnow cannot filter by place: Berlin + the remote Munich job, never Düsseldorf.
        assertEquals(5, st.getValue("arbeitnow").count)
        assertTrue(r.listings.none { it.location == "Düsseldorf" })
        assertEquals(SearchEngine.State.LINK, st.getValue("stepstone").state)
        assertEquals("https://www.stepstone.de/jobs//in-Berlin", st.getValue("stepstone").link)
        assertEquals(9, r.listings.size)
        assertTrue("the BA key header is sent", http.calls.any { "wo=Berlin" in it })
    }

    @Test fun queryTermIsEncodedAndAppliedLocallyWhereTheApiCannot() {
        val r = engine().query("jobs", "marketing manager", "berlin")
        assertTrue(http.calls.any { "was=marketing%20manager" in it })
        val an = r.listings.filter { it.source == "arbeitnow" }.map { it.id }
        assertEquals(listOf("digital-marketing-manager-automotive-performance-berlin-194420", "product-marketing-manager-berlin-475869"), an)
    }

    @Test fun freshAnswersComeFromTheCacheStaleOnesWhenOffline() {
        val e = engine()
        e.query("groceries", "", "berlin")
        val asked = http.calls.size
        val again = e.query("groceries", "", "berlin")
        assertEquals("a fresh cache entry is not refetched", asked, http.calls.size)
        assertEquals(SearchEngine.State.CACHED, again.statuses.first { it.id == "open-prices" }.state)
        now += Fixtures.cfg.cacheTtlMinutes * 60_000L
        http.down = true
        val offline = e.query("groceries", "", "berlin")
        val op = offline.statuses.first { it.id == "open-prices" }
        assertEquals(SearchEngine.State.STALE, op.state)
        assertEquals(6, op.count)
        assertEquals(1_000_000L, op.at)
        assertTrue(op.detail.contains("offline"))
        assertEquals(6, offline.listings.size)
    }

    @Test fun neverFetchedAndOfflineIsAnError() {
        http.down = true
        val st = engine().query("groceries", "milch", "berlin").statuses.first { it.id == "open-prices" }
        assertEquals(SearchEngine.State.ERROR, st.state)
        assertEquals(0, st.count)
    }

    @Test fun httpErrorIsReported() {
        val cfgJson = Fixtures.searchJson()
        cfgJson.getJSONObject("sources").getJSONObject("open-prices").put("url", "https://nowhere.example/x")
        val st = engine(SearchConfig.parse(cfgJson)).query("groceries", "", "berlin").statuses.first { it.id == "open-prices" }
        assertEquals(SearchEngine.State.ERROR, st.state)
        assertEquals("HTTP 404", st.detail)
    }

    @Test fun querySourcesWaitForATermAndDisabledOnesSayWhy() {
        val r = engine().query("groceries", "", "berlin")
        assertEquals(SearchEngine.State.SKIPPED, r.statuses.first { it.id == "open-food-facts" }.state)
        val house = engine().query("house", "Kreuzberg", "berlin").statuses.associateBy { it.id }
        val scr = house.getValue("scrappers-api")
        assertEquals(SearchEngine.State.DISABLED, scr.state)
        assertTrue(scr.detail.isNotBlank())
        assertEquals("https://duckduckgo.com/?q=site%3Aimmobilienscout24.de+Kreuzberg+Berlin", house.getValue("immoscout24").link)
    }

    @Test fun groceriesWithATermAskBothApis() {
        val r = engine().query("groceries", "hafermilch", "berlin")
        assertEquals(SearchEngine.State.OK, r.statuses.first { it.id == "open-food-facts" }.state)
        assertEquals(4, r.statuses.first { it.id == "open-food-facts" }.count)
        assertEquals(0, r.statuses.first { it.id == "open-prices" }.count)
        assertTrue(http.calls.any { "lat=52.52&lon=13.405&radius_km=30" in it })
    }

    @Test fun feedKeepsKeywordItemsNewestFirst() {
        val j = Fixtures.searchJson()
        j.getJSONArray("verticals").getJSONObject(0).put("feed_keywords", org.json.JSONArray().put("tankrabatt"))
        val f = engine(SearchConfig.parse(j)).feed("house")
        assertEquals(12, f.fetched) // two feeds, the same saved answer
        assertEquals(3, f.items.size)
        assertTrue(f.items.zipWithNext().all { (x, y) -> (x.date ?: 0) >= (y.date ?: 0) })
    }

    @Test fun jobsAnalysisFromTheLiveSources() {
        val a = engine().analysis("jobs", "", "berlin")!!
        assertEquals(189, a.total)
        assertEquals(9, a.sample)
        assertEquals(5, a.remoteSample)
        assertEquals(0.4, a.remoteShare!!, 1e-9)
        assertEquals(13.9, a.medianHourly!!, 1e-9)
        assertEquals(2, a.hourlySample)
        assertNull(a.medianYearly)
        assertEquals(Analysis.Bar("Softwareentwicklung und Programmierung", 101), a.fields.first())
        assertEquals(Analysis.TOP_FIELDS, a.fields.size)
        assertNull(engine().analysis("house", "", "berlin"))
        assertNotNull(a.toJson().getJSONArray("fields"))
    }

    @Test fun medianOddEvenEmpty() {
        assertNull(Analysis.median(emptyList()))
        assertEquals(2.0, Analysis.median(listOf(3.0, 1.0, 2.0))!!, 0.0)
        assertEquals(2.5, Analysis.median(listOf(4.0, 1.0, 2.0, 3.0))!!, 0.0)
    }

    @Test fun templatesEncodeEveryPlaceholder() {
        val c = Fixtures.cfg.city("cologne")
        assertEquals("q=a%20%26%20b&c=K%C3%B6ln&p=50.938,6.96&r=25", Templates.fill("q={q}&c={city}&p={lat},{lon}&r={radius}", "a & b", c))
    }

    @Test fun statusJsonCarriesEveryField() {
        val st = engine().query("jobs", "", "berlin").statuses
        val j = engine().statusJson(st)
        assertEquals(st.size, j.length())
        assertEquals("ok", j.getJSONObject(0).getString("state"))
    }

    @Test fun cacheSurvivesANewEngine() {
        engine().query("groceries", "", "berlin")
        http.down = true
        now += 1
        assertEquals(SearchEngine.State.CACHED, engine().query("groceries", "", "berlin").statuses.first { it.id == "open-prices" }.state)
        assertNull(Cache(tmp.newFolder()).get("nothing"))
    }

    @Test fun unfetchedNeedsNoNetwork() {
        http.down = true
        val st = engine().unfetched("house", " Mitte ", "berlin").associateBy { it.id }
        assertEquals(setOf("immoscout24", "immowelt", "kleinanzeigen-immo", "wg-gesucht", "scrappers-api"), st.keys)
        assertEquals(SearchEngine.State.DISABLED, st.getValue("scrappers-api").state)
        assertEquals("https://duckduckgo.com/?q=site%3Aimmowelt.de+Mitte+Berlin", st.getValue("immowelt").link)
        assertTrue(http.calls.isEmpty())
        assertTrue(engine().unfetched("nope", "", null).isEmpty())
    }

    /** UrlHttp against a real local server: method, headers, body, error bodies. */
    @Test fun urlHttpTalksHttp() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val got = ex.requestMethod + "|" + ex.requestHeaders.getFirst("X-Test") + "|" + ex.requestHeaders.getFirst("User-Agent") + "|" +
                ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val code = if (ex.requestURI.path == "/fail") 503 else 200
            val bytes = got.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val h = UrlHttp("UA/1")
            assertEquals(Http.Response(200, "GET|x|UA/1|"), h.get("$base/ok", mapOf("X-Test" to "x"), 5000))
            assertEquals(Http.Response(200, "POST|y|UA/1|{\"a\":1}"), h.post("$base/ok", mapOf("X-Test" to "y"), "{\"a\":1}", 5000))
            assertEquals(503, h.get("$base/fail", emptyMap(), 5000).code)
        } finally {
            server.stop(0)
        }
    }
}
