package com.diegonmarcos.cloudnav.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** tracker/places' digest: stays, not raw fixes; pass-throughs dropped; per local day, newest first. */
class PlacesDigestTest {
    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 16)
    private fun at(h: Int, m: Int) = day.atTime(h, m).toInstant(zone).toEpochMilli()

    @Test fun `two dwells with a pass-through between them are two stays`() {
        val home = (0..6).map { PlaceFix(at(8, it * 5), 40.4168, -3.7038, accuracyM = 20.0) }        // 30 min
        val passing = listOf(PlaceFix(at(9, 0), 40.4300, -3.7000, accuracyM = 20.0))                 // one fix
        val work = (0..4).map { PlaceFix(at(10, it * 10), 40.4500, -3.6900, accuracyM = 20.0) }     // 40 min
        val stays = PlacesDigest.stays(home + passing + work)
        assertEquals(2, stays.size)
        assertEquals(at(8, 0), stays[0].startMs)
        assertEquals(at(10, 40), stays[1].endMs)
        assertEquals("08:00–08:30", PlacesDigest.span(stays[0], zone))
    }

    @Test fun `inaccurate fixes are ignored and a lone short visit still counts`() {
        val stays = PlacesDigest.stays(listOf(
            PlaceFix(at(12, 0), 41.0, 2.0, accuracyM = 900.0),
            PlaceFix(at(12, 1), 40.0, -3.0, accuracyM = 10.0),
        ))
        assertEquals(1, stays.size)
        assertEquals(40.0, stays[0].lat, 1e-9)
    }

    @Test fun `by day lists every day newest first, empty days included`() {
        val fixes = (0..3).map { PlaceFix(at(9, it * 10), 40.0, -3.0) }
        val days = PlacesDigest.byDay(fixes, zone, day.plusDays(1), 3)
        assertEquals(listOf(day.plusDays(1), day, day.minusDays(1)), days.map { it.first })
        assertTrue(days[0].second.isEmpty())
        assertEquals(1, days[1].second.size)
    }

    // The tracker had 7 days of recorded stops (what Cloud Nav's Stops/Daily pages list) but no raw points kept:
    // tracker/places used to read points only and answered empty days, so the card said "no places".
    @Test fun `stops alone give places for each seeded day, newest first, named`() {
        val now = at(23, 0)
        val stops = (0..6).flatMap { back ->
            val d = day.minusDays(back.toLong())
            fun t(h: Int) = d.atTime(h, 0).toInstant(zone).toEpochMilli()
            listOf(StopFix(t(8), t(9), 40.4168, -3.7038, "Home"), StopFix(t(10), t(18), 40.45, -3.69, null))
        }
        val days = PlacesDigest.byDay(emptyList(), zone, day, 7, stops, now)
        assertEquals(7, days.size)
        assertEquals(day, days[0].first)
        days.forEach { assertEquals(2, it.second.size) }
        assertEquals("Home", days[0].second[0].label)
        assertEquals(null, days[0].second[1].label)
        assertEquals("08:00–09:00", PlacesDigest.span(days[3].second[0], zone))
    }

    @Test fun `a stop still open runs to now and a point-derived stay under a stop is not doubled`() {
        val now = at(15, 0)
        val stops = listOf(StopFix(at(12, 0), null, 40.0, -3.0, "Office"))
        val under = (0..3).map { PlaceFix(at(12, 10 + it * 10), 40.0, -3.0) }          // inside the stop
        val elsewhere = (0..3).map { PlaceFix(at(8, it * 10), 41.0, 2.0) }              // before it
        val d = PlacesDigest.byDay(under + elsewhere, zone, day, 1, stops, now)[0].second
        assertEquals(2, d.size)
        assertEquals(at(8, 0), d[0].startMs)
        assertEquals("Office", d[1].label)
        assertEquals(now, d[1].endMs)
    }
}
