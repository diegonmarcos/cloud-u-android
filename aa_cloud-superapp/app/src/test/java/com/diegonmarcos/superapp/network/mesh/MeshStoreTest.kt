package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #877 The page's verbs: each control reaches the engine port it names, and nothing ends silently. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshStoreTest {
    private var now = 1_000_000L
    private fun store(port: FakePort = FakePort(), host: FakeHost = FakeHost()) =
        MeshStore(port, MeshDecl.baked, host, clock = { now.also { now += 1000 } }, exec = { it.run() })

    @Test fun `a poll builds the snapshot and a down tunnel is DOWN`() {
        val s = store(); s.poll()
        assertEquals(Light.DOWN, s.snapshot!!.light); assertFalse(s.connected())
        assertEquals(2, s.snapshot!!.cfg.peers.size)
    }

    @Test fun `no engine is CANNOT TELL, never down`() {
        val s = store(FakePort(engine = false)); s.poll()
        assertEquals(Light.UNKNOWN, s.snapshot!!.light)
        assertEquals(Block.ENGINE_MISSING, s.blockOf(s.decl.control("connect")!!))
        assertEquals(Block.ENGINE_MISSING, s.blockOf(s.decl.control("dns_preset")!!))
        assertNull("a prefs-only control stays usable without the engine", s.blockOf(s.decl.control("mtu")!!))
    }

    @Test fun `connect without consent drives the engine at once`() {
        val port = FakePort(); val s = store(port); s.poll()
        s.run("state.set", "on")
        assertTrue("connect" in port.calls); assertTrue(s.connected()); assertTrue(s.notice.startsWith("connect:"))
    }

    @Test fun `connect with consent asks the host first and runs after the answer`() {
        val port = FakePort(consent = Intent("consent")); val host = FakeHost(); val s = store(port, host); s.poll()
        s.run("state.set", "on")
        assertEquals(listOf("consent"), host.events); assertTrue(port.calls.isEmpty())
        host.consentThen!!.invoke()
        assertEquals(listOf("connect"), port.calls)
    }

    @Test fun `disconnect and reconnect go through the same port`() {
        val port = FakePort(up = true); val s = store(port); s.poll()
        s.run("state.reconnect"); s.run("state.set", "off")
        assertEquals(listOf("reconnect", "disconnect"), port.calls); assertFalse(s.connected())
    }

    @Test fun `a refusal becomes the notice and a journal error, never silence`() {
        val port = FakePort(); port.failWith = "no handshake possible"; val s = store(port); s.poll()
        s.run("prefs.mtu", "1400")
        assertEquals("MTU failed: no handshake possible", s.notice)
        assertTrue(s.journal.list().any { it.kind == "error" && it.text.contains("no handshake possible") })
    }

    @Test fun `connect is blocked without a key but a connected tunnel can still be dropped`() {
        val port = FakePort(key = false); val s = store(port); s.poll()
        assertEquals(Block.NO_KEY, s.blockOf(s.decl.control("connect")!!))
        port.up = true; s.poll()
        assertNull(s.blockOf(s.decl.control("connect")!!))
    }

    @Test fun `interface controls write through the port`() {
        val port = FakePort(); val s = store(port)
        s.run("prefs.mtu", "1400"); s.run("prefs.keepalive", "40"); s.run("prefs.tunnelName", "x"); s.run("prefs.address", "10.9.9.9/32"); s.run("prefs.listenPort", "")
        assertEquals(listOf("mtu=1400", "keepalive=40", "name=x", "address=10.9.9.9/32", "port="), port.calls)
        assertEquals("1400", s.snapshot!!.cfg.mtu); assertEquals("40", s.snapshot!!.cfg.peers.first().keepalive)
    }

    @Test fun `the fallback controls reach the port and Test fallbacks lands rung by rung`() {
        val port = FakePort(); val s = store(port); s.poll()
        s.run("transport.mode", "relay"); s.run("prefs.relayKey", "k".repeat(16)); s.run("transport.test")
        assertEquals(listOf("mode=relay", "relaykey", "test"), port.calls)
        assertEquals("relay", s.transport!!.mode); assertTrue(s.hasRelayKey)
        assertEquals(listOf("udp", "dns", "relay"), s.probes.map { it.id })
        assertTrue(s.notice, s.notice.contains("1 of 3 pass") && s.notice.contains("dns fails"))
    }

    @Test fun `excluded apps, dns preset, generate key and peers`() {
        val port = FakePort(); val s = store(port); s.poll()
        s.run("prefs.excludedApps", "com.a, org.b"); s.run("fleetdns.preset", "public_open"); s.run("prefs.generateKey")
        s.run("prefs.peer.add"); s.run("prefs.peer.remove", index = 2)
        assertEquals(listOf("excluded=com.a,org.b", "dns=public_open", "genkey", "peer.add", "peer.remove=2"), port.calls)
        assertEquals(listOf("com.a", "org.b"), s.excluded); assertEquals("public_open", s.dnsPreset)
    }

    @Test fun `a profile is activated when stored and installed from the declaration when not`() {
        val port = FakePort(); val s = store(port)
        s.run("prefs.profile.activate", "v4-split")
        port.stored["config-v4-split"] = "x"
        s.run("prefs.profile.activate", "v4-split")
        assertEquals(listOf("install=v4-split", "activate=config-v4-split"), port.calls)
    }

    @Test fun `clipboard re-import names the profile and says so when the clipboard is empty`() {
        val port = FakePort(); val host = FakeHost(); val s = store(port, host)
        s.run("mesh.applyMesh", "v4-split")
        assertTrue(port.calls.isEmpty()); assertTrue(s.notice.contains("clipboard"))
        host.clip = "[Interface]\nPrivateKey = x"
        s.run("mesh.applyMesh", "v4-split")
        assertEquals(listOf("import=config-v4-split"), port.calls)
    }

    @Test fun `copies never carry a key and say what they copied`() {
        val host = FakeHost(); val s = store(host = host); s.poll()
        s.run("clipboard.peer", index = 0)
        assertTrue(host.clip.contains("PublicKey = KEY-HUB")); assertFalse(host.clip.contains("PrivateKey"))
        s.run("clipboard.profile", "v4-split")
        assertTrue(host.events.contains("copy:v4-split")); assertTrue(host.clip.contains("PrivateKey = "))
        assertFalse("a declared template has an empty key line", Regex("(?m)^PrivateKey = \\S").containsMatchIn(host.clip))
        s.run("clipboard.log"); assertTrue(s.notice.contains("journal lines copied"))
    }

    @Test fun `cloud over a custom config asks first`() {
        val s = store(); s.run("prefs.provider", "custom"); assertFalse(s.confirmCloud)
        val port = FakePort(); val drift = object : MeshPort by port {
            override fun matchesCloudPreset() = false
            override fun applyCloudPreset() = port.applyCloudPreset()
        }
        val t = MeshStore(drift, MeshDecl.baked, FakeHost(), exec = { it.run() })
        t.run("prefs.provider", "cloud")
        assertTrue("a drifted config must be confirmed, not replaced", t.confirmCloud); assertTrue(port.calls.isEmpty())
        t.confirmCloudPreset()
        assertEquals(listOf("cloud"), port.calls); assertFalse(t.confirmCloud)
    }

    @Test fun `view controls are state, seeded from the declaration`() {
        val s = store()
        assertEquals("config", s.view["peer_sort"]); assertEquals("all", s.view["route_filter"]); assertEquals("false", s.view["topology"])
        s.run("view.sort", "traffic"); s.run("view.routeFilter", "nat64"); s.run("view.topology", "true")
        assertEquals("traffic", s.view["peer_sort"]); assertEquals("nat64", s.view["route_filter"]); assertEquals("true", s.view["topology"])
        s.run("view.diff", "v4-split"); assertEquals("v4-split", s.diffFor); s.run("view.diff", "v4-split"); assertNull(s.diffFor)
    }

    @Test fun `the journal fills from polls and actions and clears`() {
        val s = store(); s.poll(); s.run("prefs.mtu", "1400")
        assertTrue(s.journal.list().size >= 2)
        s.run("log.clear"); assertTrue(s.journal.list().isEmpty())
    }

    @Test fun `polling runs only while resumed`() {
        val s = store(); assertTrue(s.shouldPoll(true)); assertFalse(s.shouldPoll(false))
    }

    @Test fun `an unknown engine id is reported`() {
        val s = store(); s.run("no.such.call"); assertTrue(s.notice.contains("no.such.call"))
    }

    @Test fun `latency is probed on the Status tab every Nth poll and carried between`() {
        val s = store(FakePort(up = true)); s.page = "status"
        s.poll()
        assertEquals(12, s.snapshot!!.hubLatencyMs); assertEquals(20, s.snapshot!!.peers[0].latencyMs)
        s.poll()
        assertEquals("the last reading is carried on a thinned tick", 12, s.snapshot!!.hubLatencyMs)
        val off = store(FakePort(up = true)); off.page = "log"; off.poll()
        assertNull("no probing from a tab that does not show latency", off.snapshot!!.hubLatencyMs)
    }

    @Test fun `a pending apply is surfaced`() {
        val port = FakePort(up = true); port.pendingApply = true; val s = store(port); s.poll()
        assertTrue(s.pending)
    }
}
