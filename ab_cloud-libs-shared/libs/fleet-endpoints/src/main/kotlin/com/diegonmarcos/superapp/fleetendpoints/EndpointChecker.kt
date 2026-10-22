package com.diegonmarcos.superapp.fleetendpoints

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * #1093 What the Status page shows per endpoint: is each of the two endpoints of a fleet service reachable, measured
 * one at a time (the router answers which one a request takes; this answers whether each would work). Holds no secret
 * and returns none: a status code, a yes or a no.
 *
 * - The mesh endpoint is "up" when a TCP connection to its host and port opens in time: the check the router itself
 *   takes, so the Status page and the traffic cannot disagree.
 * - The public endpoint is asked for [path] with a GET that does NOT go through the route interceptor (it would be
 *   moved onto the mesh) but does wear the bearer interceptor, so the edge gate sees what real traffic would send.
 *   Any HTTP answer, a 401 included, means the endpoint is reachable; it is the status that tells the rest.
 */
class EndpointChecker(
    private val router: EndpointRouter = FleetTransport.router,
    private val bearers: FleetBearers = FleetTransport.bearers,
    private val probe: MeshProbe = TcpMeshProbe,
    private val probeTimeoutMs: Int = 1_200,
    base: OkHttpClient.Builder = OkHttpClient.Builder(),
) {
    private val publicClient: OkHttpClient = base
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).followRedirects(false)
        .addNetworkInterceptor(FleetBearerInterceptor(router, bearers))
        .build()

    /** True when the mesh origin [meshUrl] accepts a connection; false for an unusable URL. */
    fun meshUp(meshUrl: String): Boolean {
        val u = meshUrl.toHttpUrlOrNull() ?: return false
        return probe.reachable(u.host, u.port, probeTimeoutMs)
    }

    /** The HTTP status the public origin [publicUrl] answers [path] with, or null when it was not reached. */
    fun publicStatus(publicUrl: String, path: String): Int? {
        val base = publicUrl.toHttpUrlOrNull() ?: return null
        val url = base.newBuilder().encodedPath("/" + path.trimStart('/')).build()
        return try {
            publicClient.newCall(Request.Builder().url(url).get().build()).execute().use { it.code }
        } catch (_: IOException) {
            null
        }
    }
}
