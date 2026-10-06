package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * #866 The fleet bearer the Store's Commits / CI-CD feeds send, for a host that
 * does not hold it itself (Cloud Store).
 *
 * The token lives in SuperApp (libs:ops DaguPrefs). SuperApp exposes it through
 * a read-only provider behind CONSTELLATION_DATA (the fleet's signature
 * permission, as AccountData.Provider is), and [resolve] reads it from there
 * when SuperApp is installed; otherwise it falls back to the token the user
 * typed into this app's own settings ([Own]). The token is never logged, never
 * echoed in an error, and a failed read is simply "no token" (the proxy answers
 * 401 and the feed falls back to its public url).
 */
object FleetBearer {
    const val SUPERAPP_PKG = "com.diegonmarcos.superapp"
    const val AUTHORITY = "$SUPERAPP_PKG.fleetbearer"
    const val METHOD_BEARER = "bearer"
    const val KEY_OK = "ok"
    const val KEY_TOKEN = "token"

    fun superAppInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(SUPERAPP_PKG, 0).enabled
    }.getOrDefault(false)

    /** SuperApp's token, or "" when it is not installed, refuses, holds none or fails. */
    fun fromSuperApp(ctx: Context): String {
        if (ctx.packageName == SUPERAPP_PKG || !superAppInstalled(ctx)) return ""
        val r = runCatching {
            ctx.contentResolver.call(Uri.parse("content://$AUTHORITY"), METHOD_BEARER, null, null)
        }.getOrNull()
        return if (r?.getBoolean(KEY_OK) == true) r.getString(KEY_TOKEN).orEmpty().trim() else ""
    }

    /** Which source answered, for the settings line; never the token. */
    enum class Source { SUPERAPP, OWN, NONE }

    fun source(ctx: Context): Source = when {
        fromSuperApp(ctx).isNotEmpty() -> Source.SUPERAPP
        Own(ctx).token.isNotBlank() -> Source.OWN
        else -> Source.NONE
    }

    /** SuperApp's token when it is installed and holds one, else this app's own. */
    fun resolve(ctx: Context): String = fromSuperApp(ctx).ifEmpty { Own(ctx).token.trim() }

    /** This app's own entry: encrypted, degrading to plain prefs like DaguPrefs does. */
    class Own(context: Context) {
        private val sp: SharedPreferences = try {
            val key = MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(context.applicationContext, "store_fleet_bearer", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (_: Throwable) {
            context.applicationContext.getSharedPreferences("store_fleet_bearer_fallback", Context.MODE_PRIVATE)
        }
        var token: String
            get() = sp.getString("token", "") ?: ""
            set(v) { sp.edit().putString("token", v.trim()).apply() }
    }
}
