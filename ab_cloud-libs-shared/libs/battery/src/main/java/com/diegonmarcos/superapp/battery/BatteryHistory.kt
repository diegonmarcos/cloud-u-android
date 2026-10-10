package com.diegonmarcos.superapp.battery

import kotlin.math.abs

/**
 * One row of the SoT's rolling history (battery_sot.db), written by
 * [BatteryRepository] on ACTION_BATTERY_CHANGED, screen on/off and the
 * 15-minute tick. Pure data: [BatteryHistory] turns a list of them into
 * sessions, cycles and the since-last-charge averages.
 */
data class BatterySample(
    val ts: Long,
    val levelPct: Int,
    val status: Int,
    val plugged: Int,
    /** Signed (+ into the battery), already normalised by [BatteryMath.signedMa]. */
    val currentMa: Int? = null,
    val voltageMv: Int? = null,
    /** EXTRA_TEMPERATURE in tenths of a degree. */
    val tempDc: Int? = null,
    val counterUah: Long? = null,
    /** PowerManager.isInteractive at the sample; null when unknown. */
    val screenOn: Boolean? = null,
) {
    val onPower: Boolean get() = plugged != 0
    val tempC: Double? get() = tempDc?.let { it / 10.0 }
    val powerW: Double? get() = BatteryMath.powerW(currentMa?.toDouble(), voltageMv)
}

/** One uninterrupted run on battery (a discharge cycle) or on power (a charge session). */
data class BatterySession(
    val charging: Boolean,
    val startTs: Long,
    val endTs: Long,
    val startPct: Int,
    val endPct: Int,
    /** The plug bitmask of a charge session (AC/USB/wireless); 0 for a discharge. */
    val plugged: Int,
    /** Time-weighted mean power over the run (signed), from V×I where measured, else from %/h × capacity. */
    val avgW: Double?,
    val maxTempC: Double?,
    /** Still running: the last session of the history, ending at the newest sample. */
    val ongoing: Boolean,
    val screenOnMs: Long = 0,
    val screenOffMs: Long = 0,
    /** Percent moved while the screen was on / off (positive numbers). */
    val screenOnPct: Int = 0,
    val screenOffPct: Int = 0,
) {
    val durationMs: Long get() = (endTs - startTs).coerceAtLeast(0L)
    val deltaPct: Int get() = endPct - startPct
    /** Signed average %/h over the run; null when it is too short to say. */
    val pctPerHour: Double?
        get() = if (durationMs < BatteryHistory.MIN_SESSION_MS) null else deltaPct / (durationMs / 3_600_000.0)
}

/** "Since last charge": every run on battery after the last real charge, summed. */
data class SinceCharge(
    /** When the last real charge ended (the unplug), and at what level. */
    val fromTs: Long,
    val fromPct: Int,
    /** True when the history holds no real charge yet and this counts from its oldest run on battery. */
    val approximate: Boolean,
    /** Wall time since [fromTs]. */
    val wallMs: Long,
    /** Time actually on battery (top-ups excluded). */
    val onBatteryMs: Long,
    /** Percent used on battery (positive). */
    val usedPct: Int,
    /** Short top-ups bridged inside the window: how many, and how many percent they added. */
    val topUps: Int,
    val topUpPct: Int,
    /** Average drain over the time on battery, signed (negative); null while learning. */
    val avgPctPerHour: Double?,
    /** Duration-weighted average power of the runs on battery (signed). */
    val avgW: Double?,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val screenOnPct: Int,
    val screenOffPct: Int,
) {
    /** The screen split is only "measurable" once some samples knew the screen state. */
    val screenMeasured: Boolean get() = screenOnMs + screenOffMs > 0
}

/** min / max / mean of one quantity over a window. */
data class Spread(val min: Double, val max: Double, val avg: Double)

object BatteryHistory {

    const val RETENTION_MS = 14L * 24 * 3_600_000L
    /** A run shorter than this has no meaningful rate. */
    const val MIN_SESSION_MS = 60_000L
    /** A charge that added less than this is a top-up, bridged by "since last charge". */
    const val MIN_REAL_CHARGE_PCT = 10
    /** The learning gate: an average is believed once this much was used... */
    const val MIN_USED_PCT = 2
    /** ...or this long has passed with at least [MIN_USED_PCT_LONG] used. */
    const val MIN_ELAPSED_MS = 15 * 60_000L
    const val MIN_USED_PCT_LONG = 1
    /** A longer sample interval whose ends disagree says nothing about the screen (the recorder was not running). */
    const val MAX_SCREEN_INTERVAL_MS = 30 * 60_000L

    // ── segmentation ─────────────────────────────────────────────────────

