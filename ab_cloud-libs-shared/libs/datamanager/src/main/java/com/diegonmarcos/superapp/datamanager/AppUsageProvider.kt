package com.diegonmarcos.superapp.datamanager

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * data-manager (usage side) — per-app recency / active-window / launch-count
 * metrics from [UsageStatsManager]. Zero privilege beyond
 * PACKAGE_USAGE_STATS, which the user grants once via
 * Configs → Permissions → Usage access (same grant the Battery & Usage
 * screen uses). Every query is wrapped in `runCatching` and degrades to an
 * empty list on any failure — a missing grant or a throwing OEM
 * implementation just hides the dependent smart folder, never crashes.
 *
 * All methods return a RANKED list of package names, best-first, with NO
 * cap — the caller filters to its own launchable-app set and takes its own
 * limit (so a system app that outranks everything can't starve the folder).
 *
 * Mirrors the proven UsageStatsManager query shape in
 * libs:battery/EnergyWatchdog.perAppEstimate.
 */
object AppUsageProvider {

    private const val DAY_MS = 24L * 60 * 60_000
    private const val HOUR_MS = 60L * 60_000

    private fun usm(ctx: Context): UsageStatsManager? =
        ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** Packages ranked by most-recent use (lastTimeUsed desc), 30-day window. */
    fun recentUsed(ctx: Context, now: Long = System.currentTimeMillis()): List<String> =
        rankByLastUse(ctx, now - 30 * DAY_MS, now, floor = 0L)

    /** Packages used within the last [windowH] hours, most-recent first. */
    fun activeSince(ctx: Context, windowH: Int, now: Long = System.currentTimeMillis()): List<String> {
        val start = now - windowH.coerceAtLeast(1) * HOUR_MS
        return rankByLastUse(ctx, start, now, floor = start)
    }

    /**
     * Packages that are STILL ALIVE right now — running a foreground
     * service, or sitting in the foreground — most recently active first.
     *
     * This is what "active" means to a person holding the phone: an app
     * doing something while they are somewhere else, not merely an app they
     * happened to open lately. Ranking by recency, as [recentUsed] does,
     * cannot express that — it returns the same head of the list as
     * [lastOpened] and the two sections render as twins.
     *
     * Android hands a non-system app no process list, but it does report
     * FOREGROUND_SERVICE_START / FOREGROUND_SERVICE_STOP and
     * ACTIVITY_RESUMED / ACTIVITY_STOPPED, and a START with no matching
     * STOP is precisely a service that is still running. An app that was
     * opened and then left behind — no service, no visible activity —
     * is deliberately absent here; that one belongs to [lastOpened].
     *
     * The two event families are replayed in one pass and each package
     * keeps whichever is still open, so an app that is BOTH in the
     * foreground and running a service appears once.
     */
    fun activeNow(ctx: Context, now: Long = System.currentTimeMillis()): List<String> {
        val u = usm(ctx) ?: return emptyList()
        // 7 days, not 24 hours: a media player or a VPN can hold a
        // foreground service open far longer than a day, and its START
        // event would fall outside a shorter window — the service would
        // then look stopped purely because we stopped looking.
        val start = now - 7 * DAY_MS
        val alive = HashMap<String, Long>()
        runCatching {
            val ev = u.queryEvents(start, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                when (e.eventType) {
                    // ACTIVITY_RESUMED is the same constant as the older
                    // MOVE_TO_FOREGROUND, so one branch covers both names.
                    UsageEvents.Event.FOREGROUND_SERVICE_START,
                    UsageEvents.Event.ACTIVITY_RESUMED ->
                        alive[e.packageName] = e.timeStamp
                    UsageEvents.Event.FOREGROUND_SERVICE_STOP,
                    UsageEvents.Event.ACTIVITY_STOPPED ->
                        alive.remove(e.packageName)
                    else -> Unit
                }
            }
        }
        return alive.entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * Packages ranked by the LAST TIME THEY WERE OPENED — most recent first,
     * 7-day window.
     *
     * Deliberately not [recentUsed]: that reads `lastTimeUsed`, which the
     * system also bumps for work an app does without the user ever choosing
     * it, so a sync adapter or a widget refresh outranks an app you actually
     * launched. This counts MOVE_TO_FOREGROUND events only — an app appears
     * here because it was brought to the front, which is what "last opened"
     * means to a person reading the list.
     */
    fun lastOpened(ctx: Context, now: Long = System.currentTimeMillis()): List<String> {
        val u = usm(ctx) ?: return emptyList()
        val start = now - 7 * DAY_MS
        val last = HashMap<String, Long>()
        runCatching {
            val ev = u.queryEvents(start, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last[e.packageName] = maxOf(last[e.packageName] ?: 0L, e.timeStamp)
                }
            }
        }
        return last.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Packages ranked by launch count — MOVE_TO_FOREGROUND events over 7 days. */
    fun mostOpened(ctx: Context, now: Long = System.currentTimeMillis()): List<String> {
        val u = usm(ctx) ?: return emptyList()
        val start = now - 7 * DAY_MS
        val counts = HashMap<String, Int>()
        runCatching {
            val ev = u.queryEvents(start, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    counts[e.packageName] = (counts[e.packageName] ?: 0) + 1
                }
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Packages ranked by total foreground time (totalTimeInForeground sum), 7-day window. */
    fun mostUsedTime(ctx: Context, now: Long = System.currentTimeMillis()): List<String> {
        val u = usm(ctx) ?: return emptyList()
        val start = now - 7 * DAY_MS
        val totals = HashMap<String, Long>()
        runCatching {
            u.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now)
        }.getOrNull()?.forEach { s ->
            if (s.totalTimeInForeground > 0L) {
                totals[s.packageName] = (totals[s.packageName] ?: 0L) + s.totalTimeInForeground
            }
        }
        return totals.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Shared: max lastTimeUsed per package over [start,end], keeping only
     *  rows whose lastTimeUsed is at/after [floor], ranked most-recent first. */
    private fun rankByLastUse(ctx: Context, start: Long, end: Long, floor: Long): List<String> {
        val u = usm(ctx) ?: return emptyList()
        val last = HashMap<String, Long>()
        runCatching {
            u.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
        }.getOrNull()?.forEach { s ->
            if (s.lastTimeUsed >= floor && s.lastTimeUsed > 0L) {
                last[s.packageName] = maxOf(last[s.packageName] ?: 0L, s.lastTimeUsed)
            }
        }
        return last.entries.sortedByDescending { it.value }.map { it.key }
    }
}
