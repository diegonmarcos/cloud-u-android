package com.diegonmarcos.cloudnav.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** #1114: the one timeline builder (segmentation, sleep, migration) and the views over what it produces. */
class TimelineBuilderTest {
    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 10, 14)
    private fun at(h: Int, m: Int = 0, plusDays: Long = 0) = day.plusDays(plusDays).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val HOME = 40.4168 to -3.7038
    private val WORK = 40.4300 to -3.6800

    private fun stop(a: Long, b: Long?, p: Pair<Double, Double>, name: String? = null) = TlStop(a, b, p.first, p.second, name)
    private fun build(stops: List<TlStop>, fixes: List<TlFix> = emptyList(), steps: List<TlSteps> = emptyList(), now: Long) =
        TimelineBuilder.build(stops, fixes, steps, now, zone)

    // ── segmentation ────────────────────────────────────────────────

    @Test fun `stops with gaps become stop move stop move`() {
        val segs = build(listOf(stop(at(8), at(9), HOME, "Home"), stop(at(10), at(17), WORK, "Office")), now = at(18))
        assertEquals(listOf(TlKind.STOP, TlKind.MOVE, TlKind.STOP, TlKind.MOVE), segs.map { it.kind })
        assertEquals(listOf(at(8), at(9), at(10), at(17)), segs.map { it.startMs })
        assertEquals(listOf(at(9), at(10), at(17), at(18)), segs.map { it.endMs })
        assertEquals("Home", segs[0].place)
        assertNull("a move has no place name", segs[1].place)
        assertEquals("a move ends where the next stop is", WORK.first, segs[1].lat!!, 1e-9)
    }

    @Test fun `an open stop runs to now and has no trailing move`() {
        val segs = build(listOf(stop(at(8), null, HOME)), now = at(12))
        assertEquals(1, segs.size)
        assertEquals(at(12), segs[0].endMs)
    }

    @Test fun `the segments never overlap and stay ordered`() {
        val segs = build(listOf(stop(at(10), at(12), WORK), stop(at(8), at(11), HOME), stop(at(11, 30), at(13), WORK)), now = at(14))
        for (i in 1 until segs.size) assertTrue(segs[i - 1].endMs <= segs[i].startMs)
        assertEquals(segs.sortedBy { it.startMs }, segs)
    }

    @Test fun `move distance follows the fixes, else the straight line`() {
        val straight = build(listOf(stop(at(8), at(9), HOME), stop(at(10), at(11), WORK)), now = at(11))[1]
        val expected = TimelineBuilder.haversineM(HOME.first, HOME.second, WORK.first, WORK.second)
        assertEquals(expected, straight.distanceM, 1.0)
        val detour = build(listOf(stop(at(8), at(9), HOME), stop(at(10), at(11), WORK)),
            fixes = listOf(TlFix(at(9, 30), 40.50, -3.70, 20.0)), now = at(11))[1]
        assertTrue("a fix off the line makes the path longer", detour.distanceM > expected * 1.5)
        val noisy = build(listOf(stop(at(8), at(9), HOME), stop(at(10), at(11), WORK)),
            fixes = listOf(TlFix(at(9, 30), 41.50, -3.70, 5000.0)), now = at(11))[1]
        assertEquals("a fix with a 5 km radius is ignored", expected, noisy.distanceM, 1.0)
    }

    @Test fun `a gap longer than twelve hours with no fix is unknown time, not a move`() {
        val segs = build(listOf(stop(at(8), at(9), HOME), stop(at(10, plusDays = 1), at(11, plusDays = 1), WORK)), now = at(12, plusDays = 1))
        assertEquals(listOf(TlKind.STOP, TlKind.STOP, TlKind.MOVE), segs.map { it.kind })
    }

    @Test fun `steps are shared out by overlap and the totals match`() {
        val steps = listOf(TlSteps(at(8), at(10), 2000))   // 1 h in the stop, 1 h in the move
        val segs = build(listOf(stop(at(8), at(9), HOME), stop(at(10), at(11), WORK)), steps = steps, now = at(11))
        assertEquals(1000, segs[0].steps)
        assertEquals(1000, segs[1].steps)
        assertEquals(2000, segs.sumOf { it.steps })
    }

    @Test fun `steps with no location stay in the store as place-less moves`() {
        val segs = build(emptyList(), steps = listOf(TlSteps(at(9), at(9, 15), 400), TlSteps(at(9, 15), at(9, 30), 300), TlSteps(at(15), at(15, 15), 5)), now = at(20))
        assertEquals(1, segs.size)                      // the two adjacent slices join; 5 steps is noise
        assertEquals(TlKind.MOVE, segs[0].kind)
        assertEquals(700, segs[0].steps)
        assertNull(segs[0].lat)
        assertEquals("steps", segs[0].locSource)
    }

    // ── sleep ───────────────────────────────────────────────────────

    @Test fun `a long stop across the night is sleep`() {
        val segs = build(listOf(stop(at(21), at(7, plusDays = 1), HOME, "Home")), now = at(12, plusDays = 1))
        assertEquals(TlKind.SLEEP, segs[0].kind)
    }

    @Test fun `a long daytime stop is not sleep, nor a short night stop`() {
        assertEquals(TlKind.STOP, build(listOf(stop(at(9), at(17), WORK)), now = at(18))[0].kind)
        assertEquals(TlKind.STOP, build(listOf(stop(at(23), at(1, plusDays = 1), HOME)), now = at(5, plusDays = 1))[0].kind)
        assertFalse(TimelineBuilder.isSleep(at(8), at(10), zone))
    }

    @Test fun `sleep is judged in the local zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")   // 22:00 UTC is 07:00 in Tokyo: not night for them
        val a = at(22); val b = at(6, plusDays = 1)
        assertTrue(TimelineBuilder.isSleep(a, b, zone))
        assertFalse(TimelineBuilder.isSleep(a, b, tokyo))
    }

    @Test fun `the full day reads start move stop move sleep`() {
        val segs = build(listOf(stop(at(0), at(8), HOME, "Home"), stop(at(9), at(17), WORK, "Office"), stop(at(20), null, HOME, "Home")), now = at(23, 30))
        assertEquals(listOf(TlKind.SLEEP, TlKind.MOVE, TlKind.STOP, TlKind.MOVE, TlKind.STOP), segs.map { it.kind })
    }

    // ── migration: legacy tables in, the store out ──────────────────

    @Test fun `migration keeps every legacy stop and step and a second run changes nothing`() {
        val legacyStops = listOf(stop(at(0), at(7), HOME, "Home"), stop(at(9), at(12), WORK, "Office"), stop(at(13), null, WORK, "Office"))
        val legacyFixes = listOf(TlFix(at(8), 40.42, -3.70, 30.0), TlFix(at(12, 30), 40.425, -3.69, 40.0))
        val legacySteps = listOf(TlSteps(at(7), at(7, 30), 900), TlSteps(at(10), at(10, 15), 100), TlSteps(at(12, 15), at(12, 45), 1200))
        val once = build(legacyStops, legacyFixes, legacySteps, now = at(15))
        assertEquals("every stop survives", 3, once.count { it.kind != TlKind.MOVE })
        assertEquals("every step survives", legacySteps.sumOf { it.count }, once.sumOf { it.steps })
        assertTrue("the open stop runs to now", once.last().endMs == at(15))
        assertEquals(once, build(legacyStops, legacyFixes, legacySteps, now = at(15)))
    }

    // ── the views ───────────────────────────────────────────────────

    private val sample = build(listOf(stop(at(0), at(7), HOME, "Home"), stop(at(9), at(17), WORK, "Office"), stop(at(20), null, HOME, "Home")),
        steps = listOf(TlSteps(at(7), at(9), 2400), TlSteps(at(10), at(11), 600)), now = at(23))

    @Test fun `Daily view picks the longest stay of the day`() {
        val daily = computeDailyLocations(TimelineViews.richStops(sample))
        assertTrue("the office (8 h) is the longest single stay of its day", daily.any { it.placeName == "Office" })
        assertTrue(daily.all { it.placeName != null })
    }

    @Test fun `Stops view lists the stays newest first with their place`() {
        val rows = TimelineViews.richStops(sample).sortedByDescending { it.startedAt }
        assertEquals(listOf(at(20), at(9), at(0)), rows.map { it.startedAt })
        assertEquals("Home", stopRowText(rows[0]).place.substringBefore("   "))
    }

    @Test fun `Steps view is one slice per segment with steps`() {
        val slices = TimelineViews.stepSlices(sample)
        assertEquals(3000, slices.sumOf { it.count })
        assertTrue(slices.all { it.count > 0 })
    }

    @Test fun `MyTrips view clips to the last hours and the wire round trips`() {
        val now = at(23)
        val last = TimelineViews.lastHours(sample, now, 6)
        assertEquals(at(17), last.first().startMs)           // cut at now - 6 h
        assertEquals(now, last.last().endMs)
        val json = TimelineJson.toJson(last, now, 6).toString()
        val back = TimelineJson.fromJson(json)
        assertEquals(last.map { it.kind }, back.map { it.kind })
        assertEquals(last.map { it.startMs }, back.map { it.startMs })
        assertEquals(last.sumOf { it.steps }, back.sumOf { it.steps })
    }

    @Test fun `unusable coordinates are not a place`() {
        assertFalse(TimelineSync.usable(TlStop(0, 1, 0.0, 0.0, null)))
        assertFalse(TimelineSync.usable(TlStop(0, 1, Double.NaN, 3.0, null)))
        assertFalse(TimelineSync.usable(TlStop(0, 1, 91.0, 3.0, null)))
        assertTrue(TimelineSync.usable(TlStop(0, 1, 40.0, -3.0, null)))
    }
}
