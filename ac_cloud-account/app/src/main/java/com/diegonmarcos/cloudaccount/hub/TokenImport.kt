package com.diegonmarcos.cloudaccount.hub

import org.json.JSONArray
import org.json.JSONObject

/**
 * Home ▸ Import tokens: a picked file or a paste, parsed into rows the owner reviews and edits before anything
 * is stored. Three shapes are understood:
 *
 *   JSON     `{"tokens": [{provider, type, name, value, expires}, …]}`, a bare array of those, or any object
 *            (its credential leaves become rows, by the same rules as the Connections table)
 *   .env     `export NAME="value"` lines; `# comments` and blank lines ignored
 *   key=value plain `name=value` lines
 *
 * A sibling `NAME_EXPIRES` / `NAME_EXPIRES_AT` line (or `expires` field) becomes the row's expiry. A line that
 * holds no credential (an id, a URL) is counted as skipped, never imported. A row prints masked ([toString]).
 */
object TokenImport {

    data class Row(
        val provider: String,
        val type: AuthMethod,
        val name: String,
        val value: String,
        val expires: String = "",
        val selected: Boolean = true,
    ) {
        val masked: String get() = Providers.mask(value)
        /** Where Import puts it in the account's Connections (the same store every page reads). */
        val path: String get() = pathFor(this)
        override fun toString(): String = "Row(provider=$provider, type=$type, name=$name, value=$masked, expires=$expires)"
    }

    data class Result(val rows: List<Row>, val skipped: Int, val format: String)

    fun parse(text: String): Result {
        val t = text.trim()
        if (t.isEmpty()) return Result(emptyList(), 0, "empty")
        if (t.startsWith("{")) runCatching { JSONObject(t) }.getOrNull()?.let { return parseJson(it) }
        if (t.startsWith("[")) runCatching { JSONArray(t) }.getOrNull()?.let { return parseArray(it, "json") }
        return parseLines(t)
    }

    // ── JSON ─────────────────────────────────────────────────────────────

    private fun parseJson(o: JSONObject): Result {
        o.optJSONArray("tokens")?.let { return parseArray(it, "json") }
        val all = Providers.leaves(o)
        val rows = ArrayList<Row>(); var skipped = 0
        for ((path, v) in all) {
            val m = Providers.methodOf(path)
            if (m == null || v.toString().isBlank()) { if (!isExpiryKey(path)) skipped++; continue }
            val exp = Providers.expiryOf(path, all)?.toString().orEmpty()
            rows += Row(Providers.providerOf(path).id, m, path, v.toString(), exp)
        }
        return Result(rows, skipped, "json")
    }

