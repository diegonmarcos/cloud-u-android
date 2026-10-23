package com.diegonmarcos.cloudnav.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1145: wake sources start a pass at once; GPS is only ever asked while moving; the tick is the fallback only. */
class WakePolicyTest {
    private val now = 10_000_000L
    private val cfg = EngineConfig(enabled = true, useGps = true, useCell = true)

    @Test fun `activity moving and geofence exit start a MOVE, still ends it`() {
        val m = WakePolicy.apply(WakeState(), WakeKind.ACTIVITY_MOVING, now)
        assertTrue(m.moving); assertEquals(now + WakePolicy.MOVE_HOLD_MS, m.untilMs)
        assertTrue(WakePolicy.apply(WakeState(), WakeKind.GEOFENCE_EXIT, now).moving)
        assertFalse(WakePolicy.apply(m, WakeKind.ACTIVITY_STILL, now + 1).moving)
        assertEquals(m, WakePolicy.apply(m, WakeKind.TICK, now + 1))
    }

    @Test fun `significant motion alone holds only briefly and never shortens a longer hold`() {
        val s = WakePolicy.apply(WakeState(), WakeKind.SIGNIFICANT_MOTION, now)
        assertEquals(now + WakePolicy.MOTION_HOLD_MS, s.untilMs)
        val long = WakePolicy.apply(WakeState(), WakeKind.ACTIVITY_MOVING, now)
        assertEquals(long, WakePolicy.apply(long, WakeKind.SIGNIFICANT_MOTION, now + 1))
    }

    @Test fun `a lapsed hold is not moving, the step cadence still wins`() {
        val s = WakePolicy.apply(WakeState(), WakeKind.ACTIVITY_MOVING, now)
        assertTrue(WakePolicy.effectiveMoving(false, s, now + WakePolicy.MOVE_HOLD_MS - 1))
        assertFalse(WakePolicy.effectiveMoving(false, s, now + WakePolicy.MOVE_HOLD_MS))
        assertTrue(WakePolicy.effectiveMoving(true, WakeState(), now))
    }

    @Test fun `no high-accuracy request during STAY whatever the wake history`() {
        // STAY = not moving after any sequence of wakes ending in STILL, or after the hold lapsed
        for (g in listOf(true, false)) for (c in listOf(true, false)) {
            val conf = EngineConfig(enabled = true, useGps = g, useCell = c)
            var s = WakePolicy.apply(WakeState(), WakeKind.ACTIVITY_MOVING, now)
            s = WakePolicy.apply(s, WakeKind.ACTIVITY_STILL, now + 1)
            val moving = WakePolicy.effectiveMoving(false, s, now + 2)
            assertFalse(moving)
            assertNotEquals(Provider.GPS, BatteryPolicy.accurateProvider(conf, moving))
            assertFalse(BatteryPolicy.allowGps(moving))
        }
    }

    @Test fun `a MOVE wake plans an accurate fix at once, then at most every gap`() {
        val st = EngineState(lastAccurateMs = now - 1_000)
        assertEquals(emptyList<Action>(), TriggerPlanner.due(cfg.copy(accurateBySteps = false, motionBySteps = false, cellByTime = false), st, now, moving = true))
        val later = now + WakePolicy.MOVE_FIX_GAP_MS
        assertEquals(listOf(Action.ACCURATE_FIX), TriggerPlanner.due(cfg, st, later, moving = true))
        assertFalse(Action.ACCURATE_FIX in TriggerPlanner.due(cfg, st.copy(lastAccurateMs = now), now + 60_000, moving = false))
    }

    @Test fun `the periodic tick is relaxed only while a wake source is armed`() {
        assertEquals(15L, WakePolicy.tickMinutes(false))
        assertEquals(60L, WakePolicy.tickMinutes(true))
    }

    @Test fun `geofence radius follows accuracy within bounds and re-arms when the STAY moves`() {
        assertEquals(WakePolicy.FENCE_MIN_M, WakePolicy.fenceRadiusM(10.0), 0.0)
        assertEquals(150.0, WakePolicy.fenceRadiusM(100.0), 0.0)
        assertEquals(WakePolicy.FENCE_MAX_M, WakePolicy.fenceRadiusM(5000.0), 0.0)
        assertTrue(WakePolicy.needsFence(WakeState(), 40.0, 3.0))
        val armed = WakeState(fenceLat = 40.0, fenceLon = 3.0, fenceRadiusM = 100.0)
        assertFalse(WakePolicy.needsFence(armed, 40.0001, 3.0))
        assertTrue(WakePolicy.needsFence(armed, 40.01, 3.0))
    }

    @Test fun `movement signals map to moving, STILL and the tick do not`() {
        assertTrue(WakePolicy.movesFor(WakeKind.GEOFENCE_EXIT)); assertTrue(WakePolicy.movesFor(WakeKind.ACTIVITY_MOVING))
        assertFalse(WakePolicy.movesFor(WakeKind.ACTIVITY_STILL)); assertFalse(WakePolicy.movesFor(WakeKind.TICK))
    }
}
