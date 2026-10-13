package com.diegonmarcos.clouddrive.manage

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import com.diegonmarcos.cloudlib.disk.AppSizes
import com.diegonmarcos.superapp.adbdebug.ShellChannels

/**
 * Manage ▸ Apps' reader: every installed app with its sizes, running state and RAM, from the best
 * source this phone allows. Blocking; call off the main thread.
 *
 *  - sizes: StorageStatsManager.queryStatsForPackage. Usage access (PACKAGE_USAGE_STATS, granted in
 *    Settings) is needed for every package but this one; without it those cells are null ("—").
 *  - running + RAM: ActivityManager.runningAppProcesses + getProcessMemoryInfo (PSS). Since Android
 *    5.1 that lists only this app's own processes, so for everyone else the fleet's privileged shell
 *    channel (libs:shizuku-adb-debug-tools: the SuperApp bridge, our embedded adb, or Shizuku) answers
 *    `ps -A -o NAME` and `dumpsys meminfo` when one is up. Otherwise the cells are null ("—").
 */
object AppSnapshot {
    data class Snapshot(val rows: List<AppRow>, val usageAccess: Boolean, val shell: String?)

    fun collect(ctx: Context, useShell: Boolean = true): Snapshot {
        val pm = ctx.packageManager
        val usage = AppSizes.hasUsageAccess(ctx)
        val ss = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val apps = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
        val own = runCatching {
            val procs = am.runningAppProcesses.orEmpty()
            val infos = am.getProcessMemoryInfo(procs.map { it.pid }.toIntArray())
            procs.mapIndexed { i, p -> p.pkgList.orEmpty().map { it to infos[i].totalPss.toLong() } }.flatten()
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        }.getOrDefault(emptyMap())
        val rows = apps.map { ai ->
            val pkg = ai.packageName
            val sizes = if (usage || pkg == ctx.packageName) runCatching {
                val s = ss.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                Triple(s.appBytes, (s.dataBytes - s.cacheBytes).coerceAtLeast(0), s.cacheBytes)
            }.getOrNull() else null
            AppRow(
                pkg = pkg,
                label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg),
                system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                appBytes = sizes?.first, dataBytes = sizes?.second, cacheBytes = sizes?.third,
                running = if (own.containsKey(pkg)) true else null,
                ramKb = own[pkg],
            )
        }
        if (!useShell) return Snapshot(AppTable.sort(rows), usage, null)
        val channel = runCatching { ShellChannels.active(ctx) }.getOrNull()
        val ps = channel?.let { c -> runCatching { c.exec(ctx, "ps -A -o NAME") }.getOrNull() }?.let(AppTable::parsePs)?.takeIf { it.isNotEmpty() }
        val mem = channel?.let { c -> runCatching { c.exec(ctx, "dumpsys meminfo") }.getOrNull() }?.let(AppTable::parseMeminfo)?.takeIf { it.isNotEmpty() }
        return Snapshot(AppTable.sort(AppTable.withShell(rows, ps, mem)), usage, channel?.let { runCatching { it.name() }.getOrNull() })
    }
}
