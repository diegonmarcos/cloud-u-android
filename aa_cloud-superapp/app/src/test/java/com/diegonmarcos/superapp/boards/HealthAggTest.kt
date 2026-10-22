package com.diegonmarcos.superapp.boards

import com.diegonmarcos.superapp.health.HealthBoardReader
import com.diegonmarcos.superapp.health.HealthBoardReader.Night
import com.diegonmarcos.superapp.health.HealthBoardReader.Raw
import com.diegonmarcos.superapp.health.HealthBoardReader.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The My-Health aggregation rules: km conversion, averages, and missing days (missing is never zero). */
class HealthAggTest {
    private val today = LocalDate.of(2026, 10, 22)
    private fun raw(
        steps: Map<LocalDate, Long> = emptyMap(), dist: Map<LocalDate, Double> = emptyMap(),
        rhr: Map<LocalDate, Long> = emptyMap(), sleep: Map<LocalDate, Night> = emptyMap(), height: Double? = null,
    ) = Raw(Status.READY, emptySet(), steps, dist, rhr, sleep, height)

    // ── km conversion ───────────────────────────────────────────────

    @Test fun `a distance record wins over the step estimate`() {
        val k = HealthAgg.km(5000.0, 9000, 0.75)!!
        assertEquals(5.0, k.first, 1e-9); assertFalse(k.second)
    }

    @Test fun `without distance km is steps times stride and is marked estimated`() {
        val k = HealthAgg.km(null, 10_000, 0.75)!!
        assertEquals(7.5, k.first, 1e-9); assertTrue(k.second)
        assertTrue(HealthAgg.km(0.0, 10_000, 0.75)!!.second)   // a zero distance record is no record
    }

    @Test fun `no distance and no steps is missing, not zero`() {
        assertNull(HealthAgg.km(null, null, 0.75))
        assertNull(HealthAgg.km(null, 0, 0.75))
    }

    @Test fun `stride comes from height when plausible, else 0_75 m`() {
        assertEquals(0.75, HealthAgg.stride(null).first, 1e-9); assertFalse(HealthAgg.stride(null).second)
        assertEquals(0.75, HealthAgg.stride(0.3).first, 1e-9)    // a child-size or garbage height is ignored
        assertEquals(1.80 * 0.415, HealthAgg.stride(1.80).first, 1e-9); assertTrue(HealthAgg.stride(1.80).second)
    }

    @Test fun `text is distance first with the steps in brackets`() {
        assertEquals("8.4km (11,234 steps)", HealthAgg.kmStepsText(8.4, 11_234))
        assertEquals("~7.5km (10,000 steps)", HealthAgg.kmStepsText(7.5, 10_000, estimated = true))
        assertEquals("--", HealthAgg.kmText(null))
        assertEquals("7h 05m", HealthAgg.hm(425.0)); assertEquals("--", HealthAgg.hm(null))
    }

    // ── averages and missing days ───────────────────────────────────

    @Test fun `the window is N days ending today, oldest first`() {
        val w = HealthAgg.window(today, 7)
        assertEquals(7, w.size); assertEquals(today.minusDays(6), w.first()); assertEquals(today, w.last())
    }

    @Test fun `steps averages cover only days with data`() {
        // 3 of the last 7 days have steps (and distance): 4 km, 6 km, 8 km -> 6 km average over 3 days, not over 7.
        val r = raw(steps = mapOf(today to 8000L, today.minusDays(2) to 6000L, today.minusDays(5) to 4000L),
            dist = mapOf(today to 8000.0, today.minusDays(2) to 6000.0, today.minusDays(5) to 4000.0))
        val s = HealthAgg.steps(r, today)
        assertEquals(6.0, s.avg7.km!!, 1e-9); assertEquals(3, s.avg7.days); assertEquals(6000L, s.avg7.steps)
        assertEquals(6.0, s.avg30.km!!, 1e-9); assertEquals(3, s.avg30.days)
        assertEquals(8.0, s.today.km!!, 1e-9); assertEquals(8000L, s.today.steps)
        assertFalse(s.avg7.estimated)
        assertEquals(7, s.week.size); assertNull(s.week[0].km)   // a missing day stays missing in the chart
    }

