package com.x8bit.bitwarden.fleet

import android.content.Context
import com.bitwarden.annotation.OmitFromCoverage
import com.diegonmarcos.superapp.fleetendpoints.android.FleetBoot

/**
 * #1093 Cloud Vault reaches the fleet's self-hosted vault the way every fleet app does: [FleetBoot] pulls the endpoint pairs
 * Cloud Account publishes and registers them on the shared transport, and the network module's fleet interceptors (the shared
 * libs:fleet-endpoints) send a request for that host over the mesh first and, where Account's policy allows, fall back to the
 * public host with the fleet bearer. The vault service itself is declared `auth: mtls`: [FleetBoot] does not route it onto the
 * mesh and never sends it the bearer; the app reaches it on its one URL (the mesh from a mesh source, else the public host with the
 * per-device client certificate). Nothing is typed in here.
 */
@OmitFromCoverage
object VaultFleetEndpoints {
    fun start(context: Context) = FleetBoot.start(context)
}
