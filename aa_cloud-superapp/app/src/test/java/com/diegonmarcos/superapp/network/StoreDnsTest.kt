package com.diegonmarcos.superapp.network

import android.app.Application
import com.diegonmarcos.cloudlib.sysdns.DnsWire
import com.diegonmarcos.cloudlib.sysdns.FleetDnsBridge
import com.diegonmarcos.superapp.appstore.StoreDns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #860/#874 The Store's downloader resolves through the fleet DNS bridge, whose
 * routes are the active preset. Mutation-proven: put a public server into
 * Mirror's routes (or drop Android's resolver from its tail) and the first test
 * goes red; let a public preset's routes skip its own servers and the second does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreDnsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val d = FleetDns.decl
    private val publicServers = d.presets.filter { it.kind == FleetDns.KIND_PUBLIC }.flatMap { it.servers + it.fallback }.toSet()
    private fun choose(id: String) { FleetDns.Prefs(ctx).preset = id }

    @Test fun `under Mirror the bridge ends on Android's resolver and names no server of its own`() {
        choose(d.presets.first { it.kind == FleetDns.KIND_MIRROR }.id)
        val routes = FleetDns.bridgeRoutes(ctx, "github.com")
        assertEquals("Android system resolver (Mirror Android)", routes.last().label)
        assertTrue("every Mirror route is Android's resolver", routes.all { it.servers.isEmpty() })
        assertTrue("Mirror's routes carry no network of the VPN", routes.dropLast(1).all { it.network != null })
    }

    @Test fun `a public preset is Android's resolver first, then its own servers in order`() {
        d.presets.filter { it.kind == FleetDns.KIND_PUBLIC }.forEach { p ->
            choose(p.id)
            val routes = FleetDns.bridgeRoutes(ctx, "github.com")
            assertEquals("Android system resolver (${p.label})", routes.first().label)
            assertEquals(p.servers + p.fallback, routes.drop(1).flatMap { it.servers })
            assertTrue(routes.drop(1).all { it.label.startsWith(p.label) })
        }
    }

    @Test fun `Private only with the mesh down keeps the fleet resolver and nothing public`() {
        choose("private_only")
        val routes = FleetDns.bridgeRoutes(ctx, "github.com")
        val servers = routes.flatMap { it.servers }
        assertEquals(FleetDns.fleetResolvers(ctx), servers)
        assertTrue("$servers", servers.none { it in publicServers })
    }

    @Test fun `the Store's download hosts and the mesh leg's origins are routed, nothing else`() {
        listOf("github.com", "objects.githubusercontent.com", "ghcr.io", "pkg-containers.githubusercontent.com", "git-proxy-api.app")
            .forEach { assertTrue(it, StoreDns.isStoreHost(it)) }
        listOf("example.com", "notgithub.com", "ghcr.io.evil").forEach { assertFalse(it, StoreDns.isStoreHost(it)) }
    }

    @Test fun `the bridge's wire format round-trips a question and reads A and AAAA answers`() {
        val q = DnsWire.query("github.com", DnsWire.A)
        assertEquals("github.com", DnsWire.name(q))
        assertEquals(0, DnsWire.rcode(q))
        // The question echoed with QR+RA, one A record (compressed name) and one AAAA.
        val a = q.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 2 } +
            byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 140.toByte(), 82, 121, 4) +
            byteArrayOf(0xC0.toByte(), 12, 0, 28, 0, 1, 0, 0, 0, 60, 0, 16) + ByteArray(15) + byteArrayOf(1)
        assertEquals(listOf("140.82.121.4", java.net.InetAddress.getByName("::1").hostAddress), DnsWire.addresses(a).map { it.hostAddress })
        assertTrue(DnsWire.isLiteral("10.0.0.4") && DnsWire.isLiteral("fd0c:1d00::1") && !DnsWire.isLiteral("github.com"))
        assertTrue(DnsWire.addresses(a.copyOf().apply { this[3] = 0x83.toByte() }).isEmpty())
        assertEquals("bridge 127.0.0.1:0", FleetDnsBridge.label)
    }
}
