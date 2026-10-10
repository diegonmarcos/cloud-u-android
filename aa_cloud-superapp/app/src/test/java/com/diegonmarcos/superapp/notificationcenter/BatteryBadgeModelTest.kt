package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.battery.BatteryExtras
import com.diegonmarcos.superapp.battery.BatteryMath
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_CHARGING
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_DISCHARGING
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_FULL
import com.diegonmarcos.superapp.battery.BatteryMath.STATUS_NOT_CHARGING
import com.diegonmarcos.superapp.battery.BatteryReading
import com.diegonmarcos.superapp.battery.BatteryReport
import com.diegonmarcos.superapp.battery.BatteryRows
import com.diegonmarcos.superapp.battery.BatterySample
import com.diegonmarcos.superapp.battery.BatteryTruth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Battery badge's report -> notification model: headline first, then the popup's rows. */
class BatteryBadgeModelTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val hour = 3_600_000L

    private fun reading(level: Int = 80, status: Int = STATUS_DISCHARGING, plugged: Int = 0, raw: Int? = -312_000,
                        sys: Long = -1, health: Int = 2, counter: Long? = 3_100_000L) = BatteryReading(
        nowMs = now, levelPct = level, status = status, plugged = plugged, health = health, tempDc = 312,
        voltageMv = 4050, rawCurrentNow = raw, counterUah = counter, systemCycles = 212, systemChargeRemainingMs = sys)

    /** A real charge ending [ago] before now at [from]%, then on battery. */
    private fun discharged(from: Int = 100, ago: Long = 2 * hour) = listOf(
        BatterySample(now - ago - hour, 40, STATUS_CHARGING, 2),
        BatterySample(now - ago, from, STATUS_DISCHARGING, 0),
    )

    private fun report(r: BatteryReading, samples: List<BatterySample> = discharged(), cycles: Double? = null): BatteryReport {
        val all = samples + BatteryTruth.sampleOf(r, BatteryMath.CurrentScale.UNKNOWN)
        val ma = BatteryMath.signedMa(r.rawCurrentNow, r.status, r.plugged)
        return BatteryTruth.compute(r, all, ma?.let { BatteryMath.Ema(it.toDouble(), now) },
            BatteryExtras(ratedMah = 4000, cycleEstimate = cycles))
    }

    private fun card(r: BatteryReading, samples: List<BatterySample> = discharged(), cycles: Double? = null) =
        BatteryBadgeModel.card(report(r, samples, cycles))

    // ── headline ─────────────────────────────────────────────────────────

    @Test fun `discharging headline is the time to empty from the average since charge`() {
        assertEquals("Battery 80% · ~8:00 left (est)", card(reading()).title)
    }

    @Test fun `charging headline is the time to full`() {
        val c = card(reading(level = 50, status = STATUS_CHARGING, plugged = 2, raw = 1_800_000, sys = 65 * min))
        assertEquals("Battery 50% · ~1:05 to full (est)", c.title)
    }

    @Test fun `learning is said, not faked, and falls back to the current rate first`() {
        val fresh = discharged(from = 100, ago = 3 * min)
        val c = card(reading(level = 99, raw = null), fresh)
        assertEquals("Battery 99% · learning…", c.title)
        assertTrue(c.expanded, c.expanded.contains("Average: learning… (needs 2% used or 15 min)"))
        val now = card(reading(level = 99), fresh)
        assertTrue(now.title, now.title.endsWith("left (est, now)"))
    }

    @Test fun `charging with no estimate available says so`() {
        val c = card(reading(level = 50, status = STATUS_CHARGING, plugged = 2, raw = null))
        assertEquals("Battery 50% · estimating time to full…", c.title)
    }

    @Test fun `full is full`() {
        val c = card(reading(level = 100, status = STATUS_FULL, plugged = 1))
        assertEquals("Battery 100% · Fully charged", c.title)
        assertTrue(c.expanded, c.expanded.contains("To full: Full"))
    }

    @Test fun `plugged but not charging is not given a time`() {
        assertEquals("Battery 70% · Plugged in, not charging",
            card(reading(level = 70, status = STATUS_NOT_CHARGING, plugged = 2)).title)
    }

    @Test fun `empty and unknown level`() {
        assertEquals("Battery 0% · Empty", card(reading(level = 0)).title)
        assertEquals("Battery -- · level unknown", card(reading(level = -1)).title)
    }

    // ── details ──────────────────────────────────────────────────────────

    @Test fun `the collapsed line carries state, the rate in both units, voltage and temperature`() {
        assertEquals("Discharging (on battery) · −8.1 %/h · −1.26 W · 4.05 V · 31.2 °C", card(reading()).text)
    }

    @Test fun `a bad health is surfaced in the collapsed line, a good one is not`() {
        assertTrue(card(reading(health = 3)).text.endsWith("Overheat"))
        assertFalse(card(reading()).text.contains("Good"))
    }

    @Test fun `the expanded body is the popup's sections, then capacity and cycles`() {
        val r = report(reading())
        val e = BatteryBadgeModel.card(r).expanded
        for (want in listOf("— Now —", "— Since last charge —", "Level: 80%", "Voltage: 4.05 V", "Temperature: 31.2 °C",
            "Health: Good", "Cycles: 212", "Charge: 3100 mAh of 3875 mAh", "Used: 20% in 2:00 on battery", "(est)",
        )) assertTrue("missing '$want' in:\n$e", e.contains(want))
        for (sec in BatteryRows.popup(r)) for (row in sec.rows) assertTrue(e.contains(BatteryBadgeModel.line(row)))
    }

    @Test fun `missing data reads as a dash, never as zero`() {
        val e = card(reading(raw = null, counter = null).copy(voltageMv = null, tempDc = null, systemCycles = null),
            samples = emptyList()).expanded
        assertTrue(e, e.contains("Current: —"))
        assertTrue(e, e.contains("Voltage: —"))
        assertTrue(e, e.contains("Temperature: —"))
        assertTrue(e, e.contains("Cycles: not available"))
        assertTrue(e, e.contains("Capacity: 4000 mAh (rated)"))
    }

    @Test fun `cycles come from the system, else the counted estimate, labelled`() {
        val est = card(reading().copy(systemCycles = null), cycles = 3.46).expanded
        assertTrue(est, est.contains("Cycles: ~3.5 (est, counted since install)"))
    }

    @Test fun `plugged in shows time, source and percent gained`() {
        val samples = listOf(BatterySample(now - 3 * hour, 90, STATUS_DISCHARGING, 0),
            BatterySample(now - 20 * min, 30, STATUS_CHARGING, 2))
        val e = card(reading(level = 50, status = STATUS_CHARGING, plugged = 2, raw = 1_800_000, sys = 65 * min), samples).expanded
        assertTrue(e, e.contains("Plugged: 0:20 ago · USB (at 30%)"))
        assertTrue(e, e.contains("Gained: +20%"))
        assertTrue(e, e.contains("To full: ~1:05 (est, system)"))
    }

    @Test fun `h-mm rounds to the minute and caps`() {
        assertEquals("0:00", BatteryRows.fmtHm(0))
        assertEquals("0:01", BatteryRows.fmtHm(30_000))
        assertEquals("1:05", BatteryRows.fmtHm(65 * min))
        assertEquals("99:59+", BatteryRows.fmtHm(500 * hour))
        assertEquals("—", BatteryRows.fmtHm(-1))
    }
}
