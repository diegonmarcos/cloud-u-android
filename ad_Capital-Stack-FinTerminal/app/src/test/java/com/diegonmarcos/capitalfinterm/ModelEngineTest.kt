package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** The model panels end to end on the real declarations and a fake network: the container first, then the panel's own source, then the fallbacks. */
class ModelEngineTest {
    private val sources = Catalog.parse(DataFiles.sourcesJson)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private val today = LocalDate.of(2026, 10, 30)
    private val cloud = sources.first { it.primary }
    private val api = ApiRoute(cloud, "http://mesh-base")

    private class Fake(val answer: (String) -> String?) : Http {
        val asked: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun get(url: String): String {
            asked += url
            return answer(url) ?: throw IOException("no route")
        }

        override fun post(url: String): String = throw IOException("no post")
    }

    private fun dash(id: String) = Dashboards.byId(dashboards, id)!!
    private fun src(id: String) = sources.first { it.id == id }
    private fun load(d: String, p: String, http: Http, api: ApiRoute? = null, user: UserLists = UserLists(), now: String = "2026-10-30T12:00Z"): PanelData {
        val dash = dash(d)
        val panel = dash.panels.first { it.id == p }
        return Engine.load(panel, src(panel.source), http, today, model = ModelCtx(sources, dash, api, user, now))
    }

    private val ff = """[{"title":"Non-Farm Employment Change","country":"USD","date":"2026-11-06T08:30:00-05:00","impact":"High","forecast":"180K","previous":"254K"},
        {"title":"CPI Flash Estimate y/y","country":"EUR","date":"2026-10-31T11:00:00+01:00","impact":"High","forecast":"2.1%","previous":"2.2%"},
        {"title":"Retail Sales m/m","country":"AUD","date":"2026-10-30T11:30:00+11:00","impact":"Low","forecast":"0.2%","previous":"0.3%"},
        {"title":"Old event","country":"USD","date":"2026-10-20T08:30:00-04:00","impact":"High","forecast":"","previous":""}]"""
    private val cb = """{"banks":[{"id":"fed","country":"US","events":[{"date":"2026-11-05","ts":"2026-11-05T19:00Z","title":"Fed: rate decision"}]}]}"""

    @Test fun theContainerIsAskedFirstAndNothingElseWhenItAnswers() {
        val http = Fake { u -> if (u.startsWith("http://mesh-base/v1/calendar/economic")) """{"events":[{"ts":"2026-10-31T10:00Z","country":"EA","title":"CPI","importance":3}]}""" else null }
        val d = load("economic_calendar", "week", http, api)
        assertEquals(1, http.asked.size)
        assertTrue(http.asked[0].contains("days=10"))
        assertEquals(cloud.name, d.servedBy)
        assertEquals(listOf("2026-10-31 10:00", "EA", "CPI", "●●●", "", "", ""), d.rows.single())
    }

    @Test fun withoutTheContainerTheCalendarMergesEveryPublicRouteInTimeOrder() {
        val http = Fake { u ->
            when {
                u.endsWith("/ff_calendar_thisweek.json") -> ff
                u.endsWith("/ff_calendar_nextweek.json") -> null
                u.endsWith("/bls.ics") -> "BEGIN:VEVENT\nDTSTART;TZID=US-Eastern:20261104T083000\nSUMMARY:Consumer Price Index\nEND:VEVENT\n"
                u == "asset:central-banks.json" -> cb
                else -> null
            }
        }
        val d = load("economic_calendar", "week", http)
        assertFalse("no container without a route", http.asked.any { it.contains("mesh") })
        assertEquals(listOf("2026-10-30 00:30", "2026-10-31 10:00", "2026-11-04 13:30", "2026-11-05 19:00", "2026-11-06 13:30"), d.rows.map { it[0] })
        assertTrue("the event outside the window is dropped", d.rows.none { it[2] == "Old event" })
        assertEquals(listOf("Forex Factory weekly calendar (Fair Economy)", "BLS release schedule (iCalendar)", "Central bank meeting calendar (declared)").joinToString(" + "), d.servedBy)
        assertEquals("●○○", d.rows[0][3])
    }

    @Test fun theImportanceFilterAndWindowAreTheDeclaredOnes() {
        val http = Fake { u -> if (u.endsWith("/ff_calendar_thisweek.json")) ff else if (u.startsWith("asset:")) cb else null }
        val d = load("market_monitor", "next", http)
        // next 48 hours, importance 3 only: the EUR flash (31 Oct); NFP (6 Nov) and the Fed (5 Nov) are beyond, the AUD print is Low
        assertEquals(listOf("CPI Flash Estimate y/y"), d.rows.map { it[2] })
    }