    /**
     * Cut the history into runs at every plug/unplug. A run ends where the next
     * begins (the sample that saw the plug change is the boundary), so the runs
     * tile the history with no gap; the last one is [BatterySession.ongoing].
     * [capMah] lets a run with no current reading still get a power, from its %/h.
     */
    fun segment(samples: List<BatterySample>, capMah: Int? = null): List<BatterySession> {
        val s = samples.filter { it.levelPct in 0..100 }.sortedBy { it.ts }
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<BatterySession>()
        var start = 0
        for (i in 1..s.size) {
            val boundary = i == s.size || s[i].onPower != s[start].onPower
            if (!boundary) continue
            val last = i == s.size
            val endSample = if (last) s[i - 1] else s[i]
            out += session(s.subList(start, i), endSample, ongoing = last, capMah = capMah)
            start = i
        }
        return out
    }

    private fun session(run: List<BatterySample>, end: BatterySample, ongoing: Boolean, capMah: Int?): BatterySession {
        val first = run.first()
        val charging = first.onPower
        // Each interval [run[k], next) is attributed to run[k]'s readings.
        val points = run + if (end !== run.last()) listOf(end) else emptyList()
        var wSum = 0.0; var wMs = 0L
        var onMs = 0L; var offMs = 0L; var onPct = 0; var offPct = 0
        for (k in 0 until points.size - 1) {
            val a = points[k]; val b = points[k + 1]
            val dt = (b.ts - a.ts).coerceAtLeast(0L)
            a.powerW?.let { wSum += it * dt; wMs += dt }
            val moved = abs(b.levelPct - a.levelPct)
            // A long interval counts only when both ends saw the same screen (a change is its own sample).
            if (dt <= MAX_SCREEN_INTERVAL_MS || a.screenOn == b.screenOn) when (a.screenOn) {
                true -> { onMs += dt; onPct += moved }
                false -> { offMs += dt; offPct += moved }
                null -> Unit
            }
        }
        val durMs = (end.ts - first.ts).coerceAtLeast(0L)
        val avgW = if (wMs > 0) wSum / wMs else {
            val rate = if (durMs >= MIN_SESSION_MS) (end.levelPct - first.levelPct) / (durMs / 3_600_000.0) else null
            BatteryMath.wattsFromPctPerHour(rate, capMah, meanVoltage(run))
        }
        return BatterySession(
            charging = charging, startTs = first.ts, endTs = end.ts,
            startPct = first.levelPct, endPct = end.levelPct,
            plugged = if (charging) run.firstOrNull { it.plugged != 0 }?.plugged ?: 0 else 0,
            avgW = avgW, maxTempC = run.mapNotNull { it.tempC }.maxOrNull(), ongoing = ongoing,
            screenOnMs = onMs, screenOffMs = offMs, screenOnPct = onPct, screenOffPct = offPct,
        )
    }

    private fun meanVoltage(run: List<BatterySample>): Int? =
        run.mapNotNull { it.voltageMv?.takeIf { v -> v > 0 } }.takeIf { it.isNotEmpty() }?.average()?.toInt()

    /** A charge that counts as "the last charge" (not a top-up). */
    fun isRealCharge(s: BatterySession): Boolean =
        s.charging && !s.ongoing && (s.deltaPct >= MIN_REAL_CHARGE_PCT || s.endPct >= 100)

    // ── the recorder's filter ────────────────────────────────────────────

    /** Store a sample at least this often even when nothing moved. */
    const val HEARTBEAT_MS = 5 * 60_000L
    /** ...and, when only the level moved, at most this often. */
    const val MIN_GAP_MS = 15_000L

    /** Keep a sample when the plug, status or screen changed, the level moved, or [HEARTBEAT_MS] passed. */
    fun shouldStore(prev: BatterySample?, next: BatterySample): Boolean {
        if (prev == null) return true
        val dt = next.ts - prev.ts
        if (dt < 0) return false
        if (prev.plugged != next.plugged || prev.status != next.status || prev.screenOn != next.screenOn) return true
        if (prev.levelPct != next.levelPct) return dt >= MIN_GAP_MS
        return dt >= HEARTBEAT_MS
    }

    // ── since last charge ────────────────────────────────────────────────

