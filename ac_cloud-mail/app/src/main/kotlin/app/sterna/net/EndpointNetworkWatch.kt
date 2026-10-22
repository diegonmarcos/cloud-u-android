package app.sterna.net

import android.content.Context
import com.diegonmarcos.superapp.fleetendpoints.android.NetworkChangeWatch
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport

/**
 * #1090 Tells the endpoint router the network changed, so a fleet account re-probes its mesh address instead of
 * trusting a verdict taken on another network. The signal itself is the fleet's one [NetworkChangeWatch]
 * (shared with Cloud Account); this only says what to do with it.
 */
object EndpointNetworkWatch {
    fun start(ctx: Context) { NetworkChangeWatch.start(ctx) { FleetTransport.router.onNetworkChanged() } }
}
