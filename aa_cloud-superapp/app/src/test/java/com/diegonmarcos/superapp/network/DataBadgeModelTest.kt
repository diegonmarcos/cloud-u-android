package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.DataBadgeModel.Act
import com.diegonmarcos.superapp.network.DataBadgeModel.App
import com.diegonmarcos.superapp.network.DataBadgeModel.Sim
import com.diegonmarcos.superapp.network.DataBadgeModel.Snapshot
import com.diegonmarcos.superapp.network.DataBadgeModel.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Data badge's figures -> notification model, including the permission-missing state. */
class DataBadgeModelTest {

    private val mb = 1024L * 1024
    private val apps = (1..7).map { App("app$it", (10L - it) * mb) }
    private val ok = Snapshot(
        hasAccess = true, today = Window(mobile = 300 * mb, wifi = 900 * mb),
        month = Window(mobile = 3 * 1024 * mb, wifi = 20 * 1024 * mb), topApps = apps,
    )
    private val denied = Snapshot(hasAccess = false)

    @Test fun `title is the month estimate both ways, collapsed is today when there is no multi sim split`() {
        val c = DataBadgeModel.card(ok.copy(monthElapsedMs = 10 * day, monthLengthMs = 31 * day,
            month = Window(mobile = 3 * gb, wifi = 20 * gb), last30 = Window(mobile = 6 * gb), sims = listOf(Sim(0, "Alpha", 3 * gb, true, 6 * gb))))
        // 3 GB over 10 days -> 9.30 GB; 6 GB over 30 days -> 0.2 GB/day -> 6.20 GB
        assertEquals("Month est: 9.30 GB (this month avg) \u00b7 6.20 GB (30-day avg)", c.title)
        assertEquals("Today mobile 300.0 MB \u00b7 Wi-Fi 900.0 MB", c.text)
    }

    @Test fun `the estimates come first in the expanded text, then today, wifi and the apps`() {
        val e = DataBadgeModel.expanded(tenOf31).split("\n")
        val iEst = e.indexOfFirst { it.startsWith("Month est:") }
        val iToday = e.indexOfFirst { it.startsWith("Today:") }
        val iWifi = e.indexOfFirst { it.startsWith("Wi-Fi this month") }
        val iApps = e.indexOfFirst { it.startsWith("Top apps") }
        assertTrue(e.toString(), iEst == 0 && iEst < iToday && iToday < iWifi && iWifi < iApps)
    }

    @Test fun `expanded gives both windows and only the top five apps in order`() {
        val e = DataBadgeModel.expanded(ok)
        assertTrue(e, e.contains("Today: 1.17 GB (mobile 300.0 MB \u00b7 Wi-Fi 900.0 MB)"))
        assertTrue(e, e.contains("This month: 23.00 GB (mobile 3.00 GB \u00b7 Wi-Fi 20.00 GB)"))
        assertTrue(e, e.contains("1. app1 \u00b7 9.0 MB"))
        assertTrue(e, e.contains("5. app5 \u00b7 5.0 MB"))
        assertFalse(e, e.contains("6. app6"))
    }

    @Test fun `no apps says so`() {
        assertTrue(DataBadgeModel.expanded(ok.copy(topApps = emptyList())).contains("Top apps: none yet"))
    }

    @Test fun `buttons open the data manager and refresh`() {
        val a = DataBadgeModel.actions(ok)
        assertEquals(listOf(Act.REFRESH, Act.OPEN), a.map { it.act })
        assertEquals("Data Manager", a.last().label)
    }

    // ── Usage Access missing ─────────────────────────────────────────────

    @Test fun `without usage access the badge says so instead of showing zeros`() {
        val c = DataBadgeModel.card(denied)
        assertEquals("Data · Usage access needed", c.title)
        assertTrue(c.text, c.text.contains("Usage Access"))
        assertTrue(c.expanded, c.expanded.contains("Usage Access"))
        assertFalse(c.expanded.contains("Today:"))
        assertFalse(c.title.contains("0 B"))
    }

    @Test fun `without usage access there is a grant button and still the data manager`() {
        val a = DataBadgeModel.actions(denied)
        assertEquals(listOf(Act.GRANT, Act.OPEN), a.map { it.act })
        assertEquals("Grant access", a.first().label)
    }

    @Test fun `bytes`() {
        assertEquals("0 B", DataBadgeModel.bytes(-5))
        assertEquals("1.0 KB", DataBadgeModel.bytes(1024))
        assertEquals("2.00 GB", DataBadgeModel.bytes(2048 * mb))
    }

    // ── the two estimates, per SIM and in total ──────────────────────────

    private val day = DataBadgeModel.DAY_MS
    private val gb = 1024L * mb
    /** 10 days into a 31-day month; two SIMs measured: Alpha 3 GB this month / 6 GB in 30 days, Beta 1 GB / 3 GB. */
    private val tenOf31 = ok.copy(
        monthElapsedMs = 10 * day, monthLengthMs = 31 * day, monthEndLabel = "Oct 31",
        month = Window(mobile = 4 * gb, wifi = 20 * gb), last30 = Window(mobile = 9 * gb),
        sims = listOf(Sim(0, "Alpha", 3 * gb, true, 6 * gb), Sim(1, "Beta", 1 * gb, true, 3 * gb)),
    )

    @Test fun `this month formula is month to date over elapsed days times the month length`() {
        assertEquals(31.0 * gb * 3 / 10, DataBadgeModel.forecastBytes(3 * gb, 10 * day, 31 * day)!!.toDouble(), 2.0)
        assertEquals(2 * gb, DataBadgeModel.forecastBytes(gb, 15 * day, 30 * day))
    }

