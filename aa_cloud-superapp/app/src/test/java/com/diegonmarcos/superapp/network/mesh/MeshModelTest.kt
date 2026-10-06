package com.diegonmarcos.superapp.network.mesh

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #877 The Cloud Mesh page's pure rules, on the JVM: rates, handshake age, the peer light, the route
 * table, the profile diff, the journal. No Android, no engine, no clock - a sample goes in, a verdict
 * comes out.
 */
class MeshModelTest {

    private val th = Thresholds(freshS = 150, staleS = 300)
    private val hub = PeerCfg("gcp-proxy", "KEY-HUB", "", "35.0.0.1:443", "10.0.0.0/24, fd0c:1d00::/64", "25")
    private val oci = PeerCfg("oci-analytics", "KEY-OCI", "", "[2603::1]:51821", "10.1.0.0/24, 2603:c026:c104:8f00:ff9b::/96", "25")
    private fun cfg(vararg p: PeerCfg) = ConfigView("wg-mesh", "10.0.0.9/24", "PUB", "10.0.0.1, 10.1.0.1", "1380", "", "config-v4-split", "", p.toList())
    private fun sample(at: Long, up: Boolean = true, engine: Boolean = true, vararg c: PeerCounters) = Sample(at, engine, up, c.toList())

    // ── rates ──
    @Test fun `rate is bytes per second between two reads`() {
        assertEquals(1000.0, MeshReducer.rate(0, 1000, 1000), 0.001)
        assertEquals(512.0, MeshReducer.rate(1000, 1256, 500), 0.001)
    }

    @Test fun `a counter that went down is a restart not a negative rate`() {
        assertEquals(0.0, MeshReducer.rate(5000, 100, 1000), 0.0)
    }

    @Test fun `no previous read or a jitter-short gap is zero`() {
        assertEquals(0.0, MeshReducer.rate(null, 100, 1000), 0.0)
        assertEquals(0.0, MeshReducer.rate(0, 100, MeshReducer.MIN_DT_MS - 1), 0.0)
    }

    @Test fun `the reducer derives per-peer and total rates from consecutive polls`() {
        val (a, _) = MeshReducer.reduce(null, cfg(hub, oci), sample(0, c = arrayOf(PeerCounters("KEY-HUB", 0, 0, 1), PeerCounters("KEY-OCI", 0, 0, 1))), th)
        val (b, _) = MeshReducer.reduce(a, cfg(hub, oci), sample(2000, c = arrayOf(PeerCounters("KEY-HUB", 2000, 400, 1), PeerCounters("KEY-OCI", 1000, 0, 1))), th)
        assertEquals(1000.0, b.peers[0].rxRate, 0.001)
        assertEquals(200.0, b.peers[0].txRate, 0.001)
        assertEquals(1500.0, b.rxRate, 0.001)
        assertEquals(3000L, b.rx)
    }

    @Test fun `rates restart from zero after the tunnel was down`() {
        val (a, _) = MeshReducer.reduce(null, cfg(hub), sample(0, c = arrayOf(PeerCounters("KEY-HUB", 100, 100, 1))), th)
        val (down, _) = MeshReducer.reduce(a, cfg(hub), sample(1000, up = false), th)
        val (b, _) = MeshReducer.reduce(down, cfg(hub), sample(2000, c = arrayOf(PeerCounters("KEY-HUB", 50_000, 0, 1))), th)
        assertEquals("a first read after a down poll has nothing to subtract from", 0.0, b.peers[0].rxRate, 0.0)
    }

    // ── handshake age + the light ──
    @Test fun `handshake age is whole seconds and null before the first handshake`() {
        assertEquals(12L, MeshReducer.handshakeAgeS(20_000, 8_000))
        assertNull(MeshReducer.handshakeAgeS(20_000, 0))
        assertEquals("a handshake stamped ahead of the clock is zero, not negative", 0L, MeshReducer.handshakeAgeS(1_000, 9_000))
    }

    @Test fun `health follows the declared thresholds`() {
        assertEquals(Health.FRESH, MeshReducer.health(true, true, 150, th))
        assertEquals(Health.AGING, MeshReducer.health(true, true, 151, th))
        assertEquals(Health.AGING, MeshReducer.health(true, true, 300, th))
        assertEquals(Health.STALE, MeshReducer.health(true, true, 301, th))
        assertEquals(Health.NONE, MeshReducer.health(true, true, null, th))
        assertEquals(Health.DOWN, MeshReducer.health(true, false, 5, th))
        assertEquals("no engine is UNKNOWN, never DOWN", Health.UNKNOWN, MeshReducer.health(false, false, null, th))
    }

