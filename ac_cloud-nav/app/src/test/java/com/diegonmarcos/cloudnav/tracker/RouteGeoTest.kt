package com.diegonmarcos.cloudnav.tracker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1145: the Day map's route model: polyline per mode, mode icons on moves, category icons on visits, current position. */
class RouteGeoTest {
    private val home = Place(40.0, 3.0, "Home")
    private val cafe = Place(40.01, 3.01, "Cafe", category = PoiCategory.FOOD.name)
    private val day = listOf<DaySeg>(
        DaySeg.Stay(0, 100, StayKind.SLEEP, home),
        DaySeg.Move(100, 200, 1500.0, 0, 15.0, 100, home, cafe, Mode.CAR),
        DaySeg.Stay(200, 300, StayKind.IDLE, cafe),
        DaySeg.Move(300, 400, 500.0, 600, 1.4, 100, cafe, Place(null, null), Mode.WALK),
    )

    @Test fun `a move becomes a line in its mode with the measured fixes between its ends`() {
        val fixes = listOf(PlaceFix(150, 40.005, 3.005, false, 10.0), PlaceFix(160, 41.0, 4.0, true, 10.0), PlaceFix(500, 9.0, 9.0))
        val m = RouteGeo.build(day, fixes)
        assertEquals(1, m.lines.size)   // the walk has no located end and no fixes: nothing to draw
        val l = m.lines[0]
        assertEquals(Mode.CAR, l.mode)
        assertEquals(listOf(LatLon(40.0, 3.0), LatLon(40.005, 3.005), LatLon(40.01, 3.01)), l.points)   // predicted and out-of-range fixes skipped
    }

    @Test fun `markers carry mode icons on moves, moon on sleep and the category icon on visits`() {
        val icons = RouteGeo.build(day).markers.map { it.icon }
        assertEquals(listOf("stay_sleep", "mode_car", "poi_food"), icons)
    }

    @Test fun `the current position is the newest measured fix, else the last stay, and only today`() {
        assertNull(RouteGeo.build(day, live = false).current)
        assertEquals(LatLon(40.01, 3.01), RouteGeo.build(day, live = true).current)
        assertEquals(LatLon(1.0, 2.0), RouteGeo.build(day, listOf(PlaceFix(450, 1.0, 2.0)), live = true).current)
    }

    @Test fun `an empty or unlocated day has nothing to draw`() {
        assertTrue(RouteGeo.build(emptyList()).isEmpty)
        assertTrue(RouteGeo.build(listOf(DaySeg.Stay(0, 10, StayKind.IDLE, null))).isEmpty)
    }

    @Test fun `geojson is lon-lat, coloured per mode and valid`() {
        val m = RouteGeo.build(day, live = true)
        val lines = JSONObject(RouteGeo.linesJson(m)).getJSONArray("features")
        val f = lines.getJSONObject(0)
        assertEquals("#60A5FA", f.getJSONObject("properties").getString("color"))
        assertEquals(3.0, f.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).getDouble(0), 0.0)   // lon first
        assertEquals(3, JSONObject(RouteGeo.markersJson(m)).getJSONArray("features").length())
        assertEquals(1, JSONObject(RouteGeo.currentJson(m)).getJSONArray("features").length())
        assertEquals(0, JSONObject(RouteGeo.currentJson(RouteGeo.build(day))).getJSONArray("features").length())
    }

    @Test fun `every mode has its own colour and icon, and every icon is registered for the map`() {
        assertEquals(Mode.values().size, Mode.values().map { RouteGeo.modeArgb(it) }.toSet().size)
        for (m in Mode.values()) assertTrue(RouteGeo.modeIcon(m) in RouteGeo.ALL_ICONS)
        for (c in PoiCategory.values()) assertTrue("poi_" + c.name.lowercase() in RouteGeo.ALL_ICONS)
    }
}
