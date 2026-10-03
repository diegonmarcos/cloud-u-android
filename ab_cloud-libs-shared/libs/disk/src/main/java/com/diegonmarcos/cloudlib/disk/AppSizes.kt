package com.diegonmarcos.cloudlib.disk

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings

/**
 * Per-app sizes through StorageStatsManager: the APK (and its splits), the data and the cache,
 * for each package asked about. Another package's numbers need the usage-access special grant,
 * held by the CALLING app — [hasUsageAccess] says whether it is, and [grantIntents] is the
 * standard way to give it (#639: the per-package toggle first, the system list as the fallback;
 * the page shows a button, never a sentence telling the user where to go).
 */
object AppSizes {
    data class Size(val pkg: String, val apkBytes: Long, val dataBytes: Long, val cacheBytes: Long, val error: String? = null) {
        val totalBytes: Long get() = apkBytes + dataBytes + cacheBytes
    }

    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** The settings screens that grant usage access, most specific first; start the first that resolves. */
    fun grantIntents(ctx: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /**
     * Sizes of [packages]. This app's own size needs no grant; any other package without the grant
     * answers with [Size.error] instead of a zero that would read as "empty".
     */
    fun sizes(ctx: Context, packages: List<String>): List<Size> {
        val ss = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val granted = hasUsageAccess(ctx)
        return packages.distinct().map { pkg ->
            if (!granted && pkg != ctx.packageName) return@map Size(pkg, 0, 0, 0, "usage access not granted")
            runCatching {
                val s = ss.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                // dataBytes INCLUDES the cache; report the two apart so they add up to the total.
                Size(pkg, s.appBytes, (s.dataBytes - s.cacheBytes).coerceAtLeast(0), s.cacheBytes)
            }.getOrElse { Size(pkg, 0, 0, 0, if (it is android.content.pm.PackageManager.NameNotFoundException) "not installed" else (it.message ?: it.javaClass.simpleName)) }
        }.sortedByDescending { it.totalBytes }
    }
}
