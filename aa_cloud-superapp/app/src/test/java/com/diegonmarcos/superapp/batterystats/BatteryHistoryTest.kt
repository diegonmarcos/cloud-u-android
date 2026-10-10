package com.diegonmarcos.superapp.batterystats

import com.diegonmarcos.superapp.battery.BatteryHistory
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_CHARGING
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_DISCHARGING
import com.diegonmarcos.superapp.battery.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SoT's history: runs, cycles and charge sessions, since-last-charge across plugs, the recorder's filter. */
class BatteryHistoryTest {

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L
    private val hour = 3_600_000L

    /** A sample [m] minutes after t0; plugged 0 = on battery. */
    private fun s(m: Int, level: Int, plugged: Int = 0, ma: Int? = null, mv: Int? = null,
                  temp: Int? = null, screen: Boolean? = null) = BatterySample(
        ts = t0 + m * min, levelPct = level,
        status = if (plugged != 0) STATUS_CHARGING else STATUS_DISCHARGING,
        plugged = plugged, currentMa = ma, voltageMv = mv, tempDc = temp, screenOn = screen)

    // ── segmentation ─────────────────────────────────────────────────────

    @Test fun `the history is cut at every plug and unplug, runs tile it`() {
        val h = listOf(s(0, 100), s(60, 90), s(120, 80, plugged = 2), s(150, 92, plugged = 2),
            s(200, 100), s(230, 96), s(260, 90))
        val r = BatteryHistory.segment(h)
        assertEquals(3, r.size)
        val (d1, c, d2) = r
        assertFalse(d1.charging); assertTrue(c.charging); assertFalse(d2.charging)
        // A run ends on the sample that saw the plug change.
        assertEquals(t0 + 120 * min, d1.endTs); assertEquals(100, d1.startPct); assertEquals(80, d1.endPct)
        assertEquals(t0 + 200 * min, c.endTs); assertEquals(80, c.startPct); assertEquals(100, c.endPct)
        assertEquals(2, c.plugged)
        assertFalse(d1.ongoing); assertFalse(c.ongoing); assertTrue(d2.ongoing)
        assertEquals(-10.0, d1.pctPerHour!!, 1e-9)
        assertEquals(15.0, c.pctPerHour!!, 1e-9)
    }

    @Test fun `a run's power is the time-weighted mean of V times I, and its max temperature is kept`() {
        val h = listOf(s(0, 100, ma = -500, mv = 4000, temp = 300), s(30, 97, ma = -1000, mv = 4000, temp = 345),
            s(90, 90, ma = -500, mv = 4000, temp = 310))
        val d = BatteryHistory.segment(h).single()
        // 30 min at −2 W then 60 min at −4 W
        assertEquals((-2.0 * 30 + -4.0 * 60) / 90, d.avgW!!, 1e-9)
        assertEquals(34.5, d.maxTempC!!, 1e-9)
    }

    @Test fun `with no current the power comes from the run's percent per hour and the capacity`() {
        val h = listOf(s(0, 100, mv = 4000), s(60, 90, mv = 4000))
        val d = BatteryHistory.segment(h, capMah = 4000).single()
        assertEquals(-0.1 * 4.0 * 4.0, d.avgW!!, 1e-9)       // −10 %/h of 4 Ah at 4 V
    }

    // ── since last charge ────────────────────────────────────────────────

    private val withTopUp = listOf(
        s(-60, 60, plugged = 1), s(-30, 85, plugged = 1),   // a real charge 60→100
        s(0, 100), s(60, 85), s(120, 70),                   // on battery
        s(120, 70, plugged = 2).copy(ts = t0 + 121 * min),  // a 5% top-up
        s(130, 75),                                         // unplugged again
        s(190, 65), s(250, 55),                             // on battery, ongoing
    )

    @Test fun `since last charge sums the runs on battery across a top-up`() {
        val since = BatteryHistory.sinceLastCharge(BatteryHistory.segment(withTopUp), t0 + 250 * min)!!
        assertEquals(t0, since.fromTs)
        assertEquals(100, since.fromPct)
        assertFalse(since.approximate)
        assertEquals(250 * min, since.wallMs)
        assertEquals("100→70 then 75→55", 50, since.usedPct)
        assertEquals("121 + 120 min on battery", 241 * min, since.onBatteryMs)
        assertEquals(1, since.topUps)
        assertEquals(5, since.topUpPct)
        assertEquals(-50 / (241 / 60.0), since.avgPctPerHour!!, 1e-9)
    }

    @Test fun `the ongoing run counts up to now, not to its newest sample`() {
        val since = BatteryHistory.sinceLastCharge(BatteryHistory.segment(withTopUp), t0 + 280 * min)!!
        assertEquals(271 * min, since.onBatteryMs)
    }

