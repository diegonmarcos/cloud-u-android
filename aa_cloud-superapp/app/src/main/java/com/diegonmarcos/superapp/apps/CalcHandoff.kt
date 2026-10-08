package com.diegonmarcos.superapp.apps

import android.content.Context
import android.content.Intent

/**
 * The Notify quick-action calculator opens OUR calculator, Cloud Calc
 * (ac_cloud-calc, applicationId com.diegonmarcos.cloudcalc), never the
 * system one. Absent, it hands off to Cloud Store (which has no per-row
 * target, so it opens on its Cloud tab where the Calc row is); with neither
 * installed, SuperApp's own Store installs it. No <queries> entry is needed:
 * QUERY_ALL_PACKAGES is held, so the package is always visible.
 */
object CalcHandoff {
    const val PKG = "com.diegonmarcos.cloudcalc"
    /** Cloud Store tab that lists the fleet's apps (CloudStoreHandoff.TAB_OF_PAGE). */
    const val STORE_TAB = "cloud"

    enum class Target { CLOUD_CALC, CLOUD_STORE, SUPERAPP_STORE }

    /** Pure routing: Cloud Calc if installed, else Cloud Store if installed, else SuperApp's Store. */
    fun decide(calcInstalled: Boolean, storeInstalled: Boolean): Target = when {
        calcInstalled -> Target.CLOUD_CALC
        storeInstalled -> Target.CLOUD_STORE
        else -> Target.SUPERAPP_STORE
    }

    fun installed(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(PKG, 0).enabled
    }.getOrDefault(false)

    /** Where the tap goes now; the caller performs it. */
    fun target(ctx: Context): Target = decide(installed(ctx), CloudStoreHandoff.installed(ctx))

    /** Launches Cloud Calc; false when it will not start. */
    fun launch(ctx: Context): Boolean = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
