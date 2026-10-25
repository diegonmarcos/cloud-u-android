package com.diegonmarcos.superapp.fleetendpoints

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException

/** The one request header the fleet bearer travels in (the edge gate reads it); `Authorization` stays the caller's own login. */
object FleetHeaders {
    const val FLEET = "X-Fleet-Authorization"
}

/**
 * #1090/#1093 Endpoint routing for the fleet's services, ONE component for Cloud Mail and Cloud Account. A fleet
 * service has TWO endpoints, both derived from the service's fleet declaration: the mesh URL (`<service>.mesh.diegonmarcos.com`,
 * private, reached over the WireGuard mesh) and the public URL (reached with the fleet bearer). Everything else
 * (a plain IMAP/JMAP provider, GitHub) has one endpoint and is never touched here.
 *
 * - [EndpointPolicy.MESH_FIRST]: probe the mesh with a short TCP check, use it when it answers, and fall back to
 *   the public URL when it does not (or when a request cannot even reach it). The verdict is cached, and
 *   re-taken when the network changes ([EndpointRouter.onNetworkChanged]) or after [EndpointRouter.recheckAfterMs].
 * - [EndpointPolicy.MESH_ONLY] / [EndpointPolicy.PUBLIC_ONLY]: one route, no fallback.
 *
 * The router holds URLs and verdicts only: no secret, so it can be logged and shown. The bearer lives in
 * [FleetBearers] and is added by [FleetBearerInterceptor] on the PUBLIC host alone.
 */
enum class EndpointPolicy(val wire: String) {
    MESH_FIRST("mesh_first"), MESH_ONLY("mesh_only"), PUBLIC_ONLY("public_only");

    companion object {
        fun parse(s: String?): EndpointPolicy = entries.firstOrNull { it.wire == s } ?: MESH_FIRST
    }
}

enum class EndpointRoute { MESH, PUBLIC }

/** What the account row says about the transport. */
enum class EndpointState { MESH, PUBLIC, OFFLINE, UNKNOWN }

/** The mesh reachability check; a seam so the order of tries is testable without a network. */
fun interface MeshProbe {
    fun reachable(host: String, port: Int, timeoutMs: Int): Boolean
}

