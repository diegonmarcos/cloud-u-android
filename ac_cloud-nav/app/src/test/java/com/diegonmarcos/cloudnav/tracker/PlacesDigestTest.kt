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
}
