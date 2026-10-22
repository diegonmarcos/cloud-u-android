package com.diegonmarcos.superapp.fleetendpoints

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
 *
 * #1093 The service declaration, its rules, the policies and the mask are [FleetEndpointsContract]'s (Account's one
 * `fleet_endpoints` declaration, of which the mail services are the ones with `kind: mail`); this adds what is
 * mail's alone: the per-account entries and their wire.
 */
object MailEndpointsContract {
    const val VERSION = 1
    const val ACCOUNT_PACKAGE = FleetEndpointsContract.ACCOUNT_PACKAGE
    const val AUTHORITY = FleetEndpointsContract.AUTHORITY
    const val METHOD = "mail_endpoints"
    const val KEY_OK = FleetEndpointsContract.KEY_OK
    const val KEY_ERROR = FleetEndpointsContract.KEY_ERROR
    const val KEY_JSON = FleetEndpointsContract.KEY_JSON
    const val GRANT_ENDPOINTS = "mail.endpoints"
    const val GRANT_BEARER = FleetEndpointsContract.GRANT_BEARER

    const val POLICY_MESH_FIRST = FleetEndpointsContract.POLICY_MESH_FIRST
    const val POLICY_MESH_ONLY = FleetEndpointsContract.POLICY_MESH_ONLY
    const val POLICY_PUBLIC_ONLY = FleetEndpointsContract.POLICY_PUBLIC_ONLY
    val POLICIES = FleetEndpointsContract.POLICIES

    const val MASK = FleetEndpointsContract.MASK
    fun mask(secret: String): String = FleetEndpointsContract.mask(secret)

    /** The mail services of a declaration: those with a public URL (a mail account is matched by its public host). */
    fun parseDecl(text: String): List<FleetEndpointsContract.Service> = FleetEndpointsContract.parseDecl(text).filter { it.hasPublic }

    /** One account's pair of endpoints. No secret in here, so it can be logged and compared freely. */
    data class Entry(val email: String, val service: String, val mesh: String, val public: String, val policy: String)

    /** [accounts] = (email, JMAP host) of the vault's declared mail accounts; only those on a declared service are fleet accounts. */
    fun entries(services: List<FleetEndpointsContract.Service>, accounts: List<Pair<String, String>>): List<Entry> = accounts.mapNotNull { (email, server) ->
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

    fun hostOf(server: String): String = FleetEndpointsContract.hostOf(server)
}
