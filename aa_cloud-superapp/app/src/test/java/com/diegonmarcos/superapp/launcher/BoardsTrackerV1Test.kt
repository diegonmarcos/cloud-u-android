package com.diegonmarcos.superapp.launcher

import android.app.Application
import com.diegonmarcos.superapp.boards.BoardsWire
import com.diegonmarcos.superapp.boards.Read
import com.diegonmarcos.superapp.boards.TimelineBoard
import com.diegonmarcos.superapp.boards.TimelineSeg
import com.diegonmarcos.superapp.boards.TrackerAgg
import com.diegonmarcos.superapp.boards.TrackerDay
import com.diegonmarcos.superapp.boards.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** #1145: Boards ME / MyHealth / MyTrips read Cloud Nav's tracker/segments and tracker/daily (contract v1); tracker/timeline is only the shim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BoardsTrackerV1Test {
    private val segments = """{"version":1,"from":0,"to":300,"segments":[
        {"type":"move","start":100,"end":200,"distance_m":1200,"steps":900,"avg_speed_mps":1.2,"moving_ms":100,"mode":"walk","from":{"lat":1.0,"lon":2.0,"name":"Home"},"to":{"lat":1.5,"lon":2.5,"name":"Cafe","address":"Main 1"}},
        {"type":"stay","kind":"sleep","start":0,"end":100,"confirmed":false,"missing":false,"location":{"lat":1.0,"lon":2.0,"name":"","address":"Seestrasse 1, 8000, Zurich"}},
        {"type":"stay","kind":"idle","start":200,"end":300,"confirmed":true,"missing":false,"location":{"lat":1.5,"lon":2.5,"name":"Cafe","address":"Main 1"}}]}"""
    private val daily = """{"version":1,"days":[
        {"date":"2026-10-21","sleep_ms":1,"sleep_h":7.5,"sleep_city":"Zurich","walk_m":4200,"run_m":0,"vehicle_m":{"car":12000},"by_mode":{},"visits":3},
        {"date":"2026-10-22","sleep_ms":1,"sleep_h":0.0,"sleep_city":"","walk_m":0,"run_m":0,"vehicle_m":{},"by_mode":{},"visits":0},
        {"date":"2026-10-23","sleep_ms":1,"sleep_h":6.5,"sleep_city":"Bern","walk_m":2000,"run_m":0,"vehicle_m":{},"by_mode":{},"visits":1}]}"""

    @Test fun `v1 segments become ordered stop, move and sleep rows with their places`() {
        val s = BoardsWire.segments(segments)
        assertEquals(listOf("sleep", "move", "stop"), s.map { it.kind })
        assertEquals("Seestrasse 1, 8000, Zurich", s[0].place)   // no name: the address stands in
        assertEquals(1200L, s[1].distanceM); assertEquals(900L, s[1].steps); assertEquals(1.5, s[1].lat!!, 0.0)
        assertEquals("Cafe", s[2].place)
    }

    @Test fun `a contract version other than 1 is refused, not half-read`() {
        try { BoardsWire.segments(segments.replace("\"version\":1", "\"version\":2")); fail() } catch (_: IllegalStateException) {}
        try { BoardsWire.daily("""{"days":[]}"""); fail() } catch (_: IllegalStateException) {}
    }

    @Test fun `the window clips segments and scales steps and distance`() {
        val b = BoardsWire.timelineOf(BoardsWire.segments(segments), nowMs = 300, hours = 0)
        assertTrue(b.segments.isEmpty())
        val h = 3_600_000L
        val seg = TimelineSeg("move", 0, 2 * h, "", null, null, 200, 1000, "")
        val c = BoardsWire.timelineOf(listOf(seg), nowMs = 2 * h, hours = 1).segments.single()
        assertEquals(h, c.startMs); assertEquals(100L, c.steps); assertEquals(500L, c.distanceM)
    }

    @Test fun `daily v1 parses days with vehicle distance, oldest first`() {
        val d = BoardsWire.daily(daily)
        assertEquals(3, d.size); assertEquals(7.5, d[0].sleepH, 0.0); assertEquals("Zurich", d[0].sleepCity)
        assertEquals(12000L, d[0].vehicleM["car"]); assertEquals(LocalDate.of(2026, 10, 23), d[2].date)
    }

    @Test fun `MyHealth sleep and walking come from the tracker, a day without sleep is missing not zero`() {
        val t = TrackerAgg.summarize(BoardsWire.daily(daily), LocalDate.of(2026, 10, 23))
        assertEquals(6.5, t.lastNightH!!, 0.0); assertEquals(7.0, t.avgSleepH!!, 0.0); assertEquals("Bern", t.sleepCity)
        assertEquals(2.0, t.walkTodayKm!!, 0.0); assertEquals(3.1, t.avgWalkKm!!, 1e-9)
        val none = TrackerAgg.summarize(listOf(TrackerDay(LocalDate.of(2026, 10, 23), 0.0, "", 0, 0, emptyMap(), 0)), LocalDate.of(2026, 10, 23))
        assertNull(none.lastNightH); assertNull(none.avgSleepH); assertNull(none.walkTodayKm); assertEquals("--", TrackerAgg.hoursText(none.lastNightH))
    }

    @Test fun `MyTrips reads v1 and asks the old route only when Cloud Nav is too old`() {
        val today = LocalDate.of(2026, 10, 23)
        var legacyCalls = 0
        val shim = Read.Ok(TimelineBoard(10, 24, listOf(TimelineSeg("stop", 1, 2, "Old", null, null, 0, 0, ""))))
        val legacy = { legacyCalls++; shim }

        val asked = mutableListOf<LocalDate>()
        val v1 = BoardsWire.timelineV1OrShim(today, 300, 24, { d -> asked += d; Read.Ok(BoardsWire.segments(segments)) }, legacy)
        assertEquals(listOf(today, today.minusDays(1)), asked); assertEquals(0, legacyCalls)
        assertTrue(v1 is Read.Ok)

        val old = BoardsWire.timelineV1OrShim(today, 300, 24, { Read.Unavailable(Why.OUTDATED, "too old") }, legacy)
        assertEquals(1, legacyCalls); assertEquals(shim, old)

        val refused = Read.Unavailable(Why.NO_PERMISSION, "no")
        assertEquals(refused, BoardsWire.timelineV1OrShim(today, 300, 24, { refused }, legacy)); assertEquals(1, legacyCalls)
    }
}