/** Production probe: can a TCP connection to the mesh endpoint be opened within the timeout. */
object TcpMeshProbe : MeshProbe {
    override fun reachable(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** What is known about one endpoint right now. No secret. */
data class RouteStatus(val route: EndpointRoute?, val meshUp: Boolean?, val failed: Boolean) {
    fun state(): EndpointState = when {
        failed -> EndpointState.OFFLINE
        route == EndpointRoute.MESH -> EndpointState.MESH
        route == EndpointRoute.PUBLIC -> EndpointState.PUBLIC
        else -> EndpointState.UNKNOWN
    }
}

class EndpointRouter(
    private val probe: MeshProbe = TcpMeshProbe,
    private val clock: () -> Long = System::currentTimeMillis,
    private val probeTimeoutMs: Int = 1_200,
    val recheckAfterMs: Long = 10 * 60_000L,
    /** The public URL carries the bearer, so production only accepts https there; a JVM test uses a plain MockWebServer. */
    private val requireHttpsPublic: Boolean = true,
) {
    /**
     * One endpoint pair. [mesh] and [public] are origins, optionally with a base path: the public one may be a PATH under
     * a shared host (`https://api.example.org/pub`) that the mesh service serves from its root (`http://svc.mesh.diegonmarcos.com:8087`).
     * [only] narrows the pair to those paths (relative to the base): the rest of the host is not this service's.
     */
    private class Entry(val mesh: HttpUrl, val public: HttpUrl, @Volatile var policy: EndpointPolicy, val only: List<String>) {
        val pubPath = prefix(public)
        val meshPath = prefix(mesh)
        val key = public.host + pubPath
        @Volatile var meshUp: Boolean? = null
        @Volatile var epoch: Int = -1
        @Volatile var checkedAt: Long = 0L
        @Volatile var last: EndpointRoute? = null
        @Volatile var failed: Boolean = false
    }

    /** Opaque handle of a registered endpoint, handed to the interceptor and back. */
    class Handle internal constructor(internal val entry: Any)

    private val entries = CopyOnWriteArrayList<Entry>()
    @Volatile private var epoch = 0
    private val _status = MutableStateFlow<Map<String, RouteStatus>>(emptyMap())

    /** Per endpoint key (the public host, plus its base path when it has one). Collect it to redraw a row when the route changes. */
    val status: StateFlow<Map<String, RouteStatus>> = _status.asStateFlow()

    /**
     * Registers (or replaces) the endpoint pair of [publicUrl]. [only] (paths relative to the base, matched on segment
     * boundaries) narrows the pair to the part of the host this service serves; empty means all of it. False, and nothing
     * registered, when a URL is unusable.
     */
    fun set(publicUrl: String, meshUrl: String, policy: EndpointPolicy, only: List<String> = emptyList()): Boolean {
        val pub = origin(publicUrl) ?: return false
        val mesh = origin(meshUrl) ?: return false
        if (requireHttpsPublic && !pub.isHttps) return false
        if (pub.host == mesh.host) return false // the same host would rewrite onto itself: not two endpoints
        val narrowed = only.map { it.trim().let { p -> if (p.startsWith("/")) p.trimEnd('/') else "/" + p.trimEnd('/') } }.filter { it.isNotEmpty() }
        val key = pub.host + prefix(pub)
        val old = entries.firstOrNull { it.key == key }
        if (old != null && old.public == pub && old.mesh == mesh && old.only == narrowed) {
            old.policy = policy
        } else {
            old?.let { entries.remove(it) }
            entries.add(Entry(mesh, pub, policy, narrowed))
        }
        publish()
        return true
    }

    fun setPolicy(key: String, policy: EndpointPolicy) {
        entries.firstOrNull { it.key == key.lowercase() }?.policy = policy
        publish()
    }

    fun remove(key: String) {
        entries.removeAll { it.key == key.lowercase() }
        publish()
    }

    fun clear() { entries.clear(); publish() }

    /** [key] is the public host, plus its base path for a pair that lives under one. */
    fun isRegistered(key: String): Boolean = entries.any { it.key == key.lowercase() }
    fun policyOf(key: String): EndpointPolicy? = entries.firstOrNull { it.key == key.lowercase() }?.policy

    /** True when a request to [url] is one of a registered pair's (its public or its mesh form): it will be routed. */
    fun routes(url: HttpUrl): Boolean = entryFor(url) != null

    /** True for the mesh host of a registered endpoint: the bearer must never be sent there. */
    fun isMeshHost(host: String): Boolean = entries.any { it.mesh.host == host.lowercase() }

    /** True for the exact public origin (https, host, port) of a registered endpoint, on a path that is the service's. */
    fun isPublicOrigin(url: HttpUrl): Boolean = entries.any {
        it.public.host == url.host && it.public.port == url.port && it.public.scheme == url.scheme && covers(it, url.encodedPath, it.pubPath)
    }

    /** The network changed (Wi-Fi, mobile, VPN up or down): the cached mesh verdicts are stale, probe again on next use. */
    fun onNetworkChanged() { epoch++ }

    /** The routes to try for a request to [url], in order, or null when [url] is not a fleet endpoint. */
    fun plan(url: HttpUrl): Pair<Handle, List<EndpointRoute>>? {
        val e = entryFor(url) ?: return null
        val routes = when (e.policy) {
            EndpointPolicy.PUBLIC_ONLY -> listOf(EndpointRoute.PUBLIC)
            EndpointPolicy.MESH_ONLY -> listOf(EndpointRoute.MESH)
            EndpointPolicy.MESH_FIRST -> if (meshAnswers(e)) listOf(EndpointRoute.MESH, EndpointRoute.PUBLIC) else listOf(EndpointRoute.PUBLIC)
        }
        return Handle(e) to routes
    }

    /** [url] moved onto [route]'s origin and base path; the rest of the path, the query and the fragment stay. */
    fun rebase(h: Handle, route: EndpointRoute, url: HttpUrl): HttpUrl {
        val e = h.entry as Entry
        val base = if (route == EndpointRoute.MESH) e.mesh else e.public
        val toPath = if (route == EndpointRoute.MESH) e.meshPath else e.pubPath
        val fromPath = if (url.host == e.public.host) e.pubPath else e.meshPath
        val path = toPath + url.encodedPath.removePrefix(fromPath)
        return url.newBuilder().scheme(base.scheme).host(base.host).port(base.port).encodedPath(path.ifEmpty { "/" }).build()
    }

    internal fun succeeded(h: Handle, route: EndpointRoute) {
        val e = h.entry as Entry
        e.last = route; e.failed = false
        if (route == EndpointRoute.MESH) { e.meshUp = true; e.epoch = epoch; e.checkedAt = clock() }
        publish()
    }

    /** A request could not use [route]. A mesh failure is cached so the next request goes straight to public. */
    internal fun failed(h: Handle, route: EndpointRoute, lastRoute: Boolean) {
        val e = h.entry as Entry
        if (route == EndpointRoute.MESH) { e.meshUp = false; e.epoch = epoch; e.checkedAt = clock() }
        if (lastRoute) e.failed = true
        publish()
    }

    private fun entryFor(url: HttpUrl): Entry? {
        val h = url.host; val p = url.encodedPath
        return entries.filter { it.public.host == h && covers(it, p, it.pubPath) }.maxByOrNull { it.pubPath.length }
            ?: entries.filter { it.mesh.host == h && covers(it, p, it.meshPath) }.maxByOrNull { it.meshPath.length }
    }

    /** [path] is under [base] (on a segment boundary) and, when the pair is narrowed, under one of its allowed paths. */
    private fun covers(e: Entry, path: String, base: String): Boolean {
        if (!under(path, base)) return false
        if (e.only.isEmpty()) return true
        val rel = path.removePrefix(base).ifEmpty { "/" }
        return e.only.any { under(rel, it) }
    }

    private fun meshAnswers(e: Entry): Boolean = synchronized(e) {
        val fresh = e.meshUp != null && e.epoch == epoch && clock() - e.checkedAt < recheckAfterMs
        if (fresh) return e.meshUp == true
        val up = probe.reachable(e.mesh.host, e.mesh.port, probeTimeoutMs)
        e.meshUp = up; e.epoch = epoch; e.checkedAt = clock()
        publish()
        up
    }

    private fun publish() {
        _status.value = entries.associate { e -> e.key to RouteStatus(e.last, e.meshUp, e.failed) }
    }

    private fun origin(url: String): HttpUrl? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        if (u.query != null || u.fragment != null || u.username.isNotEmpty()) return null
        return u
    }

    companion object {
        /** The base path of an origin: "" for the root, else `/a/b` without a trailing slash. */
        private fun prefix(u: HttpUrl): String = u.encodedPath.trimEnd('/')

        private fun under(path: String, base: String): Boolean = base.isEmpty() || path == base || path.startsWith("$base/")

        /** A failure on which the server cannot have seen the request, so sending it again on the other route is safe. */
        fun neverReached(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                when (t) {
                    is ConnectException, is UnknownHostException, is NoRouteToHostException, is SSLHandshakeException -> return true
                    // Android refuses a cleartext request its network security config does not permit BEFORE it sends anything.
                    is UnknownServiceException -> return true
                    is SocketTimeoutException -> return t.message.orEmpty().contains("connect", ignoreCase = true)
                }
                t = t.cause
            }
            return false
        }
    }
}

