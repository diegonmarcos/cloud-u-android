package com.diegonmarcos.superapp.fleetconfig

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * #873 `<package>.fleetsetup`: the [SetupContract] every fleet app serves, merged in from this
 * module's manifest. Exported behind CONSTELLATION_DATA and re-checked in [call], because the
 * framework does not permission-check call() by itself. Everything else a ContentProvider can do
 * is refused. Declarations come from [FleetPolicy] over the baked fleet-config.json; reads and
 * writes go through [SetupStores].
 *
 * Never logs a value: results carry key names and reasons only.
 */
class SetupProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return error("no context")
        val caller = callerPackage(ctx)
        if (Binder.getCallingUid() != Process.myUid() &&
            ctx.checkCallingPermission(SetupContract.PERMISSION) != PackageManager.PERMISSION_GRANTED)
            return error("caller is not a fleet app")
        return runCatching {
            when (method) {
                SetupContract.METHOD_DESCRIBE -> ok(describe(ctx))
                SetupContract.METHOD_EXPORT -> ok(export(ctx, caller, arg))
                SetupContract.METHOD_STATUS -> ok(status(ctx))
                SetupContract.METHOD_APPLY -> {
                    val r = apply(ctx, caller, arg ?: return error("apply needs a store name"),
                        JSONObject(extras?.getString(SetupContract.KEY_JSON) ?: return error("no body")))
                    if (extras.getBoolean(SetupContract.KEY_RESTART) && r.optInt("written") > 0 && caller != ctx.packageName)
                        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, RESTART_DELAY_MS)
                    ok(r)
                }
                else -> error("unknown method $method")
            }
        }.getOrElse { error("${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}") }
    }

    // ── describe ─────────────────────────────────────────────────────────

    private class Declared(val store: FleetPolicy.Store, val app: FleetPolicy.App, val m: FleetPolicy.Manifest) {
        val files = store.filesFor(app.pkg)
        val served get() = SetupStores.handlerFor(store.name, store.kind) != null
    }

    /** The migrating stores this build declares for itself: used by its module or its libs, config or secret class. */
    private fun declared(ctx: Context): Pair<FleetPolicy.App?, List<Declared>> {
        val m = manifest(ctx) ?: return null to emptyList()
        val app = m.appByPackage(ctx.packageName) ?: return null to emptyList()
        val mods = app.libs.toSet() + app.module
        return app to m.stores.values.filter { s -> s.usedBy.any { it in mods } && m.classOf(s, app.id) in m.migrate }
            .sortedBy { it.name }.map { Declared(it, app, m) }
    }

    fun describe(ctx: Context): JSONObject {
        val (app, stores) = declared(ctx)
        val out = JSONObject().put("setup", SetupContract.VERSION).put("package", ctx.packageName).put("declared", app != null)
        if (app == null) return out.put("stores", JSONArray())
        out.put("app", app.id).put("schema_version", app.schema)
        val arr = JSONArray()
        for (d in stores) {
            val keys = JSONObject()
            for ((k, c) in d.store.keys) keys.put(k, c)
            val e = JSONObject().put("name", d.store.name).put("kind", d.store.kind).put("class", d.m.classOf(d.store, app.id))
                .put("files", JSONArray(d.files)).put("served", d.served).put("keys", keys)
            if (!d.served) e.put("why", "${d.store.kind} store: no handler registered (SetupStores.register)")
            arr.put(e)
        }
        return out.put("stores", arr)
    }

    // ── export ───────────────────────────────────────────────────────────

    private fun export(ctx: Context, caller: String, only: String?): JSONObject {
        val (app, stores) = declared(ctx)
        val out = JSONObject()
        val result = JSONObject().put("setup", SetupContract.VERSION).put("app", app?.id ?: "").put("stores", out)
        for (d in stores) {
            if (only != null && d.store.name != only) continue
            val h = SetupStores.handlerFor(d.store.name, d.store.kind) ?: continue
            val files = JSONObject()
            for (file in d.files) {
                val all = runCatching { h.read(ctx, file) }.getOrNull() ?: continue
                val keep = all.filter { (k, v) ->
                    v != null && d.m.migratesKeyAny(d, k) &&
                        SetupStores.authorizer.allow(ctx, caller, SetupStores.OP_EXPORT, d.store.name, k, d.m.secret(d.store, k, d.app.id))
                }
                if (keep.isNotEmpty()) files.put(file, FleetPolicyEngine.encode(keep))
            }
            if (files.length() > 0) out.put(d.store.name, files)
        }
        return result
    }

    /** migratesKey without the portable-kind gate: a registered handler can serve a non-portable kind. */
    private fun FleetPolicy.Manifest.migratesKeyAny(d: Declared, key: String) = keyClass(d.store, key, d.app.id) in migrate

    // ── apply ────────────────────────────────────────────────────────────

    private fun apply(ctx: Context, caller: String, storeName: String, body: JSONObject): JSONObject {
        val res = JSONObject().put("store", storeName).put("ok", false).put("written", 0)
        val keys = JSONObject(); res.put("keys", keys)
        val d = declared(ctx).second.firstOrNull { it.store.name == storeName }
            ?: return res.put("why", "not a declared migrating store of this app")
        val file = body.optString("file").ifEmpty { d.files.first() }
        res.put("file", file)
        if (file !in d.files) return res.put("why", "not a file of $storeName")
        val h = SetupStores.handlerFor(d.store.name, d.store.kind) ?: return res.put("why", "${d.store.kind} store has no handler")
        val values = body.optJSONObject("values") ?: JSONObject()
        val types = values.optJSONObject(FleetPolicy.TYPES) ?: JSONObject()
        val removes = body.optJSONArray("remove")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val existing = runCatching { h.read(ctx, file) }.getOrNull().orEmpty()
        val typed = FleetPolicyEngine.decode(JSONObject(values.toString())).toMutableMap()
        // An untyped number takes the type the key already holds (a SharedPreferences getInt on a Long throws);
        // a key the app has not written yet gets the FleetPolicy default: Int when it fits, Float when fractional.
        for ((k, v) in typed.entries.toList()) if (v is Number && !types.has(k)) typed[k] = when (existing[k]) {
            is Int -> v.toInt(); is Float -> v.toFloat(); is Long -> v.toLong()
            else -> if (v.toDouble() % 1.0 != 0.0) v.toFloat() else if (v.toLong() in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v.toLong()
        }
        var refused = 0
        fun check(k: String, incoming: Any?) {
            val why = when {
                !d.m.migratesKeyAny(d, k) -> "${d.m.keyClass(d.store, k, d.app.id)} key: never migrates"
                !SetupStores.authorizer.allow(ctx, caller, SetupStores.OP_APPLY, d.store.name, k, d.m.secret(d.store, k, d.app.id)) -> "no grant for $caller"
                incoming != null && existing[k] != null && !compatible(existing[k], incoming, types.optString(k)) -> "type mismatch"
                else -> null
            }
            if (why != null) refused++
            keys.put(k, JSONObject().put("ok", why == null).also { if (why != null) it.put("why", why) })
        }
        for ((k, v) in typed) check(k, v)
        for (k in removes) check(k, null)
        if (refused > 0) return res.put("why", "$refused key(s) refused: nothing written")
        val ok = runCatching { h.write(ctx, file, typed, removes.toSet()) }.getOrDefault(false)
        if (!ok) {
            for (k in keys.keys().asSequence().toList()) keys.put(k, JSONObject().put("ok", false).put("why", "commit failed"))
            res.put("why", "commit failed")
        } else res.put("ok", true).put("written", typed.size + removes.size)
        record(ctx, storeName, file, ok, typed.size + removes.size)
        return res
    }

    private fun compatible(old: Any?, new: Any?, type: String): Boolean = when (old) {
        is Boolean -> new is Boolean
        is Number -> new is Number
        is Set<*> -> new is Set<*>
        is String -> new is String
        else -> true
    }

    // ── status ───────────────────────────────────────────────────────────

    private fun record(ctx: Context, store: String, file: String, ok: Boolean, written: Int) {
        ctx.getSharedPreferences(STATUS_FILE, Context.MODE_PRIVATE).edit()
            .putString(K_LAST, JSONObject().put("store", store).put("file", file).put("at", System.currentTimeMillis()).put("ok", ok).put("written", written).toString())
            .apply()
    }

    private fun status(ctx: Context): JSONObject {
        val (_, stores) = declared(ctx)
        val last = ctx.getSharedPreferences(STATUS_FILE, Context.MODE_PRIVATE).getString(K_LAST, null)
        return JSONObject().put("setup", SetupContract.VERSION).put("stores", stores.size).put("served", stores.count { it.served })
            .put("last_apply", last?.let { JSONObject(it) } ?: JSONObject.NULL)
    }

    // ── plumbing ─────────────────────────────────────────────────────────

    private fun callerPackage(ctx: Context): String =
        ctx.packageManager.getPackagesForUid(Binder.getCallingUid())?.firstOrNull() ?: ctx.packageName

    private fun ok(json: JSONObject) = Bundle().apply { putString(SetupContract.KEY_JSON, json.toString()) }
    private fun error(why: String) = Bundle().apply { putString(SetupContract.KEY_ERROR, why) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val RESTART_DELAY_MS = 600L
        private const val STATUS_FILE = "fleet_setup_status"
        private const val K_LAST = "last_apply"

        /** The declaration this build answers from; a JVM test hands in a sample. */
        @Volatile var manifestSource: (Context) -> FleetPolicy.Manifest? = { FleetPolicy.manifestOrNull(it) }
        private fun manifest(ctx: Context) = manifestSource(ctx)
    }
}
