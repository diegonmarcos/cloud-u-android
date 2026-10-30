package com.diegonmarcos.cloudme.health

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Health > Tracked: the declaration, the ring, the chart grid, its summary and history, and manual entry bounds. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TrackedTest {
    private val metrics = Tracked.parse(HealthTestData.text("data/health/${Tracked.ASSET}"))
    private fun m(id: String) = metrics.first { it.id == id }

    @Test fun `the eight Fit-style cards are declared, each mapped in TrackedHc`() {
        assertEquals(listOf("steps", "active", "calories", "distance", "heart", "sleep", "weight", "water"), metrics.map { it.id })
        assertEquals(TrackedHc.IDS.toSet(), metrics.map { it.id }.toSet())
        assertTrue(m("heart").avg && m("weight").avg && !m("steps").avg)
    }

    @Test fun `the ring fills toward the goal and stops at full, no goal no ring`() {
        assertEquals(0.5f, Tracked.progress(4000.0, 8000.0)!!, 0.0001f)
        assertEquals(1f, Tracked.progress(12000.0, 8000.0)!!, 0.0001f)
        assertEquals(0f, Tracked.progress(null, 8000.0)!!, 0.0001f)
        assertNull(Tracked.progress(70.0, 0.0))
    }

    @Test fun `day is 24 hourly slots, week 7 and month 30 daily slots ending today, gaps stay null`() {
        val today = LocalDate.of(2026, 10, 30)
        val day = Tracked.grid(TrackedRange.DAY, today, mapOf(today.atTime(9, 0) to 1200.0))
        assertEquals(24, day.size); assertEquals(1200.0, day[9].value!!, 0.0); assertNull(day[10].value)
        val week = Tracked.grid(TrackedRange.WEEK, today, mapOf(today.minusDays(6).atStartOfDay() to 5.0))
        assertEquals(7, week.size); assertEquals(today.minusDays(6).atStartOfDay(), week.first().start); assertEquals(today.atStartOfDay(), week.last().start)
        assertEquals(5.0, week.first().value!!, 0.0)
        assertEquals(30, Tracked.grid(TrackedRange.MONTH, today, emptyMap()).size)
    }

    @Test fun `summary totals sum metrics and averages avg metrics over slots with data, history newest first`() {
        val today = LocalDate.of(2026, 10, 30)
        val g = Tracked.grid(TrackedRange.WEEK, today, mapOf(today.atStartOfDay() to 70.0, today.minusDays(1).atStartOfDay() to 72.0))
        assertEquals(71.0, Tracked.summary(m("weight"), g)!!, 1e-9)
        assertEquals(142.0, Tracked.summary(m("steps"), g)!!, 1e-9)
        assertNull(Tracked.summary(m("steps"), Tracked.grid(TrackedRange.WEEK, today, emptyMap())))
        assertEquals(listOf(today.atStartOfDay(), today.minusDays(1).atStartOfDay()), Tracked.history(g).map { it.start })
    }

    @Test fun `a manual entry is positive and below its ceiling`() {
        assertTrue(Tracked.validEntry(m("weight"), 72.5))
        assertFalse(Tracked.validEntry(m("weight"), 7250.0))
        assertFalse(Tracked.validEntry(m("water"), 0.0))
        assertFalse(Tracked.validEntry(m("sleep"), null))
        assertEquals("--", Tracked.format(m("steps"), null)); assertEquals("1.25", Tracked.format(m("distance"), 1.25))
    }
}