/**
 * The fleet bearer per PUBLIC host. Exact host, never a domain: a token synced for `jmap.example.org` is not
 * sent to `git.example.org`. [toString] lists hosts only.
 */
class FleetBearers {
    private val byHost = ConcurrentHashMap<String, String>()

    /**
     * Where a host's token comes from when none was [set] for it (Cloud Account reads the vault each time, so a
     * rotated bearer applies at once and no second copy of it is kept). Never listed by [toString].
     */
    @Volatile var source: ((String) -> String?)? = null

    fun set(host: String, token: String?) {
        if (token.isNullOrBlank()) byHost.remove(host.lowercase()) else byHost[host.lowercase()] = token.trim()
    }
    fun forHost(host: String): String? = byHost[host.lowercase()] ?: source?.invoke(host.lowercase())?.trim()?.takeIf { it.isNotEmpty() }
    fun clear() = byHost.clear()
    override fun toString() = "FleetBearers(hosts=${byHost.keys.sorted()})"
}

/** The process-wide router and bearers the default JMAP client is built with. */
object FleetTransport {
    val router = EndpointRouter()
    val bearers = FleetBearers()
}

/**
 * Application interceptor: sends a request for a fleet endpoint over the planned route(s), in order. A request
 * for any other host passes through untouched. Only a failure that proves the server never saw the request
 * (refused, unresolvable, TLS handshake, cleartext not permitted, connect timeout) moves on to the next route; anything later is thrown,
 * so a request (a mail, a commit) is never sent twice.
 */
class EndpointRouteInterceptor(private val router: EndpointRouter) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val (handle, routes) = router.plan(req.url) ?: return chain.proceed(req)
        var error: IOException? = null
        for ((i, route) in routes.withIndex()) {
            val last = i == routes.lastIndex
            try {
                val resp = chain.proceed(req.newBuilder().url(router.rebase(handle, route, req.url)).build())
                router.succeeded(handle, route)
                return resp
            } catch (e: IOException) {
                router.failed(handle, route, last)
                if (chain.call().isCanceled() || !EndpointRouter.neverReached(e)) throw e
                error = e
            }
        }
        throw error ?: IOException("no route to the endpoint")
    }
}

/**
 * Network interceptor, per hop (a redirect to another host never carries the token): the fleet bearer goes in
 * `X-Fleet-Authorization` on the EXACT public https origin of a registered endpoint and nowhere else, and the
 * header is removed from any request to the mesh host, where the private path needs none. `Authorization`
 * stays the caller's own login (a mailbox, a forge token).
 */
class FleetBearerInterceptor(private val router: EndpointRouter, private val bearers: FleetBearers) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        if (router.isMeshHost(url.host)) {
            return if (req.header(FleetHeaders.FLEET) == null) chain.proceed(req)
            else chain.proceed(req.newBuilder().removeHeader(FleetHeaders.FLEET).build())
        }
        val token = bearers.forHost(url.host)
        if (token == null || !router.isPublicOrigin(url) || req.header(FleetHeaders.FLEET) != null) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header(FleetHeaders.FLEET, "Bearer $token").build())
    }
}
