package com.diegonmarcos.cloudaccount.hub

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The three ways a provider can be signed in to: the columns of Home's Connections table. */
enum class AuthMethod(val label: String) { WEB_OAUTH("Web OAuth"), API_TOKEN("API token / JWT"), SSH("SSH") }

/** One cell of the Connections table. */
enum class CellStatus(val word: String) { CONNECTED("connected"), EXPIRED("expired"), MISSING("missing"), NOT_USED("n/a") }

/**
 * A provider the account keeps credentials for. [prefixes] say which Connections paths (AccountVault, `a.b.c`)
 * belong to it; [methods] are the auth methods it offers; [probeUrl] is a read-only "who am I" endpoint the
 * provider's Setup ▸ test calls with the stored credential in the Authorization header ([scheme] + value).
 */
data class Provider(
    val id: String,
    val label: String,
    val prefixes: List<String>,
    val methods: Set<AuthMethod>,
    val probeUrl: String? = null,
    val scheme: String = "Bearer",
    val core: Boolean = false,
)

/** One stored credential: where it is, what kind, and when it stops working. The value is never held here. */
data class SecretEntry(val path: String, val provider: String, val method: AuthMethod, val expiresAt: Instant?, val expired: Boolean) {
    val name: String get() = path.substringAfterLast('.')
}

data class Cell(val status: CellStatus, val paths: List<String>)

data class ConnectionRow(val provider: Provider, val cells: Map<AuthMethod, Cell>) {
    val worst: CellStatus get() = cells.values.map { it.status }.let { s ->
        when { CellStatus.EXPIRED in s -> CellStatus.EXPIRED; CellStatus.MISSING in s -> CellStatus.MISSING; else -> CellStatus.CONNECTED }
    }
}

object Providers {
    private val ALL = setOf(AuthMethod.WEB_OAUTH, AuthMethod.API_TOKEN, AuthMethod.SSH)

    /** Core rows always show (the owner's four); the rest show when the account holds something for them. */
    val CATALOG: List<Provider> = listOf(
        Provider("cloudflare", "Cloudflare One", listOf("cloudflare", "cf", "cf_access", "cloudflared", "warp"), ALL,
            "https://api.cloudflare.com/client/v4/user/tokens/verify", core = true),
        Provider("google", "Google", listOf("google", "gcp", "gmail", "gws"), setOf(AuthMethod.WEB_OAUTH, AuthMethod.API_TOKEN),
            "https://www.googleapis.com/oauth2/v3/userinfo", core = true),
        Provider("github", "GitHub", listOf("forge.github", "github", "gh"), ALL, "https://api.github.com/user", core = true),
        Provider("cloud-authelia", "Cloud-Authelia", listOf("authelia", "auth", "fleet", "ssh"), ALL, core = true),
        Provider("openrouter", "OpenRouter", listOf("openrouter", "ai.openrouter"), setOf(AuthMethod.API_TOKEN), "https://openrouter.ai/api/v1/key"),
        Provider("gitea", "Gitea", listOf("forge.gitea", "gitea"), setOf(AuthMethod.WEB_OAUTH, AuthMethod.API_TOKEN),
            "https://git.diegonmarcos.com/api/v1/user", scheme = "token"),
        Provider("mail", "Mail", listOf("mail", "smtp", "imap", "jmap"), setOf(AuthMethod.API_TOKEN)),
        Provider("other", "Other", emptyList(), ALL),
    )

    fun byId(id: String): Provider = CATALOG.firstOrNull { it.id == id } ?: CATALOG.last()

    // ── classification (pure) ────────────────────────────────────────────

    private val META = Regex("(^|_)(expires(_at)?|exp|last|primary|preset|id|url|server|host|endpoint|port|user(name)?|email|auto)$")
    private val SSH = Regex("id_ed25519|id_rsa|id_ecdsa")
    private val OAUTH = Regex("oauth|refresh|session|cookie|web_login")
    private val TOKEN = Regex("token|api_?key|jwt|bearer|secret|password|passwd|pat$|key$")

    /** The provider a Connections path belongs to: the first catalog entry whose prefix it starts with. */
    fun providerOf(path: String): Provider {
        val p = path.lowercase()
        val first = p.substringBefore('.')
        return CATALOG.firstOrNull { pr ->
            pr.prefixes.any { x -> p == x || p.startsWith("$x.") || (!x.contains('.') && (first == x || first.startsWith("${x}_"))) }
        } ?: CATALOG.last()
    }

