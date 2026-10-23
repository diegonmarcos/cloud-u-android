package com.diegonmarcos.cloudnav.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Trip grouping over per-day sleep cities: home detection, multi-city trips, day boundaries. */
class TripsTest {
    private val d0 = LocalDate.of(2026, 9, 1)
    private fun day(i: Int, city: String?, car: Double = 0.0, plane: Double = 0.0, lat: Double? = null): DayStats {
        val m = LinkedHashMap<Mode, ModeStat>()
        if (car > 0) m[Mode.CAR] = ModeStat(car, 1000); if (plane > 0) m[Mode.PLANE] = ModeStat(plane, 1000)
        return DayStats(d0.plusDays(i.toLong()), if (city == null) 0 else 8 * 3_600_000L, city, DaySummary(1, m), lat, lat?.let { it + 1 })
    }
    private fun days(vararg c: String?) = c.mapIndexed { i, s -> day(i, s) }

    @Test fun `home is the most frequent sleep city`() {
        assertEquals("Madrid", Trips.detectHome(days("Madrid", "Lisbon", "Madrid", "Madrid", "Paris")))
    }

    @Test fun `home counts only the last 90 days`() {
        val old = (0 until 100).map { day(it, "Old") }
        val recent = (100 until 150).map { day(it, "New") }
        assertEquals("New", Trips.detectHome(old + recent))
    }

    @Test fun `home tie goes to the more recent city and no data means no home`() {
        assertEquals("B", Trips.detectHome(days("A", "B")))
        assertNull(Trips.detectHome(days(null, null)))
        assertTrue(Trips.group(emptyList()).trips.isEmpty())
    }

    @Test fun `a single away night is a one-night trip with the departure day`() {
        val r = Trips.group(days("Madrid", "Madrid", "Lisbon", "Madrid", "Madrid"))
        val t = r.trips.single()
        assertEquals(1, t.nights); assertEquals(listOf("Lisbon"), t.cities)
        assertEquals(d0.plusDays(1), t.start); assertEquals(d0.plusDays(2), t.end)
        assertFalse(t.ongoing); assertEquals("Madrid", r.home); assertFalse(r.owner)
    }

    @Test fun `consecutive nights in several cities are one multi-city trip`() {
        val r = Trips.group(days("Madrid", "Madrid", "Madrid", "Lisbon", "Porto", "Porto", "Paris", "Madrid", "Madrid", "Madrid"))
        val t = r.trips.single()
        assertEquals(4, t.nights); assertEquals(listOf("Lisbon", "Porto", "Paris"), t.cities)
        assertEquals(4, t.stops.size)
    }

    @Test fun `two trips separated by a night at home stay two`() {
        val r = Trips.group(days("Madrid", "Madrid", "Madrid", "Rome", "Madrid", "Madrid", "Oslo", "Oslo", "Madrid", "Madrid"))
        assertEquals(listOf(1, 2), r.trips.map { it.nights })
    }

    @Test fun `a day with no recorded sleep keeps the previous city`() {
        val r = Trips.group(days("Madrid", "Madrid", "Madrid", "Rome", null, "Rome", "Madrid", "Madrid", "Madrid"))
        assertEquals(3, r.trips.single().nights)
        // unknown before any city is skipped, and an unknown at home does not open a trip
        assertTrue(Trips.group(days(null, "Madrid", "Madrid", null, "Madrid")).trips.isEmpty())
    }

    @Test fun `a missing calendar date ends the trip`() {
        val l = listOf(day(0, "Madrid"), day(1, "Madrid"), day(2, "Rome"), day(5, "Rome"), day(6, "Madrid"), day(7, "Madrid"), day(8, "Madrid"))
        assertEquals(listOf(1, 1), Trips.group(l).trips.map { it.nights })
    }

    @Test fun `a trip still running at the last date is ongoing`() {
        val t = Trips.group(days("Madrid", "Madrid", "Madrid", "Rome", "Rome")).trips.single()
        assertTrue(t.ongoing); assertEquals(2, t.nights); assertEquals(d0.plusDays(4), t.end)
    }

    @Test fun `vehicle distance covers departure day to return day only`() {
        val l = listOf(day(0, "Madrid", car = 5000.0), day(1, "Madrid", car = 100.0, plane = 1_300_000.0), day(2, "Rome", car = 20_000.0),
            day(3, "Rome", car = 30_000.0, plane = 1_300_000.0), day(4, "Madrid", car = 7000.0), day(5, "Madrid"), day(6, "Madrid"))
        val t = Trips.group(l).trips.single()
        assertEquals(d0.plusDays(1), t.start); assertEquals(d0.plusDays(3), t.end); assertEquals(2, t.nights)
        assertEquals(2_600_000.0, t.vehicleM[Mode.PLANE]!!, 0.1)
        assertEquals(100.0 + 20_000.0 + 30_000.0, t.vehicleM[Mode.CAR]!!, 0.1)
    }

    @Test fun `owner home overrides detection and city case is ignored`() {
        val r = Trips.group(days("Madrid", "madrid", "Madrid", "Rome", "Rome", "Rome"), "rome")
        assertTrue(r.owner); assertEquals("rome", r.home)
        val t = r.trips.single()   // Madrid is now the away city
        assertEquals(listOf("Madrid"), t.cities); assertEquals(3, t.nights)
    }

    @Test fun `route joins located stops and json carries the contract`() {
        val l = listOf(day(0, "Madrid"), day(1, "Madrid"), day(2, "Rome", lat = 41.0), day(3, "Milan", lat = 45.0), day(4, "Madrid"), day(5, "Madrid"))
        val r = Trips.group(l)
        val m = Trips.route(r.trips.single())
        assertEquals(1, m.lines.size); assertEquals(2, m.markers.size)
        val j = TripsJson.toJson(r)
        assertEquals(1, j.getInt("version")); assertEquals("auto", j.getString("home_source")); assertEquals("Madrid", j.getString("home"))
        val t = j.getJSONArray("trips").getJSONObject(0)
        assertEquals(2, t.getInt("nights")); assertEquals(2, t.getJSONArray("cities").length()); assertEquals(2, t.getJSONArray("route").length())
    }
}
