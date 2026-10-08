package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.DataBadgeModel.Act
import com.diegonmarcos.superapp.network.DataBadgeModel.App
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
}
