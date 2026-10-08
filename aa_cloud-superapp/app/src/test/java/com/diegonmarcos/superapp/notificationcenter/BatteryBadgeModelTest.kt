package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.notificationcenter.BatteryBadgeModel.STATUS_CHARGING
import com.diegonmarcos.superapp.notificationcenter.BatteryBadgeModel.STATUS_DISCHARGING
import com.diegonmarcos.superapp.notificationcenter.BatteryBadgeModel.STATUS_FULL
import com.diegonmarcos.superapp.notificationcenter.BatteryBadgeModel.STATUS_NOT_CHARGING
import com.diegonmarcos.superapp.notificationcenter.BatteryBadgeModel.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Battery badge's state -> notification model: headline first, then the details. */
class BatteryBadgeModelTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val hour = 3_600_000L

    private fun discharging(level: Int = 80, usedFrom: Int = 100, ago: Long = 2 * hour) = Snapshot(
        nowMs = now, levelPct = level, status = STATUS_DISCHARGING, plugged = 0, health = 2,
        tempC = 31.2, voltageMv = 4050, currentNowMa = -312, currentAvgMa = -285,
        cycleCount = 212, chargeCounterUah = 3_100_000, capacityMah = 4000, capacityIsRated = false,
        anchorMs = now - ago, anchorPct = usedFrom,
    )

    private fun charging(level: Int = 50, sys: Long = 65 * min) = discharging(level).copy(
        status = STATUS_CHARGING, plugged = 2, currentNowMa = 1800, currentAvgMa = 1750,
        systemChargeRemainingMs = sys, anchorMs = now - 20 * min, anchorPct = 30)

    // ── headline ─────────────────────────────────────────────────────────

    @Test fun `discharging headline is the time to empty from the average since charge`() {
        val c = BatteryBadgeModel.card(discharging())
        assertEquals("Battery 80% · ~8:00 left (est)", c.title)
    }

    @Test fun `charging headline is the time to full`() {
        assertEquals("Battery 50% · ~1:05 to full (est)", BatteryBadgeModel.card(charging()).title)
    }

    @Test fun `learning is said, not faked`() {
        val c = BatteryBadgeModel.card(discharging(level = 99, usedFrom = 100, ago = 3 * min))
        assertEquals("Battery 99% · learning…", c.title)
        assertTrue(c.expanded, c.expanded.contains("To empty: learning… (needs 2% used or 15 min)"))
    }

    @Test fun `no anchor yet is learning too, and says why`() {
        val c = BatteryBadgeModel.card(discharging().copy(anchorMs = 0, anchorPct = -1))
        assertTrue(c.title, c.title.endsWith("learning…"))
        assertTrue(c.expanded, c.expanded.contains("no unplug seen yet"))
    }

    @Test fun `charging with no estimate available says so`() {
        val c = BatteryBadgeModel.card(charging().copy(systemChargeRemainingMs = -1, currentNowMa = null, currentAvgMa = null))
        assertEquals("Battery 50% · estimating time to full…", c.title)
    }

    @Test fun `charging falls back to the recent rate and names the source`() {
        val c = BatteryBadgeModel.card(charging().copy(systemChargeRemainingMs = -1, recentPctPerMin = 0.5))
        assertEquals("Battery 50% · ~1:40 to full (est)", c.title)
        assertTrue(c.expanded, c.expanded.contains("recent charge rate"))
    }

    @Test fun `full is full`() {
        val c = BatteryBadgeModel.card(charging(level = 100).copy(status = STATUS_FULL))
        assertEquals("Battery 100% · Fully charged", c.title)
        assertTrue(c.expanded, c.expanded.contains("To full: 0:00"))
    }

    @Test fun `plugged but not charging is not given a time`() {
        val c = BatteryBadgeModel.card(charging(level = 70).copy(status = STATUS_NOT_CHARGING))
        assertEquals("Battery 70% · Plugged in, not charging", c.title)
    }

    @Test fun `empty and unknown level`() {
        assertEquals("Battery 0% · Empty", BatteryBadgeModel.card(discharging(level = 0)).title)
        assertEquals("Battery -- · level unknown", BatteryBadgeModel.card(discharging(level = -1)).title)
    }

    // ── details ──────────────────────────────────────────────────────────

    @Test fun `the collapsed line carries status, current, voltage and temperature`() {
        assertEquals("Discharging · -312 mA · 4.05 V · 31.2 °C", BatteryBadgeModel.card(discharging()).text)
        assertEquals("Charging (USB) · +1800 mA · 4.05 V · 31.2 °C", BatteryBadgeModel.card(charging()).text)
    }

    @Test fun `a bad health is surfaced in the collapsed line, a good one is not`() {
        assertTrue(BatteryBadgeModel.card(discharging().copy(health = 3)).text.endsWith("Overheat"))
        assertFalse(BatteryBadgeModel.card(discharging()).text.contains("Good"))
    }

    @Test fun `the expanded body has every requested field`() {
        val e = BatteryBadgeModel.card(discharging()).expanded
        for (want in listOf(
            "Level: 80%", "Status: Discharging", "Current: now -312 mA · avg -285 mA", "Voltage: 4.05 V",
            "Temperature: 31.2 °C", "Health: Good", "Cycles: 212", "Charge: 3100 mAh of 4000 mAh",
            "To empty: ~8:00 at 10.0 %/h (est, avg since charge)",
            "Since last charge: 2:00 · 20% used (from 100%)", "(est)",
        )) assertTrue("missing '$want' in:\n$e", e.contains(want))
    }

    @Test fun `current rate is shown beside the average`() {
        val e = BatteryBadgeModel.card(discharging().copy(recentPctPerMin = 0.25)).expanded
        assertTrue(e, e.contains("Right now: ~5:20 at 15.0 %/h (est, recent rate)"))
    }

    @Test fun `plug type words`() {
        fun p(v: Int) = BatteryBadgeModel.plugText(discharging().copy(plugged = v))
        assertEquals("AC", p(1)); assertEquals("USB", p(2)); assertEquals("wireless", p(4)); assertEquals("dock", p(8))
        assertEquals(null, p(0)); assertEquals("plugged", p(16))
    }

    @Test fun `plugged in shows time and percent gained`() {
        val e = BatteryBadgeModel.card(charging()).expanded
        assertTrue(e, e.contains("Plugged in: 0:20 ago · +20% since then"))
        assertTrue(e, e.contains("To full: ~1:05 (est, system estimate)"))
    }

    @Test fun `missing data reads as not available, never as zero`() {
        val e = BatteryBadgeModel.card(Snapshot(nowMs = now, levelPct = 60, status = STATUS_DISCHARGING)).expanded
        assertTrue(e, e.contains("Current: now -- · avg --"))
        assertTrue(e, e.contains("Voltage: --"))
        assertTrue(e, e.contains("Temperature: --"))
        assertTrue(e, e.contains("Cycles: not available"))
        assertTrue(e, e.contains("Charge: not available"))
    }

    @Test fun `cycles come from the system, else the counted estimate, labelled`() {
        assertTrue(BatteryBadgeModel.card(discharging()).expanded.contains("Cycles: 212"))
        val est = BatteryBadgeModel.card(discharging().copy(cycleCount = null, cycleEstimate = 3.46)).expanded
        assertTrue(est, est.contains("Cycles: ~3.5 (est, counted since install)"))
    }

    @Test fun `rated capacity is labelled as rated`() {
        val e = BatteryBadgeModel.card(discharging().copy(capacityIsRated = true)).expanded
        assertTrue(e, e.contains("Charge: 3100 mAh of 4000 mAh (rated)"))
    }

    // ── formatting ───────────────────────────────────────────────────────

    @Test fun `h-mm rounds to the minute and caps`() {
        assertEquals("0:00", BatteryBadgeModel.fmtHm(0))
        assertEquals("0:01", BatteryBadgeModel.fmtHm(30_000))
        assertEquals("0:00", BatteryBadgeModel.fmtHm(29_999))
        assertEquals("1:05", BatteryBadgeModel.fmtHm(65 * min))
        assertEquals("12:30", BatteryBadgeModel.fmtHm(12 * hour + 30 * min))
        assertEquals("99:59+", BatteryBadgeModel.fmtHm(500 * hour))
        assertEquals("--", BatteryBadgeModel.fmtHm(-1))
    }
}
