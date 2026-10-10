package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.network.NetworkBadgeModel.Act
import com.diegonmarcos.superapp.network.NetworkBadgeModel.PeerRow
import com.diegonmarcos.superapp.network.NetworkBadgeModel.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Network" badge's state -> notification model: the labels, the action
 * set and the expanded text, with no device. Addresses are TEST-NET stand-ins.
 */
class NetworkBadgeModelTest {

    private val now = 1_700_000_000_000L
    private val peerA = PeerRow("hub", "192.0.2.1:51820", "10.0.0.0/24, fd00::/64", now - 12_000, 1536, 2048)
    private val peerB = PeerRow("phone2", "198.51.100.7:51820", "10.0.0.7/32", 0, 0, 1024 * 1024)

    private fun up(vararg p: PeerRow) = Snapshot(
        engineInstalled = true, connected = true, tunnel = "wg-mesh", profile = "wg-mesh",
        addresses = listOf("10.0.0.5/32", "fd00::5/128"), dns = listOf("10.0.0.1", "10.0.0.2"),
        peers = p.toList(), alwaysOn = true, upSinceMs = now - 7_500_000, upSinceExact = true, nowMs = now,
    )

    private val down = up(peerA).copy(connected = false, alwaysOn = false, upSinceMs = 0, upSinceExact = false)

    // ── collapsed view ───────────────────────────────────────────────────

    @Test fun `connected title and line carry tunnel, mesh ip, peer count and rx tx`() {
        val c = NetworkBadgeModel.card(up(peerA, peerB))
        assertEquals("Mesh · Connected", c.title)
        assertEquals("wg-mesh · 10.0.0.5 · 2 peers · ↓1.5 KB ↑1.0 MB", c.text)
    }

    @Test fun `the fallback path shows on the line and in the expanded text, only while connected`() {
        val relay = up(peerA).copy(path = "TLS-443 relay", pathDetail = "vpn.example -> 192.0.2.9 (pinned IP)")
        assertTrue(NetworkBadgeModel.collapsed(relay).contains("TLS-443 relay"))
        assertTrue(NetworkBadgeModel.expanded(relay).contains("Path: TLS-443 relay (vpn.example -> 192.0.2.9 (pinned IP))"))
        assertFalse(NetworkBadgeModel.collapsed(relay.copy(connected = false)).contains("TLS-443 relay"))
        assertFalse(NetworkBadgeModel.expanded(up(peerA)).contains("Path:"))
    }

    @Test fun `disconnected says so and shows no traffic`() {
        val c = NetworkBadgeModel.card(down)
        assertEquals("Mesh · Disconnected", c.title)
        assertEquals("wg-mesh · 10.0.0.5 · 1 peer", c.text)
    }

    @Test fun `a running tunnel whose hubs stopped answering is not called connected`() {
        val stale = up(peerA.copy(lastHandshakeMs = now - 7 * 60_000L), peerB)
        val c = NetworkBadgeModel.card(stale)
        assertEquals("Mesh · Tunnel up, off mesh", c.title)
        assertTrue(c.text, c.text.contains("Tunnel up, no handshake for 7 min — off mesh"))
        assertTrue(c.expanded, c.expanded.contains("Mesh: Tunnel up, no handshake for 7 min — off mesh"))
        assertEquals("Disconnect", NetworkBadgeModel.toggleLabel(stale))   // the tunnel still runs
        assertEquals("Mesh · Tunnel up, off mesh", NetworkBadgeModel.title(up(peerB)))   // never handshook
    }

    @Test fun `the mesh ip is the first address without its prefix`() {
        assertEquals("10.0.0.5", NetworkBadgeModel.meshIp(up()))
        assertEquals("", NetworkBadgeModel.meshIp(up().copy(addresses = emptyList())))
    }

    @Test fun `a missing engine is named instead of a fake disconnected mesh`() {
        val c = NetworkBadgeModel.card(down.copy(engineInstalled = false))
        assertTrue(c.text, c.text.contains("Cloud-Lib-Net-Wg is not installed"))
    }

    @Test fun `a refused action leads the collapsed line and the expanded text`() {
        val c = NetworkBadgeModel.card(down.copy(note = "Connect failed: boom"))
        assertTrue(c.text.startsWith("Connect failed: boom"))
        assertTrue(c.expanded.startsWith("Connect failed: boom"))
    }

    // ── buttons ──────────────────────────────────────────────────────────

    @Test fun `exactly three actions in the owner's order`() {
        assertEquals(listOf(Act.ALWAYS_ON, Act.TOGGLE, Act.MORE), NetworkBadgeModel.actions(up()).map { it.act })
    }

    @Test fun `always on label reflects the current state`() {
        assertEquals("Always On: ON", NetworkBadgeModel.alwaysOnLabel(up().copy(alwaysOn = true)))
        assertEquals("Always On: OFF", NetworkBadgeModel.alwaysOnLabel(up().copy(alwaysOn = false)))
        assertEquals("Always On: ON", NetworkBadgeModel.actions(up().copy(alwaysOn = true))[0].label)
    }

    @Test fun `connect disconnect label follows the connection`() {
        assertEquals("Disconnect", NetworkBadgeModel.actions(up())[1].label)
        assertEquals("Connect", NetworkBadgeModel.actions(down)[1].label)
    }

    @Test fun `more is labelled More`() {
        assertEquals("More", NetworkBadgeModel.actions(down)[2].label)
    }

    // ── expanded view ────────────────────────────────────────────────────