    @Test fun `the tunnel light is three honest states`() {
        fun light(engine: Boolean, up: Boolean, hs: Long) =
            MeshReducer.reduce(null, cfg(hub), Sample(10_000, engine, up, listOf(PeerCounters("KEY-HUB", 1, 1, hs))), th).first.light
        assertEquals(Light.UP, light(true, true, 9_000))
        assertEquals("up with no handshake yet is a warning", Light.WARN, light(true, true, 0))
        assertEquals(Light.DOWN, light(true, false, 0))
        assertEquals(Light.UNKNOWN, light(false, false, 0))
    }

    // ── journal ──
    @Test fun `the journal records state transitions and a peer going stale`() {
        val (a, e0) = MeshReducer.reduce(null, cfg(hub), sample(0, up = false), th)
        assertEquals("observe", e0.single().kind)
        val (b, e1) = MeshReducer.reduce(a, cfg(hub), sample(1_000, c = arrayOf(PeerCounters("KEY-HUB", 1, 1, 0))), th)
        assertTrue(e1.any { it.kind == "state" && it.text.endsWith("UP") })
        val (c, e2) = MeshReducer.reduce(b, cfg(hub), sample(2_000, c = arrayOf(PeerCounters("KEY-HUB", 1, 1, 1_900))), th)
        assertTrue("first handshake is a line: $e2", e2.any { it.kind == "handshake" && it.text.startsWith("first handshake") })
        val (_, e3) = MeshReducer.reduce(c, cfg(hub), sample(2_000 + 400_000, c = arrayOf(PeerCounters("KEY-HUB", 1, 1, 1_900))), th)
        assertTrue("a peer silent past stale_s is journalled: $e3", e3.any { it.kind == "stale" })
        val (_, e4) = MeshReducer.reduce(c, cfg(hub), sample(3_000, up = false), th)
        assertTrue(e4.any { it.kind == "state" && it.text.endsWith("DOWN") })
    }

    @Test fun `an unchanged poll writes nothing`() {
        val s = sample(0, c = arrayOf(PeerCounters("KEY-HUB", 1, 1, 0)))
        val (a, _) = MeshReducer.reduce(null, cfg(hub), s, th)
        assertTrue(MeshReducer.reduce(a, cfg(hub), s.copy(atMs = 1_000), th).second.isEmpty())
    }

    @Test fun `the journal is capped and keeps the newest`() {
        val j = MeshJournal(3)
        for (i in 1..5) j.add(LogEvent(i.toLong(), "k", "e$i"))
        assertEquals(listOf("e3", "e4", "e5"), j.list().map { it.text })
        j.clear(); assertTrue(j.list().isEmpty())
    }

    @Test fun `journal text is stamped`() {
        val j = MeshJournal(5); j.add(LogEvent(3_723_000, "state", "up"))
        assertEquals("01:02:03 state     up", j.text(TimeZone.getTimeZone("UTC")))
    }

    // ── formats ──
    @Test fun `formats read the same everywhere`() {
        assertEquals("0 B", MeshFormat.bytes(0))
        assertEquals("1.50 KiB", MeshFormat.bytes(1536))
        assertEquals("12.0 MiB", MeshFormat.bytes(12L * 1024 * 1024))
        assertEquals("0 B/s", MeshFormat.rate(0.4))
        assertEquals("2.00 KiB/s", MeshFormat.rate(2048.0))
        assertEquals("12s", MeshFormat.age(12)); assertEquals("3m 05s", MeshFormat.age(185))
        assertEquals("2h 10m", MeshFormat.age(7800)); assertEquals("3d 04h", MeshFormat.age(3 * 86_400L + 4 * 3600))
        assertEquals("-", MeshFormat.age(null)); assertEquals("-", MeshFormat.latency(null)); assertEquals("12 ms", MeshFormat.latency(12))
    }

    // ── routes ──
    @Test fun `the split mesh profile is SPLIT with the NAT64 prefix found`() {
        val t = Routes.derive(listOf(hub, oci), "ff9b")
        assertEquals(RouteMode.SPLIT, t.mode)
        assertEquals(listOf("2603:c026:c104:8f00:ff9b::/96"), t.nat64)
        assertTrue(t.v4Coverage < 0.001)
        assertEquals(RouteKind.NAT64, t.rows.single { it.cidr.endsWith("ff9b::/96") }.kind)
        assertEquals(RouteKind.SUBNET, t.rows.first { it.cidr == "10.0.0.0/24" }.kind)
    }

    @Test fun `a default route makes it FULL`() {
        val full = oci.copy(allowedIps = "10.1.0.0/24, ::/0")
        assertEquals(RouteMode.FULL, Routes.derive(listOf(hub, full), "ff9b").mode)
        assertEquals(RouteMode.FULL, Routes.derive(listOf(hub.copy(allowedIps = "0.0.0.0/0")), "ff9b").mode)
    }

