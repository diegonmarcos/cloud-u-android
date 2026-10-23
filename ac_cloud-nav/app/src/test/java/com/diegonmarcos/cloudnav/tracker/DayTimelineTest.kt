package com.diegonmarcos.cloudnav.tracker

import com.diegonmarcos.cloudnav.maps.TlKind
import com.diegonmarcos.cloudnav.maps.TlSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class DayTimelineTest {
    private val zone = ZoneOffset.UTC
    private val d = LocalDate.of(2026, 10, 20)
    private fun at(h: Int, m: Int = 0, day: LocalDate = d) = day.atTime(h, m).toInstant(zone).toEpochMilli()
    private val from = at(0); private val to = at(0, 0, d.plusDays(1))
    private val homeLat = 40.4168; private val homeLon = -3.7038

    private fun stay(kind: TlKind, a: Long, b: Long, name: String? = null, city: String? = "Madrid", lat: Double = homeLat) =
        TlSegment(kind, a, b, lat, homeLon, name, 0, 0.0, "fix", null, city, "Spain")
    private fun move(a: Long, b: Long, dist: Double, steps: Long) = TlSegment(TlKind.MOVE, a, b, null, null, null, steps, dist, null)

    private fun assertGapless(day: List<DaySeg>, a: Long = from, b: Long = to) {
        assertEquals(a, day.first().startMs); assertEquals(b, day.last().endMs)
        day.zipWithNext().forEach { (x, y) -> assertEquals("gap or overlap after ${x.endMs}", x.endMs, y.startMs) }
        assertEquals(b - a, day.sumOf { it.durationMs })
    }

    @Test fun `the example day is gapless 24 h`() {
        val store = listOf(
            stay(TlKind.SLEEP, at(20, 0, d.minusDays(1)), at(8), "Home"),
            move(at(8), at(9), 500.0, 700),
            stay(TlKind.STOP, at(10), at(20), "Office", lat = 40.45),
            stay(TlKind.SLEEP, at(21), at(23, 59), "Home"),
        )
        val day = DayTimeline.build(store, from, to)
        assertGapless(day)
        val first = day.first() as DaySeg.Stay
        assertEquals(StayKind.SLEEP, first.kind); assertEquals(at(8), first.endMs)
        val mv = day.filterIsInstance<DaySeg.Move>().single()
        assertEquals(Mode.WALK, mv.mode); assertEquals(500.0, mv.distanceM, 1e-6)
        // 09:00-10:00 was a gap: it belongs to the stay before? no - the move ended there, so the next stay starts earlier
        assertEquals(at(9), (day[2] as DaySeg.Stay).startMs)
    }

    @Test fun `an empty day is one unplaced stay and counts as a missing visit`() {
        val day = DayTimeline.build(emptyList(), from, to)
        assertGapless(day)
        assertEquals(1, day.size)
        assertEquals(1, DayTimeline.missingVisits(day).size)
    }

    @Test fun `a gap between moves becomes a missing visit`() {
        val store = listOf(move(at(8), at(9), 900.0, 1200), move(at(12), at(13), 900.0, 1200))
        val day = DayTimeline.build(store, from, to)
        assertGapless(day)
        val missing = DayTimeline.missingVisits(day)
        assertTrue(missing.any { it.startMs == at(9) && it.endMs == at(12) })
        assertTrue(missing.all { it.missing && it.durationMs >= DayTimeline.MISSING_MIN_MS })
    }

    @Test fun `short unplaced time is not offered as a missing visit`() {
        val store = listOf(move(at(8), at(9), 900.0, 1200), move(at(9, 5), at(10), 900.0, 1200))
        val day = DayTimeline.build(store, from, at(10))
        assertGapless(day, from, at(10))
        assertTrue(DayTimeline.missingVisits(day).none { it.startMs == at(9) })
    }

    @Test fun `a gap after a stay is covered by that stay`() {
        val day = DayTimeline.build(listOf(stay(TlKind.STOP, at(9), at(10), "Cafe")), from, to)
        assertGapless(day)
        assertEquals(1, day.size); assertEquals(from, day[0].startMs); assertFalse((day[0] as DaySeg.Stay).missing)
    }

    @Test fun `today ends at now and is still gapless`() {
        val now = at(15)
        val day = DayTimeline.build(listOf(stay(TlKind.STOP, at(9), at(12), "Cafe")), from, now)
        assertGapless(day, from, now)
    }

    @Test fun `segments crossing midnight are cut with steps and distance in proportion`() {
        val day = DayTimeline.build(listOf(move(at(23), at(1, 0, d.plusDays(1)), 2000.0, 2000)), at(22), to)
        val mv = day.filterIsInstance<DaySeg.Move>().single()
        assertEquals(at(23), mv.startMs); assertEquals(to, mv.endMs)
        assertEquals(1000.0, mv.distanceM, 1e-6); assertEquals(1000L, mv.steps)
    }

    @Test fun `steps without a distance are measured with the step length`() {
        val day = DayTimeline.build(listOf(move(at(8), at(9), 0.0, 1000)), from, to)
        assertEquals(750.0, day.filterIsInstance<DaySeg.Move>().single().distanceM, 1e-6)
    }

    @Test fun `moves know the places they leave and reach`() {
        val store = listOf(stay(TlKind.STOP, at(7), at(8), "Home"), move(at(8), at(9), 5000.0, 0), stay(TlKind.STOP, at(9), at(18), "Work", lat = 40.5))
        val mv = DayTimeline.build(store, from, to).filterIsInstance<DaySeg.Move>().single()
        assertEquals("Home", mv.from?.name); assertEquals("Work", mv.to?.name)
    }

    // ---- visit confirm / edit and the other corrections -------------------------------------------------------------

    private fun visitDay(vararg c: Correction, memory: List<Remembered> = emptyList()) =
        DayTimeline.build(listOf(stay(TlKind.STOP, at(10), at(12), null, city = "Madrid")), at(10), at(12), c.toList(), memory)

    @Test fun `an unconfirmed visit is listed for Visited X, and Yes confirms it`() {
        val named = listOf(stay(TlKind.STOP, at(10), at(12), "Cafe Sol"))
        val before = DayTimeline.build(named, at(10), at(12))
        assertEquals(1, DayTimeline.unconfirmed(before).size)
        val s = before.single() as DaySeg.Stay
        val after = DayTimeline.build(named, at(10), at(12), listOf(Edits.confirm(s)))
        assertTrue((after.single() as DaySeg.Stay).confirmed)
        assertTrue(DayTimeline.unconfirmed(after).isEmpty())
    }

    @Test fun `Edit renames the visit, confirms it and the name is remembered for later stays`() {
        val s = visitDay().single() as DaySeg.Stay
        assertNull(s.place?.name)
        val edit = Edits.editPlace(s, "Dentist")
        val after = visitDay(edit).single() as DaySeg.Stay
        assertEquals("Dentist", after.place?.name); assertTrue(after.confirmed)
        // feedback: another day, same spot, unnamed in the store -> takes the remembered name
        val memory = CorrectionStore.memoryOf(listOf(edit))
        assertEquals(1, memory.size)
        val other = DayTimeline.build(listOf(stay(TlKind.STOP, at(10), at(12), null)), at(10), at(12), memory = memory).single() as DaySeg.Stay
        assertEquals("Dentist", other.place?.name)
        // far away: not renamed
        val far = DayTimeline.build(listOf(stay(TlKind.STOP, at(10), at(12), null, lat = 41.0)), at(10), at(12), memory = memory).single() as DaySeg.Stay
        assertNull(far.place?.name)
    }

    @Test fun `a correction survives a rebuild that shifts the boundaries a little`() {
        val s = visitDay().single() as DaySeg.Stay
        val edit = Edits.editPlace(s, "Dentist")
        val shifted = DayTimeline.build(listOf(stay(TlKind.STOP, at(10, 10), at(12, 20), null)), at(10), at(13), listOf(edit))
        assertEquals("Dentist", (shifted.first() as DaySeg.Stay).place?.name)
    }

    @Test fun `editing the mode of a move sticks and feeds the summary`() {
        val store = listOf(move(at(8), at(9), 20000.0, 0))
        val m = DayTimeline.build(store, at(8), at(9)).single() as DaySeg.Move
        assertEquals(Mode.CAR, m.mode)
        val fixed = DayTimeline.build(store, at(8), at(9), listOf(Edits.editMode(m, Mode.TRAIN))).single() as DaySeg.Move
        assertEquals(Mode.TRAIN, fixed.mode); assertTrue(fixed.modeFixed)
        assertEquals(40000.0, Aggregates.summary(listOf(fixed)).byMode[Mode.TRAIN]!!.distanceM, 1e-6)
    }

    @Test fun `delete removes a false move and a stay stretches over it`() {
        val store = listOf(stay(TlKind.STOP, at(8), at(9), "Home"), move(at(9), at(9, 20), 300.0, 400), stay(TlKind.STOP, at(9, 20), at(12), "Home"))
        val day = DayTimeline.build(store, at(8), at(12))
        val mv = day.filterIsInstance<DaySeg.Move>().single()
        val after = DayTimeline.build(store, at(8), at(12), listOf(Edits.delete(mv)))
        assertGapless(after, at(8), at(12))
        assertTrue(after.none { it is DaySeg.Move })
    }

    @Test fun `split cuts a stay in two at the chosen time`() {
        val s = visitDay().single()
        val after = DayTimeline.build(listOf(stay(TlKind.STOP, at(10), at(12), "A")), at(10), at(12), listOf(Edits.split(s, at(11))))
        assertEquals(2, after.size); assertEquals(at(11), after[0].endMs); assertEquals(at(11), after[1].startMs)
    }

    @Test fun `splitting a move shares distance and steps`() {
        val store = listOf(move(at(8), at(10), 4000.0, 0))
        val m = DayTimeline.build(store, at(8), at(10)).single()
        val after = DayTimeline.build(store, at(8), at(10), listOf(Edits.split(m, at(9)))).filterIsInstance<DaySeg.Move>()
        assertEquals(2, after.size); assertEquals(2000.0, after[0].distanceM, 1e-6); assertEquals(4000.0, after.sumOf { it.distanceM }, 1e-6)
    }

    @Test fun `Add visit fills a missing visit`() {
        val store = listOf(move(at(8), at(9), 900.0, 1200), move(at(12), at(13), 900.0, 1200))
        val day = DayTimeline.build(store, from, to)
        val gap = DayTimeline.missingVisits(day).first { it.startMs == at(9) }
        val after = DayTimeline.build(store, from, to, listOf(Edits.addVisit(gap.startMs, gap.endMs, "Library", null)))
        assertGapless(after)
        val v = after.filterIsInstance<DaySeg.Stay>().first { it.startMs == at(9) }
        assertEquals("Library", v.place?.name); assertTrue(v.confirmed); assertFalse(v.missing)
        assertTrue(DayTimeline.missingVisits(after).none { it.startMs == at(9) })
    }

    @Test fun `Add visit over a longer stay trims it`() {
        val store = listOf(stay(TlKind.STOP, at(8), at(18), "Office"))
        val after = DayTimeline.build(store, at(8), at(18), listOf(Edits.addVisit(at(12), at(13), "Lunch", null)))
        assertGapless(after, at(8), at(18))
        assertEquals(listOf("Office", "Lunch", "Office"), after.map { (it as DaySeg.Stay).place?.name })
    }

    // ---- summary -------------------------------------------------------------------------------------------------------

    @Test fun `summary has distance and moving time per mode and the number of visits`() {
        val store = listOf(
            stay(TlKind.STOP, at(7), at(8), "Home"),
            move(at(8), at(9), 600.0, 800),                  // walk
            stay(TlKind.STOP, at(9), at(10), "Cafe", lat = 40.42),
            move(at(10), at(10, 30), 15000.0, 0),            // 30 km/h avg -> car
            stay(TlKind.STOP, at(10, 30), at(12), "Work", lat = 40.5),
            move(at(12), at(12, 30), 3000.0, 0),             // 6.7 km/h?? no: 1.67 m/s -> walk by speed
        )
        val sum = Aggregates.summary(DayTimeline.build(store, at(7), at(12, 30)))
        assertEquals(3, sum.visits)
        assertEquals(3600.0, sum.byMode[Mode.WALK]!!.distanceM, 1e-6)
        assertEquals(90 * 60_000L, sum.byMode[Mode.WALK]!!.movingMs)
        assertEquals(15000.0, sum.byMode[Mode.CAR]!!.distanceM, 1e-6)
        assertEquals(30 * 60_000L, sum.byMode[Mode.CAR]!!.movingMs)
        assertEquals(18600.0, sum.totalDistanceM, 1e-6)
        assertEquals(2 * 3_600_000L, sum.totalMovingMs)
    }

    @Test fun `places cities and countries rank by time`() {
        val day1 = DayTimeline.build(listOf(stay(TlKind.STOP, at(8), at(18), "Office", city = "Madrid")), at(8), at(18))
        val day2 = DayTimeline.build(listOf(stay(TlKind.STOP, at(8), at(10), "Hotel", city = "Lisbon").copy(country = "Portugal")), at(8), at(10))
        assertEquals("Office", Rankings.places(listOf(day1, day2)).first().label)
        assertEquals(listOf("Madrid", "Lisbon"), Rankings.cities(listOf(day1, day2)).map { it.city })
        assertEquals(listOf("Spain", "Portugal"), Rankings.countries(listOf(day1, day2)).map { it.country })
        assertNotNull(Rankings.countries(listOf(day1)).single())
    }
}
