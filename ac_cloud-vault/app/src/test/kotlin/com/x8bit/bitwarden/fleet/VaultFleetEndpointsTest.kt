package com.x8bit.bitwarden.fleet

import com.diegonmarcos.superapp.fleetconfig.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #1093 What Cloud Vault puts on the fleet transport from the pairs Cloud Account published. */
class VaultFleetEndpointsTest {
    private val marker = "tok-SECRET-123456" // gitleaks:allow (a made-up test marker, not a credential)
    private val entries = listOf(
        FleetEndpointsContract.Entry("vaultwarden", "vault", "http://vaultwarden.app:8880", "https://vault.example.org", "mesh_only", "/alive"),
        FleetEndpointsContract.Entry("me-api", "data", "http://cloud-me-api.app:8124", "", "mesh_only", "/me/health"),
    )

    @AfterEach
    fun tearDown() {
        FleetTransport.router.clear()
        FleetTransport.bearers.source = null
    }

    @Test
    fun `only a service with a public host is keyed and it keeps the policy Account published`() {
        assertEquals(1, VaultFleetEndpoints.register(entries, marker))
        assertTrue(FleetTransport.router.isRegistered("vault.example.org"))
        assertFalse(FleetTransport.router.isRegistered("cloud-me-api.app"))
        assertEquals(EndpointPolicy.MESH_ONLY, FleetTransport.router.policyOf("vault.example.org"))
        assertTrue(FleetTransport.router.isMeshHost("vaultwarden.app"))
    }

    @Test
    fun `the bearer is given to the vault host only, taken from the last pull, and never printed`() {
        VaultFleetEndpoints.register(entries, marker)
        assertEquals(marker, FleetTransport.bearers.forHost("vault.example.org"))
        assertNull(FleetTransport.bearers.forHost("vaultwarden.app"))
        assertNull(FleetTransport.bearers.forHost("api.bitwarden.com"))
        assertFalse(FleetTransport.bearers.toString().contains(marker))
        VaultFleetEndpoints.register(entries, "")
        assertNull(FleetTransport.bearers.forHost("vault.example.org"))
    }
}
