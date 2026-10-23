package com.diegonmarcos.superapp.network

import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import android.content.Context
import android.content.SharedPreferences
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * #1074 Cloudflare WARP and Cloudflare One as Cloud Mesh transports, run on the EXISTING WireGuard engine.
 *
 * The official WARP client is closed source, so this reimplements its open registration logic: the device
 * generates a Curve25519 key pair, sends the PUBLIC half to the WARP API, and the answer carries the
 * WireGuard peer (key, endpoint, addresses). The wire shapes follow the MIT-licensed wgcf
 * (github.com/ViRb3/wgcf); NO wgcf source is vendored or translated, this file is written here, so there
 * is nothing to add to the licence inventory beyond the reference note in LICENSING.md.
 *
 * Everything declarative (API base, endpoint, DNS, team, whether Zero Trust is applied) lives in
 * `build.json::ui.mesh_page.cloudflare` ([Decl]); the keys live ONLY in [CfKeyStore] (encrypted).
 * Pure functions here are unit-tested with a mock [CfHttp]; the engine is never touched in this file.
 */
object CloudflareWarp {

    /** `ui.mesh_page.cloudflare`, parsed. */
    data class Decl(val warp: Warp, val one: One) {
        data class Warp(
            val api: String, val apiVersion: String, val clientVersion: String, val userAgent: String,
            val endpoint: String, val profileName: String, val dns: String, val mtu: String,
            val allowedIps: String, val keepalive: String,
        )
        data class One(
            val team: String, val enabled: Boolean, val waitingOn: String, val loginPath: String,
            val callbackScheme: String, val profileName: String, val allowedIps: String, val keepalive: String,
        ) {
            val loginHost: String get() = "$team.cloudflareaccess.com"
        }

        companion object {
            fun parse(o: JSONObject?): Decl {
                val w = o?.optJSONObject("warp") ?: JSONObject()
                val n = o?.optJSONObject("one") ?: JSONObject()
                return Decl(
                    Warp(
                        w.optString("api"), w.optString("api_version"), w.optString("client_version"), w.optString("user_agent"),
                        w.optString("endpoint"), w.optString("profile_name"), w.optString("dns"), w.optString("mtu"),
                        w.optString("allowed_ips"), w.optString("keepalive"),
                    ),
                    One(
                        n.optString("team"), n.optBoolean("enabled", false), n.optString("waiting_on"), n.optString("login_path"),
                        n.optString("callback_scheme"), n.optString("profile_name"), n.optString("allowed_ips"), n.optString("keepalive"),
                    ),
                )
            }
        }
    }

    data class HttpReq(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
    data class HttpRes(val code: Int, val body: String)

    /** One HTTP round trip; the app uses [UrlHttp], a test hands a mock. */
    fun interface CfHttp { fun call(req: HttpReq): HttpRes }

    /** What the WARP API answers a registration with: the device and the WireGuard config to run. */
    data class Registration(
        val id: String, val token: String, val license: String,
        val peerPublicKey: String, val endpoint: String, val addressV4: String, val addressV6: String,
    )

    // ── requests ──

    private fun baseHeaders(w: Decl.Warp) = linkedMapOf(
        "User-Agent" to w.userAgent, "CF-Client-Version" to w.clientVersion, "Content-Type" to "application/json",
    )

    fun tos(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))

    /** POST /reg: the device's public key and the terms timestamp. [accessJwt] (Cloudflare One) rides in the Access header. */
    fun registerRequest(d: Decl, publicKey: String, installId: String, now: Long, accessJwt: String = ""): HttpReq {
        val body = JSONObject()
            .put("key", publicKey).put("install_id", installId).put("fcm_token", "")
            .put("tos", tos(now)).put("model", "Android").put("type", "Android")
            .put("serial_number", installId).put("locale", "en_US")
        val h = baseHeaders(d.warp)
        if (accessJwt.isNotBlank()) h["CF-Access-Jwt-Assertion"] = accessJwt
        return HttpReq("POST", "${d.warp.api}/${d.warp.apiVersion}/reg", h, body.toString())
    }

    fun deleteRequest(d: Decl, id: String, token: String): HttpReq =
        HttpReq("DELETE", "${d.warp.api}/${d.warp.apiVersion}/reg/$id", baseHeaders(d.warp) + ("Authorization" to "Bearer $token"), null)

