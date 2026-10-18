package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.updater.Fleet

/**
 * Configs > Network > Webserver. Opens the Cloud C3 Webserver app with its own explicit launch intent; the package is
 * read from the fleet manifest (data/constellation-fleet.json via BuildConfig), never spelled here. When that app is
 * not installed it opens [FALLBACK_URL] in Cloud Browser (also a manifest package). Nothing else is installed or asked.
 */
object WebserverLaunch {
    const val FLEET_ID = "c3-webserver"
    const val BROWSER_FLEET_ID = "browser"
    const val FALLBACK_URL = "http://localhost:8002"

    enum class Opened { APP, BROWSER, NONE }

    /** The package the fleet manifest declares for [fleetId], or null. */
    fun fleetPackage(fleetId: String, manifestB64: String = BuildConfig.CONSTELLATION_FLEET_B64): String? =
        runCatching { Fleet.parse(manifestB64).firstOrNull { it.id == fleetId }?.pkg }.getOrNull()?.takeIf { it.isNotBlank() }

    fun open(ctx: Context): Opened {
        val pm = ctx.packageManager
        fleetPackage(FLEET_ID)?.let { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launch != null && runCatching { ctx.startActivity(launch) }.isSuccess) return Opened.APP
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(FALLBACK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fleetPackage(BROWSER_FLEET_ID)?.let { view.setPackage(it) }
        return if (runCatching { ctx.startActivity(view) }.isSuccess) Opened.BROWSER else Opened.NONE
    }
}