    /** The auth method a leaf holds, or null when the leaf is not a credential (an endpoint, a date, a flag). */
    fun methodOf(path: String): AuthMethod? {
        val leaf = path.lowercase().substringAfterLast('.')
        if (META.containsMatchIn(leaf)) return null
        val p = path.lowercase()
        return when {
            SSH.containsMatchIn(leaf) || (p.contains("ssh") && (TOKEN.containsMatchIn(leaf) || leaf == "key")) -> AuthMethod.SSH
            OAUTH.containsMatchIn(leaf) -> AuthMethod.WEB_OAUTH
            TOKEN.containsMatchIn(leaf) -> AuthMethod.API_TOKEN
            else -> null
        }
    }

    /** Every leaf of a Connections blob as `a.b.c` -> value; keys starting with `_` are bookkeeping. */
    fun leaves(o: JSONObject, base: String = "", out: MutableMap<String, Any> = LinkedHashMap()): Map<String, Any> {
        for (k in o.keys().asSequence().toList().sorted()) {
            if (k.startsWith("_")) continue
            val path = if (base.isEmpty()) k else "$base.$k"
            when (val v = o.opt(k)) {
                is JSONObject -> leaves(v, path, out)
                null, JSONObject.NULL -> {}
                is JSONArray -> out[path] = v.toString()
                else -> out[path] = v
            }
        }
        return out
    }

    /** When the credential at [path] stops working: a sibling `<leaf>_expires[_at]` / `expires[_at]`, else a JWT's `exp`. */
    fun expiryOf(path: String, all: Map<String, Any>): Instant? {
        val parent = path.substringBeforeLast('.', "")
        val sib = { k: String -> if (parent.isEmpty()) k else "$parent.$k" }
        val leaf = path.substringAfterLast('.')
        val keys = listOf("${path}_expires", "${path}_expires_at", "${path}.expires", sib("${leaf}_exp"), sib("expires"), sib("expires_at"))
        for (k in keys) all[k]?.let { v -> parseTime(v)?.let { return it } }
        return jwtExpiry(all[path]?.toString().orEmpty())
    }

    /** Epoch seconds or millis, an ISO instant, or a plain ISO date (end of that day UTC). */
    fun parseTime(v: Any): Instant? {
        val s = v.toString().trim()
        s.toLongOrNull()?.let { n -> return if (n > 100_000_000_000L) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n) }
        runCatching { return Instant.parse(s) }
        runCatching { return LocalDate.parse(s).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) }
        return null
    }

    /** A JWT's `exp` (seconds), read from its payload without verifying it; null when the value is no JWT. */
    fun jwtExpiry(token: String): Instant? {
        val parts = token.trim().removePrefix("Bearer ").split('.')
        if (parts.size != 3) return null
        val json = runCatching { String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)) }.getOrNull() ?: return null
        val exp = runCatching { JSONObject(json).optLong("exp", 0L) }.getOrDefault(0L)
        return if (exp > 0) Instant.ofEpochSecond(exp) else null
    }

    /** Every credential the blob holds, by provider, catalog order. Values are not carried. */
    fun secrets(connections: JSONObject, now: Instant = Instant.now()): Map<Provider, List<SecretEntry>> {
        val all = leaves(connections)
        val out = LinkedHashMap<Provider, MutableList<SecretEntry>>()
        for (pr in CATALOG) out[pr] = ArrayList()
        for ((path, v) in all) {
            val m = methodOf(path) ?: continue
            if (v.toString().isBlank()) continue
            val pr = providerOf(path)
            val exp = expiryOf(path, all)
            out.getValue(pr) += SecretEntry(path, pr.id, m, exp, exp != null && !exp.isAfter(now))
        }
        return out
    }

    /**
     * Home's Connections table, built from the stored auth state: one row per core provider plus every other
     * provider that holds something; one cell per method. A provider that does not offer a method and holds
     * nothing for it is n/a; otherwise a live credential is connected, only stale ones expired, none missing.
     */
    fun matrix(connections: JSONObject, now: Instant = Instant.now()): List<ConnectionRow> =
        secrets(connections, now).filter { (pr, s) -> pr.core || s.isNotEmpty() }.map { (pr, s) ->
            ConnectionRow(pr, AuthMethod.values().associateWith { m ->
                val mine = s.filter { it.method == m }
                val status = when {
                    mine.any { !it.expired } -> CellStatus.CONNECTED
                    mine.isNotEmpty() -> CellStatus.EXPIRED
                    m !in pr.methods -> CellStatus.NOT_USED
                    else -> CellStatus.MISSING
                }
                Cell(status, mine.map { it.path })
            })
        }

    /** A short, non-reversible label for a value: its sha256 prefix and its length. Never any of its characters. */
    fun mask(value: String): String {
        if (value.isEmpty()) return "not set"
        val h = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        return "sha256:${h.take(8)} · ${value.length} chars"
    }
}