    @Test fun `30 day formula is the daily average of the last 30 days times the month length`() {
        // 9 GB over 30 days = 0.3 GB/day, x 31 days = 9.3 GB
        assertEquals(31.0 * gb * 9 / 30, DataBadgeModel.avg30Estimate(9 * gb, tenOf31)!!.toDouble(), 2.0)
        // a window that is not exactly 30 days divides by its own length
        assertEquals(31.0 * gb * 9 / 29.5, DataBadgeModel.avg30Estimate(9 * gb, tenOf31.copy(last30WindowMs = (29.5 * day).toLong()))!!.toDouble(), 2048.0)
    }

    @Test fun `the two formulas differ when this month runs hotter than the last 30 days`() {
        val m = DataBadgeModel.monthEstimate(4 * gb, tenOf31)!!   // 4 GB in 10 days -> 12.4 GB
        val a = DataBadgeModel.avg30Estimate(9 * gb, tenOf31)!!   // 9.3 GB
        assertTrue(m > a)
    }

    @Test fun `title shows both estimates, labelled`() {
        assertEquals("Month est: 12.40 GB (this month avg) \u00b7 9.30 GB (30-day avg)", DataBadgeModel.title(tenOf31))
    }

    @Test fun `day one learns the month figure only, the 30 day figure is there from the start`() {
        val d1 = tenOf31.copy(monthElapsedMs = day / 3)
        assertEquals(null, DataBadgeModel.monthEstimate(gb, d1))
        assertEquals("Month est: learning (this month avg) \u00b7 9.30 GB (30-day avg)", DataBadgeModel.title(d1))
    }

    @Test fun `no history for the 30 day figure reads as no history, not as zero`() {
        val t = DataBadgeModel.title(tenOf31.copy(last30 = Window()))
        assertTrue(t, t.endsWith("no history (30-day avg)"))
    }

    @Test fun `with a measured split the headline per SIM carries both estimates for each`() {
        assertEquals("SIM 1 Alpha 9.30 GB | 6.20 GB \u00b7 SIM 2 Beta 3.10 GB | 3.10 GB", DataBadgeModel.collapsed(tenOf31))
        val e = DataBadgeModel.expanded(tenOf31)
        assertTrue(e, e.contains("\u2022 SIM 1 Alpha \u00b7 3.00 GB this month \u00b7 est 9.30 GB (this month avg) \u00b7 6.20 GB (30-day avg)"))
        assertTrue(e, e.contains("\u2022 SIM 2 Beta \u00b7 1.00 GB this month \u00b7 est 3.10 GB (this month avg) \u00b7 3.10 GB (30-day avg)"))
    }

    @Test fun `a single SIM is the total, named`() {
        val one = tenOf31.copy(sims = listOf(Sim(0, "Alpha", 4 * gb, true, 9 * gb)))
        assertTrue(DataBadgeModel.perSim(one))
        val e = DataBadgeModel.expanded(one)
        assertTrue(e, e.contains("\u2022 SIM 1 Alpha \u00b7 4.00 GB this month"))
        assertEquals("Today mobile 300.0 MB \u00b7 Wi-Fi 900.0 MB", DataBadgeModel.collapsed(one))
    }

    @Test fun `dual sim with one inactive lists only the active one`() {
        val e = DataBadgeModel.expanded(tenOf31.copy(sims = listOf(Sim(1, "Beta", 1 * gb, true, 3 * gb))))
        assertTrue(e, e.contains("SIM 2 Beta"))
        assertFalse(e, e.contains("SIM 1"))
    }

    @Test fun `no sim says so and still shows the total estimates`() {
        val s = tenOf31.copy(sims = emptyList())
        assertTrue(DataBadgeModel.expanded(s), DataBadgeModel.expanded(s).contains("Mobile: no active SIM"))
        assertTrue(DataBadgeModel.title(s).startsWith("Month est:"))
    }

    @Test fun `a split the platform refuses falls back to the total and is not invented per SIM`() {
        val shared = tenOf31.copy(sims = listOf(Sim(0, "Alpha", 4 * gb, false), Sim(1, "Beta", 4 * gb, false)))
        assertFalse(DataBadgeModel.perSim(shared))
        val e = DataBadgeModel.expanded(shared)
        assertTrue(e, e.contains("cannot be split per SIM (SIM 1 Alpha, SIM 2 Beta)"))
        assertFalse("the shared total must not be listed as per-SIM usage", e.contains("\u2022 SIM"))
        assertEquals("the headline stays the total", "Month est: 12.40 GB (this month avg) \u00b7 9.30 GB (30-day avg)", DataBadgeModel.title(shared))
        assertEquals("Today mobile 300.0 MB \u00b7 Wi-Fi 900.0 MB", DataBadgeModel.collapsed(shared))
    }

    @Test fun `missing phone permission falls back to the total, says what is missing and offers the grant`() {
        val s = tenOf31.copy(hasPhone = false, sims = emptyList())
        val e = DataBadgeModel.expanded(s)
        assertTrue(e, e.contains("needs the Phone permission"))
        assertTrue(DataBadgeModel.title(s).startsWith("Month est: 12.40 GB"))
        assertEquals(listOf(Act.GRANT_PHONE, Act.REFRESH, Act.OPEN), DataBadgeModel.actions(s).map { it.act })
        assertEquals("Grant phone", DataBadgeModel.actions(s).first().label)
    }

    @Test fun `usage access outranks the phone permission`() {
        val s = Snapshot(hasAccess = false, hasPhone = false)
        assertEquals(listOf(Act.GRANT, Act.OPEN), DataBadgeModel.actions(s).map { it.act })
    }
}
