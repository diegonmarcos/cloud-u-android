package app.sterna.core.jmap

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
 * Network interceptor: on every hop (redirects included) sends the gateway cookie to hosts of the
 * domain it belongs to, and to no other. Runs per hop on purpose: an application-level header
 * would ride a redirect to a foreign host.
 */
class GatewayCookieInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val cookie = GatewayCookies.cookieFor(req.url.host)
        val signed = if (cookie != null && req.header("Cookie") == null)
            req.newBuilder().header("Cookie", "${GatewayCookies.NAME}=$cookie").build() else req
        return chain.proceed(signed)
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
            throw JmapException("Sign-in required: the server redirected to its login page", httpCode = 401)
        }
        if (resp.code == 401) GatewayCookies.markRejected(req.url.host)
        return resp
    }
}

/** Test-connection failures told apart in words: 401, 403, TLS, DNS, timeout, refused. */
object GatewayErrors {
    enum class Kind { UNAUTHORIZED, FORBIDDEN, TLS, DNS, TIMEOUT, REFUSED, OTHER }

    fun kind(t: Throwable): Kind {
        var c: Throwable? = t
        while (c != null) {
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

    fun describe(t: Throwable): String = when (kind(t)) {
        Kind.UNAUTHORIZED -> "401 Unauthorized: the server rejected the credential. Check the token, or sign in again."
        Kind.FORBIDDEN -> "403 Forbidden: signed in, but not allowed to use this server."
        Kind.TLS -> "TLS error: the certificate could not be verified for this host."
        Kind.DNS -> "DNS error: the server name could not be resolved. Check the address and the network."
        Kind.TIMEOUT -> "Timed out: the server did not answer."
        Kind.REFUSED -> "Connection refused: nothing is listening at that address."
        Kind.OTHER -> t.message ?: t.javaClass.simpleName
    }
}
