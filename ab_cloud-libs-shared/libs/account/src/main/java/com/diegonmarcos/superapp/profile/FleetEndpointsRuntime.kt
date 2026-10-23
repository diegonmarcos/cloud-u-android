package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.cloudlib.auth.AuthHttp
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.android.FleetHttpInstall
import com.diegonmarcos.superapp.fleetendpoints.android.NetworkChangeWatch
import com.diegonmarcos.superapp.fleetendpoints.EndpointPair
import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouter
import com.diegonmarcos.superapp.fleetendpoints.EndpointState
import com.diegonmarcos.superapp.fleetendpoints.FleetBearers
import com.diegonmarcos.superapp.fleetendpoints.FleetHttp
import com.diegonmarcos.superapp.fleetendpoints.FleetRegistry
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport
import com.diegonmarcos.superapp.fleetendpoints.RouteStatus
import com.diegonmarcos.superapp.settings.AccountVault
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * #1093 Cloud Account's OWN use of the fleet endpoint fallback (the component Cloud Mail also links, libs:fleet-endpoints):
 * every fleet service Account talks to is declared once ([AccountHost.fleetEndpointsDecl], build.json::fleet_endpoints,
 * taken from the service's fleet declaration) with its mesh endpoint and, where it has one, its public endpoint. A
 * request to a declared public host is routed mesh first and falls back to public with the fleet bearer.
 *
 * The bearer is the vault's `fleet.bearer`, read when a request needs it and kept nowhere else; it is sent on the exact
 * public origin of a declared service and on no other host (the interceptor's rule, proven in libs:fleet-endpoints).
 */
object FleetEndpointsRuntime {
    fun services(decl: String = AccountHost.fleetEndpointsDecl()): List<FleetEndpointsContract.Service> = FleetEndpointsContract.parseDecl(decl)

    /**
     * Registers every service that has a public host with [router] (under its declared policy: a `mesh_only` service is
     * routed onto the mesh and never to the public host) and points [bearers] at [bearer]. A service with no public host
     * has nothing to key a request on, so it is not routed (the Status page still checks it). Returns the number registered.
     */
    fun register(
        services: List<FleetEndpointsContract.Service>,
        router: EndpointRouter,
        bearers: FleetBearers,
        bearer: () -> String?,
    ): Int {
        return FleetRegistry.register(
            services.filter { it.hasPublic }.map { EndpointPair(it.publicUrl(), it.meshUrl(), it.policy, it.routePaths) },
            router, bearers, bearer)
    }

    /** Process start: register the declared pairs on the shared transport, and re-probe the mesh on a network change. */
    fun start(ctx: Context): Int {
        val app = ctx.applicationContext
        val vault = AccountVault(app)
        val n = register(services(), FleetTransport.router, FleetTransport.bearers) { vault.connection(FleetEndpointsContract.GRANT_BEARER)?.toString() }
        NetworkChangeWatch.start(app) { FleetTransport.router.onNetworkChanged() }
        FleetHttpInstall.install() // libs below the transport (core's Telemetry) ride it through FleetHttpSeam
        // #1093 libs:auth's fleet calls (config artifact, vault bundle, sign-in probes) ride the same transport.
        AuthHttp.transport = AuthHttp.Transport { m, u, h, b, c, r -> FleetHttp.exchange(m, u, h, b, c, r)?.let { AuthHttp.Raw(it.code, it.body, it.location) } }
        return n
    }

    /** True when a fleet bearer is stored. The value is not returned: the UI shows [FleetEndpointsContract.mask] at most. */
    fun hasBearer(vault: AccountVault): Boolean = vault.connection(FleetEndpointsContract.GRANT_BEARER)?.toString().isNullOrBlank().not()
}

/** What one explicit check of a service found. No secret: a yes or no and a status code. */
data class EndpointCheck(val meshUp: Boolean, val publicStatus: Int?)

/** One line of Connect ▸ Status ▸ Endpoints. */
data class EndpointRow(
    val service: FleetEndpointsContract.Service,
    val state: EndpointState,
    val meshUp: Boolean?,
    val publicStatus: Int?,
    val checked: Boolean,
)

object FleetEndpointRows {
    /** The route a request to a service with [policy] would take, from what a check found. */
    fun derive(policy: EndpointPolicy, hasPublic: Boolean, meshUp: Boolean, publicStatus: Int?): EndpointState = when (policy) {
        EndpointPolicy.MESH_ONLY -> if (meshUp) EndpointState.MESH else EndpointState.OFFLINE
        EndpointPolicy.PUBLIC_ONLY -> if (hasPublic && publicStatus != null) EndpointState.PUBLIC else EndpointState.OFFLINE
        EndpointPolicy.MESH_FIRST -> when {
            meshUp -> EndpointState.MESH
            hasPublic && publicStatus != null -> EndpointState.PUBLIC
            else -> EndpointState.OFFLINE
        }
    }

    /**
     * One row per declared service. A service that was checked shows what the check found; one that was not shows the
     * route its last real request took (the router's [routes], keyed by public host), or UNKNOWN before any.
     */
    fun build(services: List<FleetEndpointsContract.Service>, routes: Map<String, RouteStatus>, checks: Map<String, EndpointCheck>): List<EndpointRow> =
        services.map { s ->
            val c = checks[s.id]
            if (c != null) EndpointRow(s, derive(EndpointPolicy.parse(s.policy), s.hasPublic, c.meshUp, c.publicStatus), c.meshUp, c.publicStatus, true)
            else EndpointRow(s, if (s.hasPublic) routes[s.publicDomain + s.publicPath]?.state() ?: EndpointState.UNKNOWN else EndpointState.UNKNOWN, null, null, false)
        }

    /** `stalwart.mesh.diegonmarcos.com:2443`: the host and the port the mesh probe connects to. Never a path, never a credential. */
    fun hostPort(url: String): String = url.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: ""

    /** Runs the two checks of every declared service; blocking, call off the main thread. */
    fun check(services: List<FleetEndpointsContract.Service>, checker: com.diegonmarcos.superapp.fleetendpoints.EndpointChecker): Map<String, EndpointCheck> =
        services.associate { s ->
            s.id to EndpointCheck(checker.meshUp(s.meshUrl()), if (s.hasPublic) checker.publicStatus(s.publicUrl(), s.health) else null)
        }
}