    @Test fun `7-day and 30-day averages differ by window`() {
        val steps = HealthAgg.window(today, 30).associateWith { 10_000L }.toMutableMap()
        HealthAgg.window(today, 30).take(23).forEach { steps[it] = 2_000L }   // older 23 days are low
        val s = HealthAgg.steps(raw(steps = steps, height = null), today)
        assertEquals(10_000L, s.avg7.steps)
        assertEquals(Math.round((23 * 2_000L + 7 * 10_000L) / 30.0), s.avg30.steps)
        assertTrue(s.avg7.estimated); assertEquals(7.5, s.avg7.km!!, 1e-9)   // 10 000 x 0.75 m
    }

    @Test fun `an average that mixes real and estimated days is marked estimated`() {
        val r = raw(steps = mapOf(today to 10_000L, today.minusDays(1) to 10_000L),
            dist = mapOf(today to 9000.0))
        val s = HealthAgg.steps(r, today)
        assertEquals((9.0 + 7.5) / 2, s.avg7.km!!, 1e-9); assertTrue(s.avg7.estimated)
        assertFalse(s.today.estimated); assertTrue(s.week[5].estimated)
    }

    @Test fun `no data at all is null everywhere, never zero`() {
        val b = HealthAgg.board(raw(), today)
        assertNull(b.steps.today.km); assertNull(b.steps.avg7.km); assertNull(b.steps.avg30.km)
        assertNull(b.rhr.today); assertNull(b.rhr.avg7); assertNull(b.rhr.avg30)
        assertNull(b.sleep.lastNight); assertNull(b.sleep.avg7); assertNull(b.sleep.avg30)
    }

    @Test fun `resting heart rate averages skip missing days`() {
        val r = raw(rhr = mapOf(today to 60L, today.minusDays(3) to 56L, today.minusDays(20) to 50L))
        val b = HealthAgg.rhr(r, today)
        assertEquals(60.0, b.today!!, 1e-9)
        assertEquals(58.0, b.avg7!!, 1e-9)
        assertEquals((60 + 56 + 50) / 3.0, b.avg30!!, 1e-9)
        assertNull(b.week[0].bpm)
    }

    @Test fun `sleep averages stages over the nights that exist`() {
        val a = Night(60, 90, 240, 30, true); val b = Night(80, 110, 200, 10, true)
        val s = HealthAgg.sleep(raw(sleep = mapOf(today to a, today.minusDays(2) to b)), today)
        assertEquals(a, s.lastNight)
        val avg = s.avg7!!
        assertEquals(70.0, avg.deepMin, 1e-9); assertEquals(100.0, avg.remMin, 1e-9)
        assertEquals(220.0, avg.lightMin, 1e-9); assertEquals(20.0, avg.awakeMin, 1e-9)
        assertEquals(2, avg.nights); assertEquals(390.0, avg.asleepMin, 1e-9)
    }

    @Test fun `last night is missing when today has no session`() {
        val s = HealthAgg.sleep(raw(sleep = mapOf(today.minusDays(1) to Night(1, 1, 1, 1, true))), today)
        assertNull(s.lastNight); assertNotNull(s.avg7)
    }

    // ── permissions and sessions ────────────────────────────────────

    @Test fun `missing permissions are the four reads not granted, height is optional`() {
        assertEquals(4, HealthAgg.missing(emptySet()).size)
        assertEquals(setOf(HealthBoardReader.SLEEP),
            HealthAgg.missing(setOf(HealthBoardReader.STEPS, HealthBoardReader.DISTANCE, HealthBoardReader.RESTING_HR)))
        assertTrue(HealthAgg.missing(HealthBoardReader.BOARD_PERMISSIONS).isEmpty())
    }

    @Test fun `sessions become nights by stage and by wake-up day`() {
        fun t(min: Long) = Instant.parse("2026-10-22T00:00:00Z").plusSeconds(min * 60)
        val staged = HealthBoardReader.SessionIn(today, t(0), t(480), listOf(
            HealthBoardReader.StageIn(t(0), t(60), 4), HealthBoardReader.StageIn(t(60), t(120), 5),   // light, deep
            HealthBoardReader.StageIn(t(120), t(180), 6), HealthBoardReader.StageIn(t(180), t(190), 1), // REM, awake
            HealthBoardReader.StageIn(t(190), t(200), 3)))                                              // out of bed: ignored
        val nap = HealthBoardReader.SessionIn(today, t(600), t(630), emptyList())
        val n = HealthBoardReader.nights(listOf(staged, nap))[today]!!
        assertEquals(60, n.deepMin); assertEquals(60, n.remMin); assertEquals(90, n.lightMin); assertEquals(10, n.awakeMin)
        assertFalse(n.staged)   // the nap had no stages
    }
}
