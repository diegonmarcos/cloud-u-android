package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.BtLinks.Battery
import com.diegonmarcos.superapp.network.BtLinks.Link
import com.diegonmarcos.superapp.network.BtLinks.Reading
import org.junit.Assert.assertEquals
import org.junit.Test

/** The strip's BT icon (adapter on/off) + dots (connected devices) and the popup's BT rows. Addresses are made up. */
class BtLinksTest {

    @Test fun `dots count connected devices, four or more is four, none is all grey`() {
        assertEquals(0, BtLinks.dots(0))
        for (n in 1..4) assertEquals(n, BtLinks.dots(n))
        assertEquals(4, BtLinks.dots(9))
        assertEquals(0, BtLinks.dots(-1))
    }

    @Test fun `the label is white with the adapter on and grey with it off, whatever is connected`() {
        assertEquals(WgLink.TINT_ON, BtLinks.tint(true))
        assertEquals(WgLink.TINT_OFF, BtLinks.tint(false))
    }

    @Test fun `one row per device, every profile it rides, first name wins`() {
        val links = BtLinks.merge(listOf(
            "A2DP" to mapOf("AA:01" to "Buds"),
            "Headset" to mapOf("AA:01" to "", "AA:02" to "Car"),
            "BLE" to mapOf("AA:03" to ""),
        ))
        assertEquals(listOf(
            Link("AA:01", "Buds", listOf("A2DP", "Headset")),
            Link("AA:02", "Car", listOf("Headset")),
            Link("AA:03", "AA:03", listOf("BLE")),
        ), links)
    }

    @Test fun `the popup lists names, profiles, battery and the count`() {
        val r = Reading(true, true, listOf(
            Link("AA:01", "Buds", listOf("A2DP", "Headset"), Battery(left = 80, right = 75, case = 40)),
            Link("AA:02", "Watch", listOf("BLE"), Battery(main = 62)),
            Link("AA:03", "Car", emptyList()),
        ))
        assertEquals(listOf(
            "ON · 3 connected",
            "  • Buds (A2DP, Headset) · battery L 80% · R 75% · Case 40%",
            "  • Watch (BLE) · battery 62%",
            "  • Car · battery —",
        ), BtLinks.lines(r))
        assertEquals(listOf("ON · 0 connected", "No active links"), BtLinks.lines(Reading(true, true, emptyList())))
        assertEquals(listOf("OFF"), BtLinks.lines(Reading(false, true, emptyList())))
    }

    @Test fun `without the bluetooth grant the count is zero and the section says what to grant`() {
        val r = Reading(true, false, emptyList())
        assertEquals(0, BtLinks.dots(r.count))
        assertEquals("Grant Nearby devices (Bluetooth) to the SuperApp to count connections", BtLinks.lines(r)[1])
    }

    @Test fun `battery never shows a made-up number`() {
        assertEquals("—", BtLinks.batteryText(Battery()))
        assertEquals("—", BtLinks.batteryText(Battery(main = -1)))
        assertEquals("—", BtLinks.batteryText(Battery(main = 140)))
        assertEquals("55%", BtLinks.batteryText(Battery(main = 55)))
        assertEquals("L 80% · R 75%", BtLinks.batteryText(Battery(main = 78, left = 80, right = 75)))
        assertEquals("Case 40%", BtLinks.batteryText(Battery(case = 40)))
    }

    @Test fun `metadata battery values are ascii digits, anything else is unknown`() {
        assertEquals(80, BtLinks.metadataLevel("80".toByteArray()))
        assertEquals(-1, BtLinks.metadataLevel(null))
        assertEquals(-1, BtLinks.metadataLevel("".toByteArray()))
        assertEquals(-1, BtLinks.metadataLevel("-1".toByteArray()))
        assertEquals(-1, BtLinks.metadataLevel("abc".toByteArray()))
    }
}
