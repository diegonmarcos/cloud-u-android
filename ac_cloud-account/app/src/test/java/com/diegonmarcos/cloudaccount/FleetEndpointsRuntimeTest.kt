package com.diegonmarcos.cloudaccount

import com.diegonmarcos.superapp.fleetconfig.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.EndpointRoute
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouter
import com.diegonmarcos.superapp.fleetendpoints.EndpointState
import com.diegonmarcos.superapp.fleetendpoints.FleetBearers
import com.diegonmarcos.superapp.fleetendpoints.RouteStatus
import com.diegonmarcos.superapp.profile.EndpointCheck
import com.diegonmarcos.superapp.profile.FleetEndpointRows
import com.diegonmarcos.superapp.profile.FleetEndpointsRuntime
import com.diegonmarcos.superapp.profile.endpointDetail
import com.diegonmarcos.superapp.profile.endpointStateWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1093 Cloud Account's own use of the fleet endpoint fallback: only a service with BOTH endpoints is routed, the policy
 * is the declared one, the bearer is read from its source when a request needs it and only for a declared public
 * host, and Connect > Status draws each service's two endpoints without ever printing the bearer.
 */
@RunWith(RobolectricTestRunner::class)
class FleetEndpointsRuntimeTest {
    private val marker = "tok-SECRET-123456" // gitleaks:allow (a made-up test marker, not a credential)
    private val decl = """{"services":[
        {"id":"gitea","kind":"git","mesh_dns":"gitea.app","mesh_port":3002,"mesh_scheme":"http","public_domain":"git.example.org","policy":"mesh_first","health":"/api/healthz"},
        {"id":"auth","kind":"auth","mesh_dns":"authelia.app","mesh_port":9091,"mesh_scheme":"http","public_domain":"auth.example.org","policy":"public_only","health":"/api/health"},
        {"id":"me-api","kind":"data","mesh_dns":"cloud-me-api.app","mesh_port":8124,"mesh_scheme":"http","policy":"mesh_only","health":"/me/health"}]}"""
    private val services = FleetEndpointsRuntime.services(decl)
    private val up = EndpointRouter(probe = { _, _, _ -> true })

    // ── registration ─────────────────────────────────────────────────────

    @Test fun onlyAServiceWithBothEndpointsIsRoutedAndItKeepsItsDeclaredPolicy() {
        val bearers = FleetBearers()
        assertEquals(2, FleetEndpointsRuntime.register(services, up, bearers) { marker })
        assertTrue(up.isRegistered("git.example.org")); assertTrue(up.isRegistered("auth.example.org"))
        assertFalse("a mesh-only service has nothing to fall back to", up.isRegistered("cloud-me-api.app"))
        assertEquals(EndpointPolicy.MESH_FIRST, up.policyOf("git.example.org"))
        assertEquals(EndpointPolicy.PUBLIC_ONLY, up.policyOf("auth.example.org"))
        assertTrue(up.isMeshHost("gitea.app")); assertTrue(up.isMeshHost("authelia.app"))
    }

    @Test fun theBearerComesFromItsSourceForADeclaredPublicHostOnly() {
        val bearers = FleetBearers(); var current: String? = marker
        FleetEndpointsRuntime.register(services, up, bearers) { current }
        assertEquals(marker, bearers.forHost("git.example.org")); assertEquals(marker, bearers.forHost("GIT.example.org"))
        assertNull("the mesh host is not a public host", bearers.forHost("gitea.app"))
        assertNull("a sibling subdomain is not the declared host", bearers.forHost("other.example.org"))
        assertNull("a mesh-only service has no public host", bearers.forHost("cloud-me-api.app"))
        current = "rotated"
        assertEquals("a rotated bearer applies at once: nothing was copied", "rotated", bearers.forHost("git.example.org"))
        current = null
        assertNull(bearers.forHost("git.example.org"))
    }

    @Test fun theBearerIsNotPrintableThroughTheTransportState() {
        val bearers = FleetBearers()
        FleetEndpointsRuntime.register(services, up, bearers) { marker }
        bearers.forHost("git.example.org")
        assertFalse(bearers.toString().contains(marker)); assertFalse(up.status.value.toString().contains(marker))
    }

    @Test fun anUnusableDeclarationRegistersNothing() {
        assertEquals(0, FleetEndpointsRuntime.register(FleetEndpointsRuntime.services("nope"), up, FleetBearers()) { marker })
        assertTrue(FleetEndpointsRuntime.services("").isEmpty())
    }

    // ── what Status shows ────────────────────────────────────────────────

