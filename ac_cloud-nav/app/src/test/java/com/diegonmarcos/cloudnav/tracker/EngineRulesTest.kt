package com.diegonmarcos.cloudnav.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRulesTest {
    private val h = 3_600_000L
    private val on = EngineConfig(enabled = true)

    // ── source selection ──────────────────────────────────────────────
    @Test fun gpsOnly_accurateFromGps_noCellUpdates() {
        val c = on.copy(useGps = true, useCell = false)
        assertEquals(Provider.GPS, SourceRules.accurateProvider(c))
        assertNull(SourceRules.lowPowerProvider(c))
    }
    @Test fun gpsOff_accurateFromCellTowers() {
        val c = on.copy(useGps = false, useCell = true)
        assertEquals(Provider.NETWORK, SourceRules.accurateProvider(c))
        assertEquals(Provider.NETWORK, SourceRules.lowPowerProvider(c))
    }
    @Test fun both_gpsUsedForAccurateFix() {
        val c = on.copy(useGps = true, useCell = true)
        assertEquals(Provider.GPS, SourceRules.accurateProvider(c))
        assertEquals(Provider.NETWORK, SourceRules.lowPowerProvider(c))
    }
    @Test fun atLeastOneSourceStays() {
        assertNull(on.withSources(false, false))
        assertNotNull(on.withSources(true, false))
        assertNotNull(on.withSources(false, true))
    }

    // ── trigger scheduling: 4 h / 2000 steps / 1 h / 100 steps ────────
    private val fresh = EngineState(lastAccurateMs = 1_000 * h, lastCellMs = 1_000 * h)

    @Test fun nothingDueRightAfterAFix() {
        assertTrue(TriggerPlanner.due(on, fresh, 1_000 * h + 60_000).isEmpty())
    }
    @Test fun firstRunTakesAnAccurateFix() {
        assertEquals(listOf(Action.ACCURATE_FIX), TriggerPlanner.due(on, EngineState(), 5 * h))
    }
    @Test fun accurateEveryFourHours() {
        assertTrue(TriggerPlanner.due(on, fresh, 1_000 * h + 4 * h - 1).none { it == Action.ACCURATE_FIX })
        assertEquals(listOf(Action.ACCURATE_FIX), TriggerPlanner.due(on, fresh, 1_000 * h + 4 * h))
    }
    @Test fun accurateEvery2000Steps() {
        val now = 1_000 * h + 10 * 60_000
        assertFalse(Action.ACCURATE_FIX in TriggerPlanner.due(on, fresh.copy(stepsSinceAccurate = 1_999), now))
        assertEquals(listOf(Action.ACCURATE_FIX), TriggerPlanner.due(on, fresh.copy(stepsSinceAccurate = 2_000), now))
    }
    @Test fun cellEveryHour() {
        assertTrue(TriggerPlanner.due(on, fresh, 1_000 * h + h - 1).isEmpty())
        assertEquals(listOf(Action.CELL_UPDATE), TriggerPlanner.due(on, fresh, 1_000 * h + h))
    }
    @Test fun motionEvery100Steps() {
        val now = 1_000 * h + 10 * 60_000
        assertTrue(TriggerPlanner.due(on, fresh.copy(stepsSinceMotion = 99), now).isEmpty())
        assertEquals(listOf(Action.MOTION_ESTIMATE), TriggerPlanner.due(on, fresh.copy(stepsSinceMotion = 100), now))
    }
    @Test fun accurateSupersedesLowPower() {
        val s = fresh.copy(stepsSinceAccurate = 2_500, stepsSinceMotion = 500)
        assertEquals(listOf(Action.ACCURATE_FIX), TriggerPlanner.due(on, s, 1_000 * h + 2 * h))
    }
    @Test fun cellAndMotionCanBeDueTogether() {
        val s = fresh.copy(stepsSinceAccurate = 300, stepsSinceMotion = 300)
        assertEquals(listOf(Action.CELL_UPDATE, Action.MOTION_ESTIMATE), TriggerPlanner.due(on, s, 1_000 * h + 2 * h))
    }
    @Test fun disabledTriggersNeverFire() {
        val c = on.copy(accurateByTime = false, accurateBySteps = false, cellByTime = false, motionBySteps = false)
        val s = fresh.copy(stepsSinceAccurate = 99_999, stepsSinceMotion = 99_999)
        assertTrue(TriggerPlanner.due(c, s, 1_000 * h + 99 * h).isEmpty())
    }
    @Test fun noCellUpdatesWithoutTheCellSource() {
        val c = on.copy(useGps = true, useCell = false)
        assertTrue(TriggerPlanner.due(c, fresh, 1_000 * h + 3 * h).isEmpty())
    }
    @Test fun engineOffDoesNothing() {
        assertTrue(TriggerPlanner.due(on.copy(enabled = false), EngineState(), 5 * h).isEmpty())
    }
    @Test fun presetsStepAndClamp() {
        assertEquals(6, EngineConfig.step(EngineConfig.HOURS_ACCURATE, 4, 1))
        assertEquals(2, EngineConfig.step(EngineConfig.HOURS_ACCURATE, 4, -1))
        assertEquals(24, EngineConfig.step(EngineConfig.HOURS_ACCURATE, 24, 1))
        assertEquals(1, EngineConfig.step(EngineConfig.HOURS_ACCURATE, 1, -1))
    }

    // ── explanation matches the rules ────────────────────────────────
    @Test fun explanationFollowsTheConfig() {
        val text = Explanation.sections(on).joinToString(" ") { it.second }
        assertTrue("4 h" in text && "2000 steps" in text && "100 steps" in text && "GPS" in text)
        val noGps = Explanation.sections(on.copy(useGps = false)).joinToString(" ") { it.second }
        assertTrue("GPS is off" in noGps)
        val noCell = Explanation.sections(on.copy(useCell = false)).joinToString(" ") { it.second }
        assertTrue("no cell-tower updates" in noCell)
    }
}
