package com.diegonmarcos.cloudlib.disk

import android.app.ActivityManager
import android.content.Context

/**
 * RAM: the device's total and available memory (ActivityManager.MemoryInfo), and the PSS of every
 * running process this app is allowed to see. Since Android 5.1 runningAppProcesses lists only
 * the caller's own processes, so a fleet app's process is reported as `readable=false` rather than
 * guessed — the honest answer for an unrooted phone.
 */
object Memory {
    data class Proc(val pkg: String, val process: String?, val pssKb: Int?, val readable: Boolean)
    data class Snapshot(val totalBytes: Long, val availableBytes: Long, val thresholdBytes: Long, val low: Boolean, val processes: List<Proc>)

    fun snapshot(ctx: Context, fleet: List<String>): Snapshot {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val running = runCatching { am.runningAppProcesses.orEmpty() }.getOrDefault(emptyList())
        val pss = runCatching {
            val infos = am.getProcessMemoryInfo(running.map { it.pid }.toIntArray())
            running.mapIndexed { i, p -> p to infos[i].totalPss }
        }.getOrDefault(emptyList())
        val seen = pss.flatMap { (p, kb) -> p.pkgList.orEmpty().map { pkg -> Proc(pkg, p.processName, kb, true) } }
        val seenPkgs = seen.map { it.pkg }.toSet()
        val unseen = fleet.filter { it !in seenPkgs }.distinct().map { Proc(it, null, null, false) }
        return Snapshot(mi.totalMem, mi.availMem, mi.threshold, mi.lowMemory, seen + unseen)
    }
}
