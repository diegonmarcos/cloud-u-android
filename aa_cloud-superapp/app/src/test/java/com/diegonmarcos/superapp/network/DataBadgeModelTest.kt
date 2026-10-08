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

    @Test fun `title and line carry today and the month, mobile vs wifi`() {
        val c = DataBadgeModel.card(ok)
        assertEquals("Data · 1.17 GB today", c.title)
        assertEquals("Today mobile 300.0 MB · Wi-Fi 900.0 MB · Month 23.00 GB", c.text)
    }

    @Test fun `expanded gives both windows and only the top five apps in order`() {
        val e = DataBadgeModel.expanded(ok)
        assertTrue(e, e.contains("Today: 1.17 GB (mobile 300.0 MB · Wi-Fi 900.0 MB)"))
        assertTrue(e, e.contains("This month: 23.00 GB (mobile 3.00 GB · Wi-Fi 20.00 GB)"))
        assertTrue(e, e.contains("1. app1 · 9.0 MB"))
        assertTrue(e, e.contains("5. app5 · 5.0 MB"))
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

    // ── per SIM and the month-end forecast ───────────────────────────────

    private val day = DataBadgeModel.DAY_MS
    private val gb = 1024L * mb
    /** 10 days into a 31-day month. */
    private val tenOf31 = ok.copy(
        monthElapsedMs = 10 * day, monthLengthMs = 31 * day, monthEndLabel = "Oct 31",
        month = Window(mobile = 4 * gb, wifi = 20 * gb),
        sims = listOf(Sim(0, "Alpha", 3 * gb, true), Sim(1, "Beta", 1 * gb, true)),
    )

    @Test fun `forecast is month to date over elapsed days times the month length`() {
        assertEquals(31.0 * gb * 3 / 10, DataBadgeModel.forecastBytes(3 * gb, 10 * day, 31 * day)!!.toDouble(), 2.0)
    }

    @Test fun `forecast with half a month gone doubles it`() {
        assertEquals(2 * gb, DataBadgeModel.forecastBytes(gb, 15 * day, 30 * day))
    }

    @Test fun `day one has nothing to extrapolate from`() {
        assertEquals(null, DataBadgeModel.forecastBytes(gb, day - 1, 31 * day))
        assertEquals(null, DataBadgeModel.forecastBytes(gb, 0, 31 * day))
        assertEquals(null, DataBadgeModel.forecastBytes(gb, 5 * day, 0))
        assertEquals("forecast: learning", DataBadgeModel.forecast(gb, tenOf31.copy(monthElapsedMs = day / 2)))
    }

    @Test fun `forecast text reads as an estimate with the month end`() {
        assertEquals("\u2248 9.30 GB by Oct 31 (forecast)", DataBadgeModel.forecast(3 * gb, tenOf31))
    }

    @Test fun `each active sim gets its carrier, its month and its own forecast, plus the wifi total`() {
        val e = DataBadgeModel.expanded(tenOf31)
        assertTrue(e, e.contains("Wi-Fi this month: 20.00 GB"))
        assertTrue(e, e.contains("\u2022 SIM 1 Alpha \u00b7 3.00 GB \u00b7 \u2248 9.30 GB by Oct 31 (forecast)"))
        assertTrue(e, e.contains("\u2022 SIM 2 Beta \u00b7 1.00 GB \u00b7 \u2248 3.10 GB by Oct 31 (forecast)"))
    }

    @Test fun `dual sim with one inactive lists only the active one`() {
        val e = DataBadgeModel.expanded(tenOf31.copy(sims = listOf(Sim(1, "Beta", 1 * gb, true))))
        assertTrue(e, e.contains("SIM 2 Beta"))
        assertFalse(e, e.contains("SIM 1"))
    }

    @Test fun `no sim says so`() {
        val e = DataBadgeModel.expanded(tenOf31.copy(sims = emptyList()))
        assertTrue(e, e.contains("Mobile: no active SIM"))
        assertTrue(e, e.contains("Wi-Fi this month"))
    }

    @Test fun `day one shows learning for each sim`() {
        val e = DataBadgeModel.expanded(tenOf31.copy(monthElapsedMs = day / 3))
        assertEquals(2, Regex("forecast: learning").findAll(e).count())
    }

    @Test fun `a split the platform refuses is not invented`() {
        // Android 10+: two SIMs, no subscriber id -> the engine hands the full mobile total to each, exact=false.
        val shared = tenOf31.copy(sims = listOf(Sim(0, "Alpha", 4 * gb, false), Sim(1, "Beta", 4 * gb, false)))
        val e = DataBadgeModel.expanded(shared)
        assertTrue(e, e.contains("cannot be split per SIM (SIM 1 Alpha, SIM 2 Beta)"))
        assertTrue(e, e.contains("Mobile: 4.00 GB, all SIMs together"))
        assertFalse("the shared total must not be listed as per-SIM usage", e.contains("\u2022 SIM"))
        assertTrue(e, e.contains("Mobile forecast: \u2248 12.40 GB by Oct 31 (forecast)"))
    }

    @Test fun `a single sim is exact even though the subscriber id is hidden`() {
        val e = DataBadgeModel.expanded(tenOf31.copy(sims = listOf(Sim(0, "Alpha", 4 * gb, true))))
        assertTrue(e, e.contains("\u2022 SIM 1 Alpha \u00b7 4.00 GB"))
    }

    @Test fun `missing phone permission says what is missing and offers the grant`() {
        val s = tenOf31.copy(hasPhone = false, sims = emptyList())
        val e = DataBadgeModel.expanded(s)
        assertTrue(e, e.contains("needs the Phone permission"))
        assertTrue(e, e.contains("Mobile this month: 4.00 GB"))
        assertEquals(listOf(Act.GRANT_PHONE, Act.REFRESH, Act.OPEN), DataBadgeModel.actions(s).map { it.act })
        assertEquals("Grant phone", DataBadgeModel.actions(s).first().label)
    }

    @Test fun `usage access outranks the phone permission`() {
        val s = Snapshot(hasAccess = false, hasPhone = false)
        assertEquals(listOf(Act.GRANT, Act.OPEN), DataBadgeModel.actions(s).map { it.act })
    }
}