    @Test fun `a charge of ten percent or more is the last charge, not a top-up`() {
        val h = listOf(s(0, 100), s(60, 60), s(61, 60, plugged = 2), s(100, 75, plugged = 2), s(101, 75), s(161, 65))
        val since = BatteryHistory.sinceLastCharge(BatteryHistory.segment(h), t0 + 161 * min)!!
        assertEquals(t0 + 101 * min, since.fromTs)
        assertEquals(75, since.fromPct)
        assertEquals(10, since.usedPct)
        assertEquals(0, since.topUps)
    }

    @Test fun `with no charge recorded yet it counts from the oldest run, and says so`() {
        val since = BatteryHistory.sinceLastCharge(BatteryHistory.segment(listOf(s(0, 90), s(60, 80))), t0 + 60 * min)!!
        assertTrue(since.approximate)
        assertEquals(10, since.usedPct)
    }

    @Test fun `the average waits for the learning gate`() {
        val since = BatteryHistory.sinceLastCharge(BatteryHistory.segment(listOf(s(0, 100), s(5, 99))), t0 + 5 * min)!!
        assertNull(since.avgPctPerHour)
        val later = BatteryHistory.sinceLastCharge(BatteryHistory.segment(listOf(s(0, 100), s(20, 99))), t0 + 20 * min)!!
        assertEquals(-3.0, later.avgPctPerHour!!, 1e-9)
    }

    @Test fun `on power there is no since-last-charge`() {
        val h = listOf(s(0, 80), s(60, 70), s(61, 70, plugged = 2))
        assertNull(BatteryHistory.sinceLastCharge(BatteryHistory.segment(h), t0 + 70 * min))
    }

    @Test fun `screen on and off split the time and the drain`() {
        val h = listOf(s(0, 100, screen = true), s(30, 94, screen = false), s(150, 90, screen = false),
            s(151, 90, screen = true), s(171, 86))
        val since = BatteryHistory.sinceLastCharge(BatteryHistory.segment(h), t0 + 171 * min)!!
        assertTrue(since.screenMeasured)
        assertEquals(50 * min, since.screenOnMs)
        assertEquals(121 * min, since.screenOffMs)
        assertEquals(10, since.screenOnPct)
        assertEquals(4, since.screenOffPct)
    }

    @Test fun `a long gap whose ends disagree about the screen is not attributed`() {
        val h = listOf(s(0, 100, screen = false), s(120, 90, screen = true), s(130, 88, screen = true))
        val d = BatteryHistory.segment(h).single()
        assertEquals(0L, d.screenOffMs)
        assertEquals(10 * min, d.screenOnMs)
    }

    @Test fun `without screen samples the split is not claimed`() {
        val since = BatteryHistory.sinceLastCharge(BatteryHistory.segment(listOf(s(0, 100), s(60, 90))), t0 + 60 * min)!!
        assertFalse(since.screenMeasured)
    }

    // ── the level slope, the graph, the recorder ─────────────────────────

    @Test fun `the recent level slope needs two whole percent in the current run`() {
        val h = listOf(s(0, 90), s(10, 89), s(30, 87))
        assertEquals(-6.0, BatteryHistory.recentLevelRate(h, t0 + 30 * min)!!, 1e-9)
        assertNull(BatteryHistory.recentLevelRate(h.take(2), t0 + 10 * min))
    }

    @Test fun `the graph is bounded and keeps the newest point per bucket`() {
        val h = (0 until 1000).map { s(it, 100 - it / 10) }
        val g = BatteryHistory.levelSeries(h, t0, t0 + 1000 * min, maxPoints = 100)
        assertTrue(g.size <= 100)
        assertEquals(t0 + 999 * min, g.last().ts)
    }

    @Test fun `the recorder keeps news and a heartbeat, not every broadcast`() {
        val a = s(0, 80)
        assertTrue(BatteryHistory.shouldStore(null, a))
        assertFalse("same state 1 min later", BatteryHistory.shouldStore(a, s(1, 80)))
        assertTrue("heartbeat", BatteryHistory.shouldStore(a, s(5, 80)))
        assertTrue("a level step", BatteryHistory.shouldStore(a, s(1, 79)))
        assertFalse("a level step 5 s later waits", BatteryHistory.shouldStore(a, a.copy(ts = a.ts + 5_000, levelPct = 79)))
        assertTrue("a plug is always kept", BatteryHistory.shouldStore(a, a.copy(ts = a.ts + 1_000, plugged = 2)))
        assertTrue("a screen change is always kept", BatteryHistory.shouldStore(a, a.copy(ts = a.ts + 1_000, screenOn = true)))
    }
}
