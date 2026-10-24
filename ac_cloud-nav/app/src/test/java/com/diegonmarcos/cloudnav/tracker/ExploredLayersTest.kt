package com.diegonmarcos.cloudnav.tracker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** #1156 Explored > Maps: the Trips data (tracker/trips + tracker/segments JSON) becomes pins, routes and drawn areas. */
class ExploredLayersTest {
    private val d0 = LocalDate.of(2026, 9, 1)
    private fun stat(i: Int, city: String, lat: Double, lon: Double, car: Double = 0.0): DayStats {
        val m = LinkedHashMap<Mode, ModeStat>(); if (car > 0) m[Mode.CAR] = ModeStat(car, 1000)
        return DayStats(d0.plusDays(i.toLong()), 8 * 3_600_000L, city, DaySummary(1, m), lat, lon)
    }
    // six nights at home (10.0, 20.0), then two cities away, then home again
    private val stats = (0 until 6).map { stat(it, "Homeburg", 10.0, 20.0) } +
        listOf(stat(6, "Awayton", 11.0, 21.0, car = 80_000.0), stat(7, "Faraway", 12.0, 22.0, car = 90_000.0), stat(8, "Homeburg", 10.0, 20.0))
    private val trips = TripsJson.toJson(Trips.group(stats))

    private fun seg(lat: Double, lon: Double, name: String, city: String, state: String, country: String) =
        JSONObject().put("type", "stay").put("kind", "idle").put("start", 1).put("end", 2).put("confirmed", true).put("missing", false)
            .put("location", JSONObject().put("lat", lat).put("lon", lon).put("name", name).put("city", city).put("state", state).put("country", country))
    private val move = JSONObject().put("type", "move").put("start", 2).put("end", 3).put("mode", "car").put("distance_m", 5000)
        .put("from", JSONObject().put("lat", 10.0).put("lon", 20.0)).put("to", JSONObject().put("lat", 10.05).put("lon", 20.05))
    private val day = JSONObject().put("version", 1).put("segments", JSONArray().put(seg(10.0, 20.0, "Cafe", "Homeburg", "Homestate", "Testland"))
        .put(move).put(seg(10.05, 20.05, "Library", "Homeburg", "Homestate", "Testland")).put(seg(10.0, 20.0, "Cafe", "Homeburg", "Homestate", "Testland")))

    private val layers = ExploredMapData.layers(trips, listOf(day))

    @Test fun `pins cover the trip's sleep stops and the day's stays, without duplicates`() {
        val sleep = layers.pins.filter { it.kind == "sleep" }.map { it.label }
        assertEquals(listOf("Awayton", "Faraway"), sleep)
        val stays = layers.pins.filter { it.kind == "stay" }.map { it.label }
        assertEquals(listOf("Cafe", "Library"), stays)   // the second Cafe visit is the same spot
    }

    @Test fun `routes are the trip's route in its main vehicle plus each located move`() {
        assertEquals(2, layers.routes.size)
        val trip = layers.routes[0]
        assertEquals(listOf(LatLon(11.0, 21.0), LatLon(12.0, 22.0)), trip.points); assertEquals("car", trip.mode); assertEquals("Awayton, Faraway", trip.label)
        assertEquals(listOf(LatLon(10.0, 20.0), LatLon(10.05, 20.05)), layers.routes[1].points)
    }

    @Test fun `areas are drawn for cities, regions and countries visited`() {
        val byLevel = layers.areas.groupBy { it.level }
        assertEquals(setOf("Homeburg", "Awayton", "Faraway"), byLevel.getValue("city").map { it.label }.toSet())
        assertEquals(listOf("Homestate"), byLevel.getValue("region").map { it.label })
        assertEquals(listOf("Testland"), byLevel.getValue("country").map { it.label })
        for (a in layers.areas) { assertTrue(a.label, a.ring.size >= 4); assertEquals(a.ring.first(), a.ring.last()) }
    }

    @Test fun `geojson layers carry the mapped data, lon before lat`() {
        val pins = JSONObject(ExploredMapData.pinsJson(layers)).getJSONArray("features")
        assertEquals(layers.pins.size, pins.length())
        val c = pins.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(layers.pins[0].at.lon, c.getDouble(0), 1e-9); assertEquals(layers.pins[0].at.lat, c.getDouble(1), 1e-9)
        val routes = JSONObject(ExploredMapData.routesJson(layers)).getJSONArray("features")
        assertEquals(2, routes.length()); assertEquals("LineString", routes.getJSONObject(0).getJSONObject("geometry").getString("type"))
        assertEquals(RouteGeo.modeHex(Mode.CAR), routes.getJSONObject(0).getJSONObject("properties").getString("color"))
        val areas = JSONObject(ExploredMapData.areasJson(layers)).getJSONArray("features")
        assertEquals(layers.areas.size, areas.length()); assertEquals("Polygon", areas.getJSONObject(0).getJSONObject("geometry").getString("type"))
    }

    @Test fun `no data is an empty map`() {
        val e = ExploredMapData.layers(JSONObject("{}"), emptyList())
        assertTrue(e.isEmpty); assertTrue(e.points.isEmpty())
        assertEquals(0, JSONObject(ExploredMapData.areasJson(e)).getJSONArray("features").length())
    }

    @Test fun `hull keeps the corners and drops the inside, disc is closed and at least city sized`() {
        val h = ExploredMapData.hull(listOf(LatLon(0.0, 0.0), LatLon(0.0, 2.0), LatLon(2.0, 2.0), LatLon(2.0, 0.0), LatLon(1.0, 1.0)))
        assertEquals(5, h.size); assertEquals(h.first(), h.last()); assertTrue(LatLon(1.0, 1.0) !in h)
        val ring = ExploredMapData.disc(listOf(LatLon(10.0, 20.0)))
        assertEquals(ring.first(), ring.last())
        val maxLatKm = ring.maxOf { Math.abs(it.lat - 10.0) } * 111.32
        assertTrue("radius ${maxLatKm} km", maxLatKm >= ExploredMapData.CITY_MIN_KM - 0.01)
    }
}
