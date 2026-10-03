package com.diegonmarcos.superapp.fleetconfig

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * #825 [FleetPolicy] answered over IPC, for the `<package>.fleetconfig` provider of an app that
 * no longer compiles the policy. The provider owns the files (only its own process may open its
 * SharedPreferences); this side owns the decisions. Three calls, all JSON in, JSON out:
 *
 *   plan(manifest, pkg)                 → {"app", "files": [{"file", "kind"}]}  the files to dump
 *   export(manifest, pkg, dump)         → the wire format ([FleetPolicy.exportApp] over the dump)
 *   import(manifest, pkg, body, dump)   → {"result": <importApp's answer>,
 *                                          "writes": {"<file>": {"kind", "set": {…, "_types"}, "remove": […]}}}
 *
 * A DUMP is every key of each planned file in the wire encoding (natural JSON, `_types` for
 * i/l/f, an array for a string set). Export and import run the unchanged policy over in-memory
 * copies of those files ([MemPrefs]), so the provider applies exactly what the policy wrote.
 */
object FleetPolicyEngine {

    fun plan(manifest: String, pkg: String): String = withApp(manifest, pkg) { m, app ->
        val files = JSONArray()
        for (s in m.stores.values.sortedBy { it.name }) if (m.migrates(s, app.id))
            for (f in s.filesFor(app.pkg)) files.put(JSONObject().put("file", f).put("kind", s.kind))
        JSONObject().put("app", app.id).put("files", files)
    }

    fun export(manifest: String, pkg: String, dump: String): String = withApp(manifest, pkg) { m, app ->
        val files = JSONObject(dump)
        FleetPolicy.exportApp(m, app) { _, file, create ->
            files.optJSONObject(file)?.let { MemPrefs(decode(it)) } ?: if (create) MemPrefs(mutableMapOf()) else null
        }
    }

    fun import(manifest: String, pkg: String, body: String, dump: String): String = withApp(manifest, pkg) { m, app ->
        val files = JSONObject(dump)
        val opened = linkedMapOf<String, Pair<String, MemPrefs>>()
        val result = FleetPolicy.importApp(m, app, JSONObject(body)) { s, file, _ ->
            MemPrefs(files.optJSONObject(file)?.let(::decode) ?: mutableMapOf()).also { opened[file] = s.kind to it }
        }
        // Every file the policy opened, written to or not: the provider opens each one as the
        // device opener did, so a store it cannot open is reported even when nothing would land.
        val writes = JSONObject()
        for ((file, kp) in opened) {
            val (kind, p) = kp
            val set = encode(p.writes.filterValues { it != null })
            val remove = JSONArray(p.writes.filterValues { it == null }.keys.sorted())
            writes.put(file, JSONObject().put("kind", kind).put("set", set).put("remove", remove))
        }
        JSONObject().put("result", result).put("writes", writes)
    }

    private fun withApp(manifest: String, pkg: String, f: (FleetPolicy.Manifest, FleetPolicy.App) -> JSONObject): String {
        val m = FleetPolicy.Manifest(JSONObject(manifest))
        val app = m.appByPackage(pkg) ?: return JSONObject().put(FleetPolicy.KEY_ERROR, "$pkg is not a declared fleet app").toString()
        return f(m, app).toString()
    }

    /** The wire encoding of typed values: natural JSON plus `_types` for the numbers. */
    fun encode(values: Map<String, Any?>): JSONObject {
        val out = JSONObject(); val types = JSONObject()
        for ((k, v) in values.toSortedMap()) when (v) {
            is Int -> { out.put(k, v); types.put(k, "i") }
            is Long -> { out.put(k, v); types.put(k, "l") }
            is Float -> { out.put(k, v.toDouble()); types.put(k, "f") }
            is Set<*> -> out.put(k, JSONArray(v.map { it.toString() }.sorted()))
            null -> {}
            else -> out.put(k, v)
        }
        if (types.length() > 0) out.put(FleetPolicy.TYPES, types)
        return out
    }

    fun decode(o: JSONObject): MutableMap<String, Any?> {
        val types = o.optJSONObject(FleetPolicy.TYPES) ?: JSONObject()
        val out = mutableMapOf<String, Any?>()
        for (k in o.keys()) {
            if (k == FleetPolicy.TYPES) continue
            val v = o.get(k)
            out[k] = when {
                v is JSONArray -> (0 until v.length()).map { v.optString(it) }.toSet()
                v is Number -> when (types.optString(k)) {
                    "i" -> v.toInt(); "l" -> v.toLong(); "f" -> v.toFloat()
                    else -> if (v.toDouble() % 1.0 != 0.0) v.toFloat() else v.toLong()
                }
                else -> v
            }
        }
        return out
    }

    /** SharedPreferences over a map, recording every committed write ([writes]: key → value, null = removed). */
    class MemPrefs(private val map: MutableMap<String, Any?>) : SharedPreferences {
        val writes = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String?) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = linkedMapOf<String, Any?>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor = also { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = also { pending[key] = values?.toSet() }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = also { pending[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = also { pending[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = also { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = also { pending[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = also { pending[key] = null }
            override fun clear(): SharedPreferences.Editor = also { map.keys.forEach { k -> pending[k] = null } }
            override fun commit(): Boolean {
                for ((k, v) in pending) { if (v == null) map.remove(k) else map[k] = v; writes[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