    private fun parseArray(a: JSONArray, format: String): Result {
        val rows = ArrayList<Row>(); var skipped = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: run { skipped++; null } ?: continue
            val value = listOf("value", "token", "secret", "key").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } }
            val name = o.optString("name").ifBlank { o.optString("id") }
            if (value == null || name.isBlank()) { skipped++; continue }
            val type = typeOf(o.optString("type"), name)
            val provider = o.optString("provider").takeIf { it.isNotBlank() }?.let { normalizeProvider(it) } ?: providerOfName(name)
            val exp = listOf("expires", "expires_at", "expiry").firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it != JSONObject.NULL } }
                ?.let { Providers.parseTime(it)?.toString() }.orEmpty()
            rows += Row(provider, type, name, value, exp.ifEmpty { Providers.jwtExpiry(value)?.toString().orEmpty() })
        }
        return Result(rows, skipped, format)
    }

    // ── .env / key=value ─────────────────────────────────────────────────

    private fun parseLines(t: String): Result {
        val pairs = LinkedHashMap<String, String>(); var skipped = 0; var env = false
        for (raw in t.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val body = if (line.startsWith("export ")) { env = true; line.removePrefix("export ").trim() } else line
            val eq = body.indexOf('=')
            if (eq <= 0) { skipped++; continue }
            val k = body.substring(0, eq).trim()
            var v = body.substring(eq + 1).trim()
            if (v.length >= 2 && (v.first() == '"' && v.last() == '"' || v.first() == '\'' && v.last() == '\'')) v = v.substring(1, v.length - 1)
            else v = v.substringBefore(" #").trim()
            if (k.any { it.isWhitespace() }) { skipped++; continue }
            if (k.uppercase() == k && k.any { it.isLetter() }) env = true
            pairs[k] = v
        }
        val rows = ArrayList<Row>()
        for ((k, v) in pairs) {
            if (isExpiryKey(k)) continue
            val m = Providers.methodOf(k.lowercase())
            if (m == null || v.isBlank()) { skipped++; continue }
            val exp = (pairs["${k}_EXPIRES"] ?: pairs["${k}_EXPIRES_AT"] ?: pairs["${k}_expires"] ?: pairs["${k}_expires_at"])
                ?.let { Providers.parseTime(it)?.toString() } ?: Providers.jwtExpiry(v)?.toString()
            rows += Row(providerOfName(k), m, k, v, exp.orEmpty())
        }
        return Result(rows, skipped, if (env) "env" else "key=value")
    }

    private fun isExpiryKey(k: String) = Regex("(?i)(^|[._])(expires(_at)?|exp)$").containsMatchIn(k)

    // ── naming ───────────────────────────────────────────────────────────

    private val PROVIDER_WORDS = listOf(
        "cloudflare" to listOf("cloudflare", "cf_", "cf.", "warp"),
        "google" to listOf("google", "gcp", "gmail", "gws"),
        "github" to listOf("github", "gh_", "gh."),
        "gitea" to listOf("gitea"),
        "openrouter" to listOf("openrouter"),
        "cloud-authelia" to listOf("authelia", "fleet", "cloud_auth"),
        "mail" to listOf("mail", "smtp", "imap", "jmap"),
    )

    fun providerOfName(name: String): String {
        val n = name.lowercase()
        if (n.contains('.')) Providers.providerOf(n).id.takeIf { it != "other" }?.let { return it }
        return PROVIDER_WORDS.firstOrNull { (_, ws) ->
            ws.any { w -> n.startsWith(w) || (w.length >= 4 && n.contains(w)) || n.contains("_" + w.trimEnd('_', '.') + "_") }
        }?.first ?: "other"
    }

    fun normalizeProvider(p: String): String {
        val n = p.lowercase().trim()
        return Providers.CATALOG.firstOrNull { it.id == n || it.label.lowercase() == n }?.id ?: providerOfName(n)
    }

    private fun typeOf(declared: String, name: String): AuthMethod = when (declared.lowercase().trim()) {
        "ssh", "ssh_key" -> AuthMethod.SSH
        "oauth", "web", "web_oauth" -> AuthMethod.WEB_OAUTH
        "token", "jwt", "api", "api_token", "api_key", "pat", "password" -> AuthMethod.API_TOKEN
        else -> Providers.methodOf(name.lowercase()) ?: AuthMethod.API_TOKEN
    }

    /**
     * The Connections path a row is stored at. A GitHub / Gitea API token lands where ForgeClient reads it, the
     * Authelia fleet bearer where the fleet reads it, a JSON leaf at its own path; anything else at
     * `<provider>.<name>` in snake case, the provider's own word dropped from the front.
     */
    fun pathFor(r: Row): String {
        val n = r.name.trim()
        if (n.contains('.')) return n.lowercase()
        val slug = n.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (r.type == AuthMethod.API_TOKEN && r.provider == "github" && slug.contains("token")) return "forge.github.token"
        if (r.type == AuthMethod.API_TOKEN && r.provider == "gitea" && slug.contains("token")) return "forge.gitea.token"
        if (r.provider == "cloud-authelia" && (slug.contains("bearer") || slug.startsWith("fleet"))) return "fleet.bearer"
        val words = listOf(r.provider.replace('-', '_')) + PROVIDER_WORDS.firstOrNull { it.first == r.provider }?.second.orEmpty().map { it.trimEnd('_', '.') }
        var tail = slug
        for (w in words) if (tail.startsWith("${w}_")) { tail = tail.removePrefix("${w}_"); break }
        val base = when (r.provider) { "other" -> "imported"; "cloud-authelia" -> "authelia"; else -> r.provider }
        return "$base.${tail.ifEmpty { "token" }}"
    }

    /** Stores one row (and its expiry next to it); [put] is AccountVault.putConnection on the phone. */
    fun importOne(r: Row, put: (String, Any) -> Unit) {
        val p = r.path
        put(p, r.value)
        if (r.expires.isNotBlank()) put("${p}_expires", r.expires)
    }

    /** Import all: every selected row with a value, in order. Returns how many were stored. */
    fun importAll(rows: List<Row>, put: (String, Any) -> Unit): Int {
        var n = 0
        for (r in rows) if (r.selected && r.value.isNotBlank()) { importOne(r, put); n++ }
        return n
    }
}