    // ── response ──

    /** Parse a registration answer; throws with the reason when a field the config needs is missing. */
    fun parseRegistration(json: String): Registration {
        val o = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("the WARP API answered something that is not JSON") }
        val cfg = o.optJSONObject("config") ?: throw IllegalArgumentException("the WARP API answer has no config")
        val peer = cfg.optJSONArray("peers")?.optJSONObject(0) ?: throw IllegalArgumentException("the WARP API answer has no WireGuard peer")
        val addr = cfg.optJSONObject("interface")?.optJSONObject("addresses") ?: throw IllegalArgumentException("the WARP API answer has no interface addresses")
        val key = peer.optString("public_key")
        val v4 = addr.optString("v4")
        require(key.isNotBlank()) { "the WARP API answer has no peer public key" }
        require(v4.isNotBlank()) { "the WARP API answer has no IPv4 address" }
        return Registration(
            id = o.optString("id"), token = o.optString("token"),
            license = o.optJSONObject("account")?.optString("license").orEmpty(),
            peerPublicKey = key, endpoint = peer.optJSONObject("endpoint")?.optString("host").orEmpty(),
            addressV4 = v4, addressV6 = addr.optString("v6"),
        )
    }

    // ── config generation ──

    /** wg-quick text for the engine's parser (`Config.parse`), with the endpoint of the answer, else the declared one. */
    fun warpConf(d: Decl, privateKey: String, r: Registration): String = buildString {
        val w = d.warp
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = " + listOf(r.addressV4, r.addressV6).filter { it.isNotBlank() }.joinToString(", ") { if (it.contains(':')) "$it/128" else "$it/32" })
        appendLine("DNS = ${w.dns}")
        appendLine("MTU = ${w.mtu}")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${r.peerPublicKey}")
        appendLine("AllowedIPs = ${w.allowedIps}")
        appendLine("Endpoint = ${r.endpoint.ifBlank { w.endpoint }}")
        if (w.keepalive.isNotBlank()) appendLine("PersistentKeepalive = ${w.keepalive}")
    }

    /** The Cloudflare One config: same device answer, the mesh's own routes instead of the whole internet. */
    fun oneConf(d: Decl, privateKey: String, r: Registration): String =
        warpConf(d.copy(warp = d.warp.copy(allowedIps = d.one.allowedIps.ifBlank { d.warp.allowedIps }, keepalive = d.one.keepalive.ifBlank { d.warp.keepalive })), privateKey, r)

    // ── Cloudflare One ──

    enum class OneState { WAITING, READY, ENROLLED }

    fun oneState(d: Decl, enrolled: Boolean): OneState = when {
        !d.one.enabled -> OneState.WAITING
        enrolled -> OneState.ENROLLED
        else -> OneState.READY
    }

    /** The team login page the Custom Tab opens. */
    fun oneLoginUrl(d: Decl): String = "https://${d.one.loginHost}${d.one.loginPath}"

    /** The Access token the login redirect hands back (`<scheme>://<team host>/auth?token=...`), or null. */
    fun parseCallback(d: Decl, uri: String): String? {
        if (!uri.startsWith(d.one.callbackScheme + "://")) return null
        return Regex("[?&]token=([^&#]+)").find(uri)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    // ── the client: key store + http ──

    class Client(private val d: Decl, private val http: CfHttp, private val keys: CfKeyStore, private val clock: () -> Long = System::currentTimeMillis) {
        /** The registered WARP device, registering (key pair on this device, public half to the API) when none exists. */
        fun warp(): Pair<String, Registration> {
            val have = keys.warpRegistration()
            if (have != null) return keys.warpPrivateKey() to have
            val pair = KeyPair()
            val pub = pair.publicKey.toBase64()
            val installId = java.util.UUID.randomUUID().toString()
            val res = http.call(registerRequest(d, pub, installId, clock()))
            check(res.code in 200..299) { "the WARP API refused the registration (HTTP ${res.code})" }
            val reg = parseRegistration(res.body)
            keys.saveWarp(pair.privateKey.toBase64(), reg)
            return pair.privateKey.toBase64() to reg
        }

        /** Drop the registration at Cloudflare (best effort) and forget the keys here. */
        fun forgetWarp(): String {
            val reg = keys.warpRegistration() ?: return "no WARP registration here"
            val res = runCatching { http.call(deleteRequest(d, reg.id, reg.token)) }.getOrNull()
            keys.clearWarp()
            return if (res != null && res.code in 200..299) "device removed at Cloudflare and keys forgotten" else "keys forgotten here (Cloudflare did not confirm the removal)"
        }

        /** Cloudflare One enrolment with the Access token the team login produced; the answer is the device's WireGuard config. */
        fun enrolOne(token: String): Pair<String, Registration> {
            check(oneState(d, keys.oneRegistration() != null) != OneState.WAITING) { "Cloudflare One is waiting on ${d.one.waitingOn} (Zero Trust apply)" }
            val pair = KeyPair()
            val res = http.call(registerRequest(d, pair.publicKey.toBase64(), java.util.UUID.randomUUID().toString(), clock(), accessJwt = token))
            check(res.code in 200..299) { "Cloudflare One refused the enrolment (HTTP ${res.code})" }
            val reg = parseRegistration(res.body)
            keys.saveOne(pair.privateKey.toBase64(), reg)
            return pair.privateKey.toBase64() to reg
        }
    }

    /** HttpURLConnection transport: no extra dependency, one blocking call, never on the main thread. */
    object UrlHttp : CfHttp {
        override fun call(req: HttpReq): HttpRes {
            val c = URL(req.url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = req.method; c.connectTimeout = 10_000; c.readTimeout = 15_000
                req.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (req.body != null) { c.doOutput = true; c.outputStream.use { it.write(req.body.toByteArray()) } }
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                return HttpRes(code, body)
            } finally { c.disconnect() }
        }
    }
}

