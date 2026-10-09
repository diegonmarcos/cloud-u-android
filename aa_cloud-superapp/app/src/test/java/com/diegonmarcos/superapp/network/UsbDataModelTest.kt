package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.UsbDataModel.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Data icon lights for a data cable or an OTG device, never for a charger. */
class UsbDataModelTest {

    private fun st(vararg on: String, plugged: Int = 2, otg: List<String> = emptyList()) =
        UsbDataModel.state(on.associateWith { true }, plugged, otg)

    @Test fun `plain charging is not data`() {
        val s = st("connected", "configured")
        assertFalse(s.data)
        assertEquals(Role.DEVICE, s.role)
        assertEquals("○ Cable, no data (charge-only)", s.headline())
    }

    @Test fun `adb alone over the cable is not data`() {
        val s = st("connected", "configured", "adb")
        assertFalse(s.data)
        assertTrue(s.adbOverUsb)
        assertEquals("○ Cable, no data (charge-only + adb)", s.headline())
    }

    @Test fun `a charger with no USB session is charging only`() {
        assertEquals("○ Charging only (no data)", st(plugged = 1).headline())
        assertEquals("○ Wireless charging (no cable)", st(plugged = 4).headline())
        assertEquals("○ Disconnected", st(plugged = 0).headline())
        assertEquals(Role.NONE, st(plugged = 0).role)
    }

    @Test fun `mtp ptp tethering and midi are data in device mode`() {
        for (fn in listOf("mtp", "ptp", "rndis", "ncm", "midi")) {
            val s = st("connected", fn)
            assertTrue(fn, s.data)
            assertEquals(Role.DEVICE, s.role)
            assertEquals("● Data · device mode", s.headline())
        }
        assertEquals(listOf("MTP (file transfer)"), st("connected", "mtp", "adb").functions)
    }

    @Test fun `a data function without a connected cable is not lit`() {
        assertFalse(st("mtp").data)
    }

    @Test fun `host mode or an attached device is data`() {
        assertTrue(st("host_connected").data)
        val s = st(otg = listOf("Kingston DataTraveler (0951:1666)"), plugged = 0)
        assertTrue(s.data)
        assertEquals(Role.HOST, s.role)
        assertEquals("● Data · OTG host (1 device)", s.headline())
    }

    @Test fun `speed labels`() {
        assertEquals("USB 2.0 (480 Mbps)", UsbDataModel.udcSpeed("high-speed\n"))
        assertEquals("USB 3.x (5 Gbps)", UsbDataModel.udcSpeed("super-speed"))
        assertNull(UsbDataModel.udcSpeed("UNKNOWN"))
        assertNull(UsbDataModel.udcSpeed(null))
        assertEquals("480 Mbps", UsbDataModel.hostSpeed("480\n"))
        assertEquals("1.5 Mbps", UsbDataModel.hostSpeed("1.5"))
        assertEquals("5 Gbps", UsbDataModel.hostSpeed("5000"))
        assertNull(UsbDataModel.hostSpeed("x"))
    }
}
