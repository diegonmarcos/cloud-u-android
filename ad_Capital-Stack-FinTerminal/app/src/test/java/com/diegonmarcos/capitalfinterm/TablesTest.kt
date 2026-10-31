package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** The per-item tables (Tables.kt) on the real declarations and a fake network, plus the pure maths each one rests on. */
class TablesTest {
    private val sources = Catalog.parse(DataFiles.sourcesJson)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private val today = LocalDate.of(2026, 10, 30)
    private val api = ApiRoute(sources.first { it.primary }, "http://mesh-base")

    private class Fake(val answer: (String) -> String?) : Http {
        val asked: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun get(url: String): String {
            asked += url
            return answer(url) ?: throw IOException("no route")
        }

        override fun post(url: String): String = throw IOException("no post")
    }

    private fun load(d: String, p: String, http: Http, api: ApiRoute? = this.api): PanelData {
        val dash = Dashboards.byId(dashboards, d)!!
        val panel = dash.panels.first { it.id == p }
        return Engine.load(panel, sources.first { it.id == panel.source }, http, today, model = ModelCtx(sources, dash, api, UserLists(), "2026-10-30T12:00Z"))
    }

    private fun v1Bars(vararg b: Pair<String, Double>) = """{"bars":[${b.joinToString(",") { """{"t":"${it.first}","c":${it.second}}""" }}]}"""
    private fun cboeBars(vararg b: Pair<String, Double>) = """{"data":[${b.joinToString(",") { """{"date":"${it.first}","open":${it.second},"high":${it.second},"low":${it.second},"close":${it.second}}""" }}]}"""
    private fun d(s: String) = LocalDate.parse(s)

    // ------------------------------------------------------------------ performance

    @Test fun performanceRowsComeFromTheContainerThroughPerformanceRow() {
        val http = Fake { u -> if (u.startsWith("http://mesh-base/v1/history?symbol=XLK")) v1Bars("2025-10-30" to 100.0, "2026-10-30" to 110.0) else null }
        val data = load("eq_dm_us", "sectors", http)
        val xlk = data.rows.first { it[0] == "Technology (XLK)" }
        assertEquals(listOf("Name", "Last") + Performance.COLUMNS, data.columns)
        assertEquals("+10.00%", xlk[2 + Performance.COLUMNS.indexOf("1Y")])
        assertEquals(FinFormat.DASH, xlk[2 + Performance.COLUMNS.indexOf("10Y")])
        assertTrue(data.note, data.note.contains("Technology (XLK) ") && data.note.contains("history from 2025-10-30"))
        // an item nobody answers is a row of dashes with its reason in the note, never dropped
        val xlf = data.rows.first { it[0] == "Financials (XLF)" }
        assertTrue(xlf.drop(1).all { it == FinFormat.DASH })
        assertTrue(data.note, data.note.contains("Financials (XLF): ") && data.note.contains("no answer"))
        assertEquals(12, data.rows.size)
    }

    @Test fun whenTheMeshIsDownTheDirectSourceAnswersUnderTheMappedSymbol() {
        val http = Fake { u -> if (u.endsWith("/charts/historical/EWG.json")) cboeBars("2026-09-30" to 50.0, "2026-10-30" to 55.0) else null }
        val data = load("world_equity_indices", "global", http, api = null)
        val dax = data.rows.first { it[0] == "DAX" }
        assertEquals(Engine.sig(55.0), dax[1])
        assertEquals("+10.00%", dax[2 + Performance.COLUMNS.indexOf("MTD")])
        assertTrue(http.asked.none { it.contains("mesh-base") })
        assertEquals(32, data.rows.size)
        // the energy item with no direct proxy is never asked of Cboe
        val e = Fake { null }
        load("commodities", "energy_perf", e, api = null)
        assertFalse(e.asked.toString(), e.asked.any { it.contains("HO=F") })
        assertTrue(e.asked.any { it.endsWith("/charts/historical/USO.json") })
    }

    @Test fun trailingYearsAndRiskColumns() {
        val s = listOf(d("2000-10-30") to 100.0, d("2025-10-30") to 400.0, d("2026-10-30") to 400.0)
        assertEquals(Math.pow(4.0, 1.0 / 25) - 1, Performance.trailing(s, 25)!!, 1e-12)
        assertNull(Performance.trailing(s, 30))
        assertEquals(-0.25, Tables.maxDrawdown1y(listOf(d("2026-01-01") to 100.0, d("2026-02-01") to 120.0, d("2026-03-01") to 90.0, d("2026-04-01") to 100.0))!!, 1e-12)
        assertEquals(0.0, Tables.maxDrawdown1y(listOf(d("2026-01-01") to 100.0, d("2026-02-01") to 120.0))!!, 0.0)
        val flat = (0 until 30).map { d("2026-01-01").plusDays(it.toLong()) to 100.0 }
        assertEquals(0.0, Tables.vol1y(flat)!!, 1e-12)
        assertNull(Tables.vol1y(flat.take(10)))
        assertEquals(Performance.trailing(s, 25), Tables.column("25Y", s, Performance.row(s)))
    }

    // ------------------------------------------------------------------ model portfolio

