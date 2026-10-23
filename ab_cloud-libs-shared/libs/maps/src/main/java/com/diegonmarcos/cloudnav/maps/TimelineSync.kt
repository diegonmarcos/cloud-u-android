package com.diegonmarcos.cloudnav.maps

import android.content.Context
import java.time.ZoneId

/**
 * Keeps THE timeline store ([MapsDb.segmentsBetween]) equal to what the writers recorded (#1114). The writers keep
 * their own tables: the stop-detector writes `stops`, the tracker `points`, and an app that owns more inputs (Cloud
 * Nav: step samples, engine fixes) plugs them in through [Extra]. One run rebuilds a window with [TimelineBuilder];
 * the first run over all history IS the migration, and a rebuild is idempotent, so a run can never double a row.
 */
object TimelineSync {
    /** Inputs only the host app has. Each call is blocking. */
    interface Extra {
        fun fixes(fromMs: Long, toMs: Long): List<TlFix>
        /** Stays the app derived from raw fixes (no recorded stop needed). */
        fun derivedStops(fromMs: Long, toMs: Long): List<TlStop>
        fun steps(fromMs: Long, toMs: Long): List<TlSteps>
        /** A place for a stop whose own coordinates are unusable; null = none found. */
        fun relocate(stop: TlStop, nowMs: Long): TlStop?
    }

    @Volatile var extra: Extra? = null

    /** Stops whose coordinates cannot be a place: 0,0, NaN, out of range. */
    fun usable(s: TlStop) = s.lat.isFinite() && s.lon.isFinite() && s.lat in -90.0..90.0 && s.lon in -180.0..180.0 && !(s.lat == 0.0 && s.lon == 0.0)

    /**
     * Rebuilds the window [fromMs, nowMs] (0 = all history). The inputs reach [TimelineBuilder.MAX_GAP_MS] further back,
     * so the move that leads into the window's first stop is rebuilt too; only segments that end inside the window
     * replace what the store held. Returns the number of segments stored.
     */
    fun run(ctx: Context, nowMs: Long = System.currentTimeMillis(), fromMs: Long = 0L, zone: ZoneId = ZoneId.systemDefault(),
            ex: Extra? = extra): Int {
        val db = MapsDb.get(ctx)
        val wf = if (fromMs <= 0L) 0L else fromMs - TimelineBuilder.MAX_GAP_MS
        val recorded = db.stopsOverlapping(wf, nowMs).map { s ->
            TlStop(s.startedAt, s.endedAt, s.lat, s.lon, s.placeName?.takeIf { it.isNotBlank() }, null, s.neighborhood, s.city, s.country)
        }
        val derived = ex?.derivedStops(wf, nowMs).orEmpty()
            .filter { d -> recorded.none { r -> d.startMs <= (r.endMs ?: nowMs) && r.startMs <= (d.endMs ?: nowMs) } }
        val stops = (recorded + derived).map { s -> if (usable(s)) s else (ex?.relocate(s, nowMs) ?: s) }.filter { usable(it) }
        val fixes = db.pointsBetween(wf, nowMs).map { TlFix(it.ts, it.lat, it.lon, (it.accuracy ?: 0f).toDouble()) } +
            ex?.fixes(wf, nowMs).orEmpty()
        val segs = TimelineBuilder.build(stops, fixes, ex?.steps(wf, nowMs).orEmpty(), nowMs, zone).filter { it.endMs >= fromMs }
        db.replaceSegments(fromMs, segs)
        return segs.size
    }

    @Volatile private var lastRefreshMs = 0L
    private const val REFRESH_EVERY_MS = 30_000L
    private const val REFRESH_WINDOW_MS = 2 * 86_400_000L

    /** What the pages call first: migrate once, keep the last two days current, then read the store. */
    fun view(ctx: Context, fromMs: Long, toMs: Long): List<TlSegment> {
        val now = System.currentTimeMillis()
        runCatching {
            migrateOnce(ctx, now)
            if (now - lastRefreshMs > REFRESH_EVERY_MS) { lastRefreshMs = now; run(ctx, now, now - REFRESH_WINDOW_MS) }
        }
        return MapsDb.get(ctx).segmentsBetween(fromMs, toMs)
    }

    /** The one-time move of existing stops, steps and raw fixes into the store; later calls do nothing. */
    fun migrateOnce(ctx: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val st = TimelineState(ctx)
        if (st.migratedAt > 0L) return false
        run(ctx, nowMs, 0L)
        st.migratedAt = nowMs
        return true
    }
}

/** The migration marker (SharedPreferences `timeline_state`): which version of the store was filled from the older tables. */
class TimelineState(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("timeline_state", Context.MODE_PRIVATE)
    var migratedAt: Long
        get() = sp.getLong("migrated_at", 0L)
        set(v) { sp.edit().putLong("migrated_at", v).apply() }
}
