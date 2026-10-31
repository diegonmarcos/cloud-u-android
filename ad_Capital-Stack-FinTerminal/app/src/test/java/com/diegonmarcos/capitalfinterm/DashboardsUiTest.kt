package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI spec 3 / 6.3 declarations: each tab's default dashboard, panels drawn in order with key rows last, source segments in one card. */
class DashboardsUiTest {
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private fun d(id: String) = Dashboards.byId(dashboards, id)!!

    @Test fun eachTabOpensOnItsDeclaredDefault() {
        assertEquals("market_monitor", Dashboards.defaultOf(dashboards, listOf("yield_curves", "market_monitor")))
        assertEquals("yield_curves", Dashboards.defaultOf(dashboards, listOf("yield_curves", "forward_rates")))
        for ((tab, id) in mapOf("fi" to "market_monitor", "eq" to "world_equity_indices", "ex" to "fx", "nw" to "economic_calendar", "pm" to "portfolio_risk")) {
            assertEquals(tab, listOf(id), dashboards.filter { it.tab == tab && it.isDefault }.map { it.id })
        }
    }

    @Test fun keyRowsComeLastAndASegmentIsDrawnInsideItsCard() {
        val yc = d("yield_curves")
        assertEquals(listOf("ust", "euro", "sovereigns"), Dashboards.drawOrder(yc).map { it.id })
        assertEquals(listOf("ust", "ust_official"), Dashboards.segments(yc, yc.panels.first { it.id == "ust" }).map { it.id })
        val fx = d("fx")
        val order = Dashboards.drawOrder(fx)
        assertTrue(order.takeLastWhile { it.isPlaceholder }.isNotEmpty())
        assertTrue(order.dropLastWhile { it.isPlaceholder }.none { it.isPlaceholder })
    }

    @Test fun aKeyRowDeclaredFirstIsStillDrawnLast() {
        val d = Dashboards.parse("""{"dashboards":[{"id":"x","entry":"X","tab":"fi","panels":[
            {"id":"k","title":"K","source":"s","view":"table","placeholder":{"reason":"needs a key"}},
            {"id":"a","title":"A","source":"s","view":"table","fetch":{"provider":"p"}},
            {"id":"b","title":"B","source":"s","view":"table","fetch":{"provider":"p"},"segment_of":"a"}]}]}""").single()
        assertEquals(listOf("a", "k"), Dashboards.drawOrder(d).map { it.id })
        assertEquals(listOf("a", "b"), Dashboards.segments(d, d.panels[1]).map { it.id })
    }

    @Test fun marketMonitorLeadsWithTheTreasuryTilesAndTheDerivedSpread() {
        val mm = d("market_monitor")
        assertEquals(listOf("ust", "funding", "equity", "fx", "crypto", "next"), mm.panels.map { it.id })
        val ust = mm.panels.first()
        assertEquals("kpi", ust.raw.getString("display"))
        assertEquals("2s10s", ust.raw.getJSONObject("spread").getString("label"))
        assertTrue(mm.search)
        assertEquals(listOf("equity", "fx", "crypto"), mm.panels.filter { it.raw.optBoolean("tape") }.map { it.id })
    }

    @Test fun noPlaceholderDeclaresADashedLayout() {
        for (dd in dashboards) for (p in dd.panels) if (p.isPlaceholder) {
            assertTrue("${dd.id}/${p.id} still declares placeholder columns", p.placeholderColumns.isEmpty() && p.placeholderRows == 0)
        }
    }
}
