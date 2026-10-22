package com.diegonmarcos.superapp.fleetconfig

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1090 THE MAIL ENDPOINT CONTRACT: Cloud Account is the source of truth for how each fleet mail
 * account is reached, Cloud Mail reads it. Two endpoints per account, both derived from the ONE
 * declaration (Account's build.json::mail_endpoints, itself taken from the mail service's fleet
 * declaration): the mesh URL (`<service>.app`, private) and the public URL (reached with the fleet
 * bearer). Nothing here is typed or hard-coded in Mail.
 *
 * Wire: `content://<account package>.accountdata`, method [METHOD], JSON under [KEY_JSON]:
 *   {"v":1,"accounts":[{"email","service","mesh","public","policy"}],"bearer":"…"?}
 * Entries need the `mail.endpoints` grant; the bearer rides along only for a caller also granted
 * `fleet.bearer` (the Secrets section of Account). Pure over org.json, so both ends share the rules.
 */
object MailEndpointsContract {
    const val VERSION = 1
    const val ACCOUNT_PACKAGE = "com.diegonmarcos.cloudaccount"
    const val AUTHORITY = "$ACCOUNT_PACKAGE.accountdata"
    const val METHOD = "mail_endpoints"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_JSON = "endpoints_json"
    const val GRANT_ENDPOINTS = "mail.endpoints"
    const val GRANT_BEARER = "fleet.bearer"

    const val POLICY_MESH_FIRST = "mesh_first"
    const val POLICY_MESH_ONLY = "mesh_only"
    const val POLICY_PUBLIC_ONLY = "public_only"
    val POLICIES = listOf(POLICY_MESH_FIRST, POLICY_MESH_ONLY, POLICY_PUBLIC_ONLY)

    /** What a secret looks like on screen and in a log: fixed width, so it leaks neither value nor length. */
    const val MASK = "••••••••"
    fun mask(secret: String): String = if (secret.isEmpty()) "" else MASK

    /** One mail service of the declaration, as the endpoints it implies. */
    data class Service(
        val id: String,
        val meshScheme: String, val meshDns: String, val meshPort: Int,
        val publicScheme: String, val publicDomain: String,
        val policy: String,
    ) {
        /** `https://stalwart.app:2443`: an origin, never a path. */
        fun meshUrl(): String = "$meshScheme://$meshDns" + if (meshPort > 0 && meshPort != defaultPort(meshScheme)) ":$meshPort" else ""
        fun publicUrl(): String = "$publicScheme://$publicDomain"
    }

    private fun defaultPort(scheme: String) = if (scheme == "http") 80 else 443

    /**
     * The declaration (`{"services":[{id, mesh_dns, mesh_port, mesh_scheme, public_domain, public_scheme, policy}]}`).
     * A service that breaks a rule is dropped, not guessed at: the mesh name must be a `.app` name (mesh
     * naming rule), the public URL must be https (the bearer travels on it), the policy one of [POLICIES].
     */
    fun parseDecl(text: String): List<Service> {
        val arr = runCatching { JSONObject(text).optJSONArray("services") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val dns = o.optString("mesh_dns").trim().lowercase()
            val domain = o.optString("public_domain").trim().lowercase()
            val meshScheme = o.optString("mesh_scheme", "https").ifBlank { "https" }
            val publicScheme = o.optString("public_scheme", "https").ifBlank { "https" }
            val policy = o.optString("policy", POLICY_MESH_FIRST).ifBlank { POLICY_MESH_FIRST }
            if (o.optString("id").isBlank() || !dns.endsWith(".app") || domain.isEmpty() || '/' in domain || ':' in domain) return@mapNotNull null
            if (publicScheme != "https" || meshScheme !in setOf("http", "https") || policy !in POLICIES) return@mapNotNull null
            Service(o.optString("id"), meshScheme, dns, o.optInt("mesh_port", 0), publicScheme, domain, policy)
        }
    }

    /** One account's pair of endpoints. No secret in here, so it can be logged and compared freely. */
    data class Entry(val email: String, val service: String, val mesh: String, val public: String, val policy: String)

    /** [accounts] = (email, JMAP host) of the vault's declared mail accounts; only those on a declared service are fleet accounts. */
    fun entries(services: List<Service>, accounts: List<Pair<String, String>>): List<Entry> = accounts.mapNotNull { (email, server) ->
        val host = hostOf(server)
        val s = services.firstOrNull { it.publicDomain == host } ?: return@mapNotNull null
        Entry(email.trim().lowercase(), s.id, s.meshUrl(), s.publicUrl(), s.policy)
    }.distinctBy { it.email }

    /** The published document; the bearer only when [bearer] is non-blank (the caller was granted it). */
    fun toJson(entries: List<Entry>, bearer: String?): String = JSONObject().also { o ->
        o.put("v", VERSION)
        o.put("accounts", JSONArray().also { a ->
            for (e in entries) a.put(JSONObject().put("email", e.email).put("service", e.service)
                .put("mesh", e.mesh).put("public", e.public).put("policy", e.policy))
        })
        if (!bearer.isNullOrBlank()) o.put("bearer", bearer.trim())
    }.toString()

    /** What Mail receives. [toString] masks the bearer, so an interpolated log line cannot write it out. */
    data class Published(val entries: List<Entry>, val bearer: String) {
        override fun toString() = "Published(entries=$entries, bearer=${mask(bearer)})"
    }

    /** The document as [Published], or null when it is not one of this version. An entry with an unusable URL is dropped. */
    fun parse(text: String): Published? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.optInt("v", -1) != VERSION) return null
        val arr = o.optJSONArray("accounts") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val pub = e.optString("public"); val mesh = e.optString("mesh")
            if (e.optString("email").isBlank() || !pub.startsWith("https://") || !(mesh.startsWith("https://") || mesh.startsWith("http://"))) return@mapNotNull null
            Entry(e.optString("email").trim().lowercase(), e.optString("service"), mesh, pub,
                e.optString("policy").takeIf { it in POLICIES } ?: POLICY_MESH_FIRST)
        }
        return Published(list, o.optString("bearer"))
    }

    /** The host out of `https://h:port/path`, `h:port` or `h`. */
    fun hostOf(server: String): String =
        server.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').lowercase()
}
