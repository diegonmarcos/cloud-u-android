package com.diegonmarcos.cloudsearch.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #797 the House market analysis: each series parser against an answer saved from its source on 2026-10-03. */
class MarketTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeHttp(var down: Boolean = false) : Http {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
            calls += url to headers
            if (down) throw java.io.IOException("offline")
            return when {
                "bundesbank" in url -> Http.Response(200, Fixtures.text("bbk-mortgage-rate.json"))
                "prc_hpi_q" in url -> Http.Response(200, Fixtures.text("eurostat-hpi.json"))
                "prc_hicp_minr" in url -> Http.Response(200, Fixtures.text("eurostat-rent.json"))
                else -> Http.Response(404, "")
            }
        }
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int) = Http.Response(405, "")
    }

    private var now = 5_000_000L
    private val http = FakeHttp()
    private fun engine(cfg: SearchConfig = Fixtures.cfg) = SearchEngine(cfg, http, Cache(tmp.root), { now })

    @Test fun eurostatHousePriceIndex() {
        val p = Series.parse("jsonstat", Fixtures.text("eurostat-hpi.json"))
        assertEquals(12, p.size)
        assertEquals(Series.Point("2023-Q3", 149.3), p.first())
        assertEquals(Series.Point("2026-Q2", 153.5), p.last())
        assertEquals(p.sortedBy { it.period }, p)
    }

    @Test fun eurostatRentsDropTheUnpublishedMonth() {
        val p = Series.parse("jsonstat", Fixtures.text("eurostat-rent.json"))
        // 2026-09 is in the time dimension but has no value yet: it is not a zero.
        assertEquals(24, p.size)
        assertEquals(Series.Point("2026-08", 102.45), p.last())
        assertEquals(100.28, p.first { it.period == "2025-08" }.value, 0.0)
    }

    @Test fun bundesbankSdmxJsonStringValues() {
        val p = Series.parse("sdmx_json", Fixtures.text("bbk-mortgage-rate.json"))
        assertEquals(25, p.size)
        assertEquals(Series.Point("2024-08", 3.83), p.first())
        assertEquals(Series.Point("2026-08", 4.01), p.last())
    }

    @Test fun ecbShapedSdmxJsonNumbersAndNulls() {
        val ecb = """{"dataSets":[{"series":{"0:0":{"observations":{"0":[3.5,0],"1":[null],"2":[3.75]}}}}],
            "structure":{"dimensions":{"observation":[{"id":"TIME_PERIOD","values":[{"id":"2026-06"},{"id":"2026-07"},{"id":"2026-08"}]}]}}}"""
        assertEquals(listOf(Series.Point("2026-06", 3.5), Series.Point("2026-08", 3.75)), Series.parse("sdmx_json", ecb))
    }

    @Test fun jsonStatArrayValuesAndRefusals() {
        val arr = """{"id":["geo","time"],"size":[1,3],"dimension":{"time":{"category":{"index":{"2024":0,"2025":1,"2026":2}}}},"value":[1.5,null,2.5]}"""
        assertEquals(listOf(Series.Point("2024", 1.5), Series.Point("2026", 2.5)), Series.parse("jsonstat", arr))
        val two = """{"id":["geo","time"],"size":[2,1],"dimension":{"time":{"category":{"index":{"2026":0}}}},"value":{"0":1}}"""
        assertTrue(runCatching { Series.parse("jsonstat", two) }.exceptionOrNull()!!.message!!.contains("dimension geo has 2 values"))
        val many = """{"dataSets":[{"series":{"0":{"observations":{}},"1":{"observations":{}}}}],"structure":{"dimensions":{"observation":[{"id":"TIME_PERIOD","values":[]}]}}}"""
        assertTrue(runCatching { Series.parse("sdmx_json", many) }.exceptionOrNull()!!.message!!.contains("expected one series, got 2"))
        val noTime = """{"dataSets":[{"series":{}}],"structure":{"dimensions":{"observation":[{"id":"X","values":[]}]}}}"""
        assertTrue(runCatching { Series.parse("sdmx_json", noTime) }.exceptionOrNull()!!.message!!.contains("TIME_PERIOD"))
    }

    @Test(expected = IllegalArgumentException::class) fun unknownSeriesParserFails() {
        Series.parse("csv", "")
    }

    @Test fun yearBefore() {
        assertEquals("2025-Q2", Series.yearBefore("2026-Q2"))
        assertEquals("2025-08", Series.yearBefore("2026-08"))
        assertEquals("2025", Series.yearBefore("2026"))
        assertNull(Series.yearBefore("Q2-2026"))
    }

    /** Golden values, computed by hand from the fixtures (2026-10-03). */
    @Test fun houseMarketFromTheLiveSeries() {
        val m = engine().market("house")!!
        val s = m.stats.associateBy { it.id }
        val rate = s.getValue("bbk-mortgage-rate")
        assertEquals("2026-08", rate.latest!!.period)
        assertEquals("2025-08", rate.yearAgo!!.period)
        assertEquals(0.30, rate.delta!!, 1e-9) // 4.01 % - 3.71 %, percentage points
        val hpi = s.getValue("eurostat-house-prices")
        assertEquals(153.5 / 152.6 * 100 - 100, hpi.delta!!, 1e-9) // +0.59 %
        val rent = s.getValue("eurostat-rents")
        assertEquals("2026-08", rent.latest!!.period)
        assertEquals(102.45 / 100.28 * 100 - 100, rent.delta!!, 1e-9) // +2.16 %
        assertEquals("eurostat-house-prices", m.chart!!.id)
        assertEquals(listOf("2025-Q1", "2025-Q2", "2025-Q3", "2025-Q4", "2026-Q1", "2026-Q2"), m.chartPoints.map { it.period })
        assertTrue(m.statuses.all { it.state == SearchEngine.State.OK })
        assertEquals(listOf(25, 12, 24), m.statuses.map { it.count })
        assertEquals("the declared Accept header reaches the Bundesbank", "application/vnd.sdmx.data+json;version=1.0.0",
            http.calls.first { "bundesbank" in it.first }.second["Accept"])
        val j = m.toJson()
        assertEquals(3, j.getJSONArray("stats").length())
        assertEquals(6, j.getJSONArray("chart_points").length())
        assertEquals(0.30, j.getJSONArray("stats").getJSONObject(0).getDouble("delta"), 1e-9)
    }

    @Test fun offlineMarketIsStaleThenMissing() {
        val e = engine()
        e.market("house")
        now += Fixtures.cfg.cacheTtlMinutes * 60_000L
        http.down = true
        val stale = e.market("house")!!
        assertTrue(stale.statuses.all { it.state == SearchEngine.State.STALE })
        assertEquals(4.01, stale.stats.first().latest!!.value, 0.0)
        val never = SearchEngine(Fixtures.cfg, http, Cache(tmp.newFolder()), { now }).market("house")!!
        assertTrue(never.statuses.all { it.state == SearchEngine.State.ERROR })
        assertTrue(never.stats.all { it.latest == null && it.delta == null })
        assertTrue(never.chartPoints.isEmpty())
        assertTrue(never.toJson().getJSONArray("stats").getJSONObject(0).isNull("value"))
    }

    @Test fun anUnreadableSeriesSaysSo() {
        val j = Fixtures.searchJson()
        j.getJSONObject("series").getJSONObject("eurostat-rents").put("parser", "sdmx_json")
        val m = engine(SearchConfig.parse(j)).market("house")!!
        val st = m.statuses.first { it.id == "eurostat-rents" }
        assertEquals(SearchEngine.State.ERROR, st.state)
        assertTrue(st.detail.startsWith("unreadable answer"))
        assertNull(m.stats.first { it.id == "eurostat-rents" }.latest)
    }

    @Test fun onlyMarketVerticalsHaveAMarket() {
        assertNull(engine().market("jobs"))
        assertNull(engine().market("nope"))
        assertTrue(http.calls.isEmpty())
    }

    @Test fun aSeriesWithoutAYearBeforeHasNoDelta() {
        val st = Market.stat(Fixtures.cfg.series.getValue("eurostat-house-prices"), listOf(Series.Point("2026-Q2", 150.0)))
        assertNull(st.delta)
        assertNull(Market.stat(Fixtures.cfg.series.getValue("eurostat-house-prices"), emptyList()).delta)
    }

    private fun problemsAfter(edit: (org.json.JSONObject) -> Unit): String {
        val j = Fixtures.searchJson()
        edit(j)
        return runCatching { SearchConfig.parse(j) }.exceptionOrNull()?.message ?: ""
    }

    @Test fun marketDeclarationsAreChecked() {
        val house = { j: org.json.JSONObject -> j.getJSONArray("verticals").getJSONObject(0) }
        assertTrue(problemsAfter { house(it).getJSONArray("series").put("ghost") }.contains("names series ghost"))
        assertTrue(problemsAfter { house(it).put("series", JSONArray()) }.contains("market analysis but no series"))
        assertTrue(problemsAfter { house(it).put("chart", "bbk") }.contains("charts bbk"))
        assertTrue(problemsAfter { house(it).put("chart_points", 1) }.contains("chart_points must be at least 2"))
        assertTrue(problemsAfter { house(it).put("analysis", "vibes") }.contains("analysis vibes is none of"))
        assertTrue(problemsAfter { it.getJSONObject("series").getJSONObject("eurostat-rents").put("change", "ratio") }.contains("series eurostat-rents change ratio"))
        assertEquals("", problemsAfter { house(it).put("analysis", "none") })
    }
}
