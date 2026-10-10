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

    @Test fun `the owner's order - mobile, WiFi, BT, WG, KDE, ADB, Data, HS`() {
        assertEquals(listOf("5G", "WiFi", "BT", "WG", "KDE", "ADB", "Data", "HS"),
            NetworkSections.ORDER.map { NetworkSections.LABELS.getValue(it) })
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

    @Test fun `the popup and the strip both lay out through NetworkSections inOrder`() {
        val src = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/java/com/diegonmarcos/superapp") }.first { it.isDirectory }
        for (f in listOf("network/NetworkInfoPopup.kt", "launcher/LauncherStatusStripView.kt")) {
            val text = File(src, f).readText()
            assertTrue("$f must lay out via NetworkSections.inOrder", text.contains("NetworkSections.inOrder("))
        }
    }
}
