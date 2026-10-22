package com.diegonmarcos.superapp.fleetconfig

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1093 THE FLEET ENDPOINT CONTRACT: how each fleet service is reached, declared once, read by Cloud Account itself
 * (its own transport and Connect ▸ Status) and published by it to the other apps. A service has up to two
 * endpoints, both derived from the ONE declaration (Account's build.json::fleet_endpoints, itself taken from the
 * service's fleet declaration in cloud-u-containers): the mesh URL (`<service>.app`, private) and, where the
 * service declares one, the public URL (reached with the fleet bearer). Nothing is typed or hard-coded in an app.
 *
 * The router that acts on this is libs:fleet-endpoints (plain JVM, shared by Cloud Mail and Cloud Account). This is
 * the data half, pure over org.json, so both ends of the provider share the rules.
 *
 * Wire: `content://<account package>.accountdata`, method [METHOD], JSON under [KEY_JSON]:
 *   {"v":1,"services":[{"service","kind","mesh","public","policy","health"}],"bearer":"…"?}
 * Entries need the [GRANT_ENDPOINTS] grant; the bearer rides along only for a caller also granted [GRANT_BEARER]
 * (the Secrets section of Account). A service without a public URL is mesh-only and carries `"public":""`.
 */
object FleetEndpointsContract {
    const val VERSION = 1
    const val ACCOUNT_PACKAGE = "com.diegonmarcos.cloudaccount"
    const val AUTHORITY = "$ACCOUNT_PACKAGE.accountdata"
    const val METHOD = "fleet_endpoints"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_JSON = "endpoints_json"
    const val GRANT_ENDPOINTS = "fleet.endpoints"
    const val GRANT_BEARER = "fleet.bearer"

    const val POLICY_MESH_FIRST = "mesh_first"
    const val POLICY_MESH_ONLY = "mesh_only"
    const val POLICY_PUBLIC_ONLY = "public_only"
    val POLICIES = listOf(POLICY_MESH_FIRST, POLICY_MESH_ONLY, POLICY_PUBLIC_ONLY)

    /** What a secret looks like on screen and in a log: fixed width, so it leaks neither value nor length. */
    const val MASK = "••••••••"
    fun mask(secret: String): String = if (secret.isEmpty()) "" else MASK

    /** One service of the declaration, as the endpoints it implies. [publicDomain] is empty for a mesh-only service. */
    data class Service(
        val id: String, val kind: String,
        val meshScheme: String, val meshDns: String, val meshPort: Int,
        val publicScheme: String, val publicDomain: String,
        val policy: String, val health: String,
    ) {
        val hasPublic: Boolean get() = publicDomain.isNotEmpty()
        /** `https://stalwart.app:2443`: an origin, never a path. */
        fun meshUrl(): String = "$meshScheme://$meshDns" + if (meshPort > 0 && meshPort != defaultPort(meshScheme)) ":$meshPort" else ""
        /** `https://jmap.example.org`, or "" for a mesh-only service. */
        fun publicUrl(): String = if (hasPublic) "$publicScheme://$publicDomain" else ""
    }

    private fun defaultPort(scheme: String) = if (scheme == "http") 80 else 443

    /**
     * The declaration (`{"services":[{id, kind, mesh_dns, mesh_port, mesh_scheme, public_domain, public_scheme, policy,
     * health}]}`). A service that breaks a rule is dropped, not guessed at: the mesh name must be a `.app` name (mesh
     * naming rule), a public URL must be https (the bearer travels on it) and a bare host, the policy one of
     * [POLICIES], and a service with no public URL can only be mesh-only.
     */
    fun parseDecl(text: String): List<Service> {
        val arr = runCatching { JSONObject(text).optJSONArray("services") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            val dns = o.optString("mesh_dns").trim().lowercase()
            val domain = o.optString("public_domain").trim().lowercase()
            val meshScheme = o.optString("mesh_scheme", "https").ifBlank { "https" }
            val publicScheme = o.optString("public_scheme", "https").ifBlank { "https" }
            val policy = o.optString("policy", POLICY_MESH_FIRST).ifBlank { POLICY_MESH_FIRST }
            val health = o.optString("health", "/").ifBlank { "/" }
            if (id.isEmpty() || !dns.endsWith(".app") || '/' in domain || ':' in domain || ' ' in domain) return@mapNotNull null
            if (meshScheme !in setOf("http", "https") || policy !in POLICIES || !health.startsWith("/")) return@mapNotNull null
            if (domain.isEmpty() && policy != POLICY_MESH_ONLY) return@mapNotNull null
            if (domain.isNotEmpty() && publicScheme != "https") return@mapNotNull null
            Service(id, o.optString("kind").trim().ifEmpty { "service" }, meshScheme, dns, o.optInt("mesh_port", 0),
                publicScheme, domain, policy, health)
        }.distinctBy { it.id }
    }

    /** The services of one [kind], in declaration order. */
    fun ofKind(services: List<Service>, kind: String): List<Service> = services.filter { it.kind == kind }

    /** The declaration text narrowed to the services of [kind], still a declaration (what a kind-specific reader takes). */
    fun declOfKind(text: String, kind: String): String {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return ""
        val arr = o.optJSONArray("services") ?: return ""
        val kept = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.takeIf { it.optString("kind") == kind }?.let { kept.put(it) }
        return JSONObject().put("services", kept).toString()
    }

    /** One service's pair of endpoints. No secret in here, so it can be logged and compared freely. */
    data class Entry(val service: String, val kind: String, val mesh: String, val public: String, val policy: String, val health: String)

    fun entries(services: List<Service>): List<Entry> =
        services.map { Entry(it.id, it.kind, it.meshUrl(), it.publicUrl(), it.policy, it.health) }

    /** The published document; the bearer only when [bearer] is non-blank (the caller was granted it). */
    fun toJson(entries: List<Entry>, bearer: String?): String = JSONObject().also { o ->
        o.put("v", VERSION)
        o.put("services", JSONArray().also { a ->
            for (e in entries) a.put(JSONObject().put("service", e.service).put("kind", e.kind)
                .put("mesh", e.mesh).put("public", e.public).put("policy", e.policy).put("health", e.health))
        })
        if (!bearer.isNullOrBlank()) o.put("bearer", bearer.trim())
    }.toString()

    /** What a reader receives. [toString] masks the bearer, so an interpolated log line cannot write it out. */
    data class Published(val entries: List<Entry>, val bearer: String) {
        override fun toString() = "Published(entries=$entries, bearer=${mask(bearer)})"
    }

    /** The document as [Published], or null when it is not one of this version. An entry with an unusable URL is dropped. */
    fun parse(text: String): Published? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.optInt("v", -1) != VERSION) return null
        val arr = o.optJSONArray("services") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val pub = e.optString("public"); val mesh = e.optString("mesh")
            if (e.optString("service").isBlank() || !(mesh.startsWith("https://") || mesh.startsWith("http://"))) return@mapNotNull null
            if (pub.isNotEmpty() && !pub.startsWith("https://")) return@mapNotNull null
            Entry(e.optString("service"), e.optString("kind"), mesh, pub,
                e.optString("policy").takeIf { it in POLICIES } ?: POLICY_MESH_FIRST, e.optString("health").ifBlank { "/" })
        }
        return Published(list, o.optString("bearer"))
    }

    /** The host out of `https://h:port/path`, `h:port` or `h`. */
    fun hostOf(server: String): String =
        server.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').lowercase()
}
