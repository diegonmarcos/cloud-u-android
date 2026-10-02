package com.diegonmarcos.superapp.notificationcenter

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.diegonmarcos.superapp.core.FleetAlerts

/**
 * #777 — the receiving end of [FleetAlerts]: every fleet app's alert arrives
 * here as `call(FleetAlerts.METHOD_RAISE, …)`, is kept in [AlertStore] and
 * drawn by [AlertsNotifier].
 *
 * A provider and not a receiver because a provider call starts this process
 * when it is not running and answers synchronously, so the raising app knows
 * whether to fall back. Guarded in the manifest by CONSTELLATION_DATA
 * (signature): only the fleet's own key can call it.
 *
 * WHO raised it is [getCallingPackage] — the platform's answer — never a field
 * of the bundle, so one app cannot file an alert under another's name.
 */
class FleetAlertsProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val caller = callingPackage ?: return null
        var fresh: String? = null
        val ok = when (method) {
            FleetAlerts.METHOD_RAISE -> {
                val a = FleetAlerts.Alert.fromBundle(extras ?: return null)
                if (a.title.isBlank()) false else { fresh = AlertStore.add(ctx, caller, a).id; true }
            }
            FleetAlerts.METHOD_WITHDRAW -> {
                AlertStore.withdraw(ctx, caller, extras?.getString(FleetAlerts.KEY_DEDUPE).orEmpty()); true
            }
            else -> return null
        }
        Log.i(TAG, "$method from $caller: ok=$ok")
        if (ok) runCatching { AlertsNotifier.refresh(ctx, fresh) }
        return Bundle().apply { putBoolean(FleetAlerts.KEY_OK, ok) }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private companion object { const val TAG = "FleetAlertsProvider" }
}
