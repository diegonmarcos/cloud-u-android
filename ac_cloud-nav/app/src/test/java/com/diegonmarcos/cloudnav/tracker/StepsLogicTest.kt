package com.diegonmarcos.cloudnav.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StepsLogicTest {
    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 10, 14)  // a Wednesday
    private fun at(h: Int, m: Int = 0, d: LocalDate = day) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val from = at(0); private val to = at(23, 59)

    // ── stop / moving segmentation ───────────────────────────────────
    @Test fun noStepsIsOneStop() {
        val s = Segmenter.segment(emptyList(), from, to)
        assertEquals(1, s.size); assertEquals(SegKind.STOP, s[0].kind)
    }
    @Test fun aWalkBetweenTwoStops() {
        val s = Segmenter.segment(listOf(StepSlice(at(9), at(9, 30), 3_000)), from, to, 0.7)
        assertEquals(listOf(SegKind.STOP, SegKind.MOVING, SegKind.STOP), s.map { it.kind })
        val m = s[1]
        assertEquals(at(9), m.startMs); assertEquals(at(9, 30), m.endMs)
        assertEquals(3_000L, m.steps)
        assertEquals(2_100.0, m.distanceM, 4.0)
        assertEquals(30 * 60_000L, m.durationMs)
    }
    @Test fun aShortPauseDoesNotSplitAWalk() {
        val s = Segmenter.segment(listOf(StepSlice(at(9), at(9, 20), 2_000), StepSlice(at(9, 25), at(9, 45), 2_000)), from, to)
        assertEquals(1, s.count { it.kind == SegKind.MOVING })
    }
    @Test fun aLongPauseSplitsIntoTwoMoves() {
        val s = Segmenter.segment(listOf(StepSlice(at(9), at(9, 20), 2_000), StepSlice(at(10), at(10, 20), 2_000)), from, to)
        assertEquals(2, s.count { it.kind == SegKind.MOVING })
        assertEquals(SegKind.STOP, s[2].kind)
    }
    @Test fun fidgetingIsNotMoving() {
        val s = Segmenter.segment(listOf(StepSlice(at(9), at(9, 5), 40)), from, to)
        assertEquals(listOf(SegKind.STOP), s.map { it.kind })
    }
    @Test fun slowCadenceIsAStop() {
        // 600 steps over an hour = 10 steps/min, under the 30/min cadence
        val s = Segmenter.segment(listOf(StepSlice(at(9), at(10), 600)), from, to)
        assertTrue(s.none { it.kind == SegKind.MOVING })
    }
    @Test fun locationDistanceBeatsStride() {
        val s = Segmenter.segment(listOf(StepSlice(at(9), at(9, 30), 3_000)), from, to, 0.7) { _, _ -> 1_234.0 }
        assertEquals(1_234.0, s.first { it.kind == SegKind.MOVING }.distanceM, 0.0)
    }
    @Test fun segmentsTileTheWindowWithoutGaps() {
        val s = Segmenter.segment(listOf(StepSlice(at(9), at(9, 30), 3_000), StepSlice(at(15), at(15, 40), 4_000)), from, to)
        assertEquals(from, s.first().startMs); assertEquals(to, s.last().endMs)
        s.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
    }

    // ── day / week / month aggregation ───────────────────────────────
    private val daily = mapOf(
        LocalDate.of(2026, 10, 14) to 4_000L,  // Wed (today)
        LocalDate.of(2026, 10, 12) to 3_000L,  // Mon
        LocalDate.of(2026, 10, 11) to 9_000L,  // Sun: previous week
        LocalDate.of(2026, 10, 1) to 1_000L,
        LocalDate.of(2026, 9, 30) to 7_000L,   // previous month
        LocalDate.of(2026, 10, 15) to 555L,    // future: ignored
    )
    @Test fun totalsForDayWeekMonth() {
        val t = StepStats.totals(daily, day)
        assertEquals(4_000L, t.day)
        assertEquals(7_000L, t.week)          // Mon 12 + Wed 14
        assertEquals(4_000L + 3_000 + 9_000 + 1_000, t.month)
    }
    @Test fun perDayGroupsSlicesByStartDay() {
        val m = StepStats.perDay(listOf(StepSlice(at(9), at(10), 100), StepSlice(at(20), at(21), 50), StepSlice(at(9, 0, day.plusDays(1)), at(10, 0, day.plusDays(1)), 7)), zone)
        assertEquals(150L, m[day]); assertEquals(7L, m[day.plusDays(1)])
    }
    @Test fun dayBucketsAreContiguousAndEndToday() {
        val b = StepStats.buckets(daily, Period.DAY, day, 5)
        assertEquals(listOf(10, 11, 12, 13, 14), b.map { it.start.dayOfMonth })
        assertEquals(listOf(0L, 9_000L, 3_000L, 0L, 4_000L), b.map { it.steps })
    }
    @Test fun weekBucketsStartOnMonday() {
        val b = StepStats.buckets(daily, Period.WEEK, day, 2)
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12)), b.map { it.start })
        assertEquals(9_000L, b[0].steps)           // Sun Oct 11; Oct 1 belongs to the week of Sep 28
    }
    @Test fun monthBuckets() {
        val b = StepStats.buckets(daily, Period.MONTH, day, 2)
        assertEquals(listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)), b.map { it.start })
        assertEquals(7_000L, b[0].steps)
        assertEquals(4_000L + 3_000 + 9_000 + 1_000 + 555, b[1].steps)
    }

    // ── morning / noon / night picker ────────────────────────────────
    private fun fix(h: Int, m: Int = 0, predicted: Boolean = false) = PlaceFix(at(h, m), 47.0, 8.0 + h / 100.0 + m / 10000.0, predicted, 10.0)

    @Test fun picksTheFixNearestEachTarget() {
        val fixes = listOf(fix(6), fix(7, 30), fix(9), fix(12), fix(13, 20), fix(16), fix(19), fix(20, 50), fix(23))
        val p = DayPlacePicker.pick(day, zone, fixes)
        assertEquals(at(7, 30), p[DayPart.MORNING]!!.ts)   // 30 min before 08:00 beats 60 min after
        assertEquals(at(13, 20), p[DayPart.NOON]!!.ts)     // 20 min after 13:00 beats 60 min before
        assertEquals(at(20, 50), p[DayPart.NIGHT]!!.ts)
    }
    @Test fun nothingInTheWindowIsNull() {
        val p = DayPlacePicker.pick(day, zone, listOf(fix(3), fix(15)))
        assertNull(p[DayPart.MORNING]); assertNotNull(p[DayPart.NOON]); assertNull(p[DayPart.NIGHT])
    }
    @Test fun windowsDoNotLeakAcrossMidnightOrDays() {
        val other = PlaceFix(at(21, 0, day.plusDays(1)), 1.0, 1.0)
        assertNull(DayPlacePicker.pick(day, zone, listOf(other))[DayPart.NIGHT])
    }
    @Test fun measuredBeatsPredictedWhenCloseEnough() {
        val p = DayPlacePicker.pick(day, zone, listOf(fix(8, 0, predicted = true), fix(8, 20)))
        assertEquals(at(8, 20), p[DayPart.MORNING]!!.ts)
    }
    @Test fun predictedWinsWhenMeasuredIsMuchFurther() {
        val p = DayPlacePicker.pick(day, zone, listOf(fix(8, 0, predicted = true), fix(10, 0)))
        assertTrue(p[DayPart.MORNING]!!.predicted)
    }

    // ── the page model ───────────────────────────────────────────────
    @Test fun modelBuildsCardsWithPlacesAndSegments() {
        val now = at(22)
        val ui = StepsModel.build(StepsSource.DEVICE, SourceStatus.DEVICE_FALLBACK, mapOf(day to 3_000L),
            listOf(StepSlice(at(9), at(9, 30), 3_000)), listOf(fix(8), fix(13, 5, true), fix(21)), zone, now, 0.7) { "P${it.ts % 1000}" }
        assertEquals(StepsModel.CARD_DAYS, ui.days.size)
        val card = ui.days.first()
        assertEquals(day, card.date); assertEquals(3_000L, card.steps)
        assertTrue(card.places[DayPart.NOON]!!.predicted)
        assertEquals(1, card.segments.count { it.kind == SegKind.MOVING })
        assertEquals(3_000L, ui.totals.day)
    }
}
