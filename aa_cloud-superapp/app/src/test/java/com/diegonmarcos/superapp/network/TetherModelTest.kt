package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.TetherModel.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HS icon + Hotspot section: which tethering is on, and the shell-read SSID / band / clients. */
class TetherModelTest {

    @Test fun `interface names classify by kind`() {
        assertEquals(Kind.WIFI, TetherModel.kindOf("wlan1"))
        assertEquals(Kind.WIFI, TetherModel.kindOf("swlan0"))
        assertEquals(Kind.WIFI, TetherModel.kindOf("ap0"))
        assertEquals(Kind.USB, TetherModel.kindOf("rndis0"))
        assertEquals(Kind.USB, TetherModel.kindOf("ncm0"))
        assertEquals(Kind.BLUETOOTH, TetherModel.kindOf("bt-pan"))
        assertEquals(Kind.ETHERNET, TetherModel.kindOf("eth0"))
        assertEquals(Kind.OTHER, TetherModel.kindOf("p2p-wlan0-0"))
    }

    @Test fun `nothing tethered is off`() {
        val s = TetherModel.State(emptyList(), apState = 11, usbFunction = false)
        assertFalse(s.active)
        assertEquals("off", s.summary())
    }

    @Test fun `the AP state alone lights the hotspot before an interface is listed`() {
        assertTrue(TetherModel.State(emptyList(), apState = 13, usbFunction = false).wifi)
        assertTrue(TetherModel.State(emptyList(), apState = 12, usbFunction = false).active)
    }

    @Test fun `each kind of tethering is reported`() {
        val s = TetherModel.State(listOf("wlan1", "rndis0", "bt-pan"), apState = null, usbFunction = false)
        assertTrue(s.wifi && s.usb && s.bluetooth)
        assertEquals("Wi-Fi hotspot, USB tethering, Bluetooth tethering", s.summary())
        assertTrue(TetherModel.State(emptyList(), null, usbFunction = true).usb)
    }

    @Test fun `softap dump lines give ssid band and clients`() {
        val dump = """
            mApConfig.SSID: "Cloud-Phone"
            mApConfig.band: 2
            mConnectedClientWithApInfoMap.size(): 3
        """.trimIndent()
        val ap = TetherModel.parseSoftAp(dump, 34)
        assertEquals("Cloud-Phone", ap.ssid)
        assertEquals("5 GHz", ap.band)
        assertEquals(3, ap.clients)
    }

    @Test fun `the live frequency wins over the configured band`() {
        val dump = "mApConfig.band: 3\nmCurrentSoftApInfo: SoftApInfo{bandwidth= 2, frequency= 2437}"
        assertEquals("2.4 GHz", TetherModel.parseSoftAp(dump, 34).band)
        assertEquals("2.4 + 5 GHz", TetherModel.parseSoftAp("mApConfig.band: 3", 34).band)
    }

    @Test fun `older releases number the band differently`() {
        assertEquals("5 GHz", TetherModel.bandLabel(1, 29))
        assertEquals("2.4 GHz", TetherModel.bandLabel(1, 30))
        assertEquals("any band", TetherModel.bandLabel(-1, 28))
    }

    @Test fun `an unreadable dump is all unknown, never a guess`() {
        val ap = TetherModel.parseSoftAp("Permission Denial: can't dump wifi", 34)
        assertNull(ap.ssid); assertNull(ap.band); assertNull(ap.clients)
    }

    @Test fun `clients are distinct live MACs on the tethered interfaces`() {
        val neigh = """
            192.168.43.20 dev wlan1 lladdr 02:00:00:00:00:01 REACHABLE
            fe80::1 dev wlan1 lladdr 02:00:00:00:00:01 STALE
            192.168.43.21 dev wlan1 lladdr 02:00:00:00:00:02 DELAY
            192.168.43.22 dev wlan1  FAILED
            192.168.43.23 dev wlan1 lladdr 02:00:00:00:00:03 FAILED
            10.0.0.1 dev wlan0 lladdr 02:00:00:00:00:09 REACHABLE
        """.trimIndent()
        assertEquals(2, TetherModel.countClients(neigh, listOf("wlan1")))
        assertEquals(0, TetherModel.countClients(neigh, emptyList()))
    }
}
