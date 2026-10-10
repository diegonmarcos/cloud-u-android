package com.diegonmarcos.superapp.batterystats

import com.diegonmarcos.superapp.battery.BatteryMath
import com.diegonmarcos.superapp.battery.BatteryMath.CurrentScale
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_CHARGING
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_DISCHARGING
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_FULL
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_NOT_CHARGING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The battery SoT's arithmetic: µA/mA, the sign, W and %/h, the EMA, the estimates. */
class BatteryMathTest {

    private val e = 1e-6

    // ── µA vs mA ─────────────────────────────────────────────────────────

    @Test fun `microamps (the documented unit) become milliamps`() {
        assertEquals(312, BatteryMath.rawToMa(312_000))
        assertEquals(-1800, BatteryMath.rawToMa(-1_800_000))
    }

    @Test fun `milliamp vendors pass through by magnitude`() {
        assertEquals(312, BatteryMath.rawToMa(312))
        assertEquals(-4500, BatteryMath.rawToMa(-4500))
    }

    @Test fun `a magnitude no cell can move in mA is microamps`() {
        // 20 000 mA would be 20 A: it can only be 20 mA in µA (a screen-off idle).
        assertEquals(20, BatteryMath.rawToMa(20_000))
        assertEquals(13, BatteryMath.rawToMa(-13_000)!!.let(::abs))
    }

    @Test fun `once a device showed microamps, small readings are microamps too`() {
        val learned = BatteryMath.learnScale(CurrentScale.UNKNOWN, 450_000)
        assertEquals(CurrentScale.MICRO, learned)
        assertEquals(5, BatteryMath.rawToMa(5_000, learned))     // 5 mA idle, not 5 A
        assertEquals(CurrentScale.UNKNOWN, BatteryMath.learnScale(CurrentScale.UNKNOWN, 450))
        assertEquals(CurrentScale.MICRO, BatteryMath.learnScale(CurrentScale.MICRO, 3))
        assertEquals(20_000, BatteryMath.rawToMa(20_000, CurrentScale.MILLI))
    }

    @Test fun `no reading is null, zero is a reading`() {
        assertNull(BatteryMath.rawToMa(null))
        assertNull(BatteryMath.rawToMa(Int.MIN_VALUE))
        assertEquals(0, BatteryMath.rawToMa(0))
    }

    // ── the sign ─────────────────────────────────────────────────────────

    @Test fun `discharge is negative whatever sign the vendor used`() {
        assertEquals(-450, BatteryMath.signedMa(450, STATUS_DISCHARGING, 0))          // Samsung: + while discharging
        assertEquals(-450, BatteryMath.signedMa(-450_000, STATUS_DISCHARGING, 0))     // Pixel: − while discharging
    }

    @Test fun `charge is positive whatever sign the vendor used`() {
        assertEquals(1800, BatteryMath.signedMa(-1800, STATUS_CHARGING, 2))
        assertEquals(1800, BatteryMath.signedMa(1_800_000, STATUS_CHARGING, 1))
    }

    @Test fun `unplugged and not charging is on battery, plugged and full is not`() {
        assertEquals(-200, BatteryMath.signedMa(200, STATUS_NOT_CHARGING, 0))
        assertEquals(30, BatteryMath.signedMa(-30, STATUS_FULL, 1))
        assertEquals(30, BatteryMath.signedMa(-30, STATUS_NOT_CHARGING, 1))
    }

    // ── W, capacity, %/h ─────────────────────────────────────────────────

    @Test fun `power is current times EXTRA_VOLTAGE, signed`() {
        assertEquals(-2.0, BatteryMath.powerW(-500.0, 4000)!!, e)
        assertEquals(7.6, BatteryMath.powerW(2000.0, 3800)!!, e)
        assertNull(BatteryMath.powerW(-500.0, null))
        assertNull(BatteryMath.powerW(null, 4000))
    }

    @Test fun `capacity is CHARGE_COUNTER over level`() {
        assertEquals(4000, BatteryMath.capacityMah(3_000_000, 75))
        assertNull("too low a level divides noise", BatteryMath.capacityMah(100_000, 3))
        assertNull("a broken counter is not a battery", BatteryMath.capacityMah(100, 50))
        assertNull(BatteryMath.capacityMah(null, 50))
    }

    @Test fun `percent per hour and watts convert through capacity and voltage`() {
        assertEquals(-10.0, BatteryMath.pctPerHour(-400.0, 4000)!!, e)
        assertEquals(-1.54, BatteryMath.wattsFromPctPerHour(-10.0, 4000, 3850)!!, e)
        assertEquals(-10.0, BatteryMath.pctPerHourFromWatts(-1.54, 4000, 3850)!!, e)
        assertNull(BatteryMath.pctPerHour(-400.0, null))
        assertNull(BatteryMath.wattsFromPctPerHour(-10.0, 4000, null))
    }

    // ── estimates ────────────────────────────────────────────────────────

    @Test fun `time to empty is level over the drain`() {
        assertEquals(5 * 3_600_000L, BatteryMath.msToEmpty(50, -10.0))
        assertNull("a gain never empties", BatteryMath.msToEmpty(50, 10.0))
        assertNull("no movement is not a year", BatteryMath.msToEmpty(50, -0.01))
    }

    @Test fun `time to full is the remainder over the gain`() {
        assertEquals(2 * 3_600_000L, BatteryMath.msToFull(60, 20.0))
        assertEquals(0L, BatteryMath.msToFull(100, null))
        assertNull(BatteryMath.msToFull(60, -5.0))
    }

    // ── EMA ──────────────────────────────────────────────────────────────

    @Test fun `the first reading is the EMA`() {
        assertEquals(-300.0, BatteryMath.ema(null, -300.0, 0).ma, e)
    }

    @Test fun `one time constant moves 63 percent of the way`() {
        val next = BatteryMath.ema(BatteryMath.Ema(-100.0, 0), -200.0, BatteryMath.EMA_TAU_MS)
        assertEquals(-100.0 - 100.0 * (1 - Math.exp(-1.0)), next.ma, 1e-9)
    }

    @Test fun `a spike barely moves a steady draw`() {
        var m: BatteryMath.Ema? = null
        for (t in 0..60 step 5) m = BatteryMath.ema(m, -300.0, t * 1000L)
        val spiked = BatteryMath.ema(m, -3000.0, 65_000L)
        assertTrue("smoothed ${spiked.ma}", abs(spiked.ma) < 700)
    }

    @Test fun `a long gap or a plug restarts the EMA`() {
        val e1 = BatteryMath.Ema(-300.0, 0)
        assertEquals(-900.0, BatteryMath.ema(e1, -900.0, BatteryMath.EMA_MAX_GAP_MS + 1).ma, e)
        assertEquals(1500.0, BatteryMath.ema(e1, 1500.0, 5_000).ma, e)
        assertEquals(-900.0, BatteryMath.ema(e1, -900.0, -5).ma, e)
    }
}
