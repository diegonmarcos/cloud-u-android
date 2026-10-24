package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** #1156 Tracker pages: the rows are the provider's contract-v1 JSON (built here by the provider's own encoders), mapped, not recomputed. */
class TrackerRowsTest {
    private val zone = ZoneOffset.UTC
    private val d = LocalDate.of(2026, 10, 20)
    private fun at(h: Int, m: Int = 0) = d.atTime(h, m).toInstant(zone).toEpochMilli()
    private val store = listOf(
        TlSegment(TlKind.SLEEP, at(0) - 4 * 3_600_000L, at(8), 10.0, 20.0, "Home", 0, 0.0, "fix", null, "Testville", "Testland"),
        TlSegment(TlKind.MOVE, at(8), at(9), null, null, null, 700, 500.0, null),
        TlSegment(TlKind.STOP, at(9), at(20), 10.1, 20.1, "Work", 0, 0.0, "fix", null, "Testville", "Testland"),
    )

    @Test fun `timeline lists every stay and move of tracker segments in order`() {
        val day = DayTimeline.build(store, at(0), at(0) + 24 * 3_600_000L)
        val json = DayJson.segments(day, at(0), at(0) + 24 * 3_600_000L)
        val rows = TrackerRows.timeline(json)
        assertEquals(day.size, rows.size)
        assertEquals(listOf("STAY", "MOVE", "STAY"), rows.take(3).map { it.type })
        val stay = rows[0]
        assertEquals("Home", stay.title); assertEquals("sleep", stay.mode); assertEquals(10.0, stay.lat!!, 1e-9); assertEquals(20.0, stay.lon!!, 1e-9)
        assertEquals(at(0), rows[0].startMs); assertEquals(at(8), rows[0].endMs)
        val move = rows[1]
        assertEquals("walk", move.mode); assertEquals(500L, move.distanceM); assertEquals(700L, move.steps)
        assertEquals("Home > Work", move.title); assertEquals(at(8), move.startMs); assertEquals(at(9), move.endMs)
        assertEquals("Work", rows[2].title)
        // gapless on the table too
        for (i in 1 until rows.size) assertEquals(rows[i - 1].endMs, rows[i].startMs)
    }

    @Test fun `an unnamed stay still gets a row and no coordinates`() {
        val j = JSONObject("""{"version":1,"segments":[{"type":"stay","kind":"idle","start":1,"end":2,"confirmed":false,"missing":true,"location":null}]}""")
        val r = TrackerRows.timeline(j).single()
        assertEquals("Unnamed place", r.title); assertEquals(null, r.lat); assertEquals("no coordinates", TrackerFormat.coords(r.lat, r.lon))
    }

    @Test fun `sleep rows come from tracker daily, newest first`() {
        val json = DayJson.daily(Aggregates.perDay(store, listOf(d, d.plusDays(1)), at(23, 59) + 24 * 3_600_000L, zone))
        val rows = TrackerRows.sleep(json)
        assertEquals(2, rows.size)
        assertEquals(listOf("2026-10-21", "2026-10-20"), rows.map { it.date })
        val first = rows.last()
        assertEquals(12.0, first.hours, 1e-9); assertEquals("Testville", first.city)
    }

    @Test fun `steps rows come from tracker steps, newest first, and the chart is normalised`() {
        val j = JSONObject("""{"today":10,"days":[{"date":"2026-10-18","steps":2000},{"date":"2026-10-19","steps":8000},{"date":"2026-10-20","steps":0}],"source":"DEVICE","status":"DEVICE_FALLBACK"}""")
        val rows = TrackerRows.steps(j)
        assertEquals(listOf("2026-10-20", "2026-10-19", "2026-10-18"), rows.map { it.date })
        assertEquals(listOf(0L, 8000L, 2000L), rows.map { it.steps })
        assertEquals(listOf(0.25f, 1f, 0f), TrackerRows.bars(listOf(2000.0, 8000.0, 0.0)))
        assertEquals(listOf(0f, 0f), TrackerRows.bars(listOf(0.0, 0.0)))
        assertTrue(TrackerRows.bars(emptyList()).isEmpty())
    }

    @Test fun `empty provider answers give empty tables`() {
        assertTrue(TrackerRows.timeline(JSONObject("{}")).isEmpty()); assertTrue(TrackerRows.steps(JSONObject("{}")).isEmpty()); assertTrue(TrackerRows.sleep(JSONObject("{}")).isEmpty())
    }
}
