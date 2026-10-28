package com.diegonmarcos.superapp.mail

import com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** The fleet's Authelia portal and the session cookie it sets for the whole domain. */
object AutheliaPortal {
    /** The portal URL, from the one fleet declaration (BuildConfig.FLEET_AUTH_URL, baked by this module's build.gradle), never typed. */
    val URL: String get() = BuildConfig.FLEET_AUTH_URL
    const val COOKIE = "authelia_session"
    const val DOMAIN = "diegonmarcos.com"
    /** Authelia's default session lifetime when the cookie carries no Expires. */
    const val NOMINAL_TTL_MS = 24L * 60 * 60 * 1000

    /** The `authelia_session` value out of a `Cookie:` string (CookieManager's shape), or "". */
    fun sessionValue(cookieHeader: String): String =
        cookieHeader.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=').orEmpty()

    /** The portal host a gateway redirect lands on (auth.*). */
    fun isPortalHost(host: String) = host.lowercase().startsWith("auth.")
}

/** Which hosts may carry an account's credential: its server's host and siblings under its domain. */
object MailAuthScope {
    fun hostOf(server: String): String =
        server.trim().toHttpUrlOrNull()?.host ?: server.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')

    private fun registrable(host: String): String {
        val parts = host.lowercase().split('.')
        return if (parts.size >= 3 && parts.all { it.isNotEmpty() } && !parts.all { it.all(Char::isDigit) }) parts.takeLast(2).joinToString(".") else host.lowercase()
    }

    /** Same host, or same registrable domain (jmap.diegonmarcos.com ~ c3-pvt.diegonmarcos.com). */
    fun allows(serverHost: String, requestHost: String): Boolean =
        serverHost.isNotEmpty() && (serverHost.equals(requestHost, true) || registrable(serverHost) == registrable(requestHost))
}

/** Which layer refused a request: the edge gate in front, or the mailbox (Stalwart) behind it. */
enum class RejectionLayer {
    GATE, MAILBOX;

    companion object {
        /**
         * A 401/403 that carries the mail server's own marks (a `WWW-Authenticate` challenge naming
         * Stalwart, or a JSON problem body) came from the mailbox; the gate answers with a redirect to
         * auth.* or a bare/HTML 401. Pure, so both answers are pinned by tests.
         */
        fun of(wwwAuthenticate: List<String>, contentType: String?): RejectionLayer =
            if (wwwAuthenticate.any { it.contains("stalwart", ignoreCase = true) } ||
                contentType?.contains("json", ignoreCase = true) == true) MAILBOX else GATE
    }
}

/** A gateway answered with its sign-in page instead of the API: the credential is missing or dead. */
class MailAuthRequiredException(message: String) : IOException(message)

/**
 * The edge gate's credential on the wire. The fleet token travels in its OWN header so the
 * request's `Authorization` stays the mailbox login (Stalwart Basic) the server behind the gate checks:
 * the gate validates `X-Fleet-Authorization` and strips it, Stalwart sees `Authorization` untouched.
 * Pure, so the composition is unit-tested.
 */
object GateHeaders {
    const val FLEET = "X-Fleet-Authorization"

    /** The gate headers for a request to [requestHost]; empty unless the account is in BEARER mode with a token. */
    fun forRequest(serverHost: String, requestHost: String, c: MailAuthCreds): Map<String, String> =
        if (c.mode == MailAuthMode.BEARER && c.bearer.isNotBlank() && MailAuthScope.allows(serverHost, requestHost))
            mapOf(FLEET to "Bearer ${c.bearer.trim()}")
        else emptyMap()
}

/**
 * Network interceptor (per hop, so a redirect to a foreign host never carries the token): adds
 * [GateHeaders] and leaves `Authorization` alone. The Authelia cookie goes through [AutheliaCookieJar].
 */
class GateHeaderInterceptor(
    private val serverHost: String,
    private val creds: () -> MailAuthCreds,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val extra = GateHeaders.forRequest(serverHost, req.url.host, creds())
        if (extra.isEmpty()) return chain.proceed(req)
        val b = req.newBuilder()
        extra.forEach { (k, v) -> b.header(k, v) }
        return chain.proceed(b.build())
    }
}

/** Detects a gateway refusal (401, or a redirect to its sign-in portal) and reports it; adds no headers. */
class MailAuthInterceptor(
    private val serverHost: String,
    private val creds: () -> MailAuthCreds,
    /** The gateway refused the credential (401, or a redirect to its sign-in page); never called in mode NONE. */
    private val onRejected: () -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val c = creds()
        val resp = chain.proceed(req)
        // The gate itself refused (401/403 from the gate, not from the mailbox behind it).
        if ((resp.code == 401 || resp.code == 403) && c.mode != MailAuthMode.NONE &&
            RejectionLayer.of(resp.headers("WWW-Authenticate"), resp.header("Content-Type")) == RejectionLayer.GATE) {
            resp.close()
            onRejected()
            throw MailAuthRequiredException("the gate refused the request (${resp.code})")
        }
        // A redirect (followed by OkHttp) that ended on the Authelia portal means no usable credential.
        if (resp.priorResponse != null && AutheliaPortal.isPortalHost(resp.request.url.host) &&
            !AutheliaPortal.isPortalHost(req.url.host)) {
            resp.close()
            if (c.mode != MailAuthMode.NONE) onRejected()
            throw MailAuthRequiredException("redirected to the sign-in portal")
        }
        return resp
    }
}

