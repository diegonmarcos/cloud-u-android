package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The fleet door's contract v1: exact field names the readers (Boards ME, MyHealth, MyTrips) parse. */
class DayJsonContractTest {
    private val zone = ZoneOffset.UTC
    private val d = LocalDate.of(2026, 10, 20)
    private fun at(h: Int, m: Int = 0) = d.atTime(h, m).toInstant(zone).toEpochMilli()
    private val store = listOf(
        TlSegment(TlKind.SLEEP, at(0) - 4 * 3_600_000L, at(8), 40.0, -3.0, "Home", 0, 0.0, "fix", null, "Madrid", "Spain"),
        TlSegment(TlKind.MOVE, at(8), at(9), null, null, null, 700, 500.0, null),
        TlSegment(TlKind.STOP, at(9), at(20), 40.1, -3.1, "Work", 0, 0.0, "fix", null, "Madrid", "Spain"),
    )

    @Test fun `segments v1 shape`() {
        val day = DayTimeline.build(store, at(0), at(0) + 24 * 3_600_000L)
        val j = DayJson.segments(day, at(0), at(0) + 24 * 3_600_000L)
        assertEquals(1, j.getInt("version")); assertEquals(at(0), j.getLong("from"))
        val segs = j.getJSONArray("segments")
        assertEquals(day.size, segs.length())
        val stay = segs.getJSONObject(0)
        for (k in listOf("type", "kind", "start", "end", "confirmed", "missing", "location")) assertTrue(k, stay.has(k))
        assertEquals("stay", stay.getString("type")); assertEquals("sleep", stay.getString("kind"))
        for (k in listOf("lat", "lon", "name", "street", "postcode", "neighborhood", "city", "state", "country", "address")) assertTrue(k, stay.getJSONObject("location").has(k))
        val move = segs.getJSONObject(1)
        for (k in listOf("type", "start", "end", "distance_m", "steps", "avg_speed_mps", "moving_ms", "mode", "from", "to")) assertTrue(k, move.has(k))
        assertEquals("move", move.getString("type")); assertEquals("walk", move.getString("mode")); assertEquals(500L, move.getLong("distance_m"))
        // gapless on the wire too
        var cursor = at(0)
        for (i in 0 until segs.length()) { val s = segs.getJSONObject(i); assertEquals(cursor, s.getLong("start")); cursor = s.getLong("end") }
        assertEquals(at(0) + 24 * 3_600_000L, cursor)
    }

    @Test fun `daily v1 shape`() {
        val j = DayJson.daily(Aggregates.perDay(store, listOf(d), at(23, 59), zone))
        assertEquals(1, j.getInt("version"))
        val day = j.getJSONArray("days").getJSONObject(0)
        for (k in listOf("date", "sleep_ms", "sleep_h", "sleep_city", "walk_m", "run_m", "vehicle_m", "by_mode", "visits")) assertTrue(k, day.has(k))
        assertEquals("2026-10-20", day.getString("date")); assertEquals("Madrid", day.getString("sleep_city"))
        assertEquals(500L, day.getLong("walk_m")); assertEquals(12.0, day.getDouble("sleep_h"), 0.0)
        assertTrue(day.getJSONObject("vehicle_m").length() == 0)
        assertEquals(500L, day.getJSONObject("by_mode").getJSONObject("walk").getLong("distance_m"))
    }

    @Test fun `corrections round-trip through the store encoding`() {
        val c = listOf(
            Correction(Correction.Type.EDIT_PLACE, 1L, 2L, place = Place(40.0, -3.0, "Dentist", city = "Madrid")),
            Correction(Correction.Type.EDIT_MODE, 3L, 4L, mode = Mode.TRAIN),
            Correction(Correction.Type.SPLIT, 5L, 9L, atMs = 7L),
        )
        val back = CorrectionStore.decode(CorrectionStore.encode(c).toString())
        assertEquals(c, back)
        assertTrue(JSONObject(DayJson.segment(DaySeg.Stay(1, 2, StayKind.IDLE, null)).toString()).isNull("location"))
    }
}
