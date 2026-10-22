package com.diegonmarcos.superapp.fleetendpoints.android

import android.content.Context
import com.diegonmarcos.superapp.fleetendpoints.EndpointPair
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.FleetRegistry
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport
import kotlin.concurrent.thread

/**
 * #1093 How an app that is not Cloud Account runs the fleet endpoints (Cloud Vault, the SuperApp): at start and on every network
 * change it pulls the endpoint pairs Cloud Account publishes (its grant `fleet.endpoints`, and `fleet.bearer` for the public
 * path), registers them on the shared transport, and puts the transport behind [com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam].
 * Nothing is typed in the app. The bearer is held in memory only, taken from the last pull; the interceptor sends it on the
 * public path of a registered host alone, never on the mesh path.
 */
object FleetBoot {
    @Volatile
    private var bearer: String? = null

    fun start(context: Context) {
        val app = context.applicationContext
        FleetHttpInstall.install()
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
    fun register(entries: List<FleetEndpointsContract.Entry>, bearerToken: String): Int {
        bearer = bearerToken.ifBlank { null }
        return FleetRegistry.register(
            pairs = entries.filter { it.public.isNotEmpty() }.map { EndpointPair(it.public, it.mesh, it.policy, it.only) },
            router = FleetTransport.router,
            bearers = FleetTransport.bearers,
            bearer = { bearer },
        )
    }
}
