package com.diegonmarcos.superapp.boards

import android.app.Application
import android.os.BatteryManager
import com.diegonmarcos.superapp.launcher.Sections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Boards > Resources and Boards > Quick Actions:
 *   B1 the strip order is Inboxes, ME, Tasks, then the "|" separator, then Resources, Quick Actions
 *   B2 Resources declares one card per provider section, each a real section of ResourcesProvider
 *   B3 Quick Actions IS Configs > Launcher > Quick Actions (mirrored by page, no copy of it)
 *   B4 the provider's raw-value parsing (battery, bytes, uptime, cpu, signal, ip)
 *   B5 every declared section answers with rows on a device (values from the platform, none invented)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ResourcesBoardTest {
    private val boards get() = Sections.byId("boards")!!

    @Test fun `B1 the strip order carries the separator before Resources`() {
        assertEquals(listOf("msgs", "my-pm", "tasks", "resources", "quick-actions"), boards.pages.map { it.id })
        assertEquals(listOf(false, false, false, true, false), boards.pages.map { it.separatorBefore })
        val nav = boards.pages.map(Sections::navPage)
        assertTrue(nav.first { it.id == "resources" }.separatorBefore)
        assertFalse(nav.first { it.id == "quick-actions" }.separatorBefore)
        assertEquals(listOf("Resources", "Quick Actions"), boards.pages.takeLast(2).map { it.label })
    }

    @Test fun `B2 Resources declares a card per provider section`() {
        val cards = boards.stackByPage["resources"].orEmpty()
        assertTrue(cards.all { it.kind == "board_resources" })
        assertEquals(ResourcesProvider.SECTIONS, cards.map { it.resource })
    }

    @Test fun `B3 Quick Actions is the Configs page itself`() {
        val qa = boards.allPages.first { it.id == "quick-actions" }
        assertEquals("config/controls", qa.mirrorPage)
        assertTrue(qa.facet)
        assertTrue("no copy of its content in Boards", boards.stackByPage["quick-actions"].isNullOrEmpty() && boards.tilesByPage["quick-actions"].isNullOrEmpty())
        assertEquals("Quick Actions", Sections.byId("config")!!.allPages.first { it.id == "controls" }.label)
    }

    @Test fun `B4 raw values parse and format`() {
        assertEquals(87, ResourcesFormat.percent(174, 200))
        assertNull(ResourcesFormat.percent(-1, 100)); assertNull(ResourcesFormat.percent(5, 0))
        assertEquals("Charging", ResourcesFormat.batteryStatus(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals("Good", ResourcesFormat.batteryHealth(BatteryManager.BATTERY_HEALTH_GOOD))
        assertEquals("31.5 °C", ResourcesFormat.temperature(315))
        assertEquals("4.12 V", ResourcesFormat.voltage(4120))
        assertEquals("1.5 GB", ResourcesFormat.bytes(1_610_612_736L))
        assertEquals("512 B", ResourcesFormat.bytes(512))
        assertEquals("50.0 GB used / 50.0 GB free of 100.0 GB (50%)", ResourcesFormat.used(100L shl 30, 50L shl 30))
        assertEquals("2d 3h 4m", ResourcesFormat.uptime((2 * 86_400 + 3 * 3_600 + 4 * 60) * 1000L))
        assertEquals("2.40 GHz", ResourcesFormat.frequency(2_400_000)); assertEquals("800 MHz", ResourcesFormat.frequency(800_000))
        assertEquals("0.52 / 0.58 / 0.59", ResourcesFormat.loadAverage("0.52 0.58 0.59 1/345 1234\n"))
        assertNull(ResourcesFormat.loadAverage("garbage"))
        assertEquals(4, ResourcesFormat.signalBars(-50)); assertEquals(0, ResourcesFormat.signalBars(-100))
        assertEquals("192.168.1.7", ResourcesFormat.ip(0x0701A8C0))
    }

    @Test fun `B5 every declared section answers with rows`() {
        val ctx = RuntimeEnvironment.getApplication()
        for (id in listOf("device", "cpu", "memory", "storage")) {
            val rows = ResourcesProvider.read(ctx, id)
            assertTrue("$id has rows", rows.isNotEmpty())
            assertTrue("$id has no blank value", rows.all { it.label.isNotBlank() && it.value.isNotBlank() })
        }
        assertNotNull(ResourcesProvider.read(ctx, "battery"))
        assertTrue(ResourcesProvider.read(ctx, "nope").isEmpty())
    }
}