    @Test fun portfolioIsRebalancedDailyNotBoughtAndHeld() {
        val a = listOf(d("2026-01-01") to 100.0, d("2026-01-02") to 110.0, d("2026-01-03") to 121.0)
        val b = listOf(d("2026-01-01") to 100.0, d("2026-01-02") to 100.0, d("2026-01-03") to 100.0)
        val v = Tables.rebalanced(listOf(60.0 to a, 40.0 to b))
        assertEquals(106.0, v[1].second, 1e-9)
        assertEquals(106.0 * (0.6 * 1.1 + 0.4), v[2].second, 1e-9) // buy and hold would be 112.6
        // only the dates every leg has
        assertEquals(2, Tables.rebalanced(listOf(50.0 to a, 50.0 to b.drop(1))).size)
        assertTrue(Tables.rebalanced(listOf(50.0 to a, 50.0 to emptyList())).isEmpty())
    }

    @Test fun modelPortfolioPerformanceRowsArePortfolioThenLegs() {
        val http = Fake { u ->
            when {
                u.contains("symbol=AGG") -> v1Bars("2026-10-29" to 100.0, "2026-10-30" to 101.0)
                u.contains("symbol=ACWI") -> v1Bars("2026-10-29" to 100.0, "2026-10-30" to 110.0)
                else -> null
            }
        }
        val data = load("mp_60_40", "perf", http)
        assertEquals(listOf("Portfolio 60/40", "Bonds 60% (AGG)", "Equity 40% (ACWI)"), data.rows.map { it[0] })
        assertEquals(Engine.sig(100.0 * (0.6 * 1.01 + 0.4 * 1.10)), data.rows[0][1])
        assertEquals(listOf("Name", "Last") + Performance.COLUMNS + listOf(Tables.COL_VOL, Tables.COL_MDD), data.columns)
    }

    // ------------------------------------------------------------------ yields matrix

    @Test fun averagesAreGdpWeightedOverTheCountriesWithAValue() {
        assertEquals((30507 * 4.0 + 4744 * 2.0) / (30507 + 4744), Tables.weighted(listOf(30507.0 to 4.0, 4744.0 to 2.0))!!, 1e-12)
        assertEquals(2.0, Tables.weighted(listOf(30507.0 to null, 4744.0 to 2.0))!!, 1e-12)
        assertNull(Tables.weighted(listOf(1.0 to null)))
    }

    @Test fun spotCellsAreExactTenorsAndForwardCellsComeFromForward() {
        val c = listOf(1.0 to 3.0, 2.0 to 4.0, 10.0 to 5.0)
        assertEquals(4.0, Tables.cell(c, 2.0, false, 1.0)!!, 0.0)
        assertNull(Tables.cell(c, 5.0, false, 1.0))
        assertEquals(Forward.rate(c, 1.0, 2.0), Tables.cell(c, 2.0, true, 1.0)!!, 1e-12)
        assertNull(Tables.cell(emptyList(), 2.0, true, 1.0))
    }

    @Test fun theMatrixHasAveragesThenEveryCountryAndSaysWhyACellIsEmpty() {
        val curve = { y: Double -> """{"asof":"2026-10-29","points":[{"key":"3M","label":"3M","tenor_years":0.25,"value":$y},{"key":"10Y","label":"10Y","tenor_years":10,"value":${y + 1}}]}""" }
        val http = Fake { u ->
            when {
                u == "http://mesh-base/v1/curves/US" -> curve(4.0)
                u == "http://mesh-base/v1/curves/DE" -> curve(2.0)
                u == "http://mesh-base/v1/curves/BR" -> curve(10.0)
                else -> null
            }
        }
        val data = load("yields_matrix", "spot", http)
        assertEquals(listOf("Country", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y", "20Y", "30Y"), data.columns)
        assertEquals(listOf("World avg", "DM avg", "EM avg"), data.rows.take(3).map { it[0] })
        assertEquals(25, data.rows.size)
        val dm = (30507 * 4.0 + 4744 * 2.0) / (30507 + 4744)
        assertEquals(String.format(java.util.Locale.US, "%.2f", dm), data.rows[1][1])
        assertEquals("10.00", data.rows[2][1])
        assertEquals(String.format(java.util.Locale.US, "%.2f", (30507 * 4.0 + 4744 * 2.0 + 2126 * 10.0) / (30507 + 4744 + 2126)), data.rows[0][1])
        assertEquals(FinFormat.DASH, data.rows.first { it[0] == "United States" }[2])
        assertTrue(data.rows.first { it[0] == "China" }.drop(1).all { it == FinFormat.DASH })
        assertTrue(data.note, data.note.contains("no curve: ") && data.note.contains("CN ("))
        assertTrue(data.note, data.note.contains("US 6M/1Y/2Y/3Y/5Y/7Y/20Y/30Y"))
        val fwd = load("yields_matrix", "fwd", http)
        val us = listOf(0.25 to 4.0, 10.0 to 5.0)
        assertEquals(String.format(java.util.Locale.US, "%.2f", Forward.rate(us, 1.0, 10.0)), fwd.rows.first { it[0] == "United States" }[8])
        assertTrue(fwd.note.startsWith("1y-forward"))
    }

    @Test fun theDeclaredMatrixIsTheOwnersCountryList() {
        val f = Dashboards.byId(dashboards, "yields_matrix")!!.panels.first { it.id == "spot" }.fetch!!
        val g = f.getJSONObject("groups")
        val dm = (0 until g.getJSONArray("DM").length()).map { g.getJSONArray("DM").getString(it) }
        assertEquals(listOf("US", "DE", "FR", "IT", "ES", "GB", "JP", "CA", "AU", "CH", "NL", "SE"), dm)
        assertEquals(10, g.getJSONArray("EM").length())
        assertEquals("3M", Tables.tenors(f).first().label)
        assertEquals("30Y", Tables.tenors(f).last().label)
    }
}
