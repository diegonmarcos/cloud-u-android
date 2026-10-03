package com.diegonmarcos.superapp.network

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #794 the DNS page's Bridges and DNS servers, and /api/net/dns/overview, on
 * phones this test invents: every member's path with the addresses at its end
 * and what it gets wrong, the terminals' bridges, the #791 crash class, and
 * every known server once with all its roles. Addresses are TEST-NETs; the
 * presets are the declaration this build baked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DnsOverviewTest {

    private val d = FleetDns.decl
    private val FLEET = listOf("192.0.2.53", "192.0.2.54")  // the Cloud Mesh DNS field
    private val VPN = listOf("192.0.2.1", "192.0.2.2")      // what the VPN hands out
    private val NET = listOf("198.51.100.53")               // the Wi-Fi's own resolver

    private fun dns(network: String?, ups: List<String>) = JSONObject()
        .put("network", network ?: JSONObject.NULL).put("upstreams", JSONArray(ups))
        .put("private_dns", JSONObject().put("active", false))

    private fun bridge(listening: Boolean, errors: Int = 0, why: String = "") = JSONObject(
        if (listening) """{"listening":true,"port":2053,"queries":5,"answered":4,"servfail":1,"errors":$errors,""" +
            """"last_query_ms":1,"last_error_ms":0,"last_error":${if (errors > 0) "\"boom\"" else "null"}}"""
        else """{"listening":false,"port":2053,"why":"$why"}""")

    private fun member(pkg: String, dns: JSONObject?, bridge: JSONObject? = null, crashes: Int = 0) =
        DnsOverview.Member(pkg, pkg.uppercase(), 38090, dns, bridge, crashes)

    private fun row(a: JSONArray, pkg: String) = (0 until a.length()).map { a.getJSONObject(it) }.single { it.getString("pkg") == pkg }
    private fun flags(r: JSONObject) = r.getJSONArray("flags").let { f -> (0 until f.length()).map { f.getString(it) } }

    @Test fun everyMemberIsListedWithItsPathAndTheAddressesAtItsEnd() {
        val a = DnsOverview.paths(d, listOf(member("app.mail", dns("vpn", VPN)), member("app.term", dns("vpn", VPN), bridge(true))),
            onVpn = true, active = VPN)
        assertEquals(2, a.length())
        val mail = row(a, "app.mail")
        assertEquals("Android system resolver → VPN DNS ${VPN.joinToString(", ")}", mail.getString("path"))
        assertEquals(emptyList<String>(), flags(mail))
        val term = row(a, "app.term")
        assertEquals("127.0.0.1:2053 SystemDnsBridge → Android system resolver → VPN DNS ${VPN.joinToString(", ")}", term.getString("path"))
        assertEquals(emptyList<String>(), flags(term))
        assertEquals(5, term.getJSONObject("bridge").getInt("queries"))
        // control: with no VPN the same member reads as mirror on the network's DNS, and that is not a fault
        val m = DnsOverview.paths(d, listOf(member("app.mail", dns("direct", NET))), onVpn = false, active = NET).getJSONObject(0)
        assertEquals("Android system resolver → mirror: network DNS ${NET.single()}", m.getString("path"))
        assertEquals(emptyList<String>(), flags(m))
    }

    @Test fun whatAMemberGetsWrongIsFlagged() {
        val a = DnsOverview.paths(d, listOf(
            member("out", dns("direct", NET)),
            member("other", dns("vpn", NET)),
            member("mute", null),
            member("taken", dns("vpn", VPN), bridge(false, why = "127.0.0.1:2053 is taken")),
            member("errs", dns("vpn", VPN), bridge(true, errors = 2)),
            member("crashed", dns("vpn", VPN), bridge(true), crashes = 3),
        ), onVpn = true, active = VPN)
        assertTrue(flags(row(a, "out")).toString(), flags(row(a, "out")).single().startsWith("outside the VPN"))
        assertTrue(flags(row(a, "other")).toString(), flags(row(a, "other")).single().startsWith("resolves with ${NET.single()}, not"))
        assertTrue(flags(row(a, "mute")).toString(), flags(row(a, "mute")).single().startsWith("no /api/net/dns answer"))
        assertTrue(flags(row(a, "taken")).toString(), flags(row(a, "taken")).single() == "bridge not listening: 127.0.0.1:2053 is taken")
        assertTrue(flags(row(a, "errs")).toString(), flags(row(a, "errs")).single() == "bridge errors: 2, last: boom")
        assertTrue(flags(row(a, "crashed")).toString(), flags(row(a, "crashed")).single().startsWith("#791 crash class: 3"))
    }

    @Test fun eachDeclaredSelfResolverIsFlaggedOnItsApp() {
        assertTrue("ui.dns.self_resolvers is empty", d.selfResolvers.isNotEmpty())
        for (r in d.selfResolvers) assertTrue("${r.pkg}: what/how missing", r.what.isNotBlank() && r.how.isNotBlank())
        val pkg = d.selfResolvers.first().pkg
        val a = DnsOverview.paths(d, listOf(member(pkg, dns("vpn", VPN)), member("clean", dns("vpn", VPN))), true, VPN)
        assertEquals(d.selfResolvers.count { it.pkg == pkg }, flags(row(a, pkg)).count { it.startsWith("resolves by itself: ") })
        assertEquals(emptyList<String>(), flags(row(a, "clean")))
    }

    @Test fun theCrashCountReadsOnlyReportsThroughTheBridge() {
        val sep = "\n\n──────────────────────────\n\n" // AppDebugServer.readCrashes' separator
        val body = "[a.txt]\nandroid.os.NetworkOnMainThreadException\n\tat com.diegonmarcos.cloudlib.sysdns.SystemDnsBridge\$1.reply" +
            sep + "[b.txt]\njava.lang.NullPointerException\n\tat com.termux.app.TermuxActivity.onCreate" +
            sep + "[c.txt]\n\tat com.diegonmarcos.cloudlib.sysdns.SystemDnsBridge.serveUdp"
        assertEquals(2, DnsOverview.bridgeCrashes(body))
        assertEquals(0, DnsOverview.bridgeCrashes("no crashes directory yet\n"))
        assertEquals(0, DnsOverview.bridgeCrashes(null))
    }

    @Test fun everyKnownServerIsListedOnceWithEveryRoleItPlays() {
        val s = DnsOverview.servers(d, FLEET, NET, VPN, "dot.example")
        assertEquals("one row per address", s.map { it.address }.distinct(), s.map { it.address })
        for (p in d.presets) for (a in p.servers + p.fallback) assertTrue("$a lists ${p.id}", p.id in s.single { it.address == a }.presets)
        val pub = d.presets.first { it.kind == FleetDns.KIND_PUBLIC && it.available }
        assertTrue("primary" in s.single { it.address == pub.servers.first() }.roles)
        assertTrue("fallback" in s.single { it.address == pub.fallback.first() }.roles)
        val enc = d.presets.first { it.encryption.isNotEmpty() }
        assertTrue("a DoT preset's servers say so", "DoT" in s.single { it.address == enc.servers.first() }.protocol)
        for (f in FLEET) {
            val r = s.single { it.address == f }
            assertTrue("mesh-only" in r.roles)
            assertEquals(d.presets.filter { it.kind == FleetDns.KIND_PRIVATE }.map { it.id }, r.presets)
            assertEquals("a mesh resolver is asked a mesh name", d.testMesh, r.testName)
        }
        assertTrue("Android network DNS" in s.single { it.address == NET.single() }.roles)
        for (v in VPN) assertTrue(v, s.single { it.address == v }.roles.any { "active network" in it })
        val host = s.single { it.address == "dot.example" }
        assertTrue(host.dot)
        assertEquals("DoT", host.protocol)
        // control: no strict Private DNS host, no TLS row
        assertTrue(DnsOverview.servers(d, FLEET, NET, VPN, null).none { it.dot })
    }

    @Test fun theAnsweringServerIsTheFirstReachableOneAndroidIsHanded() {
        val s = DnsOverview.servers(d, emptyList(), emptyList(), VPN, null)
        val allUp = s.map { DnsOverview.Probe(true, 1L, "") }
        assertEquals(VPN[0], DnsOverview.answering(s, allUp, VPN, false, null))
        assertEquals(VPN[1], DnsOverview.answering(s, s.map { DnsOverview.Probe(it.address != VPN[0], 1L, "") }, VPN, false, null))
        assertEquals("strict Private DNS answers whatever the list", "dot.example", DnsOverview.answering(s, allUp, VPN, true, "dot.example"))
        assertEquals(null, DnsOverview.answering(s, s.map { DnsOverview.Probe(false, null, "") }, VPN, false, null))
    }
}
