package app.sterna.core.jmap

import com.diegonmarcos.superapp.fleetendpoints.FleetHeaders
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * The session cookie of the auth gateway (Authelia) in front of a public JMAP endpoint, per domain.
 * In memory only: the app restores it from its encrypted store ([GatewayCookies.set]) at start and
 * on every sign-in / sign-out. The value is never logged or printed.
 */
object GatewayCookies {
    const val NAME = "authelia_session"
    private val byDomain = ConcurrentHashMap<String, String>()
    private val rejected = ConcurrentHashMap.newKeySet<String>()

    fun domainOf(host: String): String {
        val parts = host.lowercase().trim().split('.')
        val ip = parts.all { p -> p.isNotEmpty() && p.all(Char::isDigit) }
        return if (!ip && parts.size >= 3 && parts.all { it.isNotEmpty() }) parts.takeLast(2).joinToString(".") else host.lowercase().trim()
    }

    /** Remember (or, with a blank [value], forget) the gateway cookie for [host]'s domain. */
    fun set(host: String, value: String?) {
        val d = domainOf(host)
        if (value.isNullOrBlank()) byDomain.remove(d) else byDomain[d] = value
        rejected.remove(d)
    }

    fun cookieFor(host: String): String? = byDomain[domainOf(host)]

    /** The gateway refused (401 / redirect to its sign-in page) a request to [host] since it was last set. */
    fun markRejected(host: String) { rejected.add(domainOf(host)) }
    fun isRejected(host: String): Boolean = domainOf(host) in rejected

    /** The `authelia_session` value out of a `Cookie:` header string (a WebView's), or "". */
    fun sessionValue(cookieHeader: String): String =
        cookieHeader.split(';').map { it.trim() }.firstOrNull { it.startsWith("$NAME=") }?.substringAfter('=').orEmpty()
}

/**
 * The fleet token of a Bearer-mode account, per domain, in memory only (restored from the encrypted
 * store like [GatewayCookies]). The gate reads it from `X-Fleet-Authorization`, never from
 * `Authorization`, which stays the mailbox login (Stalwart Basic / API token) behind the gate.
 */
object GatewayFleetTokens {
    private val byDomain = ConcurrentHashMap<String, String>()

    fun set(host: String, token: String?) {
        val d = GatewayCookies.domainOf(host)
        if (token.isNullOrBlank()) byDomain.remove(d) else byDomain[d] = token.trim()
    }

    fun tokenFor(host: String): String? = byDomain[GatewayCookies.domainOf(host)]
}

/** The gate credentials as request headers. Pure, so the composition is unit-tested. */
object GatewayHeaders {
    /** The one header name lives in the shared fleet-endpoints library, where the bearer interceptor sets it. */
    const val FLEET = FleetHeaders.FLEET

    /**
     * Headers to add for a request to [host]: the Authelia cookie (cookie mode) and/or the fleet token
     * header (Bearer mode), each only when the request does not already carry that header. Never
     * touches `Authorization`.
     */
    fun compose(host: String, hasCookie: Boolean, hasFleet: Boolean): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val cookie = GatewayCookies.cookieFor(host)
        if (cookie != null && !hasCookie) out["Cookie"] = "${GatewayCookies.NAME}=$cookie"
        val fleet = GatewayFleetTokens.tokenFor(host)
        if (fleet != null && !hasFleet) out[FLEET] = "Bearer $fleet"
        return out
    }
}

/**
 * Network interceptor: on every hop (redirects included) sends the gateway credentials (the cookie, or
 * the fleet token in `X-Fleet-Authorization`) to hosts of the domain they belong to, and to no other.
 * Runs per hop on purpose: an application-level header would ride a redirect to a foreign host.
 */
class GatewayCookieInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val extra = GatewayHeaders.compose(req.url.host, req.header("Cookie") != null, req.header(GatewayHeaders.FLEET) != null)
        if (extra.isEmpty()) return chain.proceed(req)
        val b = req.newBuilder()
        extra.forEach { (k, v) -> b.header(k, v) }
        return chain.proceed(b.build())
    }
}

/** Which layer refused a request: the edge gate in front, or the mailbox (Stalwart) behind it. */
enum class RejectionLayer {
    GATE, MAILBOX;