    /**
     * Every run on battery after the last real charge, summed: the time on
     * battery and the percent used add across plug/unplug events, while a short
     * top-up in between is bridged (its time is not "on battery", its gain is
     * reported apart). null while on power — the charge in progress is the
     * story then — or with no run on battery in the history at all.
     */
    fun sinceLastCharge(sessions: List<BatterySession>, nowMs: Long): SinceCharge? {
        if (sessions.isEmpty() || sessions.last().charging) return null
        val lastCharge = sessions.indexOfLast(::isRealCharge)
        val window = sessions.subList(lastCharge + 1, sessions.size)
        val runs = window.filter { !it.charging }
        if (runs.isEmpty()) return null
        val topUps = window.filter { it.charging }
        val fromTs = if (lastCharge >= 0) sessions[lastCharge].endTs else runs.first().startTs
        val fromPct = if (lastCharge >= 0) sessions[lastCharge].endPct else runs.first().startPct
        // The ongoing run's time is counted up to now, not to its newest sample.
        val onBattery = runs.sumOf { if (it.ongoing) (nowMs - it.startTs).coerceAtLeast(it.durationMs) else it.durationMs }
        val used = runs.sumOf { (it.startPct - it.endPct).coerceAtLeast(0) }
        val believed = used >= MIN_USED_PCT || (used >= MIN_USED_PCT_LONG && onBattery >= MIN_ELAPSED_MS)
        val avg = if (believed && onBattery > 0) -used / (onBattery / 3_600_000.0) else null
        val weighted = runs.filter { it.avgW != null && it.durationMs > 0 }
        val wMs = weighted.sumOf { it.durationMs }
        val avgW = if (wMs > 0) weighted.sumOf { it.avgW!! * it.durationMs } / wMs else null
        return SinceCharge(
            fromTs = fromTs, fromPct = fromPct, approximate = lastCharge < 0,
            wallMs = (nowMs - fromTs).coerceAtLeast(0L), onBatteryMs = onBattery, usedPct = used,
            topUps = topUps.size, topUpPct = topUps.sumOf { it.deltaPct.coerceAtLeast(0) },
            avgPctPerHour = avg, avgW = avgW,
            screenOnMs = runs.sumOf { it.screenOnMs }, screenOffMs = runs.sumOf { it.screenOffMs },
            screenOnPct = runs.sumOf { it.screenOnPct }, screenOffPct = runs.sumOf { it.screenOffPct },
        )
    }

    // ── the short-term level slope (the "now" rate without a current) ────

    /**
     * The %/h the level moved over the last [windowMs] of the run in progress,
     * signed; null until it moved [minPct] whole percent (levels are coarse).
     */
    fun recentLevelRate(samples: List<BatterySample>, nowMs: Long, windowMs: Long = 30 * 60_000L, minPct: Int = 2): Double? {
        val s = samples.filter { it.levelPct in 0..100 }.sortedBy { it.ts }
        if (s.size < 2) return null
        val last = s.last()
        val run = s.takeLastWhile { it.onPower == last.onPower && it.ts >= nowMs - windowMs }
        if (run.size < 2) return null
        val dPct = run.last().levelPct - run.first().levelPct
        val dMs = run.last().ts - run.first().ts
        if (abs(dPct) < minPct || dMs < MIN_SESSION_MS) return null
        return dPct / (dMs / 3_600_000.0)
    }

    // ── the "now" EMA after a process restart ────────────────────────────

    /** Rebuild the current EMA from the newest recorded currents (none older than the EMA's gap). */
    fun replayEma(samples: List<BatterySample>): BatteryMath.Ema? {
        var e: BatteryMath.Ema? = null
        for (x in samples.sortedBy { it.ts }) {
            val ma = x.currentMa ?: continue
            e = BatteryMath.ema(e, ma.toDouble(), x.ts)
        }
        return e
    }

    // ── spreads and the graph ────────────────────────────────────────────

    fun tempSpread(samples: List<BatterySample>, fromTs: Long): Spread? =
        spread(samples.filter { it.ts >= fromTs }.mapNotNull { it.tempC })

    fun voltageSpread(samples: List<BatterySample>, fromTs: Long): Spread? =
        spread(samples.filter { it.ts >= fromTs }.mapNotNull { it.voltageMv?.takeIf { v -> v > 0 }?.let { v -> v / 1000.0 } })

    private fun spread(v: List<Double>): Spread? =
        if (v.isEmpty()) null else Spread(v.min(), v.max(), v.average())

    /** One point of the level graph. */
    data class Point(val ts: Long, val levelPct: Int, val onPower: Boolean)

    /** The level over [fromTs, toTs], at most [maxPoints] (the newest sample of each bucket wins). */
    fun levelSeries(samples: List<BatterySample>, fromTs: Long, toTs: Long, maxPoints: Int = 240): List<Point> {
        val s = samples.filter { it.ts in fromTs..toTs && it.levelPct in 0..100 }.sortedBy { it.ts }
        if (s.size <= maxPoints || maxPoints < 2) return s.map { Point(it.ts, it.levelPct, it.onPower) }
        val span = (toTs - fromTs).coerceAtLeast(1L)
        val buckets = LinkedHashMap<Long, BatterySample>()
        for (x in s) buckets[(x.ts - fromTs) * maxPoints / span] = x
        return buckets.values.map { Point(it.ts, it.levelPct, it.onPower) }
    }
}
