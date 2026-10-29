package com.diegonmarcos.superapp.appstore

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Store ▸ Phone: the device side of [StoreSizes]. Measures off the main thread (the caller runs
 * [measure] on its worker), keeps every answer in a process-wide cache so a redraw, a filter or a
 * sort never re-queries, and drops a package's entry when the system says it changed
 * ([watch]: added / replaced / removed / data cleared). A grant flip clears the whole cache, so
 * "APK" figures become full ones the moment usage access is given.
 */
object StoreSizeProbe {

    /** The special access that lets StorageStatsManager answer for another package. */
    const val PERMISSION = "android.permission.PACKAGE_USAGE_STATS"

    private val cache = ConcurrentHashMap<String, StoreSizes.Size>()
    @Volatile private var cachedGrant: Boolean? = null

    /** Usage access, read from its app-op (the permission itself is always "declared, not granted"). */
    fun granted(ctx: Context): Boolean = runCatching {
        val ao = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** #1274 the catalogue feature (perms-catalogue.json) whose entries the Store's size column needs. */
    const val PERMS_FEATURE = "appstore"

    /** The "Open Permissions" action: the host's Permissions page on usage access. */
    fun openPermissions(ctx: Context) = com.diegonmarcos.superapp.perms.PermsLink.open(ctx, PERMS_FEATURE, listOf("usage_access"))

    /** What the cache holds for [pkg], or null (not measured, or not installed). Never blocks. */
    fun cached(pkg: String): StoreSizes.Size? = cache[pkg]

    /** BLOCKING: measure every package of [pkgs] the cache lacks. Call from a worker thread. */
    fun measure(ctx: Context, pkgs: Collection<String>): Map<String, StoreSizes.Size> {
        val g = granted(ctx)
        if (cachedGrant != g) { cache.clear(); cachedGrant = g }
        val ss = if (g) ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager else null
        for (pkg in pkgs) {
            if (cache.containsKey(pkg)) continue
            val s = (ss?.let { full(it, pkg) }) ?: apk(ctx, pkg) ?: continue
            cache[pkg] = s
        }
        return pkgs.mapNotNull { p -> cache[p]?.let { p to it } }.toMap()
    }

    private fun full(ss: StorageStatsManager, pkg: String): StoreSizes.Size? = runCatching {
        val s = ss.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
        StoreSizes.Size.full(s.appBytes, s.dataBytes, s.cacheBytes)
    }.getOrNull()

    /** The fallback: base.apk + every split, as the files' lengths. */
    private fun apk(ctx: Context, pkg: String): StoreSizes.Size? = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        val files = listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty().toList()
        StoreSizes.Size.apkOnly(files.map { File(it).length() })
    }.getOrNull()

    fun invalidate(pkg: String? = null) { if (pkg == null) cache.clear() else cache.remove(pkg) }

    /** Drop a package's size whenever it changes; [onChange] repaints. Returns the unregister. */
    fun watch(ctx: Context, onChange: (String) -> Unit): () -> Unit {
        val app = ctx.applicationContext
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pkg = i.data?.schemeSpecificPart ?: return
                invalidate(pkg); onChange(pkg)
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_DATA_CLEARED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(rx, f)
        return { runCatching { app.unregisterReceiver(rx) } }
    }
}
