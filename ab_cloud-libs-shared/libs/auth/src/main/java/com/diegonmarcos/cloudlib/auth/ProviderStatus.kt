package com.diegonmarcos.cloudlib.auth

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1083 The live status of the four sign-in providers (Authelia, Google, GitHub, Cloudflare), as one row each.
 *
 * Pure of UI and of Android: [rows] takes the declared providers and the [Signals] a host measured, so the JVM
 * suite exercises exactly what the phone shows. The providers come from `auth.sign_in.providers` (the one
 * declaration); ways that share a `family` (the two Authelia ways) are ONE row. No credential is read here:
 * the signals are booleans and an identity string.
 */
object ProviderStatus {

    enum class State { SIGNED_IN, SIGNED_OUT, UNREACHABLE, DISABLED, NOT_CONFIGURED }

    /** What a probe of a provider's public `probe_url` found. [enabled] is null when the document carries no `enabled` flag. */
    data class Probe(val reachable: Boolean, val enabled: Boolean? = null)

    /** What the host measured. Keys are the provider FAMILY. */
    data class Signals(
        /** An Authelia bearer is stored on this phone. */
        val autheliaBearer: Boolean = false,
        /** family -> the address/login a sign-in on this phone proved (identity only, never a credential). */
        val signedIn: Map<String, String> = emptyMap(),
        /** family -> the probe result; a missing key means "not probed yet". */
        val probes: Map<String, Probe> = emptyMap(),
        /** A VPN transport (WARP or the mesh tunnel) is active on this phone. */
        val vpnActive: Boolean = false,
    )

    data class Row(
        val family: String,
        val label: String,
        val state: State,
        val detail: String,
        /** The declared ways of this family, in declared order. */
        val ways: List<SignIn.Provider>,
    )

    /** The providers grouped by family, in the order the declaration first names each family. */
    fun families(providers: List<SignIn.Provider>): LinkedHashMap<String, List<SignIn.Provider>> {
        val out = LinkedHashMap<String, MutableList<SignIn.Provider>>()
        for (p in providers) out.getOrPut(p.familyId) { mutableListOf() }.add(p)
        return LinkedHashMap(out)
    }

    /** One word for a family: its only way's label, or the first word shared by several ("Authelia bearer" + "Authelia web-auth"). */
    fun label(ways: List<SignIn.Provider>): String =
        if (ways.size == 1) ways.single().label else ways.first().label.substringBefore(' ')

    fun rows(providers: List<SignIn.Provider>, s: Signals): List<Row> =
        families(providers).map { (family, ways) ->
            val probe = s.probes[family]
            val identity = s.signedIn[family].orEmpty()
            val (state, detail) = when {
                ways.none { it.configured } -> State.NOT_CONFIGURED to "no client declared in this build"
                family == "authelia" && s.autheliaBearer -> State.SIGNED_IN to "bearer stored on this phone"
                family == "cloudflare" && s.vpnActive -> State.SIGNED_IN to "tunnel active (WARP or mesh)"
                identity.isNotBlank() -> State.SIGNED_IN to "signed in as $identity"
                probe != null && !probe.reachable -> State.UNREACHABLE to "not reachable right now"
                probe?.enabled == false -> State.DISABLED to "sign-in is switched off on the server"
                else -> State.SIGNED_OUT to (if (probe == null) "checking…" else "not signed in")
            }
            Row(family, label(ways), state, detail, ways)
        }

    /** What a probe document says: reachable on any 2xx/3xx; `enabled` read from a JSON body when it has one. */
    fun parseProbe(code: Int, body: String): Probe {
        if (code !in 200..399) return Probe(false)
        val enabled = runCatching { JSONObject(body) }.getOrNull()?.takeIf { it.has("enabled") }?.optBoolean("enabled")
        return Probe(true, enabled)
    }

    /** One blocking GET of a declared `probe_url`; never on the main thread. A blank URL is "not reachable". */
    fun probe(url: String, timeoutMs: Int = 4000): Probe {
        if (!url.startsWith("https://")) return Probe(false)
        // #1093 a declared fleet host rides the app's transport (mesh first, then public); anything else is plain.
        val routed = runCatching {
            AuthHttp.transport?.exchange("GET", url, mapOf("Accept" to "application/json", "User-Agent" to "Cloud-Account-ProviderStatus/1"), null, timeoutMs, timeoutMs)
        }
        if (routed.isFailure) return Probe(false)
        routed.getOrNull()?.let { return parseProbe(it.code, it.body.take(4096)) }
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs; instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "Cloud-Account-ProviderStatus/1")
            }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(4096) }.orEmpty()
            parseProbe(code, body)
        } catch (t: Throwable) {
            Probe(false)
        } finally {
            c?.disconnect()
        }
    }
}
