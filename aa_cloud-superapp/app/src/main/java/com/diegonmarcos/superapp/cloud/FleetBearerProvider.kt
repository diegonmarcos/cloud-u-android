package com.diegonmarcos.superapp.cloud

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.diegonmarcos.superapp.appstore.FleetBearer
import com.diegonmarcos.superapp.core.FleetConfig
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs

/**
 * #866 The fleet bearer (libs:ops DaguPrefs) for Cloud Store's Commits / CI-CD
 * feeds. READ-ONLY: one method, [FleetBearer.METHOD_BEARER]; nothing a caller
 * sends is written. Exported behind CONSTELLATION_DATA (signature) and
 * re-checked in call(), because the framework does not permission-check call()
 * by itself - the AccountData.Provider pattern. The token is never logged.
 */
class FleetBearerProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return refuse()
        if (Binder.getCallingUid() != Process.myUid() &&
            ctx.checkCallingPermission(FleetConfig.PERMISSION) != PackageManager.PERMISSION_GRANTED) return refuse()
        if (method != FleetBearer.METHOD_BEARER) return refuse()
        val token = runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("")
        return Bundle().apply { putBoolean(FleetBearer.KEY_OK, true); putString(FleetBearer.KEY_TOKEN, token) }
    }

    private fun refuse() = Bundle().apply { putBoolean(FleetBearer.KEY_OK, false) }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
