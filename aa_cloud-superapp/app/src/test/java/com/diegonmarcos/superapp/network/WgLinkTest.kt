package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.WgLink.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WG icon's truth: on the mesh only with a hub handshake under 3 min. Every case below has
 * Android reporting a VPN transport, as the phone does whenever the engine's DNS-only tunnel or
 * the mesh tunnel is up - the signal the strip used to light the icon from.
 */
class WgLinkTest {

    private val now = 1_700_000_000_000L

    private fun assertOff(r: WgLink.Reading) {
        assertNotEquals(State.ON, r.state)
        assertEquals("dots must agree with an unlit icon", SignalLevels.NONE, r.level)
        assertEquals("an icon that is not on the mesh is never white", WgLink.TINT_OFF, WgLink.tint(r.state))
    }

    @Test fun `no mesh tunnel is off even while a VPN transport exists`() {
        val r = WgLink.derive(tunnelUp = false, handshakesMs = emptyList(), nowMs = now, vpnTransport = true)
        assertEquals(State.OFF, r.state)
        assertOff(r)
        assertTrue(r.reason, r.reason.contains("off mesh"))
    }

    @Test fun `tunnel up without any handshake is no mesh`() {
        val r = WgLink.derive(true, emptyList(), now, vpnTransport = true)
        assertEquals(State.NO_MESH, r.state)
        assertOff(r)
        assertEquals("Tunnel up, no handshake yet — off mesh", r.reason)
    }

    @Test fun `a handshake timestamp of 0 means never, not now`() {
        val r = WgLink.derive(true, listOf(0L, 0L), now, vpnTransport = true)
        assertEquals(State.NO_MESH, r.state)
        assertOff(r)
    }

    @Test fun `a stale handshake is no mesh and names its age`() {
        val r = WgLink.derive(true, listOf(now - 7 * 60_000L), now, vpnTransport = true)
        assertEquals(State.NO_MESH, r.state)
        assertOff(r)
        assertEquals("Tunnel up, no handshake for 7 min — off mesh", r.reason)
        assertEquals(State.NO_MESH, WgLink.derive(true, listOf(now - WgLink.FRESH_MS), now).state)
    }

    @Test fun `a fresh handshake is on, white, with dots`() {
        val r = WgLink.derive(true, listOf(0L, now - 42_000L), now, vpnTransport = true)
        assertEquals(State.ON, r.state)
        assertEquals(4, r.level)
        assertEquals(WgLink.TINT_ON, WgLink.tint(r.state))
        assertEquals("On mesh — handshake 42 s ago", r.reason)
        assertEquals(3, WgLink.derive(true, listOf(now - 150_000L), now).level)
        assertEquals(State.ON, WgLink.derive(true, listOf(now - WgLink.FRESH_MS + 1), now).state)
    }

    @Test fun `a handshake stamped far ahead of the clock does not look fresh`() {
        val r = WgLink.derive(true, listOf(now + 3_600_000L), now, vpnTransport = true)
        assertEquals(State.NO_MESH, r.state)
        assertOff(r)
        // engine rounding of a few ms-to-seconds still counts as just now
        assertEquals(State.ON, WgLink.derive(true, listOf(now + 1_000L), now).state)
    }

    @Test fun `no path is named while there is no handshake, a fresh handshake outranks it`() {
        val noPath = WgLink.NO_PATH
        val r = WgLink.derive(true, emptyList(), now, path = noPath)
        assertEquals(State.NO_MESH, r.state)
        assertTrue(r.reason, r.reason.contains("no path to the hubs"))
        assertEquals(State.ON, WgLink.derive(true, listOf(now - 10_000L), now, path = noPath).state)
    }

    @Test fun `off and no mesh share the grey every other strip icon uses when off`() {
        assertEquals(0x44FFFFFF, WgLink.tint(State.OFF))
        assertEquals(0x44FFFFFF, WgLink.tint(State.NO_MESH))
        assertEquals(0xFFFFFFFF.toInt(), WgLink.tint(State.ON))
    }

    @Test fun `ages read in the unit a person uses`() {
        assertEquals("42 s", WgLink.ago(42_000))
        assertEquals("7 min", WgLink.ago(7 * 60_000L + 5_000))
        assertEquals("3 h", WgLink.ago(3 * 3_600_000L))
    }
}
