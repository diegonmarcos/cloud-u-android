package com.diegonmarcos.cloudaccount

import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport
import com.diegonmarcos.superapp.fleetendpoints.android.FleetBoot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1093 What an app that runs the fleet endpoints without being Cloud Account (Cloud Vault, the SuperApp) puts on the transport. */
class FleetBootTest {
    private val marker = "tok-SECRET-123456" // gitleaks:allow (a made-up test marker, not a credential)
    private val entries = listOf(
        FleetEndpointsContract.Entry("vaultwarden", "vault", "http://vaultwarden.mesh.diegonmarcos.com:8880", "https://vault.example.org", "mesh_only", "/alive"),
        FleetEndpointsContract.Entry("me-api", "data", "http://cloud-me-api.mesh.diegonmarcos.com:8124", "", "mesh_only", "/me/health"),
    )

    @After fun tearDown() { FleetTransport.router.clear(); FleetTransport.bearers.source = null }

    @Test fun onlyAServiceWithAPublicHostIsKeyedAndItKeepsThePolicyAccountPublished() {
        assertEquals(1, FleetBoot.register(entries, marker))
        assertTrue(FleetTransport.router.isRegistered("vault.example.org"))
        assertFalse(FleetTransport.router.isRegistered("cloud-me-api.mesh.diegonmarcos.com"))
        assertEquals(EndpointPolicy.MESH_ONLY, FleetTransport.router.policyOf("vault.example.org"))
        assertTrue(FleetTransport.router.isMeshHost("vaultwarden.mesh.diegonmarcos.com"))
    }

    @Test fun theBearerIsGivenToTheVaultHostOnlyTakenFromTheLastPullAndNeverPrinted() {
        FleetBoot.register(entries, marker)
        assertEquals(marker, FleetTransport.bearers.forHost("vault.example.org"))
        assertNull(FleetTransport.bearers.forHost("vaultwarden.mesh.diegonmarcos.com"))
        assertNull(FleetTransport.bearers.forHost("api.bitwarden.com"))
        assertFalse(FleetTransport.bearers.toString().contains(marker))
        FleetBoot.register(entries, "")
        assertNull(FleetTransport.bearers.forHost("vault.example.org"))
    }
}
