package com.diegonmarcos.clouddrive.mounts

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * What a mount needs from the system to LAST, and the one-tap fix for each gap. The decision is pure
 * ([missing]) so it is unit-tested; [facts] and [fixIntent] are the thin Android half.
 */
object MountPermissions {
    enum class Need { NOTIFICATIONS, BATTERY_OPTIMIZATION, OEM_BATTERY_GUIDE }

    data class Facts(
        val sdk: Int,
        val notificationsGranted: Boolean,
        val ignoringBatteryOptimizations: Boolean,
        val manufacturer: String,
        /** The user confirmed the OEM guide was followed; the OS gives no API to read it. */
        val oemGuideConfirmed: Boolean,
    )

    /** The gaps, in the order the user should fix them. */
    fun missing(f: Facts, policy: MountPolicy): List<Need> = buildList {
        if (f.sdk >= 33 && !f.notificationsGranted) add(Need.NOTIFICATIONS)
        if (!f.ignoringBatteryOptimizations) add(Need.BATTERY_OPTIMIZATION)
        if (policy.guideFor(f.manufacturer) != null && !f.oemGuideConfirmed) add(Need.OEM_BATTERY_GUIDE)
    }

    private const val PREFS = "mount-keeper"
    private const val OEM_CONFIRMED = "oem_guide_confirmed"

    fun facts(ctx: Context): Facts {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val notif = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return Facts(
            sdk = Build.VERSION.SDK_INT,
            notificationsGranted = notif,
            ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            manufacturer = Build.MANUFACTURER ?: "",
            oemGuideConfirmed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(OEM_CONFIRMED, false),
        )
    }

    fun confirmOemGuide(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(OEM_CONFIRMED, true).apply()

    /**
     * The intent that fixes [need]. NOTIFICATIONS is a runtime permission: the caller requests
     * Manifest.permission.POST_NOTIFICATIONS through its launcher and uses this only as the fallback
     * (app notification settings) once the user has denied it for good.
     */
    @SuppressLint("BatteryLife")   // a file manager holding live remote mounts is a declared exemption use
    fun fixIntent(ctx: Context, need: Need, policy: MountPolicy): Intent = when (need) {
        Need.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        Need.BATTERY_OPTIMIZATION -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
        Need.OEM_BATTERY_GUIDE -> {
            val guide = policy.guideFor(Build.MANUFACTURER ?: "")
            val cn = guide?.intentComponent?.takeIf { it.contains('/') }?.let { ComponentName.unflattenFromString(it) }
            val oem = cn?.let { Intent().setComponent(it) }
            if (oem != null && oem.resolveActivity(ctx.packageManager) != null) oem
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
        }
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