/**
 * The Cloudflare keys, in the app's ENCRYPTED store (EncryptedSharedPreferences, AES-256-GCM, key in the
 * Android Keystore), never in the plain `wireguard_prefs`. Holds the WARP and Cloudflare One private keys
 * and device tokens, and the mesh key stashed while a Cloudflare profile borrows the engine's one
 * interface slot. [factory] lets a test hand plain prefs.
 */
class CfKeyStore(context: Context, factory: ((Context) -> SharedPreferences)? = null) {
    private val sp: SharedPreferences = factory?.invoke(context) ?: SafeEncryptedPrefs.prefs(context, "cloudflare_keys")

    private fun reg(p: String): CloudflareWarp.Registration? {
        val id = sp.getString("${p}_id", null) ?: return null
        return CloudflareWarp.Registration(id, sp.getString("${p}_token", "").orEmpty(), sp.getString("${p}_license", "").orEmpty(),
            sp.getString("${p}_peer", "").orEmpty(), sp.getString("${p}_endpoint", "").orEmpty(),
            sp.getString("${p}_v4", "").orEmpty(), sp.getString("${p}_v6", "").orEmpty())
    }

    private fun save(p: String, priv: String, r: CloudflareWarp.Registration) {
        sp.edit().putString("${p}_priv", priv).putString("${p}_id", r.id).putString("${p}_token", r.token)
            .putString("${p}_license", r.license).putString("${p}_peer", r.peerPublicKey).putString("${p}_endpoint", r.endpoint)
            .putString("${p}_v4", r.addressV4).putString("${p}_v6", r.addressV6).apply()
    }

    fun warpRegistration() = reg("warp")
    fun warpPrivateKey(): String = sp.getString("warp_priv", "").orEmpty()
    fun saveWarp(priv: String, r: CloudflareWarp.Registration) = save("warp", priv, r)
    fun clearWarp() { sp.edit().remove("warp_priv").remove("warp_id").remove("warp_token").remove("warp_license").remove("warp_peer").remove("warp_endpoint").remove("warp_v4").remove("warp_v6").apply() }
    fun oneRegistration() = reg("one")
    fun onePrivateKey(): String = sp.getString("one_priv", "").orEmpty()
    fun saveOne(priv: String, r: CloudflareWarp.Registration) = save("one", priv, r)

    /** The mesh interface key parked while a Cloudflare profile is the tunnel; "" when none is parked. */
    var meshKeyStash: String
        get() = sp.getString("mesh_key_stash", "").orEmpty()
        set(v) { sp.edit().putString("mesh_key_stash", v).apply() }
}
