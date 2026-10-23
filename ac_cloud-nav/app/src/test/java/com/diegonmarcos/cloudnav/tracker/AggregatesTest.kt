package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Per-day aggregation: distance per mode (vehicles without walking), hours slept, the city slept in. */
class AggregatesTest {
    private val zone = ZoneOffset.UTC
    private val d = LocalDate.of(2026, 10, 20)
    private fun at(h: Int, m: Int = 0, day: LocalDate = d) = day.atTime(h, m).toInstant(zone).toEpochMilli()
    private fun stay(kind: TlKind, a: Long, b: Long, city: String, lat: Double = 40.0) = TlSegment(kind, a, b, lat, -3.0, "P", 0, 0.0, "fix", null, city, "Spain")
    private fun move(a: Long, b: Long, dist: Double, steps: Long = 0) = TlSegment(TlKind.MOVE, a, b, null, null, null, steps, dist, null)
    private val now = at(23, 59, d.plusDays(1))

    @Test fun `sleep counts for the day it ends on, with the city`() {
        val store = listOf(stay(TlKind.SLEEP, at(22, 0, d.minusDays(1)), at(7), "Madrid"), stay(TlKind.STOP, at(7), at(21), "Madrid"),
            stay(TlKind.SLEEP, at(21), at(6, 0, d.plusDays(1)), "Lisbon", 38.7))
        val s = Aggregates.perDay(store, listOf(d, d.plusDays(1)), now, zone)
        assertEquals(9 * 3_600_000L, s[0].sleepMs); assertEquals("Madrid", s[0].sleepCity)
        assertEquals(9 * 3_600_000L, s[1].sleepMs); assertEquals("Lisbon", s[1].sleepCity)
    }

    @Test fun `a day without sleep has zero hours and no city`() {
        val s = Aggregates.perDay(listOf(stay(TlKind.STOP, at(8), at(20), "Madrid")), listOf(d), now, zone).single()
        assertEquals(0L, s.sleepMs); assertEquals(null, s.sleepCity)
    }

    @Test fun `distance per day per vehicle excludes walking and running`() {
        val store = listOf(
            stay(TlKind.STOP, at(7), at(8), "Madrid"),
            move(at(8), at(9), 700.0, 900),                 // walk
            stay(TlKind.STOP, at(9), at(10), "Madrid", 40.1),
            move(at(10), at(10, 30), 15000.0),              // car (30 km/h)
            stay(TlKind.STOP, at(10, 30), at(11), "Madrid", 40.2),
            move(at(11), at(12), 20000.0),                  // 5.6 m/s -> bike
            stay(TlKind.STOP, at(12), at(13), "Madrid", 40.3),
            move(at(13), at(14), 250000.0),                 // 69 m/s -> train
        )
        val s = Aggregates.perDay(store, listOf(d), now, zone).single()
        assertEquals(700.0, s.walkM, 1e-6)
        assertEquals(setOf(Mode.CAR, Mode.BIKE, Mode.TRAIN), s.vehicleM.keys)
        assertEquals(15000.0, s.vehicleM[Mode.CAR]!!, 1e-6)
        assertEquals(20000.0, s.vehicleM[Mode.BIKE]!!, 1e-6)
        assertTrue(!s.vehicleM.containsKey(Mode.WALK))
    }

    @Test fun `walking distance per day feeds the health view`() {
        val store = listOf(move(at(8), at(9), 1200.0, 1600), move(at(9, 30), at(10), 600.0, 800))
        assertEquals(1800.0, Aggregates.perDay(store, listOf(d), now, zone).single().walkM, 1e-6)
    }
}
