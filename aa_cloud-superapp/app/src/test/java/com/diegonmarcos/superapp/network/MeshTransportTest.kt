package com.diegonmarcos.superapp.network

import android.app.Application
import android.util.Base64
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.appstore.DnsLadder
import com.diegonmarcos.superapp.net.RelaySpec
import com.wireguard.config.Config
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RConfig
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.net.InetAddress
import java.net.URI

/**
 * The Cloud Mesh fallback ladder's decisions, without a network: which rung a name is taken from
 * (a system answer that disagrees with the pin is a hijack), which peers the relay carries, and the
 * endpoint rewrite every bring-up goes through. Addresses are TEST-NET stand-ins except where the
 * baked data/mesh.json is read on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@RConfig(sdk = [34], application = Application::class)
class MeshTransportTest {

    private val host = "vpn.example.org"
    private val boot = MeshTransport.Bootstrap(mapOf(host to listOf("192.0.2.10", "2001:db8::10")),
        MeshTransport.Relay(host, 443, listOf(MeshTransport.RelayRoute("127.0.0.1:51820", "hub", "HUBKEY"))))
    private val noDoh = MeshTransport.Decl("auto", 8000, 51830, 1000, emptyList())

    private fun ladder(system: (String) -> List<InetAddress>) = DnsLadder.walk(host, MeshTransport.rungs(host, boot, noDoh, system))

    @Test fun `a system answer that agrees with the pin is taken as is`() {
        val w = ladder { listOf(InetAddress.getByName("192.0.2.10")) }
        assertEquals(MeshTransport.RUNG_SYSTEM, w.via)
        assertEquals(MeshTransport.Path.DIRECT, MeshTransport.pathOf(w.via))
    }

    @Test fun `a hijacked answer falls to the pinned address and says why`() {
        val w = ladder { listOf(InetAddress.getByName("10.9.9.9")) }   // a captive portal's answer
        assertEquals(MeshTransport.RUNG_PINNED, w.via)
        assertEquals(listOf("192.0.2.10", "2001:db8:0:0:0:0:0:10"), w.addrs.map { it.hostAddress })
        assertTrue(w.trail, w.trail.contains("hijacked or stale"))
        assertEquals(MeshTransport.Path.PINNED, MeshTransport.pathOf(w.via))
    }

    @Test fun `blocked DNS falls to the pinned address`() {
        val w = ladder { throw IOException("port 53 blocked") }
        assertEquals(MeshTransport.RUNG_PINNED, w.via)
        assertTrue(w.trail.contains("port 53 blocked"))
    }

    @Test fun `a name the fleet does not pin trusts the system and then DoH`() {
        val rungs = MeshTransport.rungs("other.example.org", boot, noDoh.copy(doh = listOf(MeshTransport.Doh("DoH x", "https://192.0.2.53/dns-query"))),
            system = { listOf(InetAddress.getByName("198.51.100.1")) })
        assertEquals(listOf("system", "DoH x"), rungs.map { it.label })
        assertEquals("system", DnsLadder.walk("other.example.org", rungs).via)
        assertEquals(MeshTransport.Path.DOH, MeshTransport.pathOf("DoH x"))
        assertEquals(MeshTransport.Path.DOH, MeshTransport.worse(MeshTransport.Path.PINNED, MeshTransport.Path.DOH))
    }

    @Test fun `the relay carries only the hubs it names, each on its own loopback port`() {
        val legs = MeshTransport.relayLegs(listOf("OTHER", "HUBKEY"), boot.relay!!, 51830)
        assertEquals(listOf("HUBKEY" to RelaySpec.Route(51830, "127.0.0.1:51820")), legs)
        assertTrue(MeshTransport.relayLegs(listOf("OTHER"), boot.relay!!, 51830).isEmpty())
    }

    @Test fun `the rewrite moves only the named peer and the result still parses`() {
        val conf = """
            [Interface]
            PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
            Address = 10.0.0.9/24

            [Peer]
            PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
            AllowedIPs = 10.0.0.0/24
            Endpoint = 192.0.2.1:443

            [Peer]
            PublicKey = TrMvSoP4jYQlY6RIzBgbssQqY3vxI2Pi+y71lOWWXX0=
            AllowedIPs = 10.1.0.0/24
        """.trimIndent()
        val hub = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="
        val other = "TrMvSoP4jYQlY6RIzBgbssQqY3vxI2Pi+y71lOWWXX0="
        val out = MeshTransport.rewrite(conf, mapOf(hub to "127.0.0.1:51830", other to "[2001:db8::7]:51821"))
        val cfg = Config.parse(BufferedReader(StringReader(out)))
        val eps = cfg.peers.associate { it.publicKey.toBase64() to it.endpoint.get().toString() }
        assertEquals("127.0.0.1:51830", eps[hub])
        assertEquals("[2001:db8::7]:51821", eps[other])
        assertEquals(conf, MeshTransport.rewrite(conf, emptyMap()))
    }

    @Test fun `a plan keeps no key and reads back`() {
        val p = MeshTransport.Plan(MeshTransport.Path.RELAY, "via pinned", mapOf("HUBKEY" to "127.0.0.1:51830"),
            RelaySpec(host, 443, listOf("192.0.2.10"), "SECRET-PREFIX", listOf(RelaySpec.Route(51830, "127.0.0.1:51820"))))
        val json = p.toJson()
        assertTrue("the relay key must never be stored in the plan", !json.contains("SECRET-PREFIX"))
        val back = MeshTransport.Plan.parse(json)!!
        assertEquals(p.copy(relay = p.relay!!.copy(prefix = "")), back)
        assertNull(MeshTransport.Plan.parse(""))
    }

    @Test fun `endpoints split and join for both families`() {
        assertEquals("2001:db8::1" to 51821, MeshTransport.splitEndpoint("[2001:db8::1]:51821"))
        assertEquals("vpn.example.org" to 443, MeshTransport.splitEndpoint("vpn.example.org:443"))
        assertNull(MeshTransport.splitEndpoint("no-port"))
        assertEquals("[2001:db8::1]:443", MeshTransport.joinEndpoint("2001:db8::1", 443))
        assertTrue(MeshTransport.isIpLiteral("192.0.2.1") && MeshTransport.isIpLiteral("2001:db8::1") && !MeshTransport.isIpLiteral("vpn.example.org"))
    }

    // ── the baked declarations ──────────────────────────────────────────

    @Test fun `DoH is reached by IP, so it needs no DNS of its own`() {
        val d = MeshTransport.decl
        assertTrue("ui.mesh_transport.doh is empty", d.doh.isNotEmpty())
        for (x in d.doh) {
            val u = URI(x.url)
            assertEquals("https", u.scheme)
            assertTrue("${x.url} is not an IP literal", MeshTransport.isIpLiteral(u.host.removePrefix("[").removeSuffix("]")))
        }
        assertTrue(d.defaultMode in setOf(MeshTransport.MODE_AUTO, MeshTransport.MODE_DIRECT, MeshTransport.MODE_RELAY))
    }

    @Test fun `the shipped snapshot pins the relay and its route reaches a hub the default tunnel has`() {
        val b = MeshTransport.boot
        val relay = b.relay!!
        assertTrue("data/mesh.json pins no address for ${relay.host}", b.pinned[relay.host].orEmpty().isNotEmpty())
        assertTrue(b.pinned[relay.host]!!.all { MeshTransport.isIpLiteral(it) })
        val peers = JSONArray(String(Base64.decode(BuildConfig.UI_WG_PEERS_JSON_B64, Base64.DEFAULT)))
        val keys = (0 until peers.length()).map { peers.getJSONObject(it).getString("public_key") }
        assertTrue("no default peer is a hub the relay reaches", MeshTransport.relayLegs(keys, relay, 51830).isNotEmpty())
    }
}
