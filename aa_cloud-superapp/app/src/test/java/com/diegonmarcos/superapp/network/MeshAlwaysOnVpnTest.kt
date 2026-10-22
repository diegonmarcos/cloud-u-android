package com.diegonmarcos.superapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1120 Android's Always-on VPN: how its settings are read and the exact shell lines that change them. */
class MeshAlwaysOnVpnTest {
    private val engine = "com.diegonmarcos.cloudlib.netwg"

    @Test fun `settings get output - a package, null and blank`() {
        assertEquals(engine, AlwaysOnVpn.parseApp("$engine\n"))
        assertNull(AlwaysOnVpn.parseApp("null\n")); assertNull(AlwaysOnVpn.parseApp("")); assertNull(AlwaysOnVpn.parseApp(null))
    }

    @Test fun `lockdown reads 1 as on, 0 and null as off, anything else as unknown`() {
        assertEquals(true, AlwaysOnVpn.parseLockdown("1\n")); assertEquals(false, AlwaysOnVpn.parseLockdown("0"))
        assertEquals(false, AlwaysOnVpn.parseLockdown("null")); assertNull(AlwaysOnVpn.parseLockdown("error")); assertNull(AlwaysOnVpn.parseLockdown(null))
    }

    @Test fun `always-on is ours only for the mesh engine's package`() {
        assertTrue(AlwaysOnVpn.isOurs(engine, engine)); assertFalse(AlwaysOnVpn.isOurs("com.other.vpn", engine)); assertFalse(AlwaysOnVpn.isOurs(null, engine))
    }

    @Test fun `switching on puts the engine as the always-on app`() {
        assertEquals(listOf("settings put secure always_on_vpn_app $engine"), AlwaysOnVpn.commands(true, engine))
    }

    @Test fun `switching off deletes the app and clears lockdown`() {
        assertEquals(listOf("settings delete secure always_on_vpn_app", "settings put secure always_on_vpn_lockdown 0"), AlwaysOnVpn.commands(false, engine))
    }

    @Test fun `lockdown commands`() {
        assertEquals("settings put secure always_on_vpn_lockdown 1", AlwaysOnVpn.lockdownCommand(true))
        assertEquals("settings put secure always_on_vpn_lockdown 0", AlwaysOnVpn.lockdownCommand(false))
    }

    @Test fun `a package name that could carry shell is refused before it reaches a shell`() {
        for (bad in listOf("a.b; reboot", "a.b && id", "\$(id)", "x", "a.b c", "")) {
            try { AlwaysOnVpn.commands(true, bad); throw AssertionError("accepted: $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun `with no channel the reason names the fix and the deep link target`() {
        assertTrue(AlwaysOnVpn.NO_CHANNEL.contains("VPN settings"))
        assertTrue(AlwaysOnVpn.NO_CHANNEL.contains("ADB Shell"))
    }
}