    companion object {
        /**
         * A 401/403 carrying the mail server's own marks (a `WWW-Authenticate` challenge naming Stalwart,
         * or a JSON problem body) came from the mailbox; the gate answers with a redirect to auth.* or a
         * bare / HTML 401. Pure, so both answers are pinned by tests.
         */
        fun of(wwwAuthenticate: List<String>, contentType: String?): RejectionLayer =
            if (wwwAuthenticate.any { it.contains("stalwart", ignoreCase = true) } ||
                contentType?.contains("json", ignoreCase = true) == true) MAILBOX else GATE
    }
}

/**
 * Application interceptor: a request that ends on the gateway's sign-in portal (auth.*) means the
 * credential is missing or dead. It becomes the 401 it stands for, and the host is marked rejected
 * so the account shows "Needs sign-in". A plain 401 marks it too.
 */
class GatewayRedirectGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val resp = chain.proceed(req)
        val landed = resp.request.url.host
        if (resp.priorResponse != null && landed.startsWith("auth.") && !req.url.host.startsWith("auth.")) {
            resp.close()
            GatewayCookies.markRejected(req.url.host)
            throw JmapException("Sign-in required: the server redirected to its login page", httpCode = 401, gate = true)
        }
        // A 401/403 from the GATE (no mail-server marks) is the gate refusing the fleet token / session; one
        // from the mailbox is the account login being wrong and must not flag the gateway credential.
        if (resp.code == 401 || resp.code == 403) {
            val host = req.url.host
            val gated = GatewayCookies.cookieFor(host) != null || GatewayFleetTokens.tokenFor(host) != null
            val layer = RejectionLayer.of(resp.headers("WWW-Authenticate"), resp.header("Content-Type"))
            if (gated && layer == RejectionLayer.GATE) {
                val code = resp.code
                resp.close()
                GatewayCookies.markRejected(host)
                throw JmapException("The edge gate refused the request ($code)", httpCode = code, gate = true)
            }
            if (resp.code == 401 && !gated) GatewayCookies.markRejected(host)
        }
        return resp
    }
}

/** Test-connection failures told apart in words: 401, 403, TLS, DNS, timeout, refused. */
object GatewayErrors {
    enum class Kind { GATE, UNAUTHORIZED, FORBIDDEN, TLS, DNS, TIMEOUT, REFUSED, OTHER }

    fun kind(t: Throwable): Kind {
        var c: Throwable? = t
        while (c != null) {
            if (c is JmapException && (c.gate || c.edge)) return Kind.GATE
            if (c is JmapException) when (c.httpCode) { 401 -> return Kind.UNAUTHORIZED; 403 -> return Kind.FORBIDDEN }
            when (c) {
                is javax.net.ssl.SSLException -> return Kind.TLS
                is java.net.UnknownHostException -> return Kind.DNS
                is java.net.SocketTimeoutException -> return Kind.TIMEOUT
                is java.net.ConnectException -> return Kind.REFUSED
            }
            c = c.cause
        }
        return Kind.OTHER
    }

    /** The sync banner's text: the told-apart wording for a 401/403, else null (the caller keeps the raw message). */
    fun syncMessage(t: Throwable): String? =
        when (kind(t)) { Kind.GATE, Kind.UNAUTHORIZED, Kind.FORBIDDEN -> describe(t); else -> null }

    fun describe(t: Throwable): String = when (kind(t)) {
        Kind.GATE -> "Gate refused: the edge gate rejected the request (the mailbox was not reached). Off the VPN the edge needs a fleet token or an Authelia session: switch the VPN on so the mail host resolves to the mesh, or set Account > Authentication > Bearer token / Authelia, then sign in again."
        Kind.UNAUTHORIZED -> "401 Unauthorized: the mailbox rejected the account login. The gate let the request through; the saved app password or API token was revoked or expired. Create a new app password for this account in the mail server (Stalwart: account > app passwords) and enter it under Account > Sign in."
        Kind.FORBIDDEN -> "403 Forbidden: the account login is valid, but not allowed to use this server."
        Kind.TLS -> "TLS error: the certificate could not be verified for this host."
        Kind.DNS -> "DNS error: the server name could not be resolved. Check the address and the network."
        Kind.TIMEOUT -> "Timed out: the server did not answer."
        Kind.REFUSED -> "Connection refused: nothing is listening at that address."
        Kind.OTHER -> t.message ?: t.javaClass.simpleName
    }
}
