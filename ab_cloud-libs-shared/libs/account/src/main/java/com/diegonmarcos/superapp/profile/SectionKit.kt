package com.diegonmarcos.superapp.profile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure helpers behind the #1081 section pages. Nothing here touches the UI or the network, so each is unit-tested.
 */

/**
 * What Configs (the PUBLIC section) may show of a vault entry: a fixed set of scalar fields, minus anything whose
 * name or value looks like a secret. The Configs pages draw ONLY through [rows]; the guard test feeds it hostile
 * entries and fails if a secret gets through.
 */
object PublicConfig {
    private val SECRET_KEY = Regex("(?i)(pass|secret|token|key|pwd|credential|private|seed|otp|auth|cookie|session|bearer)")
    private val SECRET_VALUE = Regex("(?i)(ghp_|gho_|github_pat_|sk-|xox[bp]-|-----BEGIN|eyJ[A-Za-z0-9_-]{10,}\\.)")

    fun isSecretKey(key: String): Boolean = SECRET_KEY.containsMatchIn(key)
    fun looksSecret(value: String): Boolean = SECRET_VALUE.containsMatchIn(value)

    /** The [wanted] scalar fields of [entry] that are safe to show, in [wanted]'s order. */
    fun rows(entry: JSONObject, wanted: List<String>): List<Pair<String, String>> = wanted.mapNotNull { k ->
        if (isSecretKey(k)) return@mapNotNull null
        val v = entry.opt(k)
        if (v == null || v == JSONObject.NULL || v is JSONObject || v is JSONArray) return@mapNotNull null
        val s = v.toString().trim()
        if (s.isEmpty() || looksSecret(s)) null else k to s
    }

    val DESKTOP_FIELDS = listOf("type", "os", "distro", "nixos", "arch", "hostname", "home_manager", "flake")
    val CONTAINER_FIELDS = listOf("image", "host", "vm", "network", "status", "role")

    private val DESKTOP_TYPES = setOf("notebook", "laptop", "desktop", "computer", "workstation", "pc", "server")

    /** One declared desktop-class device of the vault's `electronics` section: id, display label, its public rows. */
    data class Entry(val id: String, val label: String, val rows: List<Pair<String, String>>)

    fun desktops(bundle: JSONObject?): List<Entry> {
        val electronics = bundle?.optJSONObject("electronics") ?: return emptyList()
        val out = mutableListOf<Entry>()
        for (group in electronics.keys()) {
            val g = electronics.optJSONObject(group) ?: continue
            for (id in g.keys()) {
                val e = g.optJSONObject(id) ?: continue
                val type = (e.opt("type") as? String).orEmpty().lowercase()
                if (type in DESKTOP_TYPES || group.lowercase() in setOf("computers", "notebooks", "desktops")) {
                    out += Entry(id, e.optString("label").ifBlank { id }, rows(e, DESKTOP_FIELDS))
                }
            }
        }
        return out
    }

    /** The vault's `containers` section (an object keyed by name, or an array of objects with a `name`), public rows only. */
    fun containers(bundle: JSONObject?): List<Entry> {
        val c = bundle?.opt("containers")
        val out = mutableListOf<Entry>()
        when (c) {
            is JSONObject -> for (name in c.keys()) c.optJSONObject(name)?.let { out += Entry(name, name, rows(it, CONTAINER_FIELDS)) }
            is JSONArray -> for (i in 0 until c.length()) c.optJSONObject(i)?.let {
                val n = it.optString("name").ifBlank { "container ${i + 1}" }
                out += Entry(n, n, rows(it, CONTAINER_FIELDS))
            }
        }
        return out
    }
}

/** Data ▸ Devices ▸ Mesh: one word for a declared device's place on the mesh. */
object MeshStatus {
    enum class Kind { THIS_PHONE_UP, THIS_PHONE_DOWN, REACHABLE, UNREACHABLE, TUNNEL_DOWN, UNKNOWN }

    /**
     * [thisPhone]: the row is the phone running the app, its state is the live tunnel's. Any other device: [reachable]
     * is the answer of a probe over the tunnel (null = not probed yet); with no tunnel up there is nothing to probe over.
     */
    fun classify(thisPhone: Boolean, tunnelUp: Boolean, reachable: Boolean?): Kind = when {
        thisPhone -> if (tunnelUp) Kind.THIS_PHONE_UP else Kind.THIS_PHONE_DOWN
        !tunnelUp -> Kind.TUNNEL_DOWN
        reachable == null -> Kind.UNKNOWN
        reachable -> Kind.REACHABLE
        else -> Kind.UNREACHABLE
    }

    fun word(k: Kind): String = when (k) {
        Kind.THIS_PHONE_UP -> "this phone · up"
        Kind.THIS_PHONE_DOWN -> "this phone · tunnel down"
        Kind.REACHABLE -> "reachable"
        Kind.UNREACHABLE -> "unreachable"
        Kind.TUNNEL_DOWN -> "no tunnel"
        Kind.UNKNOWN -> "not checked"
    }
}

/**
 * Activity: a short on-device log of what the account did (sync, backup, a secret revealed or copied, an import).
 * One JSON array in a private prefs file, newest last, capped at [CAP]. An entry carries a kind and a sentence,
 * never a secret value: callers pass names ("revealed github.token"), and [record] drops a text that [PublicConfig]
 * says looks like a secret.
 */
object ActivityLog {
    const val CAP = 200
    private const val FILE = "account_activity"
    private const val KEY = "log"

    data class Item(val at: Long, val kind: String, val text: String)

    fun record(ctx: Context, kind: String, text: String, now: Long = System.currentTimeMillis()) {
        if (PublicConfig.looksSecret(text)) return
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val next = append(p.getString(KEY, null), Item(now, kind, text.take(200)))
        p.edit().putString(KEY, next).apply()
    }

    fun items(ctx: Context): List<Item> =
        parse(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)).asReversed()

    fun clear(ctx: Context) { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply() }

    fun parse(json: String?): List<Item> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Item(it.optLong("at"), it.optString("kind"), it.optString("text")) } }
    }.getOrDefault(emptyList())

    /** [json] plus [item], oldest dropped past [CAP]. */
    fun append(json: String?, item: Item): String {
        val all = (parse(json) + item).takeLast(CAP)
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("at", it.at).put("kind", it.kind).put("text", it.text)) }
        return a.toString()
    }
}
