package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** The source priority: Cloud-Fincept (fin_api, the own-fleet primary) first when reachable, the public sources as fallback. */
class PriorityTest {
    private val sources = Catalog.parse(DataFiles.sourcesJson)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private val mesh = mapOf("fin-api" to "http://fin.mesh.test:8340")
    private val today = LocalDate.of(2026, 10, 30)
    private val ust = dashboards.first { it.id == "yield_curves" }

    private class Net(val answer: (String) -> String?) : Http {
        val asked: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun get(url: String): String { asked += url; return answer(url) ?: throw IOException("no route") }
        override fun post(url: String): String = throw IOException("no post")
    }

    @Test fun cloudFincepIsRankedFirstForEveryEntryAndPublicBeforeKeyed() {
        val entries = dashboards.map { it.tab to it.entry }
        for ((tab, entry) in entries) {
            val ranked = Catalog.ranked(sources, tab, entry)
            assertEquals("$tab/$entry", "cloud_fincept", ranked.first().id)
            assertTrue(ranked.first().primary)
            val prio = ranked.map { it.priority }
            assertEquals("sorted", prio.sorted(), prio)
        }
        val fred = sources.first { it.id == "fred" }
        val polygon = sources.first { it.id == "polygon" }
        assertTrue(fred.priority < polygon.priority)
        assertEquals(1, sources.count { it.primary })
    }

    @Test fun theRouteIsOnlyOfferedWhenTheMeshAnswers() {
        val panel = ust.panels.first { it.id == "ust" }
        assertNotNull(Priority.route(panel, ust, sources, mesh) { true })
        assertNull(Priority.route(panel, ust, sources, mesh) { false })
        assertNull("no baked mesh address", Priority.route(panel, ust, sources, emptyMap()) { true })
        val noPrimary = dashboards.first { it.id == "world_bonds" }.panels.first { it.id == "yields" }
        assertNull("a panel with no declared primary route", Priority.route(noPrimary, dashboards.first { it.id == "world_bonds" }, sources, mesh) { true })
    }

    private fun fredCsv(url: String): String? {
        val id = Regex("[?&]id=([A-Z0-9]+)").find(url)?.groupValues?.get(1) ?: return null
        return "observation_date,$id\n2026-10-27,4.0\n2026-10-28,4.1\n"
    }

    private fun topic(id: String) = """{"series_id":"$id","observations":[{"date":"2026-10-27","value":5.0},{"date":"2026-10-28","value":5.5},{"date":"2026-10-29","value":null}]}"""

    @Test fun whenReachableTheFleetServesEverySeriesAndThePublicSourceIsNotAsked() {
        val panel = ust.panels.first { it.id == "ust" }
        val route = Priority.route(panel, ust, sources, mesh) { true }!!
        val net = Net { u -> if (u.startsWith("http://fin.mesh.test:8340/api/v1/topics/econ:fred:")) topic(u.substringAfterLast(':').removeSuffix("/peek")) else null }
        val d = Engine.load(panel, sources.first { it.id == "fred" }, net, today, route)
        assertEquals(11, net.asked.size)
        assertTrue(net.asked.all { it.endsWith("/peek") })
        assertEquals(route.source.name, d.servedBy)
        assertEquals("5.50", d.rows.first()[1])
    }

    @Test fun aTopicTheFleetHasNotPublishedFallsBackToThePublicSourceSeriesBySeries() {
        val panel = ust.panels.first { it.id == "ust" }
        val route = Priority.route(panel, ust, sources, mesh) { true }!!
        val net = Net { u -> if (u.startsWith("http://fin.mesh.test")) (if (u.contains("DGS10/")) topic("DGS10") else null) else fredCsv(u) }
        val d = Engine.load(panel, sources.first { it.id == "fred" }, net, today, route)
        assertEquals(11, d.chart.size)
        assertTrue(d.servedBy.contains("+"))
        assertTrue(net.asked.any { it.contains("fredgraph") || it.contains("cosd=") })
    }

    @Test fun withNoRouteTheFleetIsNeverAsked() {
        val panel = ust.panels.first { it.id == "ust" }
        val net = Net(::fredCsv)
        val d = Engine.load(panel, sources.first { it.id == "fred" }, net, today, null)
        assertFalse(net.asked.any { it.contains("mesh") })
        assertEquals("", d.servedBy)
    }

    @Test fun everyFredPanelDeclaresTheFleetAsItsPrimaryAndNoAddressIsTyped() {
        for (d in dashboards) for (p in d.panels) {
            val f = p.fetch ?: continue
            if (f.optString("provider") != "fred_series") continue
            val prim = f.getJSONObject("primary")
            assertEquals("cloud_fincept", prim.getString("source"))
            assertTrue(prim.getString("topic").startsWith("econ:fred:"))
        }
        assertFalse(DataFiles.dashboardsJson.contains("mesh.diegonmarcos"))
        assertFalse(DataFiles.sourcesJson.contains("fin-api.mesh") && sources.first { it.primary }.endpoint.isNotEmpty())
        assertTrue(JSONObject(DataFiles.sourcesJson).getJSONArray("sources").getJSONObject(0).getString("fleet_endpoint").isNotEmpty())
    }
}