    @Test fun anEmptyWindowSaysSoInsteadOfFailing() {
        val d = load("market_monitor", "next", Fake { u -> if (u.endsWith("/ff_calendar_thisweek.json")) "[]" else null })
        assertTrue(d.rows.isEmpty())
        assertEquals("No event in this window.", d.note)
    }

    @Test fun theFirstRouteThatAnswersWinsWhenNotMerging() {
        val xml = "<m:properties><d:NEW_DATE m:type=\"Edm.DateTime\">2026-10-29T00:00:00</d:NEW_DATE><d:BC_1MONTH m:type=\"Edm.Double\">4.0</d:BC_1MONTH><d:BC_2YEAR m:type=\"Edm.Double\">3.50</d:BC_2YEAR><d:BC_10YEAR m:type=\"Edm.Double\">4.10</d:BC_10YEAR><d:BC_30YEAR m:type=\"Edm.Double\">4.70</d:BC_30YEAR></m:properties>" +
            "<m:properties><d:NEW_DATE m:type=\"Edm.DateTime\">2026-10-30T00:00:00</d:NEW_DATE><d:BC_1MONTH m:type=\"Edm.Double\">4.0</d:BC_1MONTH><d:BC_2YEAR m:type=\"Edm.Double\">3.52</d:BC_2YEAR><d:BC_10YEAR m:type=\"Edm.Double\">4.15</d:BC_10YEAR><d:BC_30YEAR m:type=\"Edm.Double\">4.71</d:BC_30YEAR></m:properties>"
        val http = Fake { u -> if (u.contains("field_tdr_date_value_month=202610")) xml else null }
        val d = load("market_monitor", "ust", http)
        assertEquals(1, http.asked.size)
        assertEquals("", d.servedBy)
        assertEquals(listOf("UST 2Y", "UST 10Y", "UST 30Y"), d.rows.map { it[0] })
        assertEquals(listOf("UST 2Y", "3.52", "+2.0", "-", "2026-10-30"), d.rows[0])
        // the month just started: this month's file is empty, the declared fallback (last month) serves it and is named
        val early = Fake { u -> if (u.contains("field_tdr_date_value_month=202609")) xml else if (u.contains("202610")) "<feed/>" else null }
        val e = load("market_monitor", "ust", early)
        assertEquals(2, early.asked.size)
        assertEquals("", e.servedBy)
        assertEquals(3, e.rows.size)
    }

    @Test fun aCurveIsPlacedByTheDeclaredYearsAndSorted() {
        val xml = "<m:properties><d:NEW_DATE m:type=\"Edm.DateTime\">2026-10-30T00:00:00</d:NEW_DATE><d:BC_30YEAR m:type=\"Edm.Double\">4.7</d:BC_30YEAR><d:BC_1MONTH m:type=\"Edm.Double\">4.0</d:BC_1MONTH><d:BC_10YEAR m:type=\"Edm.Double\">4.1</d:BC_10YEAR><d:BC_1_5MONTH m:type=\"Edm.Double\">4.05</d:BC_1_5MONTH></m:properties>"
        val d = load("yield_curves", "ust_official", Fake { xml })
        assertEquals(listOf("1M", "10Y", "30Y"), d.chart.map { it.label })
        assertEquals(listOf(0.083333, 10.0, 30.0), d.chart.map { it.x })
        assertEquals("4.70", d.rows.last()[1])
        assertEquals("-", d.rows.last()[2])
    }

    @Test fun onePerItemRequestAndAFailingItemIsSkipped() {
        val http = Fake { u ->
            when {
                u.contains("/products/BTC-USD/stats") -> """{"open":"100","last":"110"}"""
                u.contains("/products/ETH-USD/stats") -> """{"open":"10","last":"9"}"""
                else -> null
            }
        }
        val d = load("market_monitor", "crypto", http)
        assertEquals(3, http.asked.size)
        assertEquals(listOf("Bitcoin", "Ether"), d.rows.map { it[0] })
        assertEquals(listOf("Bitcoin", "110.00", "+10.0000", "+10.00", "24h"), d.rows[0])
        assertEquals(-10.0, d.chart[1].y, 1e-9)
    }

    @Test fun everyItemFailingIsAnError() {
        try {
            load("market_monitor", "crypto", Fake { null })
            fail("expected no data")
        } catch (e: IOException) {
            assertEquals("no data", e.message)
        }
    }