    @Test fun `expanded lists addresses, endpoints, allowed ips, dns and uptime`() {
        val e = NetworkBadgeModel.expanded(up(peerA, peerB))
        assertTrue(e, e.contains("Interface: 10.0.0.5/32, fd00::5/128"))
        assertTrue(e, e.contains("Mesh DNS: 10.0.0.1, 10.0.0.2"))
        assertTrue(e, e.contains("endpoint 192.0.2.1:51820"))
        assertTrue(e, e.contains("allowed 10.0.0.0/24, fd00::/64"))
        assertTrue(e, e.contains("endpoint 198.51.100.7:51820"))
        assertTrue(e, e.contains("Uptime: 2h 5m"))
        assertTrue(e, e.contains("Peers (2):"))
    }

    @Test fun `expanded gives each peer its last handshake as relative time`() {
        val e = NetworkBadgeModel.expanded(up(peerA, peerB))
        assertTrue(e, e.contains("hub · handshake 12s ago"))
        assertTrue(e, e.contains("phone2 · handshake never"))
    }

    @Test fun `disconnected peers do not claim a handshake`() {
        val e = NetworkBadgeModel.expanded(down)
        assertTrue(e, e.contains("hub · not connected"))
        assertFalse(e, e.contains("handshake"))
        assertTrue(e, e.contains("Uptime: -"))
    }

    @Test fun `uptime is a lower bound when only first seen up`() {
        assertEquals("≥ 2h 5m", NetworkBadgeModel.uptime(up().copy(upSinceExact = false)))
        assertEquals("unknown", NetworkBadgeModel.uptime(up().copy(upSinceMs = 0)))
    }

    @Test fun `profile is shown only when it differs from the tunnel`() {
        assertFalse(NetworkBadgeModel.expanded(up()).contains("profile"))
        assertTrue(NetworkBadgeModel.expanded(up().copy(profile = "work")).contains("Tunnel: wg-mesh (profile work)"))
    }

    // ── formatters ───────────────────────────────────────────────────────

    @Test fun `relative time`() {
        val r = { ms: Long -> NetworkBadgeModel.relative(now - ms, now) }
        assertEquals("never", NetworkBadgeModel.relative(0, now))
        assertEquals("just now", r(2_000))
        assertEquals("59s ago", r(59_000))
        assertEquals("3m ago", r(185_000))
        assertEquals("2h ago", r(7_300_000))
        assertEquals("2d ago", r(2 * 86_400_000L + 5))
        assertEquals("just now", NetworkBadgeModel.relative(now + 9_000, now)) // clock skew never goes negative
    }

    @Test fun `bytes`() {
        assertEquals("0 B", NetworkBadgeModel.bytes(0))
        assertEquals("1023 B", NetworkBadgeModel.bytes(1023))
        assertEquals("1.0 KB", NetworkBadgeModel.bytes(1024))
        assertEquals("1.5 MB", NetworkBadgeModel.bytes(1_572_864))
        assertEquals("2.00 GB", NetworkBadgeModel.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test fun `duration keeps the two largest units`() {
        assertEquals("45s", NetworkBadgeModel.duration(45_000))
        assertEquals("5m 3s", NetworkBadgeModel.duration(303_000))
        assertEquals("1d 3h", NetworkBadgeModel.duration(100_000_000))
    }

    // ── DNS details ──────────────────────────────────────────────────────

    private val dnsUp = up(peerA).copy(
        resolvers = listOf("10.0.0.1", "1.1.1.1"), resolversOnVpn = true,
        bridge = "127.0.0.1:2053 listening, last route Android resolver on Wi-Fi", privateDns = "off",
    )

    @Test fun `expanded names the resolvers in effect, the bridge and private dns`() {
        val e = NetworkBadgeModel.expanded(dnsUp)
        assertTrue(e, e.contains("Resolvers: 10.0.0.1, 1.1.1.1 (via VPN)"))
        assertTrue(e, e.contains("Bridge: 127.0.0.1:2053 listening, last route Android resolver on Wi-Fi"))
        assertTrue(e, e.contains("Private DNS: off"))
        assertTrue(e, e.contains("Mesh DNS: 10.0.0.1, 10.0.0.2"))
    }

    @Test fun `unasked dns lines stay out and an empty resolver list reads as a dash`() {
        val e = NetworkBadgeModel.expanded(up(peerA))
        assertFalse(e, e.contains("Bridge:"))
        assertFalse(e, e.contains("Private DNS:"))
        assertTrue(e, e.contains("Resolvers: -"))
    }

    @Test fun `bridge line`() {
        assertEquals("127.0.0.1:2053 listening, no query yet", NetworkBadgeModel.bridgeLine(true, 2053, null, null))
        assertEquals("127.0.0.1:2053 listening, last route X", NetworkBadgeModel.bridgeLine(true, 2053, "X", null))
        assertEquals("127.0.0.1:2053 not listening (taken)", NetworkBadgeModel.bridgeLine(false, 2053, null, "taken"))
        assertEquals("127.0.0.1:- not listening", NetworkBadgeModel.bridgeLine(false, 0, null, null))
    }

    @Test fun `private dns line`() {
        assertEquals("off", NetworkBadgeModel.privateDnsLine("off", null, null, null))
        assertEquals("automatic (not in use)", NetworkBadgeModel.privateDnsLine("opportunistic", null, false, null))
        assertEquals("automatic (active: dns.example)", NetworkBadgeModel.privateDnsLine("opportunistic", null, true, "dns.example"))
        assertEquals("hostname dns.example (active)", NetworkBadgeModel.privateDnsLine("hostname", "dns.example", true, null))
        assertEquals("hostname dns.example (not active)", NetworkBadgeModel.privateDnsLine("hostname", "dns.example", false, null))
        assertEquals("unknown", NetworkBadgeModel.privateDnsLine(null, null, null, null))
    }
}
