package com.diegonmarcos.superapp.profile

import com.diegonmarcos.superapp.core.ConfigSyncClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configs → Profile → Connect → "Vault configs": fetch the consolidated vault
 * bundle (cloud-vault configs/, emitted by configs/emit.py) and turn it into
 * rows for the Imported tab.
 *
 * TWO FACTORS, TWO CALLS. [start] proves the Authelia bearer and makes the
 * server mail a one-time code to the account address; [fetch] sends the same
 * bearer plus that code and gets the decrypted bundle back. Both go through
 * [ConfigSyncClient.request], so a redirect to the login page, a 401, a 403 or
 * a dead host read exactly as they do for every other import route.
 *
 * DISPLAY ONLY. Nothing here writes to any app's settings — [Imported.last]
 * holds the fetch in memory for the Imported tab and dies with the process.
 * Applying any of it per app is a later ticket, after the owner has reviewed
 * what arrives.
 */
object VaultConnect {

    /** Endpoint data, all of it from build.json::ui.vault_connect via BuildConfig. */
    data class Endpoints(
        val baseUrl: String,
        val startPath: String,
        val fetchPath: String,
        val connectTimeoutMs: Int,
        val readTimeoutMs: Int,
    )

    fun start(e: Endpoints, bearer: String): ConfigSyncClient.Outcome =
        post(e, e.fetchPath, bearer, JSONObject())

    fun fetch(e: Endpoints, bearer: String, code: String): ConfigSyncClient.Outcome =
        post(e, e.fetchPath, bearer, JSONObject().put("code", code))

    private fun post(e: Endpoints, path: String, bearer: String, body: JSONObject) =
        ConfigSyncClient.request(
            url = e.baseUrl.trimEnd('/') + "/" + path.trimStart('/'),
            headers = mapOf("Authorization" to "Bearer $bearer"),
            secret = bearer,
            authHint = "Store a fresh Authelia bearer in Connect and try again.",
            connectTimeoutMs = e.connectTimeoutMs,
            readTimeoutMs = e.readTimeoutMs,
            method = "POST",
            jsonBody = body.toString(),
        )

    /** What a failure MEANS for this flow, beyond the generic transport kind. */
    enum class Hint { CODE_REJECTED, SERVER_NOT_READY, NONE }

    /**
     * 403 here is the server refusing the CODE (bad, expired, never requested),
     * not a scope problem. 404 and 5xx mean the server half is not deployed or
     * not configured yet (no age key, no bundle) — which is its own state and
     * must not read as "your token is wrong".
     */
    fun hint(kind: ConfigSyncClient.Kind): Hint = when (kind) {
        ConfigSyncClient.Kind.FORBIDDEN -> Hint.CODE_REJECTED
        ConfigSyncClient.Kind.NOT_FOUND, ConfigSyncClient.Kind.SERVER -> Hint.SERVER_NOT_READY
        else -> Hint.NONE
    }

    // ── rendering ────────────────────────────────────────────────────────

    /** One leaf of the bundle. [path] is the key trail inside its section. */
    data class Row(val path: String, val value: String, val pending: Boolean)

    data class Section(val id: String, val label: String, val rows: List<Row>)

    /**
     * Group a fetch response into sections.
     *
     * The response is `{"schema": <configs/schema.json>, "bundle": {...}}`; a
     * bare bundle is accepted too. Section ORDER and LABELS come from
     * `schema.sections[]` — the vault declares them, so this app never has to
     * restate the six names. Without a schema the bundle's own top-level keys
     * are the sections, in document order. Keys starting with `_` and
     * `schema_version` are bookkeeping, not sections. A section the schema
     * names but the bundle lacks is shown empty rather than dropped, so a
     * missing section is visible.
     */
    fun sections(response: JSONObject): List<Section> {
        val bundle = response.optJSONObject("bundle") ?: response
        val declared = response.optJSONObject("schema")?.optJSONArray("sections")
        val order: List<Pair<String, String>> = if (declared != null) {
            (0 until declared.length()).map { i ->
                val s = declared.getJSONObject(i)
                s.getString("id") to s.optString("label", s.getString("id"))
            }
        } else {
            bundle.keys().asSequence()
                .filter { !it.startsWith("_") && it != "schema_version" }
                .map { it to it }
                .toList()
        }
        return order.map { (id, label) ->
            val rows = mutableListOf<Row>()
            if (bundle.has(id)) flatten(bundle.get(id), "", rows)
            Section(id, label, rows)
        }
    }

    /** A declared-but-empty value: `{"pending": true, "source": .., "reason": ..}`. */
    private fun isPending(v: Any?): Boolean =
        v is JSONObject && v.optBoolean("pending", false)

    private fun flatten(v: Any?, path: String, out: MutableList<Row>) {
        when {
            isPending(v) -> {
                val o = v as JSONObject
                out += Row(path, "pending · ${o.optString("source")} · ${o.optString("reason")}", true)
            }
            v is JSONObject && v.length() > 0 ->
                v.keys().forEach { k -> flatten(v.get(k), join(path, k), out) }
            v is JSONArray && v.length() > 0 ->
                (0 until v.length()).forEach { i -> flatten(v.get(i), join(path, "[$i]"), out) }
            v == null || v == JSONObject.NULL -> out += Row(path, "null", false)
            else -> out += Row(path, v.toString(), false)
        }
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path › $key"

    /** The last successful fetch, in memory only. */
    object Imported {
        @Volatile var last: List<Section>? = null
    }
}