/**
 * The account's cookie jar: replays the captured `authelia_session` (mode AUTHELIA) to the account's
 * hosts only, and keeps a rotated one (Authelia re-issues it) persisted through [onRefresh].
 */
class AutheliaCookieJar(
    private val serverHost: String,
    private val creds: () -> MailAuthCreds,
    private val onRefresh: (value: String, expiresAt: Long) -> Unit = { _, _ -> },
) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val c = creds()
        if (c.mode != MailAuthMode.AUTHELIA || c.sessionCookie.isEmpty() || !MailAuthScope.allows(serverHost, url.host)) return emptyList()
        return listOf(Cookie.Builder().name(AutheliaPortal.COOKIE).value(c.sessionCookie).domain(url.host).path("/").build())
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (creds().mode != MailAuthMode.AUTHELIA || !MailAuthScope.allows(serverHost, url.host)) return
        val fresh = cookies.firstOrNull { it.name == AutheliaPortal.COOKIE && it.value.isNotEmpty() } ?: return
        onRefresh(fresh.value, if (fresh.persistent) fresh.expiresAt else 0L)
    }
}

/** Builds the OkHttp client one account's requests go through. */
object MailHttp {
    fun client(
        server: String,
        creds: () -> MailAuthCreds,
        onRefresh: (String, Long) -> Unit = { _, _ -> },
        onRejected: () -> Unit = {},
        base: OkHttpClient = OkHttpClient(),
    ): OkHttpClient {
        val host = MailAuthScope.hostOf(server)
        val b = base.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(AutheliaCookieJar(host, creds, onRefresh))
            .addInterceptor(MailAuthInterceptor(host, creds, onRejected))
        // #1093 In an app that runs the fleet endpoints, the API calls of a declared fleet host go mesh first (the route
        // interceptor is added LAST, so the sign-in interceptors above still see the public host) and the fleet bearer rides the
        // public path only. The cookie sign-in itself (AutheliaCookieJar, the portal) stays bound to the auth host.
        FleetHttpSeam.okhttp?.let { (route, bearer) -> b.addInterceptor(route as Interceptor); b.addNetworkInterceptor(bearer as Interceptor) }
        return b.addNetworkInterceptor(GateHeaderInterceptor(host, creds)).build()
    }

    /** The client for the account stored in [prefs], wired to its encrypted credentials. */
    fun forAccount(ctx: android.content.Context, server: String, email: String): OkHttpClient {
        val store = MailAuthStore.of(ctx)
        val key = MailAuthStore.accountKey(server, email)
        return client(server, { store.load(key) },
            { v, exp -> store.saveCookie(key, v, exp, System.currentTimeMillis()) },
            { store.markNeedsSignIn(key) })
    }
}

/**
 * What went wrong reaching an account, as a closed set the UI words (and tests pin). GATE_REJECTED is the
 * edge gate (fleet token / Authelia session); UNAUTHORIZED and FORBIDDEN are the mailbox login behind it.
 */
enum class ConnProblem { GATE_REJECTED, UNAUTHORIZED, FORBIDDEN, TLS, DNS, TIMEOUT, REFUSED, HTTP, OTHER }

object ConnErrors {
    fun classify(code: Int?, cause: Throwable?): ConnProblem {
        if (code != null) return when {
            code == 401 -> ConnProblem.UNAUTHORIZED
            code == 403 -> ConnProblem.FORBIDDEN
            else -> ConnProblem.HTTP
        }
        var t = cause
        while (t != null) {
            when (t) {
                is MailAuthRequiredException -> return ConnProblem.GATE_REJECTED
                is SSLException -> return ConnProblem.TLS
                is UnknownHostException -> return ConnProblem.DNS
                is SocketTimeoutException -> return ConnProblem.TIMEOUT
                is ConnectException -> return ConnProblem.REFUSED
            }
            t = t.cause
        }
        return ConnProblem.OTHER
    }
}

/** The chip an account wears. */
enum class MailAuthStatus { CONNECTED, TOKEN, SIGNED_IN, NEEDS_SIGN_IN, ERROR }

object MailAuthStatuses {
    fun resolve(c: MailAuthCreds, now: Long): MailAuthStatus = when {
        c.needsSignIn -> MailAuthStatus.NEEDS_SIGN_IN
        c.mode == MailAuthMode.AUTHELIA && (c.sessionCookie.isEmpty() || c.cookieExpired(now)) -> MailAuthStatus.NEEDS_SIGN_IN
        c.lastError.isNotEmpty() -> MailAuthStatus.ERROR
        c.mode == MailAuthMode.AUTHELIA -> MailAuthStatus.SIGNED_IN
        c.mode == MailAuthMode.BEARER && c.bearer.isNotBlank() -> MailAuthStatus.TOKEN
        c.mode == MailAuthMode.BEARER -> MailAuthStatus.NEEDS_SIGN_IN
        else -> MailAuthStatus.CONNECTED
    }
}
