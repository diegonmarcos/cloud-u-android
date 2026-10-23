package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TimelineBuilder
import com.diegonmarcos.cloudnav.maps.TimelineViews
import com.diegonmarcos.cloudnav.maps.TlStop
import com.diegonmarcos.cloudnav.maps.TlSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** #1114: where a stop is, cheapest first (the order of #1108's Weather badge), and the Steps page as a view over the timeline. */
class StopLocatorTest {
    private val now = 1_760_000_000_000L
    private val both = StopLocator.Env(coarse = true, fine = true)

    private class Probe(
        val on: Set<String> = setOf("network", "passive", "gps"),
        val cached: Map<String, StopLocator.Fix> = emptyMap(),
        val live: Map<String, StopLocator.Fix> = emptyMap(),
    ) : StopLocator.Probe {
        val requested = mutableListOf<String>()
        override fun enabled(provider: String) = provider in on
        override fun lastKnown(provider: String) = cached[provider]
        override fun request(provider: String, timeoutMs: Long): StopLocator.Fix? { requested += provider; return live[provider] }
    }

    private fun fix(src: String, ageMs: Long = 0, lat: Double = 40.4) = StopLocator.Fix(lat, -3.7, 30f, now - ageMs, src)

    @Test fun `a fresh held fix is used and no radio is woken`() {
        val p = Probe(live = mapOf("network" to fix("network")))
        assertEquals("held", StopLocator.resolve(now, both, p, held = fix("held", 60_000))?.source)
        assertTrue(p.requested.isEmpty())
    }

    @Test fun `a fresh cached network fix beats a cached GPS fix and nothing is requested`() {
        val p = Probe(cached = mapOf("gps" to fix("gps", 1_000), "network" to fix("network", 5 * 60_000L)))
        assertEquals("network", StopLocator.resolve(now, both, p)?.source)
        assertTrue(p.requested.isEmpty())
    }

    @Test fun `with nothing cached the network is asked first and GPS is not touched`() {
        val p = Probe(live = mapOf("network" to fix("network"), "gps" to fix("gps")))
        assertEquals("network", StopLocator.resolve(now, both, p)?.source)
        assertEquals(listOf("network"), p.requested)
    }

    @Test fun `GPS is the last resort`() {
        val p = Probe(live = mapOf("gps" to fix("gps")))
        assertEquals("gps", StopLocator.resolve(now, both, p)?.source)
        assertEquals(listOf("network", "gps"), p.requested)
    }

    @Test fun `without the fine permission GPS is never asked`() {
        val p = Probe(live = mapOf("gps" to fix("gps")))
        assertNull(StopLocator.resolve(now, StopLocator.Env(coarse = true, fine = false), p))
        assertEquals(listOf("network"), p.requested)
    }

    @Test fun `when every request fails the newest stale fix is better than none`() {
        val p = Probe(cached = mapOf("network" to fix("network", 3 * 3_600_000L), "gps" to fix("gps", 2 * 3_600_000L)))
        assertEquals("gps", StopLocator.resolve(now, both, p)?.source)
    }

    @Test fun `no permission at all falls back to the held fix, else nothing`() {
        val none = StopLocator.Env(coarse = false, fine = false)
        assertEquals("held", StopLocator.resolve(now, none, Probe(), held = fix("held", 9 * 3_600_000L))?.source)
        assertNull(StopLocator.resolve(now, none, Probe()))
    }

    @Test fun `an invalid fix (0,0) is never a place`() {
        val p = Probe(live = mapOf("network" to fix("network", lat = 0.0).copy(lon = 0.0), "gps" to fix("gps")))
        assertEquals("gps", StopLocator.resolve(now, both, p)?.source)
    }

    // ── Steps as a view over the timeline ───────────────────────────

    @Test fun `the Steps page's slices are the timeline's steps, per day`() {
        val zone = ZoneOffset.UTC
        val d = LocalDate.of(2026, 10, 14)
        fun at(h: Int, plus: Long = 0) = d.plusDays(plus).atTime(h, 0).toInstant(zone).toEpochMilli()
        val segs = TimelineBuilder.build(
            listOf(TlStop(at(0), at(7), 40.4, -3.7, "Home"), TlStop(at(9), at(17), 40.43, -3.68, "Office")),
            emptyList(), listOf(TlSteps(at(7), at(9), 2400), TlSteps(at(10), at(11), 600), TlSteps(at(8, 1), at(9, 1), 1000)), at(18, 1), zone)
        val slices = TimelineViews.stepSlices(segs).map { StepSlice(it.startMs, it.endMs, it.count) }
        val perDay = StepStats.perDay(slices, zone)
        assertEquals(3000L, perDay[d])
        assertEquals(1000L, perDay[d.plusDays(1)])
    }
}
