package com.diegonmarcos.cloudlib.auth

import com.diegonmarcos.superapp.core.ConfigSyncClient
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * #684 THE OAuth 2.0 AUTHORIZATION-CODE WEB FLOW, provider-agnostic, declared whole.
 *
 * This is NOT the device grant #641 deleted. A [Client] is read off a rung's declared
 * `web_client` block (`ab_cloud-libs-shared/build.json::auth.git_chain.providers.<id>`):
 * the phone opens [Client.authorizeUrl] in a browser, the provider redirects to
 * [Client.redirectUri] with `?code=&state=`, the browser hands that landing back, and
 * [exchange] trades the code for a token at [Client.tokenUrl]. The exchange needs the
 * client secret, which is NEVER committed: libs:auth's build.gradle bakes it at build time
 * (the same seam as the Google device-flow secret, #611). [Client.configured] is therefore
 * the ONE question a surface asks before drawing a live button — an unconfigured client is
 * a declared absence and renders as one.
 *
 * `state` is a per-attempt nonce. A landing whose state does not match is refused by
 * [landing] and never exchanged; a landing that carries the provider's `error` is worded
 * with the provider's own text. Nothing here logs the code or the token.
 */
object OAuthWeb {

    data class Client(
        val authorizeUrl: String,
        val tokenUrl: String,
        val userinfoUrl: String,
        val clientId: String,
        val clientSecret: String,
        val scope: String,
        val redirectUri: String,
        val allowHosts: List<String>,
    ) {
        /** The block names its endpoints: the flow can be described. */
        val declared: Boolean get() = authorizeUrl.isNotBlank() && tokenUrl.isNotBlank() && redirectUri.isNotBlank()

        /** The block also carries the client: the flow can RUN. */
        val configured: Boolean get() = declared && clientId.isNotBlank() && clientSecret.isNotBlank()

        /** The page to open for [state]. Every parameter is encoded once. */
        fun authorizeUrl(state: String): String {
            val q = listOf(
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "scope" to scope,
                "state" to state,
            ).joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
            return authorizeUrl + (if (authorizeUrl.contains('?')) "&" else "?") + q
        }

        override fun toString(): String = "Client(clientId=$clientId, secret=<redacted>)"
    }

    fun parse(o: JSONObject?): Client? {
        o ?: return null
        val hosts = o.optJSONArray("allow_hosts")
        return Client(
            authorizeUrl = o.optString("authorize_url"),
            tokenUrl = o.optString("token_url"),
            userinfoUrl = o.optString("userinfo_url"),
            clientId = o.optString("client_id"),
            clientSecret = o.optString("client_secret"),
            scope = o.optString("scope"),
            redirectUri = o.optString("redirect_uri"),
            allowHosts = (0 until (hosts?.length() ?: 0)).map { hosts!!.optString(it) }.filter { it.isNotBlank() },
        )
    }

    /** A fresh per-attempt nonce. */
    fun newState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    sealed class Landing {
        /** The provider approved; [code] is exchanged once and dropped. */
        data class Code(val code: String) : Landing() {
            override fun toString(): String = "Code(<redacted>)"
        }
        /** The provider answered with its own error. */
        data class Denied(val why: String) : Landing()
        /** The landing carries another attempt's state — refused, never exchanged. */
        object StateMismatch : Landing()
        /** Not our redirect at all. */
        object NotALanding : Landing()
    }

    /** Whether [url] is a landing on this client's redirect, and what it says. Pure. */
    fun landing(client: Client, url: String, expectedState: String): Landing {
        if (!isLanding(client.redirectUri, url)) return Landing.NotALanding
        val q = query(url)
        val err = q["error"].orEmpty()
        if (err.isNotBlank()) return Landing.Denied(err + " — " + q["error_description"].orEmpty().ifBlank { "no detail" })
        if (q["state"].orEmpty() != expectedState || expectedState.isBlank()) return Landing.StateMismatch
        val code = q["code"].orEmpty()
        return if (code.isBlank()) Landing.Denied("the landing carried no code") else Landing.Code(code)
    }

    /** The prefix rule the browser applies BEFORE dialing: a landing is any URL under the redirect. */
    fun isLanding(redirectUri: String, url: String): Boolean =
        redirectUri.isNotBlank() && url.startsWith(redirectUri)

    /** Whether the browser may navigate to [url]: its host is one of [allowHosts], or it is the landing. */
    fun allowed(allowHosts: List<String>, redirectUri: String, url: String): Boolean {
        if (isLanding(redirectUri, url)) return true
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().lowercase()
        if (host.isBlank()) return false
        return allowHosts.any { h -> val a = h.lowercase(); host == a || host.endsWith(".$a") }
    }

    /** Trade [code] for an access token. Blocking; callers run it on IO. */
    fun exchange(client: Client, code: String): Result<String> {
        if (!client.configured) return Result.failure(IllegalStateException("the web client is not configured in this build (client id or secret missing)"))
        return SignIn.postForm(
            client.tokenUrl,
            mapOf(
                "client_id" to client.clientId,
                "client_secret" to client.clientSecret,
                "code" to code,
                "redirect_uri" to client.redirectUri,
            ),
        ).mapCatching { o ->
            val token = o.optString("access_token")
            if (token.isNotBlank()) token
            else error(o.optString("error").ifBlank { "no access_token" } + " — " + o.optString("error_description", "no detail"))
        }
    }

    /** The account the token proves (`login`, else `email`), or null. Blocking. */
    fun identity(client: Client, token: String): String? {
        if (client.userinfoUrl.isBlank()) return null
        val o = ConfigSyncClient.request(
            url = client.userinfoUrl,
            headers = mapOf("Authorization" to "Bearer $token"),
            secret = token,
            authHint = "The token was not accepted by ${client.userinfoUrl}.",
            connectTimeoutMs = AuthDeclaration.configSource.connectTimeoutMs,
            readTimeoutMs = AuthDeclaration.configSource.readTimeoutMs,
        )
        val body = (o as? ConfigSyncClient.Outcome.Ok)?.body ?: return null
        return body.optString("login").takeIf { it.isNotBlank() } ?: SignIn.parseIdentity(body)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun query(url: String): Map<String, String> {
        val raw = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else URLDecoder.decode(pair.substring(0, i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()
    }
}
