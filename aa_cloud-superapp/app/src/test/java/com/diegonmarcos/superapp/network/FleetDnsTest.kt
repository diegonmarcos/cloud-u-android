package com.diegonmarcos.superapp.network

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #740 Fleet DNS: what each declared preset puts on the VPN, on the
 * declaration THIS build baked (BuildConfig.UI_DNS_B64 ← build.json::ui.dns).
 * No resolver address of the fleet is named here: [FLEET] is a stand-in for
 * the Cloud Mesh DNS field, and every expectation is read off the declaration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetDnsTest {

    private val d = FleetDns.decl
    private val FLEET = listOf("192.0.2.53", "192.0.2.54") // TEST-NET-1, stands in for the mesh field
    private val publics = d.presets.filter { it.kind == FleetDns.KIND_PUBLIC && it.available }
    private val privateOnly = d.presets.single { it.kind == FleetDns.KIND_PRIVATE && !it.fallbackAllowed }
    private val privateFb = d.presets.single { it.kind == FleetDns.KIND_PRIVATE && it.fallbackAllowed }
    private fun servers(id: String, fb: List<String> = emptyList(), fleet: List<String> = FLEET) =
        FleetDns.vpnServers(d, id, fb, fleet)

    @Test fun defaultPresetIsDeclaredAndUsable() {
        val p = d.preset(d.defaultPreset)
        assertTrue("default ${d.defaultPreset} is not a declared preset", p != null)
        assertTrue("default ${d.defaultPreset} is unavailable", p!!.available)
    }

    @Test fun eachPublicPresetIsItsServersThenItsFallback() {
        assertEquals("expected the open and the encrypted public presets", 2, publics.size)
        for (p in publics) {
            assertTrue("${p.id} declares no primary", p.servers.isNotEmpty())
            assertTrue("${p.id} declares no fallback", p.fallback.isNotEmpty())
            assertEquals(p.id, (p.servers + p.fallback).distinct(), servers(p.id))
            assertTrue("${p.id} leaked the fleet resolver", servers(p.id).none { it in FLEET })
        }
    }

    @Test fun mirrorPutsNoServerOnTheVpn() {
        val m = d.presets.single { it.kind == FleetDns.KIND_MIRROR }
        assertEquals(emptyList<String>(), servers(m.id))
        // control: the same call on a public preset is not empty
        assertTrue(servers(publics.first().id).isNotEmpty())
    }

    @Test fun privateOnlyIsTheFleetResolverAndNothingElse() {
        val everyFallback = publics.map { it.id }
        assertEquals(FLEET, servers(privateOnly.id, everyFallback))
        // control: the fallback preset given the same choices DOES add them
        assertNotEquals(FLEET, servers(privateFb.id, everyFallback))
    }

    @Test fun privateWithFallbacksIsFleetFirstThenTheChosenFallbackInOrder() {
        val first = publics.first()
        val got = servers(privateFb.id, listOf(first.id))
        assertEquals(FLEET, got.take(FLEET.size))
        assertEquals((first.servers + first.fallback).distinct(), got.drop(FLEET.size))
        // no fallback ticked = fleet only (it never invents one)
        assertEquals(FLEET, servers(privateFb.id, emptyList()))
    }

    @Test fun anUnavailablePresetIsNeverChosenNorUsedAsAFallback() {
        val off = d.presets.filter { !it.available }
        assertTrue("WARP is declared unavailable — expected at least one", off.isNotEmpty())
        for (p in off) {
            assertTrue("${p.id} gives no reason", p.unavailableReason.isNotBlank())
            assertEquals(d.defaultPreset, FleetDns.effective(d, p.id).id)
            assertEquals(FLEET, servers(privateFb.id, listOf(p.id)))
        }
    }

    @Test fun aPrivatePresetWithNoFleetResolverFailsLoudly() {
        for (p in listOf(privateOnly, privateFb)) {
            assertThrows(IllegalStateException::class.java) { servers(p.id, publics.map { it.id }, emptyList()) }
        }
        // control: a public preset does not need the fleet resolver
        assertTrue(servers(publics.first().id, fleet = emptyList()).isNotEmpty())
    }

    @Test fun meshNamesGoToTheFleetResolverWhileTheMeshIsUp() {
        assertTrue(d.meshZones.isNotEmpty())
        val preset = servers(publics.first().id)
        for (z in d.meshZones) {
            val name = "probe.$z"
            assertEquals(name, FLEET, FleetDns.upstreamsFor(d, name, true, preset, FLEET))
            // control: mesh down → the preset answers
            assertEquals(name, preset, FleetDns.upstreamsFor(d, name, false, preset, FLEET))
            // a zone is a label boundary, not a string suffix
            assertFalse("probe$z", FleetDns.isMeshName(d, "probe$z"))
        }
        assertTrue(FleetDns.isMeshName(d, d.testMesh))
        assertFalse(FleetDns.isMeshName(d, d.testPublic))
        assertEquals(preset, FleetDns.upstreamsFor(d, d.testPublic, true, preset, FLEET))
    }

    @Test fun theWireParserReadsAnAAndAnNxdomain() {
        // header id=0x1234 QR+RD+RA, 1 question "a.", 1 answer c00c A IN ttl=60 → 1.2.3.4
        val q = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0,
            1, 'a'.code.toByte(), 0, 0, 1, 0, 1)
        val ans = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4)
        val pkt = q + ans
        assertEquals("1.2.3.4", FleetDns.parseAnswer(pkt, pkt.size, 0x1234, q.size))
        val nx = pkt.copyOf().also { it[3] = 0x83.toByte() }
        assertEquals("NXDOMAIN", FleetDns.parseAnswer(nx, nx.size, 0x1234, q.size))
        assertThrows(IllegalStateException::class.java) { FleetDns.parseAnswer(pkt, pkt.size, 0x4321, q.size) }
    }
}
