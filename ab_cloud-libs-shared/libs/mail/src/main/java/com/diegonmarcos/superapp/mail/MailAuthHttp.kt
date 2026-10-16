package com.diegonmarcos.superapp.mail

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
    const val URL = "https://auth.diegonmarcos.com"
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

    /** Same host, or same registrable domain (jmap.diegonmarcos.com ~ api.diegonmarcos.com). */
    fun allows(serverHost: String, requestHost: String): Boolean =
        serverHost.isNotEmpty() && (serverHost.equals(requestHost, true) || registrable(serverHost) == registrable(requestHost))
}

/** A gateway answered with its sign-in page instead of the API: the credential is missing or dead. */
class MailAuthRequiredException(message: String) : IOException(message)

/** Adds `Authorization: Bearer <token>` to every request to the account's endpoints (mode BEARER). */
class MailAuthInterceptor(
    private val serverHost: String,
    private val creds: () -> MailAuthCreds,
    /** The gateway refused the credential (401, or a redirect to its sign-in page); never called in mode NONE. */
    private val onRejected: () -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val c = creds()
        val signed = if (c.mode == MailAuthMode.BEARER && c.bearer.isNotBlank() && MailAuthScope.allows(serverHost, req.url.host))
            req.newBuilder().header("Authorization", "Bearer ${c.bearer.trim()}").build()
        else req
        val resp = chain.proceed(signed)
        // A redirect (followed by OkHttp) that ended on the Authelia portal means no usable credential.
        if (resp.priorResponse != null && AutheliaPortal.isPortalHost(resp.request.url.host) &&
            !AutheliaPortal.isPortalHost(req.url.host)) {
            resp.close()
            if (c.mode != MailAuthMode.NONE) onRejected()
            throw MailAuthRequiredException("redirected to the sign-in portal")
        }
        if (resp.code == 401 && c.mode != MailAuthMode.NONE) onRejected()
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
        return base.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(AutheliaCookieJar(host, creds, onRefresh))
            .addInterceptor(MailAuthInterceptor(host, creds, onRejected))
            .build()
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

/** What went wrong reaching an account, as a closed set the UI words (and tests pin). */
enum class ConnProblem { UNAUTHORIZED, FORBIDDEN, TLS, DNS, TIMEOUT, REFUSED, HTTP, OTHER }

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
                is MailAuthRequiredException -> return ConnProblem.UNAUTHORIZED
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
