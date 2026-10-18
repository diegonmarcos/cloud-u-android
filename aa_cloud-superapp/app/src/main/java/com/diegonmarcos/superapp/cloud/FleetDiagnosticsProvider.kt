package com.diegonmarcos.superapp.cloud

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.diegonmarcos.superapp.configs.Diagnostics
import com.diegonmarcos.superapp.core.FleetConfig
import com.diegonmarcos.superapp.devcontrol.DevControlServer
import com.diegonmarcos.superapp.devtools.LogPipe

/**
 * `<package>.fleetdiag`: this app's About, Logcat and Diagnose for another fleet app on the same phone
 * (cloud-c3-webserver's cloud.app page). READ-ONLY: [FleetDiagnostics.METHODS] and nothing else; the reply is a
 * Bundle {ok, mime, payload, error}. Exported behind CONSTELLATION_DATA (signature) and re-checked in call(),
 * because the framework does not permission-check call() by itself - the FleetBearerProvider pattern.
 *
 * The data is the same object the debug API serves (the system/about and diagnostics routes): one reader per
 * datum, here only wrapped for IPC. Logcat comes from the shared [LogPipe] stream, so it never execs its own
 * `logcat` (each exec would be another consent prompt).
 */
class FleetDiagnosticsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return refuse("no context")
        if (Binder.getCallingUid() != Process.myUid() &&
            ctx.checkCallingPermission(FleetConfig.PERMISSION) != PackageManager.PERMISSION_GRANTED) return refuse("caller is not a fleet app")
        val src = FleetDiagnostics.Source(
            about = { DevControlServer.aboutJson() },
            logcat = { n, all, errors ->
                LogPipe.tail(n = n, uid = if (all) LogPipe.ANY_UID else Process.myUid(), errorsOnly = errors)
            },
            diagnose = { Diagnostics.collect(ctx.applicationContext).toString() },
        )
        val a = FleetDiagnostics.answer(method, arg, src)
        return Bundle().apply {
            putBoolean(FleetDiagnostics.KEY_OK, a.ok)
            putString(FleetDiagnostics.KEY_MIME, a.mime)
            putString(FleetDiagnostics.KEY_PAYLOAD, a.payload)
            putString(FleetDiagnostics.KEY_ERROR, a.error)
        }
    }

    private fun refuse(why: String) = Bundle().apply { putBoolean(FleetDiagnostics.KEY_OK, false); putString(FleetDiagnostics.KEY_ERROR, why) }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
