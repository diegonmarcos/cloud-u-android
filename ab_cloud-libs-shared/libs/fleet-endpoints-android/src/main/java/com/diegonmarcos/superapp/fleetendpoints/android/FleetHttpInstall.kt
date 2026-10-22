package com.diegonmarcos.superapp.fleetendpoints.android

import com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam
import com.diegonmarcos.superapp.fleetendpoints.FleetHttp

/** #1093 Puts the fleet transport behind [FleetHttpSeam], so libs:core's Telemetry (and any library below the transport) rides it. */
object FleetHttpInstall {
    fun install() {
        FleetHttpSeam.transport = FleetHttpSeam.Transport { m, u, h, b, c, r ->
            FleetHttp.exchange(m, u, h, b, c, r)?.let { FleetHttpSeam.Reply(it.code, it.body, it.location) }
        }
    }
}
