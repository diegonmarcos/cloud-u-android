package app.sterna.core.jmap

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
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLHandshakeException

/**
 * #1090 Per-account endpoint routing for the fleet's mail services. A fleet account has TWO endpoints that
 * Cloud Account publishes: the mesh URL (`<service>.app`, private, reached over the WireGuard mesh) and the
 * public URL (reached with the fleet bearer). Everything else (a plain IMAP/JMAP provider) has one endpoint
 * and is never touched here.
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
    private class Entry(val mesh: HttpUrl, val public: HttpUrl, @Volatile var policy: EndpointPolicy) {
        @Volatile var meshUp: Boolean? = null
        @Volatile var epoch: Int = -1
        @Volatile var checkedAt: Long = 0L
        @Volatile var last: EndpointRoute? = null
        @Volatile var failed: Boolean = false
    }

    /** Opaque handle of a registered endpoint, handed to the interceptor and back. */
    class Handle internal constructor(internal val entry: Any)

    private val byPublicHost = ConcurrentHashMap<String, Entry>()
    private val byMeshHost = ConcurrentHashMap<String, Entry>()
    @Volatile private var epoch = 0
    private val _status = MutableStateFlow<Map<String, RouteStatus>>(emptyMap())

    /** Per public host. Collect it to redraw an account row when the route changes. */
    val status: StateFlow<Map<String, RouteStatus>> = _status.asStateFlow()

    /** Registers (or replaces) the endpoint pair of [publicUrl]'s host. False, and nothing registered, when a URL is unusable. */
    fun set(publicUrl: String, meshUrl: String, policy: EndpointPolicy): Boolean {
        val pub = origin(publicUrl) ?: return false
        val mesh = origin(meshUrl) ?: return false
        if (requireHttpsPublic && !pub.isHttps) return false
        if (pub.host == mesh.host) return false // the same host would rewrite onto itself: not two endpoints
        val old = byPublicHost[pub.host]
        if (old != null && old.public == pub && old.mesh == mesh) {
            old.policy = policy
        } else {
            old?.let { byMeshHost.remove(it.mesh.host) }
            val e = Entry(mesh, pub, policy)
            byPublicHost[pub.host] = e
            byMeshHost[mesh.host] = e
        }
        publish()
        return true
    }

    fun setPolicy(publicHost: String, policy: EndpointPolicy) {
        byPublicHost[publicHost.lowercase()]?.policy = policy
        publish()
    }

    fun remove(publicHost: String) {
        byPublicHost.remove(publicHost.lowercase())?.let { byMeshHost.remove(it.mesh.host) }
        publish()
    }

    fun clear() { byPublicHost.clear(); byMeshHost.clear(); publish() }

    fun isRegistered(publicHost: String): Boolean = byPublicHost.containsKey(publicHost.lowercase())
    fun policyOf(publicHost: String): EndpointPolicy? = byPublicHost[publicHost.lowercase()]?.policy

    /** True for the mesh host of a registered endpoint: the bearer must never be sent there. */
    fun isMeshHost(host: String): Boolean = byMeshHost.containsKey(host.lowercase())

    /** True for the exact public origin (https, host, port) of a registered endpoint. */
    fun isPublicOrigin(url: HttpUrl): Boolean = byPublicHost[url.host]?.let { it.public.host == url.host && it.public.port == url.port && it.public.scheme == url.scheme } == true

    /** The network changed (Wi-Fi, mobile, VPN up or down): the cached mesh verdicts are stale, probe again on next use. */
    fun onNetworkChanged() { epoch++ }

    /** The routes to try for a request to [host], in order, or null when [host] is not a fleet endpoint. */
    fun plan(host: String): Pair<Handle, List<EndpointRoute>>? {
        val e = entryFor(host) ?: return null
        val routes = when (e.policy) {
            EndpointPolicy.PUBLIC_ONLY -> listOf(EndpointRoute.PUBLIC)
            EndpointPolicy.MESH_ONLY -> listOf(EndpointRoute.MESH)
            EndpointPolicy.MESH_FIRST -> if (meshAnswers(e)) listOf(EndpointRoute.MESH, EndpointRoute.PUBLIC) else listOf(EndpointRoute.PUBLIC)
        }
        return Handle(e) to routes
    }

    /** [url] moved onto [route]'s origin; path, query and fragment stay. */
    fun rebase(h: Handle, route: EndpointRoute, url: HttpUrl): HttpUrl {
        val e = h.entry as Entry
        val base = if (route == EndpointRoute.MESH) e.mesh else e.public
        return url.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build()
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

    private fun entryFor(host: String): Entry? = byPublicHost[host.lowercase()] ?: byMeshHost[host.lowercase()]

    private fun meshAnswers(e: Entry): Boolean = synchronized(e) {
        val fresh = e.meshUp != null && e.epoch == epoch && clock() - e.checkedAt < recheckAfterMs
        if (fresh) return e.meshUp == true
        val up = probe.reachable(e.mesh.host, e.mesh.port, probeTimeoutMs)
        e.meshUp = up; e.epoch = epoch; e.checkedAt = clock()
        publish()
        up
    }

    private fun publish() {
        _status.value = byPublicHost.mapValues { (_, e) -> RouteStatus(e.last, e.meshUp, e.failed) }
    }

    private fun origin(url: String): HttpUrl? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        if (u.encodedPath != "/" || u.query != null || u.fragment != null || u.username.isNotEmpty()) return null
        return u
    }

    companion object {
        /** A failure on which the server cannot have seen the request, so sending it again on the other route is safe. */
        fun neverReached(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                when (t) {
                    is ConnectException, is UnknownHostException, is NoRouteToHostException, is SSLHandshakeException -> return true
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
    fun set(host: String, token: String?) {
        if (token.isNullOrBlank()) byHost.remove(host.lowercase()) else byHost[host.lowercase()] = token.trim()
    }
    fun forHost(host: String): String? = byHost[host.lowercase()]
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
 * (refused, unresolvable, TLS handshake, connect timeout) moves on to the next route; anything later is thrown,
 * so a mail is never sent twice.
 */
class EndpointRouteInterceptor(private val router: EndpointRouter) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val (handle, routes) = router.plan(req.url.host) ?: return chain.proceed(req)
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
 * stays the mailbox login.
 */
class FleetBearerInterceptor(private val router: EndpointRouter, private val bearers: FleetBearers) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        if (router.isMeshHost(url.host)) {
            return if (req.header(GatewayHeaders.FLEET) == null) chain.proceed(req)
            else chain.proceed(req.newBuilder().removeHeader(GatewayHeaders.FLEET).build())
        }
        val token = bearers.forHost(url.host)
        if (token == null || !router.isPublicOrigin(url) || req.header(GatewayHeaders.FLEET) != null) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header(GatewayHeaders.FLEET, "Bearer $token").build())
    }
}
