package com.diegonmarcos.superapp.fleetendpoints.android

import com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam
import com.diegonmarcos.superapp.fleetendpoints.EndpointRouteInterceptor
import com.diegonmarcos.superapp.fleetendpoints.FleetBearerInterceptor
import com.diegonmarcos.superapp.fleetendpoints.FleetHttp
import com.diegonmarcos.superapp.fleetendpoints.FleetTransport

/**
 * #1093 Puts the fleet transport behind [FleetHttpSeam], so the libraries below it (core's Telemetry, mail's HTTP client, news,
 * the keyboard's grammar check) ride it: a string exchange for one-shot calls, and the interceptor pair for a library that
 * owns an OkHttp client.
 */
object FleetHttpInstall {
    fun install() {
        FleetHttpSeam.transport = FleetHttpSeam.Transport { m, u, h, b, c, r ->
            FleetHttp.exchange(m, u, h, b, c, r)?.let { FleetHttpSeam.Reply(it.code, it.body, it.location) }
        }
        FleetHttpSeam.okhttp = EndpointRouteInterceptor(FleetTransport.router) to FleetBearerInterceptor(FleetTransport.router, FleetTransport.bearers)
    }
}