    @Test fun `chunked public space still reads as FULL and overlapping entries never count twice`() {
        val chunks = hub.copy(allowedIps = "0.0.0.0/1, 128.0.0.0/2, 192.0.0.0/3, 192.0.0.0/8")
        val t = Routes.derive(listOf(chunks), "ff9b")
        assertEquals(RouteMode.FULL, t.mode)
        assertEquals(0.875, t.v4Coverage, 0.0001)
    }

    @Test fun `host routes and no routes`() {
        val t = Routes.derive(listOf(hub.copy(allowedIps = "10.0.0.1/32, fd00::1/128")), "ff9b")
        assertEquals(listOf(RouteKind.HOST, RouteKind.HOST), t.rows.map { it.kind })
        assertEquals(RouteMode.NONE, Routes.derive(listOf(hub.copy(allowedIps = "")), "ff9b").mode)
        assertEquals(1, Routes.filter(t.rows, "host").count { it.family == 4 })
    }

    @Test fun `latency targets pick the resolver each peer's routes cover`() {
        val m = LatencyTargets.forPeers(listOf(hub, oci), listOf("10.0.0.1", "10.1.0.1"))
        assertEquals("10.0.0.1", m["KEY-HUB"]); assertEquals("10.1.0.1", m["KEY-OCI"])
        assertTrue(LatencyTargets.forPeers(listOf(hub), listOf("192.168.1.1")).isEmpty())
        assertFalse(LatencyTargets.covers("10.0.0.0/24", "10.0.1.1"))
    }

    // ── peers ──
    @Test fun `peers sort by handshake and traffic`() {
        fun live(n: String, age: Long?, rx: Long) = PeerLive(PeerCfg(n, n, "", "", "", ""), rx, 0, 0.0, 0.0, age, Health.FRESH, null)
        val l = listOf(live("a", null, 5), live("b", 30, 1), live("c", 10, 9))
        assertEquals(listOf("a", "b", "c"), PeerOrder.sort(l, "config").map { it.cfg.name })
        assertEquals(listOf("c", "b", "a"), PeerOrder.sort(l, "handshake").map { it.cfg.name })
        assertEquals(listOf("c", "a", "b"), PeerOrder.sort(l, "traffic").map { it.cfg.name })
    }

    @Test fun `a peer's copy text never carries the pre-shared key`() {
        val t = MeshText.peerBlock(hub.copy(presharedKey = "SECRETPSK"))
        assertFalse(t.contains("SECRETPSK")); assertTrue(t.contains("PublicKey = KEY-HUB"))
    }

    // ── diff ──
    private val declared = """
        [Interface]
        PrivateKey = 
        Address = 10.0.0.9/24, fd0c:1d00::9/64
        MTU = 1380
        DNS = 10.0.0.1
        [Peer]
        PublicKey = KEY-HUB
        Endpoint = 35.0.0.1:443
        AllowedIPs = 10.0.0.0/24, fd0c:1d00::/64
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun `a stored copy equal to the declaration diffs clean`() {
        val stored = declared.replace("PrivateKey = ", "PrivateKey = <PROVIDED_BY_DEVICE>").replace("10.0.0.0/24, fd0c:1d00::/64", "fd0c:1d00::/64,10.0.0.0/24")
        assertTrue("allowed IPs are a set; the key line is never compared", ProfileDiff.clean(ProfileDiff.diff(declared, stored)))
    }

    @Test fun `address order matters because the first IPv4 is the source`() {
        val d = ProfileDiff.diff(declared, declared.replace("10.0.0.9/24, fd0c:1d00::9/64", "fd0c:1d00::9/64, 10.0.0.9/24"))
        assertEquals(ProfileDiff.State.DIFFERENT, d.single { it.field == "Address" }.state)
    }

    @Test fun `changed endpoint, missing peer and extra peer are named`() {
        val d = ProfileDiff.diff(declared, declared.replace("35.0.0.1:443", "1.2.3.4:443") + "\n[Peer]\nPublicKey = KEY-EXTRA\nAllowedIPs = 1.1.1.1/32")
        assertEquals(ProfileDiff.State.DIFFERENT, d.single { it.field.endsWith("Endpoint") }.state)
        assertTrue(d.any { it.state == ProfileDiff.State.EXTRA })
        val gone = ProfileDiff.diff(declared, declared.substringBefore("[Peer]"))
        assertTrue(gone.any { it.state == ProfileDiff.State.MISSING && it.field.startsWith("Peer") })
    }

    @Test fun `a phone with no copy answers one MISSING line`() {
        assertEquals(listOf(ProfileDiff.State.MISSING), ProfileDiff.diff(declared, null).map { it.state })
    }

    @Test fun `parsing wg text drops the private key whatever it says`() {
        val p = WgText.parse("[Interface]\nPrivateKey = abc\nAddress = 1.1.1.1/32\n")
        assertFalse(p.iface.containsKey("privatekey")); assertEquals("1.1.1.1/32", p.iface["address"])
    }
}
