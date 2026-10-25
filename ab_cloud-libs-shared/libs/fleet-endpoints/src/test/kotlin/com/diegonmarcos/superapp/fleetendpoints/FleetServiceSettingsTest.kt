package com.diegonmarcos.superapp.fleetendpoints

import com.diegonmarcos.superapp.fleetendpoints.FleetServiceSettings.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Setup's per-service rows are derived from the declaration: mode, resolved URL, credential path, test verdict. */
class FleetServiceSettingsTest {
    private val decl = """{"services":[
      {"id":"ai","kind":"ai","mesh_dns":"my-ai-api.mesh.diegonmarcos.com","mesh_port":3217,"mesh_scheme":"http","policy":"mesh_only"},
      {"id":"jmap","kind":"mail","mesh_dns":"stalwart.mesh.diegonmarcos.com","mesh_port":2080,"mesh_scheme":"http","public_domain":"jmap.example.org","policy":"mesh_first"},
      {"id":"vault","kind":"vault","mesh_dns":"vaultwarden.mesh.diegonmarcos.com","mesh_port":8880,"mesh_scheme":"http","public_domain":"vault.example.org","policy":"mesh_first","auth":"mtls"},
      {"id":"sso","kind":"auth","mesh_dns":"authelia.mesh.diegonmarcos.com","mesh_port":9091,"mesh_scheme":"http","public_domain":"auth.example.org","aliases":["sso.example.org","account.example.org"],"policy":"public_only","auth":"sso"}]}"""
    private val rows = FleetServiceSettings.rows(FleetEndpointsContract.parseDecl(decl)).associateBy { it.id }

    @Test fun everyDeclaredServiceGetsARowWithItsResolvedUrl() {
        assertEquals(setOf("ai", "jmap", "vault", "sso"), rows.keys)
        assertEquals(Mode.PRIVATE, rows.getValue("ai").mode); assertEquals("http://my-ai-api.mesh.diegonmarcos.com:3217", rows.getValue("ai").url)
        assertEquals("", rows.getValue("ai").fallbackUrl); assertFalse(rows.getValue("ai").needsCredential)
        assertEquals("http://stalwart.mesh.diegonmarcos.com:2080", rows.getValue("jmap").url)
        assertEquals("https://jmap.example.org", rows.getValue("jmap").fallbackUrl)
        assertEquals(Mode.PUBLIC_SSO, rows.getValue("sso").mode); assertEquals("https://auth.example.org", rows.getValue("sso").url)
        assertEquals(listOf("https://sso.example.org", "https://account.example.org"), rows.getValue("sso").aliases)
    }

    @Test fun theCredentialPathFollowsTheAuthAndNeverHoldsAValue() {
        assertEquals("fleet.bearer", rows.getValue("jmap").credentialPath)
        assertEquals("auth.authelia_token", rows.getValue("sso").credentialPath)
        assertNull("the mTLS certificate is held by Cloud Vault", rows.getValue("vault").credentialPath)
    }

    @Test fun testVerdictsPerMode() {
        val jmap = rows.getValue("jmap"); val vault = rows.getValue("vault"); val sso = rows.getValue("sso")
        assertTrue(FleetServiceSettings.testOk(jmap, true, null)); assertTrue(FleetServiceSettings.testOk(jmap, false, 200))
        assertFalse(FleetServiceSettings.testOk(jmap, false, null))
        assertFalse("public mTLS is never probed without the certificate", FleetServiceSettings.testOk(vault, false, 400))
        assertTrue(FleetServiceSettings.testOk(vault, true, null))
        assertTrue(FleetServiceSettings.testOk(sso, null, 200)); assertFalse(FleetServiceSettings.testOk(sso, true, null))
        assertEquals("mesh down · public HTTP 200", FleetServiceSettings.testLine(jmap, false, 200))
        assertEquals("mesh up", FleetServiceSettings.testLine(rows.getValue("ai"), true, null))
    }

    @Test fun anUnknownAuthOrAliasWithoutPublicIsDropped() {
        val bad = """{"services":[{"id":"a","mesh_dns":"a.mesh.diegonmarcos.com","public_domain":"a.example.org","auth":"basic"},
            {"id":"b","mesh_dns":"b.mesh.diegonmarcos.com","policy":"mesh_only","aliases":["x.example.org"]}]}"""
        assertTrue(FleetEndpointsContract.parseDecl(bad).isEmpty())
    }

    @Test fun authAndAliasesRideTheEndpointsDocument() {
        val e = FleetEndpointsContract.entries(FleetEndpointsContract.parseDecl(decl))
        val p = FleetEndpointsContract.parse(FleetEndpointsContract.toJson(e, null))!!.entries.associateBy { it.service }
        assertEquals("mtls", p.getValue("vault").auth); assertEquals(listOf("sso.example.org", "account.example.org"), p.getValue("sso").aliases)
        assertEquals("bearer", p.getValue("jmap").auth)
    }

    @Test fun theBearerIsNeverSentToAnMtlsOrSsoHost() {
        val bearers = FleetBearers()
        FleetRegistry.register(listOf(
            EndpointPair("https://jmap.example.org", "http://stalwart.mesh.diegonmarcos.com:2080", "mesh_first"),
            EndpointPair("https://vault.example.org", "http://vaultwarden.mesh.diegonmarcos.com:8880", "mesh_first", bearer = false)),
            EndpointRouter(), bearers) { "b" }
        assertEquals("b", bearers.forHost("jmap.example.org"))
        assertNull(bearers.forHost("vault.example.org"))
    }
}