    @Test fun aCheckedServiceShowsWhatTheCheckFound() {
        fun state(policy: EndpointPolicy, hasPublic: Boolean, mesh: Boolean, pub: Int?) = FleetEndpointRows.derive(policy, hasPublic, mesh, pub)
        assertEquals(EndpointState.MESH, state(EndpointPolicy.MESH_FIRST, true, true, 200))
        assertEquals(EndpointState.PUBLIC, state(EndpointPolicy.MESH_FIRST, true, false, 401))
        assertEquals("a refusal is still an endpoint that answered", EndpointState.PUBLIC, state(EndpointPolicy.MESH_FIRST, true, false, 403))
        assertEquals(EndpointState.OFFLINE, state(EndpointPolicy.MESH_FIRST, true, false, null))
        assertEquals(EndpointState.OFFLINE, state(EndpointPolicy.MESH_ONLY, true, false, 200))
        assertEquals(EndpointState.MESH, state(EndpointPolicy.MESH_ONLY, false, true, null))
        assertEquals(EndpointState.PUBLIC, state(EndpointPolicy.PUBLIC_ONLY, true, true, 200))
        assertEquals(EndpointState.OFFLINE, state(EndpointPolicy.PUBLIC_ONLY, true, true, null))
        assertEquals("no public endpoint, public only: nothing to use", EndpointState.OFFLINE, state(EndpointPolicy.PUBLIC_ONLY, false, true, null))
    }

    @Test fun anUncheckedServiceShowsTheRouteItsLastRequestTook() {
        val routes = mapOf("git.example.org" to RouteStatus(EndpointRoute.PUBLIC, meshUp = false, failed = false))
        val rows = FleetEndpointRows.build(services, routes, emptyMap()).associateBy { it.service.id }
        assertEquals(EndpointState.PUBLIC, rows.getValue("gitea").state)
        assertEquals("no request yet", EndpointState.UNKNOWN, rows.getValue("auth").state)
        assertEquals(EndpointState.UNKNOWN, rows.getValue("me-api").state)
        assertFalse(rows.getValue("gitea").checked)
    }

    @Test fun aCheckWinsOverAStaleRoute() {
        val routes = mapOf("git.example.org" to RouteStatus(EndpointRoute.PUBLIC, meshUp = false, failed = false))
        val rows = FleetEndpointRows.build(services, routes, mapOf("gitea" to EndpointCheck(meshUp = true, publicStatus = 200))).associateBy { it.service.id }
        assertEquals(EndpointState.MESH, rows.getValue("gitea").state); assertTrue(rows.getValue("gitea").checked)
    }

    @Test fun eachRowNamesBothEndpointsAndTheirStateAndNoSecret() {
        val checks = mapOf(
            "gitea" to EndpointCheck(true, 200), "auth" to EndpointCheck(false, null), "me-api" to EndpointCheck(false, null))
        val rows = FleetEndpointRows.build(services, emptyMap(), checks).associateBy { it.service.id }
        assertEquals("mesh gitea.app:3002 up · public git.example.org HTTP 200 · mesh first", endpointDetail(rows.getValue("gitea")))
        assertEquals("mesh authelia.app:9091 down · public auth.example.org unreachable · public only", endpointDetail(rows.getValue("auth")))
        assertEquals("mesh cloud-me-api.app:8124 down · public no public endpoint · mesh only", endpointDetail(rows.getValue("me-api")))
        val unchecked = FleetEndpointRows.build(services, emptyMap(), emptyMap()).associateBy { it.service.id }
        assertEquals("mesh gitea.app:3002 · public git.example.org · mesh first", endpointDetail(unchecked.getValue("gitea")))
        val all = rows.values.joinToString { endpointDetail(it) + it.service }
        assertFalse(all.contains(marker)); assertFalse(all.contains("Bearer"))
    }

    @Test fun theStateWordsAreStable() {
        assertEquals("mesh", endpointStateWord(EndpointState.MESH).first)
        assertEquals("public", endpointStateWord(EndpointState.PUBLIC).first)
        assertEquals("offline", endpointStateWord(EndpointState.OFFLINE).first)
        assertEquals("not checked", endpointStateWord(EndpointState.UNKNOWN).first)
    }

    @Test fun theHostAndPortAreWhatTheMeshProbeConnectsTo() {
        assertEquals("stalwart.app:2443", FleetEndpointRows.hostPort("https://stalwart.app:2443"))
        assertEquals("gitea.app:80", FleetEndpointRows.hostPort("http://gitea.app"))
        assertEquals("", FleetEndpointRows.hostPort("nope"))
        assertEquals(FleetEndpointsContract.MASK, FleetEndpointsContract.mask(marker))
    }
}
