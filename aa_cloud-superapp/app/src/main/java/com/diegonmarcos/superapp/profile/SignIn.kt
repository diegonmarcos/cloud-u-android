package com.diegonmarcos.superapp.profile

import android.util.Base64
import android.util.Log
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.ConfigSyncClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Configs ▸ Profile ▸ Connect ▸ Sign in (#573): the providers, and the ONE
 * OAuth device-grant code path that GitHub and Google share.
 *
 * THE LIST IS DATA. Every provider — its kind, endpoints, client id, scope and
 * what a login through it grants — is `build.json::ui.vault_connect.sign_in`,
 * baked to [BuildConfig.UI_VAULT_CONNECT_SIGN_IN_B64]. Nothing here names a
 * provider: the code dispatches on [Provider.kind] only, so a fourth provider
 * that speaks the device grant is a JSON entry, not a Kotlin change.
 *
 * WHAT A SIGN-IN IS. A [Session]: which provider proved which identity, with
 * the credential it handed back, in memory only. It is never written to prefs,
 * never logged, and given to [ConfigSyncClient.request] as the `secret` so it
 * is redacted out of any echoed body. The Authelia bearer keeps its own store
 * ([com.diegonmarcos.superapp.settings.ConfigsPrefs]) because the vault route
 * needs it across restarts; the device-grant tokens do not outlive the process.
 */
object SignIn {

    private const val TAG = ConfigSyncClient.TAG

    enum class Kind { AUTHELIA, DEVICE_FLOW, UNKNOWN }

    /** The grant vocabulary — what a sign-in through a provider can then fetch
     *  (build.json::ui.vault_connect.sign_in._doc_sign_in). Names the code
     *  dispatches on, like [Kind]; which provider grants what is data. */
    const val GRANT_CONFIG_ARTIFACT = "config_artifact"
    const val GRANT_VAULT_BUNDLE = "vault_bundle"
    const val GRANT_REPO_ARTIFACT = "repo_artifact"
    const val GRANT_IDENTITY = "identity"

    data class Provider(
        val id: String,
        val label: String,
        val kind: Kind,
        val primary: Boolean,
        val deviceCodeUrl: String,
        val tokenUrl: String,
        val userinfoUrl: String,
        val clientId: String,
        val clientSecret: String,
        val scope: String,
        val grants: List<String>,
    ) {
        fun grants(what: String) = what in grants

        /** Whether a flow through this provider can start at all: the fleet SSO
         *  always can (its endpoints are the config source); a device grant
         *  needs a client id, and says so instead of failing halfway. */
        val configured: Boolean get() = kind == Kind.AUTHELIA || (kind == Kind.DEVICE_FLOW && clientId.isNotBlank())
    }

    fun parseProviders(o: JSONObject): List<Provider> {
        val arr = o.optJSONArray("providers") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val g = p.optJSONArray("grants")
            Provider(
                id = p.getString("id"),
                label = p.optString("label", p.getString("id")),
                kind = when (p.optString("kind")) {
                    "authelia" -> Kind.AUTHELIA
                    "device_flow" -> Kind.DEVICE_FLOW
                    else -> Kind.UNKNOWN
                },
                primary = p.optBoolean("primary", false),
                deviceCodeUrl = p.optString("device_code_url"),
                tokenUrl = p.optString("token_url"),
                userinfoUrl = p.optString("userinfo_url"),
                clientId = p.optString("client_id"),
                clientSecret = p.optString("client_secret"),
                scope = p.optString("scope"),
                grants = if (g == null) emptyList() else (0 until g.length()).map { g.getString(it) },
            )
        }
    }

    val providers: List<Provider> by lazy {
        runCatching {
            parseProviders(JSONObject(String(Base64.decode(BuildConfig.UI_VAULT_CONNECT_SIGN_IN_B64, Base64.DEFAULT))))
        }.getOrDefault(emptyList())
    }

    fun provider(id: String): Provider? = providers.firstOrNull { it.id == id }

    /** The first provider that grants [what], in declared order. */
    fun providerGranting(what: String): Provider? = providers.firstOrNull { it.grants(what) }

    // ── session ──────────────────────────────────────────────────────────

    /** Which provider proved which address. The credential itself is NOT
     *  here: a device-grant token lives in the dialog that obtained it and
     *  dies with it; the Authelia bearer keeps its own store. */
    data class Session(val provider: String, val identity: String)

    /** The current sign-in, memory only. */
    object Current {
        @Volatile var session: Session? = null
    }

    // ── OAuth 2.0 device grant (RFC 8628), provider-agnostic ─────────────

    /** What the device-code call returns; [userCode] and [verificationUri] are
     *  what the dialog shows the user. */
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    sealed class Step {
        data class Pending(val message: String, val slowDown: Boolean = false) : Step()
        data class Token(val accessToken: String) : Step()
        data class Failed(val message: String) : Step()
    }

    /** GitHub spells it verification_uri, Google verification_url; both are read.
     *  A provider that answers with an error field answers with its own words. */
    fun parseDeviceCode(o: JSONObject): DeviceCode {
        val err = o.optString("error")
        if (err.isNotBlank()) error("$err — ${o.optString("error_description", "no detail")}")
        return DeviceCode(
            deviceCode = o.getString("device_code"),
            userCode = o.getString("user_code"),
            verificationUri = o.optString("verification_uri").ifBlank { o.optString("verification_url") },
            intervalSeconds = o.optInt("interval", 5).coerceAtLeast(1),
            expiresInSeconds = o.optInt("expires_in", 900),
        )
    }

    /**
     * `authorization_pending` and `slow_down` are NORMAL, not errors: the grant
     * is defined to answer that way until approval, and treating them as
     * failure is the classic way to make this flow look broken.
     */
    fun parseTokenStep(o: JSONObject): Step {
        val token = o.optString("access_token")
        if (token.isNotBlank()) return Step.Token(token)
        return when (val err = o.optString("error")) {
            "authorization_pending" -> Step.Pending("Waiting for you to approve the code…")
            "slow_down" -> Step.Pending("The provider asked us to slow down; still waiting…", slowDown = true)
            "expired_token" -> Step.Failed("The code expired before it was approved. Start again.")
            "access_denied" -> Step.Failed("Approval was declined in the browser.")
            "" -> Step.Failed("The provider returned neither a token nor an error.")
            else -> Step.Failed("$err — ${o.optString("error_description", "no detail")}")
        }
    }

    /** Ask the provider to mint a device + user code pair. Blocking. */
    fun requestDeviceCode(p: Provider): Result<DeviceCode> {
        if (!p.configured) {
            return Result.failure(IllegalStateException(
                "No client_id for ${p.label} in this build. Set it in build.json under " +
                    "ui.vault_connect.sign_in.providers[id=${p.id}].client_id and rebuild."))
        }
        return postForm(p.deviceCodeUrl, mapOf("client_id" to p.clientId, "scope" to p.scope))
            .mapCatching { parseDeviceCode(it) }
    }

    /** One poll of the token endpoint; the caller drives the loop so it can
     *  keep the dialog responsive and honour cancellation. */
    fun pollForToken(p: Provider, deviceCode: String): Step {
        val fields = mutableMapOf(
            "client_id" to p.clientId,
            "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )
        // Google's device grant wants the (non-secret) client_secret on the token
        // exchange; GitHub's does not have one. Sent only when declared.
        if (p.clientSecret.isNotBlank()) fields["client_secret"] = p.clientSecret
        val o = postForm(p.tokenUrl, fields).getOrElse { return Step.Failed("Token poll failed: ${it.message}") }
        return parseTokenStep(o)
    }

    /** The address a userinfo document proves: `email`, else a `login`
     *  (GitHub hides the address unless it is public), else null. */
    fun parseIdentity(o: JSONObject): String? =
        o.optString("email").takeIf { it.isNotBlank() && it != "null" }
            ?: o.optString("login").takeIf { it.isNotBlank() }

    /** GET the provider's userinfo route with the token. Null when the provider
     *  declares no such route or answers without an identity. */
    fun identity(p: Provider, token: String): String? {
        if (p.userinfoUrl.isBlank()) return null
        val o = ConfigSyncClient.request(
            url = p.userinfoUrl,
            headers = mapOf("Authorization" to "Bearer $token"),
            secret = token,
            authHint = "The ${p.label} token was not accepted by ${p.userinfoUrl}.",
            connectTimeoutMs = BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS,
            readTimeoutMs = BuildConfig.UI_CONFIG_SOURCE_READ_MS,
        )
        return (o as? ConfigSyncClient.Outcome.Ok)?.let { parseIdentity(it.body) }
    }

    /** Form POST returning a JSON object. GitHub answers these endpoints with
     *  form encoding by default, so `Accept: application/json` is required or
     *  the body comes back as `a=b&c=d` and every parse fails. */
    private fun postForm(url: String, fields: Map<String, String>): Result<JSONObject> {
        var conn: HttpURLConnection? = null
        return try {
            val payload = fields.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = BuildConfig.UI_CONFIG_SOURCE_CONNECT_MS
                readTimeout = BuildConfig.UI_CONFIG_SOURCE_READ_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "Cloud-SuperApp-ConfigSync/1")
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) error("HTTP $code with an empty body from $url")
            Result.success(JSONObject(body))
        } catch (t: Throwable) {
            Log.w(TAG, "device-grant POST $url failed: ${t.javaClass.simpleName}")
            Result.failure(t)
        } finally {
            conn?.disconnect()
        }
    }
}