    @Test fun theWatchlistReplacesTheDeclaredSymbolsAndPriceAlertsFire() {
        val http = Fake { u -> Regex("/quotes/([^/]+)\\.json$").find(u)?.groupValues?.get(1)?.let { s -> """{"data":{"symbol":"$s","current_price":${if (s == "NVDA") 150 else 50},"prev_day_close":40}}""" } }
        val user = UserLists(listOf("NVDA", "TSLA"), listOf(Alerts.rule("NVDA > 120")!!, Alerts.rule("TSLA < 10")!!))
        val d = load("watchlist", "quotes", http, user = user)
        assertEquals(listOf("NVDA", "TSLA"), d.rows.map { it[0] })
        assertTrue(http.asked.none { it.contains("AAPL") })
        assertEquals(listOf("ALERT NVDA 150.00 > 120.00"), d.alerts)
        // the container gets the same list in one request
        val c = Fake { u -> if (u.startsWith("http://mesh-base/v1/quotes")) """{"quotes":[{"symbol":"NVDA","price":151,"prev_close":150}]}""" else null }
        load("watchlist", "quotes", c, api, user)
        assertEquals(listOf("http://mesh-base/v1/quotes?symbols=NVDA%2CTSLA"), c.asked)
    }

    @Test fun eventAlertsFlagTheNextDayAtTheRuleLevel() {
        val http = Fake { u -> if (u.endsWith("/ff_calendar_thisweek.json")) ff else if (u.startsWith("asset:")) cb else null }
        val d = load("watchlist", "events", http, user = UserLists(alerts = listOf(Alerts.rule("events >= 3")!!)), now = "2026-10-30T12:00Z")
        assertEquals(listOf("ALERT 2026-10-31 10:00Z EA CPI Flash Estimate y/y"), d.alerts)
        assertTrue(load("watchlist", "events", http).alerts.isEmpty())
    }

    @Test fun arrayParamsRepeatTheKeyAndLabelsSelect() {
        val http = Fake { u ->
            """{"id":["geo","time"],"size":[2,2],"dimension":{"geo":{"category":{"index":{"EA":0,"XX":1}}},"time":{"category":{"index":{"2026-08":0,"2026-09":1}}}},"value":{"0":2.0,"1":2.1,"2":9,"3":9}}"""
        }
        val d = load("economic_calendar", "ea", http)
        assertTrue(http.asked.single().contains("geo=EA") && http.asked.single().contains("coicop18=TOTAL"))
        assertEquals(listOf(listOf("Euro area HICP", "2.10", "2.00", "2026-09")), d.rows)
    }

    @Test fun theApiRouteNeedsTheV1PathTheMeshAndAnAnswer() {
        val d = dash("market_monitor")
        val p = d.panels.first { it.id == "fx" }
        val mesh = mapOf(cloud.fleetEndpoint to "http://mesh-base")
        assertEquals("http://mesh-base", Priority.api(p, d, sources, mesh) { true }!!.base)
        assertNull(Priority.api(p, d, sources, mesh) { false })
        assertNull(Priority.api(p, d, sources, emptyMap()) { true })
        val fred = dash("yield_curves").panels.first { it.id == "ust" }
        assertNull("a topic panel has no /v1 route", Priority.api(fred, dash("yield_curves"), sources, mesh) { true })
    }

    @Test fun aRateLimitedSourceIsKeptForItsDeclaredScheduleEvenThroughARefresh() {
        var now = 0L
        val inner = Fake { u -> "body-$now-$u" }
        val ff = src("forexfactory")
        assertEquals(900, ff.ttlS)
        val c = CachedHttp(inner, 60_000L, { u -> if (u.startsWith(ff.endpoint)) ff.ttlS * 1000L else null }) { now }
        val feed = ff.endpoint + "/ff_calendar_thisweek.json"
        c.get(feed); c.get("https://other/x")
        now = 120_000L
        c.get(feed); c.get("https://other/x")
        assertEquals("the feed is asked once in two minutes, the other source twice", 3, inner.asked.size)
        c.clear()
        c.get(feed)
        assertEquals("a refresh does not re-ask a feed inside its schedule", 3, inner.asked.size)
        now = 901_000L
        c.get(feed)
        assertEquals(4, inner.asked.size)
    }

    @Test fun aFailingContainerFallsBackToThePublicSource() {
        val http = Fake { u -> if (u.startsWith("https://api.frankfurter.dev")) """{"base":"USD","rates":{"2026-10-29":{"EUR":0.89},"2026-10-30":{"EUR":0.90}}}""" else null }
        val d = load("market_monitor", "fx", http, api)
        assertTrue(http.asked[0].startsWith("http://mesh-base/v1/fx/spot"))
        assertTrue(http.asked[1].contains("/v1/2026-10-23.."))
        assertEquals("", d.servedBy)
        assertEquals(listOf("EUR per USD", "0.90000", "+0.01000", "+1.12", "2026-10-30"), d.rows.single())
    }
}
