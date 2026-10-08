package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.notificationcenter.BatteryEstimator.ChargeSource
import com.diegonmarcos.superapp.notificationcenter.BatteryEstimator.DischargeState
import com.diegonmarcos.superapp.notificationcenter.BatteryEstimator.RateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Battery badge's estimators, with no device: discharge, charge, current signs, the EMA tracker. */
class BatteryEstimatorTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val hour = 3_600_000L

    // ── discharge ────────────────────────────────────────────────────────

    @Test fun `average drain since the anchor gives the time to empty`() {
        // 100 -> 80 in 2 h = 10 %/h; 80 left = 8 h.
        val d = BatteryEstimator.discharge(80, now, now - 2 * hour, 100)
        assertEquals(DischargeState.READY, d.state)
        assertEquals(20, d.usedPct)
        assertEquals(10.0, d.avgPctPerHour!!, 1e-9)
        assertEquals(8 * hour, d.avgRemainingMs)
    }

    @Test fun `learning until 2 percent is used`() {
        val d = BatteryEstimator.discharge(99, now, now - 5 * min, 100)
        assertEquals(DischargeState.LEARNING, d.state)
        assertNull(d.avgRemainingMs)
    }

    @Test fun `one percent is enough once 15 minutes have passed`() {
        assertEquals(DischargeState.LEARNING, BatteryEstimator.discharge(99, now, now - 14 * min, 100).state)
        val d = BatteryEstimator.discharge(99, now, now - 15 * min, 100)
        assertEquals(DischargeState.READY, d.state)
        assertEquals(99 * 15 * min, d.avgRemainingMs)
    }

    @Test fun `a long time with nothing used is still learning, not infinity`() {
        val d = BatteryEstimator.discharge(100, now, now - 5 * hour, 100)
        assertEquals(DischargeState.LEARNING, d.state)
        assertNull(d.avgRemainingMs)
    }

    @Test fun `no anchor is its own state and never divides by zero`() {
        assertEquals(DischargeState.NO_ANCHOR, BatteryEstimator.discharge(50, now, 0L, -1).state)
        assertEquals(DischargeState.NO_ANCHOR, BatteryEstimator.discharge(50, now, now, 101).state)
    }

    @Test fun `a level above the anchor (gauge recalibrated) counts as nothing used`() {
        val d = BatteryEstimator.discharge(83, now, now - hour, 80)
        assertEquals(DischargeState.LEARNING, d.state)
        assertEquals(0, d.usedPct)
    }

    @Test fun `an anchor in the future never yields a negative duration`() {
        val d = BatteryEstimator.discharge(70, now, now + hour, 90)
        assertEquals(0L, d.elapsedMs)
        assertEquals(DischargeState.LEARNING, d.state)
    }

    @Test fun `empty and unknown levels`() {
        assertEquals(DischargeState.EMPTY, BatteryEstimator.discharge(0, now, now - hour, 50).state)
        assertEquals(DischargeState.NO_ANCHOR, BatteryEstimator.discharge(-1, now, now - hour, 50).state)
    }

    @Test fun `the recent rate is shown next to the average`() {
        // recent 0.25 %/min = 15 %/h; at 60 % that is 4 h.
        val d = BatteryEstimator.discharge(60, now, now - 4 * hour, 100, recentPctPerMin = 0.25)
        assertEquals(15.0, d.recentPctPerHour!!, 1e-9)
        assertEquals(4 * hour, d.recentRemainingMs)
        assertEquals(10.0, d.avgPctPerHour!!, 1e-9)
        assertEquals(6 * hour, d.avgRemainingMs)
    }

    @Test fun `the recent rate is there even while the average is learning`() {
        val d = BatteryEstimator.discharge(99, now, now - min, 100, recentPctPerMin = 0.5)
        assertEquals(DischargeState.LEARNING, d.state)
        assertNotNull(d.recentRemainingMs)
    }

    // ── charge ───────────────────────────────────────────────────────────

    private fun charge(level: Int, sys: Long = -1, ema: Double = 0.0, ma: Int? = null, cap: Int? = null, full: Boolean = false) =
        BatteryEstimator.charge(level, full, sys, ema, ma, cap)

    @Test fun `the system estimate wins when it has one`() {
        val c = charge(50, sys = 45 * min, ema = 1.0, ma = 3000, cap = 4000)
        assertEquals(ChargeSource.SYSTEM, c.source)
        assertEquals(45 * min, c.remainingMs)
    }

    @Test fun `without the system estimate the recent rate is used`() {
        // 0.5 %/min, 40 % to go = 80 min.
        val c = charge(60, ema = 0.5)
        assertEquals(ChargeSource.RECENT_RATE, c.source)
        assertEquals(80 * min, c.remainingMs)
        assertEquals(30.0, c.pctPerHour!!, 1e-9)
    }

    @Test fun `without either, the instantaneous current and the capacity give a rate`() {
        // 2000 mA into 4000 mAh = 50 %/h; 50 % to go = 1 h.
        val c = charge(50, ma = 2000, cap = 4000)
        assertEquals(ChargeSource.CURRENT, c.source)
        assertEquals(hour, c.remainingMs)
    }

    @Test fun `the current's sign does not matter to the charge estimate`() {
        assertEquals(hour, charge(50, ma = -2000, cap = 4000).remainingMs)
    }

    @Test fun `unknown current or capacity is unknown, never a guess`() {
        assertEquals(ChargeSource.UNKNOWN, charge(50).source)
        assertEquals(ChargeSource.UNKNOWN, charge(50, ma = 2000).source)
        assertEquals(ChargeSource.UNKNOWN, charge(50, ma = 2000, cap = 0).source)
        assertEquals(ChargeSource.UNKNOWN, charge(50, ma = 0, cap = 4000).source)
        assertNull(charge(50).remainingMs)
    }

    @Test fun `full is zero and is not estimated`() {
        val c = charge(100, sys = 10 * min, ema = 1.0)
        assertEquals(ChargeSource.FULL, c.source)
        assertEquals(0L, c.remainingMs)
        assertEquals(ChargeSource.FULL, charge(97, full = true).source)
    }

    @Test fun `an unknown level is unknown`() {
        assertEquals(ChargeSource.UNKNOWN, charge(-1, sys = 5 * min).source)
    }

    // ── CURRENT_NOW units and signs ──────────────────────────────────────

    @Test fun `microamps (the documented unit) become milliamps`() {
        assertEquals(1500, BatteryEstimator.rawToMa(1_500_000))
        assertEquals(-312, BatteryEstimator.rawToMa(-312_000))
    }

    @Test fun `milliamp vendors are told apart by magnitude`() {
        assertEquals(2500, BatteryEstimator.rawToMa(2500))
        assertEquals(-640, BatteryEstimator.rawToMa(-640))
    }

    @Test fun `no reading is null, a zero reading is zero`() {
        assertNull(BatteryEstimator.rawToMa(null))
        assertNull(BatteryEstimator.rawToMa(Int.MIN_VALUE))
        assertEquals(0, BatteryEstimator.rawToMa(0))
    }

    @Test fun `the direction comes from the status, whichever sign the vendor reports`() {
        val charging = BatteryBadgeModel.STATUS_CHARGING
        val discharging = BatteryBadgeModel.STATUS_DISCHARGING
        // Pixel-like: positive while charging, negative while discharging.
        assertEquals(1800, BatteryEstimator.intoBatteryMa(1_800_000, charging))
        assertEquals(-400, BatteryEstimator.intoBatteryMa(-400_000, discharging))
        // Samsung-like: the opposite signs.
        assertEquals(1800, BatteryEstimator.intoBatteryMa(-1_800_000, charging))
        assertEquals(-400, BatteryEstimator.intoBatteryMa(400_000, discharging))
        // Full and plugged-but-idle keep the magnitude, into the battery.
        assertEquals(30, BatteryEstimator.intoBatteryMa(-30, BatteryBadgeModel.STATUS_FULL))
        assertEquals(30, BatteryEstimator.intoBatteryMa(30, BatteryBadgeModel.STATUS_NOT_CHARGING))
        assertNull(BatteryEstimator.intoBatteryMa(Int.MIN_VALUE, charging))
    }

    @Test fun `average current as percent per hour`() {
        assertEquals(10.0, BatteryEstimator.pctPerHourFromMa(-400, 4000)!!, 1e-9)
        assertNull(BatteryEstimator.pctPerHourFromMa(-400, null))
        assertNull(BatteryEstimator.pctPerHourFromMa(-400, 0))
        assertNull(BatteryEstimator.pctPerHourFromMa(null, 4000))
    }

    // ── the EMA tracker ──────────────────────────────────────────────────

    private val cap = 4_000_000L // 4000 mAh in uAh

    @Test fun `the first event only starts a reference`() {
        val s = BatteryEstimator.track(null, now, 80, 3_200_000, cap, charging = false)!!
        assertEquals(0.0, s.emaPctPerMin, 0.0)
        assertEquals(now, s.ts)
    }

    @Test fun `counter deltas give a smooth rate`() {
        val a = BatteryEstimator.track(null, now, 80, 3_200_000, cap, false)!!
        // 40 mAh = 1 % in 10 min = 0.1 %/min.
        val b = BatteryEstimator.track(a, now + 10 * min, 79, 3_160_000, cap, false)!!
        assertEquals(0.1, b.emaPctPerMin, 1e-9)
        assertEquals(now + 10 * min, b.ts)
    }

    @Test fun `a movement too small or too fast keeps the reference`() {
        val a = BatteryEstimator.track(null, now, 80, 3_200_000, cap, false)!!
        val tiny = BatteryEstimator.track(a, now + 10 * min, 80, 3_190_000, cap, false)!! // 0.25 %
        assertEquals(a, tiny)
        val fast = BatteryEstimator.track(a, now + 10_000, 79, 3_150_000, cap, false)!! // 1.25 % in 10 s
        assertEquals(a, fast)
    }

    @Test fun `without a counter two whole percents and five minutes are needed`() {
        val a = BatteryEstimator.track(null, now, 80, 0, 0, false)!!
        assertEquals(a, BatteryEstimator.track(a, now + 10 * min, 79, 0, 0, false))
        assertEquals(a, BatteryEstimator.track(a, now + 2 * min, 78, 0, 0, false))
        val b = BatteryEstimator.track(a, now + 20 * min, 78, 0, 0, false)!!
        assertEquals(0.1, b.emaPctPerMin, 1e-9)
    }

    @Test fun `charging counts the gain, in the same units`() {
        val a = BatteryEstimator.track(null, now, 40, 1_600_000, cap, true)!!
        val b = BatteryEstimator.track(a, now + 10 * min, 45, 1_800_000, cap, true)!! // +5 % / 10 min
        assertEquals(0.5, b.emaPctPerMin, 1e-9)
    }

    @Test fun `plugging or unplugging restarts the reference and drops the other direction's rate`() {
        val a = BatteryEstimator.track(null, now, 80, 3_200_000, cap, false)!!
        val b = BatteryEstimator.track(a, now + 10 * min, 79, 3_160_000, cap, false)!!
        val c = BatteryEstimator.track(b, now + 11 * min, 79, 3_160_000, cap, true)!!
        assertEquals(0.0, c.emaPctPerMin, 0.0)
        assertTrue(c.charging)
    }

    @Test fun `a level that moves the wrong way restarts, keeping the rate`() {
        val a = RateState(now, 80, 0, false, emaPctPerMin = 0.2)
        val b = BatteryEstimator.track(a, now + 10 * min, 85, 0, 0, false)!!
        assertEquals(85, b.levelPct)
        assertEquals(0.2, b.emaPctPerMin, 0.0)
    }

    @Test fun `a stale reference is forgotten`() {
        val a = RateState(now, 80, 3_200_000, false, emaPctPerMin = 0.2)
        val b = BatteryEstimator.track(a, now + 7 * hour, 40, 1_600_000, cap, false)!!
        assertEquals(0.0, b.emaPctPerMin, 0.0)
        assertEquals(now + 7 * hour, b.ts)
        val back = BatteryEstimator.track(a, now - hour, 79, 3_160_000, cap, false)!!
        assertEquals(0.0, back.emaPctPerMin, 0.0) // the clock went backwards
    }

    @Test fun `an unknown level changes nothing`() {
        val a = RateState(now, 80, 0, false)
        assertEquals(a, BatteryEstimator.track(a, now + min, -1, 0, 0, false))
        assertNull(BatteryEstimator.track(null, now, -1, 0, 0, false))
    }

    @Test fun `the EMA moves toward the sample by the weight of the interval`() {
        assertEquals(0.3, BatteryEstimator.ema(0.0, 0.3, min), 0.0) // first sample is taken whole
        val slow = BatteryEstimator.ema(0.1, 0.3, 5 * min)
        val fast = BatteryEstimator.ema(0.1, 0.3, 120 * min)
        assertTrue(slow > 0.1 && slow < fast && fast < 0.3)
        assertEquals(0.3, BatteryEstimator.ema(0.1, 0.3, 100 * hour), 1e-6)
    }
}
