package com.diegonmarcos.superapp.fleetendpoints

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * #1093 The blocking HTTP exchange of a caller that has no OkHttp of its own (Cloud Account's forge client): the
 * same [EndpointRouteInterceptor] and [FleetBearerInterceptor] Cloud Mail's JMAP client wears, over the
 * process-wide [FleetTransport]. A host that is not a registered fleet endpoint passes through untouched.
 */
object FleetHttp {
    private val JSON = "application/json".toMediaType()

    /** A client wearing the fleet interceptors; [base] carries the caller's own timeouts and pins. */
    fun client(
        router: EndpointRouter = FleetTransport.router,
        bearers: FleetBearers = FleetTransport.bearers,
        base: OkHttpClient.Builder = OkHttpClient.Builder(),
    ): OkHttpClient = base
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(EndpointRouteInterceptor(router))
        .addNetworkInterceptor(FleetBearerInterceptor(router, bearers))
        .build()

    /** The client the app's calls share. */
    val shared: OkHttpClient by lazy { client() }

    /** True when a request to [url] would be routed over a fleet endpoint pair. */
    fun routes(url: String, router: EndpointRouter = FleetTransport.router): Boolean =
        url.toHttpUrlOrNull()?.let { router.isRegistered(it.host) } == true

    /**
     * One exchange: (status, body). Never follows a failure into a message: the caller sees the status and the
     * server's own body, and an [java.io.IOException] for a transport failure (its message names no header).
     */
    fun call(
        client: OkHttpClient, method: String, url: String, headers: Map<String, String>, body: String?,
    ): Pair<Int, String> {
        val rb = when {
            body != null -> body.toRequestBody(JSON)
            method == "GET" || method == "HEAD" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val req = Request.Builder().url(url).method(method, rb).also { b -> headers.forEach { (k, v) -> b.header(k, v) } }.build()
        return client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
    }
}
