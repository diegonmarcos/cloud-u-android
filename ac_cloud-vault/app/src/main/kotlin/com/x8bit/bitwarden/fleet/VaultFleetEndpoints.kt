package com.x8bit.bitwarden.fleet

import android.content.Context
import com.bitwarden.annotation.OmitFromCoverage
import com.diegonmarcos.superapp.fleetconfig.FleetEndpointsClient
import com.diegonmarcos.superapp.fleetconfig.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetconfig.NetworkChangeWatch
import com.diegonmarcos.superapp.fleetendpoints.EndpointPair
import com.diegonmarcos.superapp.fleetendpoints.FleetRegistry
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport
import kotlin.concurrent.thread

/**
 * #1093 Cloud Vault reaches the fleet's self-hosted vault the way every fleet app does: Cloud Account publishes each
 * declared service's mesh endpoint (`<service>.app`) and public host, and the network module's fleet interceptors
 * (the shared libs:fleet-endpoints) send a request for that host over the mesh first and, where Account's policy
 * allows, fall back to the public host with the fleet bearer. Nothing is typed in here.
 *
 * The bearer is held in memory only, taken from the last pull, and the interceptor sends it on the public path of a
 * registered host alone: never on the mesh path, never to a Bitwarden server. The vault service is `mesh_only` until a
 * bearer-protected public host exists, so with the mesh down a request fails as unreachable instead of leaving the mesh.
 */
@OmitFromCoverage
object VaultFleetEndpoints {
    @Volatile
    private var bearer: String? = null

    /** Application start: pull once, and again whenever the network changes (the mesh verdicts are then stale). */
    fun start(context: Context) {
        val app = context.applicationContext
        refresh(app)
        NetworkChangeWatch.start(app) {
            FleetTransport.router.onNetworkChanged()
            refresh(app)
        }
    }

    private fun refresh(app: Context) {
        thread(name = "fleet-endpoints-pull", isDaemon = true) {
            val outcome = runCatching { FleetEndpointsClient.pull(app) }.getOrNull()
            (outcome as? FleetEndpointsClient.Outcome.Pulled)?.let { register(it.published.entries, it.published.bearer) }
        }
    }

    /** Puts the services that have a public host on the transport; returns how many. A mesh-only one has no host to key on. */
    internal fun register(entries: List<FleetEndpointsContract.Entry>, bearerToken: String): Int {
        bearer = bearerToken.ifBlank { null }
        return FleetRegistry.register(
            pairs = entries.filter { it.public.isNotEmpty() }.map { EndpointPair(it.public, it.mesh, it.policy, it.only) },
            router = FleetTransport.router,
            bearers = FleetTransport.bearers,
            bearer = { bearer },
        )
    }
}
