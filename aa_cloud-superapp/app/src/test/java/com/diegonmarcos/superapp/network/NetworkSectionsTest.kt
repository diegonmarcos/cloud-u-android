package com.diegonmarcos.superapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The strip's network icons and the popup's sections come from one ordered list. Pinned here, and
 * both call sites are checked to lay out through it (a hand-ordered list in either would drift).
 */
class NetworkSectionsTest {

    @Test fun `the owner's order - mobile, WiFi, BT, WG, KDE, ADB, Data, HS, GPS`() {
        assertEquals(listOf("cellular", "wifi", "bluetooth", "mesh", "kde", "adb", "data", "hotspot", "gps"), NetworkSections.ORDER)
        assertEquals(listOf("WiFi", "BT", "WG", "KDE", "ADB", "Data", "HS", "GPS"),
            NetworkSections.ORDER.drop(1).map { NetworkSections.LABELS.getValue(it) })
        assertEquals(listOf("HS", "GPS"), NetworkSections.ORDER.takeLast(2).map { NetworkSections.LABELS.getValue(it) })
    }

    @Test fun `inOrder returns any keyed map in the shared order`() {
        val popupSections = NetworkSections.ORDER.reversed().associateWith { "section:$it" }
        val stripIcons = NetworkSections.ORDER.shuffled(java.util.Random(7)).associateWith { "icon:$it" }
        assertEquals(NetworkSections.inOrder(popupSections).map { it.removePrefix("section:") },
            NetworkSections.inOrder(stripIcons).map { it.removePrefix("icon:") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a section missing from either side fails loudly`() {
        NetworkSections.inOrder(NetworkSections.ORDER.drop(1).associateWith { it })
    }

    @Test fun `every section has a settings target`() {
        assertEquals(NetworkSections.ORDER.toSet(), NetworkSections.SETTINGS.keys)
        for ((k, t) in NetworkSections.SETTINGS) when (t) {
            is NetworkSections.Target.System -> {
                assertTrue("$k has a fallback chain", t.tries.size >= 2)
                assertTrue("$k ends on Settings itself", t.tries.last() == "action:android.settings.SETTINGS")
                for (s in t.tries) assertTrue("$k: $s", s.startsWith("action:") || (s.startsWith("component:") && s.contains('/')))
            }
            is NetworkSections.Target.InApp -> assertTrue(t.page.isNotBlank())
        }
        val s = NetworkSections.SETTINGS
        assertEquals(NetworkSections.Target.InApp("config", "wg", "Cloud Mesh ›"), s[NetworkSections.MESH])
        assertEquals(NetworkSections.Target.InApp("config", "kde", "Peer Control ›"), s[NetworkSections.KDE])
        assertEquals(NetworkSections.Target.InApp("config", "adb-shell", "ADB Shell ›"), s[NetworkSections.ADB])
        assertEquals("action:android.settings.LOCATION_SOURCE_SETTINGS", (s[NetworkSections.GPS] as NetworkSections.Target.System).tries[0])
        assertEquals("action:android.settings.WIFI_SETTINGS", (s[NetworkSections.WIFI] as NetworkSections.Target.System).tries[0])
        assertEquals("action:android.settings.BLUETOOTH_SETTINGS", (s[NetworkSections.BLUETOOTH] as NetworkSections.Target.System).tries[0])
        assertTrue((s[NetworkSections.CELLULAR] as NetworkSections.Target.System).perSim)
    }

    @Test fun `a divider sits between every two sections`() {
        val l = NetworkSections.layout()
        val sections = l.filterIsInstance<NetworkSections.Item.Section>().map { it.key }
        assertEquals(NetworkSections.ORDER, sections)
        for (i in 1 until l.size) if (l[i] is NetworkSections.Item.Section) assertEquals(NetworkSections.Item.Divider, l[i - 1])
        assertTrue(l.first() is NetworkSections.Item.Section)
        assertEquals(NetworkSections.Item.Divider, l.last())   // before the trailing Network block
        assertEquals(NetworkSections.ORDER.size, l.count { it == NetworkSections.Item.Divider })
    }

    @Test fun `the popup lays out via NetworkSections layout, the strip via inOrder`() {
        val src = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/java/com/diegonmarcos/superapp") }.first { it.isDirectory }
        val popup = File(src, "network/NetworkInfoPopup.kt").readText()
        assertTrue("the popup must lay out via NetworkSections.layout", popup.contains("NetworkSections.layout()"))
        assertTrue("the popup draws the divider item", popup.contains("is NetworkSections.Item.Divider -> container.addView(divider(ctx))"))
        assertTrue("every header carries its settings link", popup.contains("NetworkSections.SETTINGS.getValue(key)"))
        val strip = File(src, "launcher/LauncherStatusStripView.kt").readText()
        assertTrue("the strip must lay out via NetworkSections.inOrder", strip.contains("NetworkSections.inOrder("))
    }
}
