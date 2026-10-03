package com.diegonmarcos.superapp.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
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
 * #783 `<package>.fleetconfig` — the [FleetConfig] contract served by EVERY fleet app, merged in
 * from libs:core's manifest so no app writes a line for it. `call(export)` answers this app's
 * migrating configuration; `call(import, json)` writes a declared copy into it; `call(hello)`
 * is the handshake. Everything else a ContentProvider can do is refused.
 *
 * #825 the in-process half only: it reads and writes this app's own SharedPreferences, the one
 * thing no other process can do. Which files to read, which keys leave and what an import may
 * write are asked of the policy engine ([FleetConfigEngine]: plan, then export or import over a
 * dump of the planned files), and the engine's writes are applied here verbatim.
 *
 * Gated twice: exported behind CONSTELLATION_DATA, and [call] re-checks the caller, because the
 * framework does not permission-check `call()` by itself.
 */
class FleetConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return error("no context")
        if (Binder.getCallingUid() != Process.myUid() &&
            ctx.checkCallingPermission(FleetConfig.PERMISSION) != PackageManager.PERMISSION_GRANTED)
            return error("caller is not a fleet app")
        val own = FleetConfig.manifestText(ctx)
        if (method == FleetConfig.METHOD_HELLO) return ok(JSONObject().put(FleetConfig.KEY_CONTRACT, FleetConfig.CONTRACT)
            .put(FleetConfig.KEY_MANIFEST, if (own != null) "self" else "caller"))
        if (method != FleetConfig.METHOD_EXPORT && method != FleetConfig.METHOD_IMPORT) return error("unknown method $method")
        return runCatching {
            // The caller's manifest first; this build's own (the SuperApp) second; nothing third.
            val m = extras?.getString(FleetConfig.KEY_MANIFEST) ?: own
                ?: return error("no manifest: this build carries none and the call brought none (contract ${FleetConfig.CONTRACT} — update the caller)")
            val pkg = ctx.packageName
            val plan = engine(ctx, FleetConfigEngine.PLAN, arrayOf(m, pkg))
            plan.optString(FleetConfig.KEY_ERROR).takeIf { it.isNotEmpty() }?.let { return error(it) }
            val files = plan.getJSONArray("files")
            val dump = JSONObject()
            for (i in 0 until files.length()) files.getJSONObject(i).let { f ->
                runCatching { FleetConfig.openStore(ctx, f.getString("kind"), f.getString("file"), false) }.getOrNull()
                    ?.let { dump.put(f.getString("file"), encode(it.all)) }
            }
            if (method == FleetConfig.METHOD_EXPORT) {
                val r = engine(ctx, FleetConfigEngine.EXPORT, arrayOf(m, pkg, dump.toString()))
                return r.optString(FleetConfig.KEY_ERROR).takeIf { it.isNotEmpty() }?.let { error(it) } ?: ok(r)
            }
            val body = extras?.getString(FleetConfig.KEY_JSON) ?: return error("no body")
            val out = engine(ctx, FleetConfigEngine.IMPORT, arrayOf(m, pkg, body, dump.toString()))
            out.optString(FleetConfig.KEY_ERROR).takeIf { it.isNotEmpty() }?.let { return error(it) }
            val r = out.getJSONObject("result")
            apply(ctx, out.optJSONObject("writes") ?: JSONObject(), r)
            // Restart once the reply is out, so singletons that cached the old values
            // cannot write them back over the import. Never for a no-op import.
            if (extras?.getBoolean(FleetConfig.KEY_RESTART) == true && r.optInt("written") > 0)
                Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, RESTART_DELAY_MS)
            ok(r)
        }.getOrElse { error("${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}") }
    }

    /** The engine's writes, file by file; a file that cannot be opened or committed is reported
     *  in [r] (its keys leave `written`), the rest still land. */
    private fun apply(ctx: Context, writes: JSONObject, r: JSONObject) {
        val files = r.optJSONObject("files") ?: return
        for (file in writes.keys()) {
            val w = writes.getJSONObject(file)
            val why = runCatching {
                val p = FleetConfig.openStore(ctx, w.getString("kind"), file, true) ?: return@runCatching "cannot open"
                val ed = p.edit()
                val set = w.optJSONObject("set") ?: JSONObject()
                val types = set.optJSONObject(FleetConfig.TYPES) ?: JSONObject()
                for (k in set.keys()) if (k != FleetConfig.TYPES) put(ed, k, set.get(k), types.optString(k))
                w.optJSONArray("remove")?.let { a -> for (i in 0 until a.length()) ed.remove(a.getString(i)) }
                if (ed.commit()) null else "commit failed"
            }.getOrElse { "cannot open" } ?: continue
            val lost = files.optJSONObject(file)?.optJSONArray("written")?.length() ?: 0
            files.put(file, JSONObject().put(FleetConfig.KEY_ERROR, why))
            r.put("written", r.optInt("written") - lost)
        }
    }

    private fun put(ed: SharedPreferences.Editor, k: String, v: Any, t: String) {
        when {
            v is JSONArray -> ed.putStringSet(k, (0 until v.length()).map { v.optString(it) }.toSet())
            v is Boolean -> ed.putBoolean(k, v)
            v is Number -> when (t) { "i" -> ed.putInt(k, v.toInt()); "f" -> ed.putFloat(k, v.toFloat()); else -> ed.putLong(k, v.toLong()) }
            else -> ed.putString(k, v.toString())
        }
    }

    /** Every value in the wire encoding: natural JSON, `_types` for the numbers. */
    private fun encode(all: Map<String, *>): JSONObject {
        val out = JSONObject(); val types = JSONObject()
        for ((k, v) in all) when (v) {
            is Int -> { out.put(k, v); types.put(k, "i") }
            is Long -> { out.put(k, v); types.put(k, "l") }
            is Float -> { out.put(k, v.toDouble()); types.put(k, "f") }
            is Set<*> -> out.put(k, JSONArray(v.map { it.toString() }))
            null -> {}
            else -> out.put(k, v)
        }
        if (types.length() > 0) out.put(FleetConfig.TYPES, types)
        return out
    }

    private fun ok(json: JSONObject) = Bundle().apply { putString(FleetConfig.KEY_JSON, json.toString()) }
    private fun error(why: String) = Bundle().apply { putString(FleetConfig.KEY_ERROR, why) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val RESTART_DELAY_MS = 600L

        /** The policy engine; a JVM test hands in an in-process one (the SuperApp's FleetConfigTest). */
        @Volatile var engine: (Context, String, Array<String>) -> JSONObject =
            { ctx, method, args -> FleetConfigEngine.call(ctx, method, *args) }
    }
}
