package com.diegonmarcos.cloudaccount

import com.diegonmarcos.cloudlib.auth.AuthHttp
import com.diegonmarcos.superapp.core.ConfigSyncClient
import com.diegonmarcos.cloudlib.auth.ProviderStatus
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouter
import com.diegonmarcos.superapp.fleetendpoints.FleetBearers
import com.diegonmarcos.superapp.profile.FleetEndpointsRuntime
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * #1093 libs:auth's fleet calls ride the app's transport through its one seam: the config artifact and the sign-in probes
 * go through [AuthHttp.transport] when Account installed one, and a URL it does not claim is the plain call. The declared
 * path-of-a-shared-host service is registered under its base path, narrowed to the paths a mesh call may reach.
 */
@RunWith(RobolectricTestRunner::class)
class AuthHttpRoutingTest {
    private val marker = "tok-SECRET-123456" // gitleaks:allow (a made-up test marker, not a credential)
    private val decl = """{"services":[{"id":"api","kind":"api","mesh_dns":"c3-public-api.mesh.diegonmarcos.com","mesh_port":8087,"mesh_scheme":"http",
        "public_domain":"api.example.org","public_path":"/pub","route_paths":["/health","/analytics"],"policy":"mesh_first","health":"/health"}]}"""

    @After fun tearDown() { AuthHttp.transport = null }

    @Test fun theConfigArtifactRequestGoesThroughTheTransportWithItsHeadersAndNoRedirectHandling() {
        val seen = mutableListOf<Triple<String, String, Map<String, String>>>()
        AuthHttp.transport = AuthHttp.Transport { m, u, h, _, c, r -> seen += Triple(m, u, h); assertEquals(8000 to 20000, c to r); AuthHttp.Raw(200, """{"a":1}""", "") }
        val o = ConfigSyncClient.request("https://api.example.org/pub/superapp/config/diego", mapOf("Authorization" to "Bearer $marker"),
            marker, "hint", 8000, 20000)
        assertTrue(o is ConfigSyncClient.Outcome.Ok)
        assertEquals("GET", seen.single().first); assertEquals("application/json", seen.single().third["Accept"])
        assertEquals("Bearer $marker", seen.single().third["Authorization"])
    }

    @Test fun aRedirectTheTransportReportsIsADiagnosisNotAFollowedPage() {
        AuthHttp.transport = AuthHttp.Transport { _, _, _, _, _, _ -> AuthHttp.Raw(302, "", "https://auth.example.org/?rd=x") }
        val o = ConfigSyncClient.request("https://api.example.org/pub/x", emptyMap(), marker, "hint", 1000, 1000)
        assertTrue(o is ConfigSyncClient.Outcome.Failed)
    }

    @Test fun aTransportFailureIsANetworkFailureAndNeverEchoesTheSecret() {
        AuthHttp.transport = AuthHttp.Transport { _, _, _, _, _, _ -> throw IOException("down $marker") }
        val o = ConfigSyncClient.request("https://api.example.org/pub/x", emptyMap(), marker, "hint", 1000, 1000)
        o as ConfigSyncClient.Outcome.Failed
        assertEquals(ConfigSyncClient.Kind.NETWORK, o.kind)
        assertFalse(o.message.contains(marker))
    }

    @Test fun theSignInProbeUsesTheTransportAndReadsItsAnswer() {
        AuthHttp.transport = AuthHttp.Transport { _, u, _, _, _, _ -> assertEquals("https://auth.example.org/api/health", u); AuthHttp.Raw(200, """{"enabled":false}""", "") }
        val p = ProviderStatus.probe("https://auth.example.org/api/health")
        assertTrue(p.reachable); assertEquals(false, p.enabled)
        AuthHttp.transport = AuthHttp.Transport { _, _, _, _, _, _ -> throw IOException("down") }
        assertFalse(ProviderStatus.probe("https://auth.example.org/api/health").reachable)
        AuthHttp.transport = AuthHttp.Transport { _, _, _, _, _, _ -> AuthHttp.Raw(503, "", "") }
        assertFalse(ProviderStatus.probe("https://auth.example.org/api/health").reachable)
    }

    @Test fun aServiceUnderAPathIsRegisteredUnderItsBasePathAndNarrowedToTheRoutesItNames() {
        val router = EndpointRouter(probe = { _, _, _ -> true }); val bearers = FleetBearers()
        assertEquals(1, FleetEndpointsRuntime.register(FleetEndpointsRuntime.services(decl), router, bearers) { marker })
        assertTrue(router.isRegistered("api.example.org/pub")); assertFalse(router.isRegistered("api.example.org"))
        assertEquals(EndpointPolicy.MESH_FIRST, router.policyOf("api.example.org/pub"))
        fun routed(u: String) = router.routes(u.toHttpUrl())
        assertTrue(routed("https://api.example.org/pub/health")); assertTrue(routed("https://api.example.org/pub/analytics/openobserve"))
        assertFalse("the config artifact is answered behind the edge gate only", routed("https://api.example.org/pub/superapp/config/diego"))
        assertFalse("another path of the host is not this service's", routed("https://api.example.org/v1/health"))
        assertFalse(router.isPublicOrigin("https://api.example.org/pub/profile/connect/start".toHttpUrl()))
    }

    @Test fun theDeclaredPublicApiRoutesOnlyItsPublicPathsAndIsStillMeshOnlyWhereDeclaredSo() {
        val services = FleetEndpointsContract.parseDecl(java.io.File("../build.json").readText().let { org.json.JSONObject(it).getJSONObject("fleet_endpoints").toString() })
        val api = services.single { it.id == "c3-public-api" }
        assertEquals("https://c3-public-api.diegonmarcos.com", api.publicUrl()); assertEquals("http://c3-public-api.mesh.diegonmarcos.com:8087", api.meshUrl())
        assertEquals(listOf("/health", "/ready", "/analytics"), api.routePaths)
        for (id in listOf("auth-broker", "vaultwarden")) assertEquals(FleetEndpointsContract.POLICY_MESH_ONLY, services.single { it.id == id }.policy)
        // #1103/#1104 cloud-me-api: mesh first, then the private tier under its path (the Autocomplete rows GET /me/autocomplete)
        val me = services.single { it.id == "cloud-me-api" }
        assertEquals(FleetEndpointsContract.POLICY_MESH_FIRST, me.policy)
        assertEquals("https://c3-pvt.diegonmarcos.com/cloud-me-api", me.publicUrl()); assertEquals("http://cloud-me-api.mesh.diegonmarcos.com:8124", me.meshUrl())
    }

    @Test fun aLibraryBelowTheTransportSendsThroughTheSeamAndAnUnclaimedUrlIsLeftToTheCaller() {
        com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam.transport = null
        assertEquals("no transport installed: null, the caller makes its plain request", null,
            com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam.exchange("POST", "https://api.example.org/x", emptyMap(), "{}", 1000, 1000))
        com.diegonmarcos.superapp.fleetendpoints.android.FleetHttpInstall.install()
        assertEquals("a host that is not a registered fleet endpoint is not claimed", null,
            com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam.exchange("POST", "https://not-a-fleet-host.example.org/x", emptyMap(), "{}", 1000, 1000))
        com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam.transport = null
    }
}
