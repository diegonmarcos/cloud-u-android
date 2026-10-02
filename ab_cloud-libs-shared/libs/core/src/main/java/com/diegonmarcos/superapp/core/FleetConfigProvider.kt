package com.diegonmarcos.superapp.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject

/**
 * #783 `<package>.fleetconfig` — the [FleetConfig] contract served by EVERY fleet app, merged in
 * from libs:core's manifest so no app writes a line for it. `call(export)` answers this app's
 * migrating configuration; `call(import, json)` writes a declared copy into it. Everything else
 * a ContentProvider can do is refused.
 *
 * Gated twice: the manifest entry is exported behind CONSTELLATION_DATA (the system refuses the
 * provider to any APK signed with another key), and [call] re-checks the caller, because the
 * framework does not permission-check `call()` by itself.
 */
class FleetConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return error("no context")
        if (Binder.getCallingUid() != Process.myUid() &&
            ctx.checkCallingPermission(FleetConfig.PERMISSION) != PackageManager.PERMISSION_GRANTED)
            return error("caller is not a fleet app")
        return runCatching {
            when (method) {
                FleetConfig.METHOD_EXPORT ->
                    FleetConfig.exportSelf(ctx)?.let { ok(it) } ?: error("${ctx.packageName} is not a declared fleet app")
                FleetConfig.METHOD_IMPORT -> {
                    val body = JSONObject(extras?.getString(FleetConfig.KEY_JSON) ?: return error("no body"))
                    val r = FleetConfig.importSelf(ctx, body)
                    // Restart once the reply is out, so singletons that cached the old values
                    // cannot write them back over the import. Never for a no-op import.
                    if (extras?.getBoolean(FleetConfig.KEY_RESTART) == true && r.optInt("written") > 0)
                        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, RESTART_DELAY_MS)
                    ok(r)
                }
                else -> error("unknown method $method")
            }
        }.getOrElse { error("${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}") }
    }

    private fun ok(json: JSONObject) = Bundle().apply { putString(FleetConfig.KEY_JSON, json.toString()) }
    private fun error(why: String) = Bundle().apply { putString(FleetConfig.KEY_ERROR, why) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val RESTART_DELAY_MS = 600L
    }
}
