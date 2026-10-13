package com.diegonmarcos.cloudaccount.hub

import org.json.JSONObject

/**
 * Profiles ▸ Apps: one row per fleet app with what the account knows of its configuration, from the Configs
 * section of the account vault (`{app: {store: {file: {key: value}}}}`, captured from each app's own export) and
 * the push plan. A credential is reported as present / absent only; an endpoint and a sync switch are shown
 * (they are not secret).
 */
object AppProfiles {

    data class Row(
        val id: String,
        val label: String,
        val pkg: String,
        val installed: Boolean,
        /** null = nothing captured that says; true / false = a credential key is set / empty. */
        val signedIn: Boolean?,
        val endpoint: String?,
        val sync: List<String>,
        val stores: List<String>,
        val captured: Int,
        val planned: Int,
    ) {
        val state: String get() = when {
            !installed -> "not installed"
            signedIn == true -> "signed in"
            signedIn == false -> "signed out"
            captured > 0 -> "configured"
            else -> "not captured"
        }
    }

    data class App(val id: String, val label: String, val pkg: String)

    private val ENDPOINT = Regex("(?i)(url|server|endpoint|host|base)$")
    private val SYNC = Regex("(?i)(sync|backup|auto_?(update|backup|sync)?|interval)")

    /** Every key of one app's captured configs as `store/file/key` -> value (`_types` and the like left out). */
    fun keys(app: JSONObject?): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        if (app == null) return out
        for (store in app.keys().asSequence().sorted()) {
            val files = app.optJSONObject(store) ?: continue
            for (file in files.keys().asSequence().sorted()) {
                val kv = files.optJSONObject(file) ?: continue
                for (k in kv.keys().asSequence().sorted()) {
                    if (k.startsWith("_")) continue
                    val v = kv.opt(k); if (v == null || v == JSONObject.NULL || v is JSONObject) continue
                    out["$store/$file/$k"] = v
                }
            }
        }
        return out
    }

    fun row(app: App, installed: Boolean, captured: JSONObject?, planned: Int): Row {
        val kv = keys(captured)
        val creds = kv.filterKeys { Providers.methodOf(it.substringAfterLast('/')) != null }
        val endpoint = kv.entries.firstOrNull { (k, v) -> ENDPOINT.containsMatchIn(k.substringAfterLast('/')) && v.toString().startsWith("http") }
            ?.value?.toString()
        val sync = kv.entries.filter { (k, v) -> SYNC.containsMatchIn(k.substringAfterLast('/')) && (v is Boolean || v is Number) }
            .map { (k, v) -> "${k.substringAfterLast('/')}=$v" }
        return Row(app.id, app.label, app.pkg, installed,
            signedIn = if (creds.isEmpty()) null else creds.values.any { it.toString().isNotBlank() },
            endpoint = endpoint, sync = sync, stores = captured?.keys()?.asSequence()?.sorted()?.toList().orEmpty(),
            captured = kv.size, planned = planned)
    }

    fun rows(apps: List<App>, installed: (String) -> Boolean, configs: JSONObject, planned: (String) -> Int): List<Row> =
        apps.map { a -> row(a, installed(a.pkg), configs.optJSONObject(a.id), planned(a.id)) }
}
