package com.diegonmarcos.cloudaccount.hub

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data ▸ Secrets ▸ Tokens: the client of the fleet token engine (cloud-u-containers infra-sec_tokens-api,
 * mesh tokens-api.app, public tokens-api.diegonmarcos.com). It lists every fleet bearer token (the `ft_` tokens the
 * engine mints and the Authelia JWTs it adopts), generates and revokes them, and returns one full value on request.
 *
 * Rules kept here, not in the page: the engine is owner-only and the call carries the vault's fleet bearer as
 * `Authorization` (the engine asks the introspect-proxy whether that bearer is the owner's device client); a
 * value is only ever returned by [reveal] / [generate], is never part of a [TokenRow], and a failure message never
 * quotes a response body.
 */
data class TokenRow(
    val id: String,
    val name: String,
    val kind: String,
    val audience: String,
    val scopes: List<String>,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val lastUsedAt: Long?,
    val source: String,
    val hint: String,
) {
    val active: Boolean get() = status == "active"
    /** What the list shows instead of the value. */
    val masked: String get() = if (hint.isEmpty()) "••••" else "••••$hint"
    override fun toString(): String = "TokenRow($id, $name, $kind, $status, value=$masked)"
}

data class AuditRow(val at: Long, val actor: String, val action: String, val tokenName: String)

/** Either a value or a one-line reason the owner can act on (never a response body). */
sealed interface TokensResult<out T> {
    data class Ok<T>(val value: T) : TokensResult<T>
    data class Fail(val reason: String) : TokensResult<Nothing>
}

class TokensClient(
    private val base: String,
    private val bearer: () -> String?,
    private val transport: Transport,
) {
    fun interface Transport {
        /** (status, body); an IOException for a transport failure. */
        fun call(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>
    }

    private fun <T> exchange(method: String, path: String, body: JSONObject?, parse: (String) -> T): TokensResult<T> {
        val token = bearer()?.trim().orEmpty()
        if (token.isEmpty()) return TokensResult.Fail("no fleet bearer stored on this phone (Connect ▸ Authentication providers)")
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val (code, text) = try {
            transport.call(method, base + path, headers, body?.toString())
        } catch (e: java.io.IOException) {
            return TokensResult.Fail("token engine not reachable (${e.javaClass.simpleName})")
        }
        return when {
            code in 200..299 -> runCatching { TokensResult.Ok(parse(text)) }.getOrElse { TokensResult.Fail("unreadable answer from the token engine") }
            code == 401 -> TokensResult.Fail("not signed in: the fleet bearer is missing or expired")
            code == 403 -> TokensResult.Fail("refused: this phone's bearer is not the owner's")
            code == 404 -> TokensResult.Fail("token not found")
            code == 503 -> TokensResult.Fail("token engine unavailable, try again shortly")
            else -> TokensResult.Fail("token engine answered $code")
        }
    }

    fun list(): TokensResult<List<TokenRow>> = exchange("GET", "/v1/tokens", null) { t ->
        val a = JSONObject(t).optJSONArray("tokens") ?: JSONArray()
        (0 until a.length()).map { row(a.getJSONObject(it)) }
    }

    /** The new token's row and its full value (shown once here; [reveal] returns it again later). */
    fun generate(name: String, audience: String, scopes: List<String>, ttlDays: Int?): TokensResult<Pair<TokenRow, String>> {
        val body = JSONObject().put("name", name.trim()).put("audience", audience.trim().ifEmpty { "*" })
            .put("scopes", JSONArray(scopes.map { it.trim() }.filter { it.isNotEmpty() }))
        if (ttlDays != null) body.put("ttl_days", ttlDays)
        return exchange("POST", "/v1/tokens", body) { t -> JSONObject(t).let { row(it) to it.getString("value") } }
    }

    fun reveal(id: String): TokensResult<String> = exchange("POST", "/v1/tokens/${enc(id)}/reveal", JSONObject()) { JSONObject(it).getString("value") }

    fun revoke(id: String): TokensResult<TokenRow> = exchange("POST", "/v1/tokens/${enc(id)}/revoke", JSONObject()) { row(JSONObject(it)) }

    fun audit(limit: Int = 30): TokensResult<List<AuditRow>> = exchange("GET", "/v1/audit?limit=$limit", null) { t ->
        val a = JSONObject(t).optJSONArray("audit") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it).let { o -> AuditRow(o.optLong("ts"), o.optString("actor"), o.optString("action"), o.optString("token_name")) } }
    }

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8")

    companion object {
        const val BASE = "https://tokens-api.diegonmarcos.com"

        /** The vault key the fleet bearer lives at (FleetEndpointsContract.GRANT_BEARER, same string). */
        const val BEARER_KEY = "fleet.bearer"

        fun row(o: JSONObject): TokenRow = TokenRow(
            id = o.getString("id"), name = o.optString("name"), kind = o.optString("kind"), audience = o.optString("audience", "*"),
            scopes = o.optJSONArray("scopes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            status = o.optString("status"), createdAt = o.optLong("created_at"),
            expiresAt = if (o.isNull("expires_at")) null else o.optLong("expires_at"),
            lastUsedAt = if (o.isNull("last_used_at")) null else o.optLong("last_used_at"),
            source = o.optString("source"), hint = o.optString("hint"),
        )
    }
}
