package com.diegonmarcos.superapp.battery

import kotlin.math.abs
import kotlin.math.exp

/**
 * The battery Source of Truth's arithmetic, as pure functions: numbers in,
 * numbers out, no Android type and no clock. Everything the Battery badge,
 * the home-screen battery popup and Configs › About › Battery show is
 * computed from these, once, in [BatteryTruth.compute].
 *
 * SIGN CONVENTION, everywhere in the SoT: a current, a power and a rate are
 * POSITIVE into the battery (charging) and NEGATIVE out of it (discharging).
 * The vendors disagree (Pixel reports + while charging, Samsung the opposite,
 * some flip), so the sign of the raw reading is never trusted: the direction
 * comes from the battery status and the plug, the magnitude from the reading.
 *
 * SCALE: BatteryManager.BATTERY_PROPERTY_CURRENT_NOW is documented in µA;
 * Samsung and kin return mA. Told apart by magnitude — no phone battery moves
 * more than [MAX_PLAUSIBLE_MA] — and, once a device has shown a reading that
 * can only be µA, it is µA for good ([CurrentScale]).
 */
object BatteryMath {

    // android.os.BatteryManager constants, mirrored so this file stays pure.
    const val STATUS_UNKNOWN = 1
    const val STATUS_CHARGING = 2
    const val STATUS_DISCHARGING = 3
    const val STATUS_NOT_CHARGING = 4
    const val STATUS_FULL = 5
    const val PLUG_AC = 1
    const val PLUG_USB = 2
    const val PLUG_WIRELESS = 4
    const val PLUG_DOCK = 8

    /** Above this |raw| a reading cannot be milliamps: no phone cell moves 12 A. */
    const val MAX_PLAUSIBLE_MA = 12_000

    /** The EMA time constant of the "now" current: how far back "now" looks. */
    const val EMA_TAU_MS = 45_000L
    /** An EMA older than this is forgotten (the process slept, the screen was off for long). */
    const val EMA_MAX_GAP_MS = 5 * 60_000L

    // ── direction ────────────────────────────────────────────────────────

    /** On battery: discharging, or unplugged in any state that is not charging. */
    fun isDischarging(status: Int, plugged: Int): Boolean =
        status == STATUS_DISCHARGING ||
            (plugged == 0 && status != STATUS_CHARGING && status != STATUS_FULL)

    // ── CURRENT_NOW scale + sign ─────────────────────────────────────────

    /** What the SoT has learned about this device's CURRENT_NOW unit. */
    enum class CurrentScale { UNKNOWN, MICRO, MILLI }

    /** A reading that settles the unit: anything above [MAX_PLAUSIBLE_MA] is µA. */
    fun learnScale(known: CurrentScale, raw: Int?): CurrentScale = when {
        known != CurrentScale.UNKNOWN -> known
        raw == null || raw == Int.MIN_VALUE || raw == Int.MAX_VALUE -> known
        abs(raw.toLong()) > MAX_PLAUSIBLE_MA -> CurrentScale.MICRO
        else -> known
    }

    /**
     * The raw BatteryManager current in milliamps, sign as reported. null when
     * the device returned nothing (Integer.MIN_VALUE); 0 is a real reading.
     * With the scale unknown, a magnitude above [MAX_PLAUSIBLE_MA] is µA and
     * anything below it is taken as mA (a µA device shows >12 000 the first
     * time the phone is awake, and from then on [CurrentScale.MICRO] holds).
     */
    fun rawToMa(raw: Int?, scale: CurrentScale = CurrentScale.UNKNOWN): Int? {
        if (raw == null || raw == Int.MIN_VALUE || raw == Int.MAX_VALUE) return null
        return when (scale) {
            CurrentScale.MICRO -> raw / 1000
            CurrentScale.MILLI -> raw
            CurrentScale.UNKNOWN -> if (abs(raw.toLong()) > MAX_PLAUSIBLE_MA) raw / 1000 else raw
        }
    }

    /** Milliamps, positive into the battery, negative out of it (see the class doc). */
    fun signedMa(raw: Int?, status: Int, plugged: Int, scale: CurrentScale = CurrentScale.UNKNOWN): Int? {
        val ma = rawToMa(raw, scale) ?: return null
        val mag = abs(ma)
        return if (isDischarging(status, plugged)) -mag else mag
    }

    // ── power, capacity, unit conversions ────────────────────────────────

    /** Watts from a signed current (mA) and EXTRA_VOLTAGE (mV); same sign as the current. */
    fun powerW(ma: Double?, mv: Int?): Double? =
        if (ma == null || mv == null || mv <= 0) null else ma / 1000.0 * (mv / 1000.0)

    /**
     * The full capacity the gauge implies right now: CHARGE_COUNTER (µAh) at
     * [levelPct]% → mAh at 100%. Too low a level divides noise, so it needs
     * 5%; an answer outside a phone's 300..30 000 mAh is a broken counter.
     */
    fun capacityMah(counterUah: Long?, levelPct: Int): Int? {
        if (counterUah == null || counterUah <= 0L || levelPct !in 5..100) return null
        val mah = (counterUah / 1000.0 * 100.0 / levelPct).toInt()
        return mah.takeIf { it in 300..30_000 }
    }

    /** %/h of the battery a signed current moves. */
    fun pctPerHour(ma: Double?, capMah: Int?): Double? =
        if (ma == null || capMah == null || capMah <= 0) null else ma / capMah * 100.0

    /** The watts a signed %/h is, at [mv]. */
    fun wattsFromPctPerHour(pctH: Double?, capMah: Int?, mv: Int?): Double? =
        if (pctH == null || capMah == null || capMah <= 0 || mv == null || mv <= 0) null
        else pctH / 100.0 * (capMah / 1000.0) * (mv / 1000.0)

    /** The signed %/h a power is, at [mv]. */
    fun pctPerHourFromWatts(w: Double?, capMah: Int?, mv: Int?): Double? =
        if (w == null || capMah == null || capMah <= 0 || mv == null || mv <= 0) null
        else w / (mv / 1000.0) / (capMah / 1000.0) * 100.0

    // ── counted cycles (CHARGE_COUNTER deltas) ───────────────────────────

    /** No single step between two readings accepts more than this: a larger jump is a counter reset. */
    const val MAX_CHARGE_STEP_UAH = 1_000_000L

    /** The charge one step put INTO the battery while charging (µAh); 0 otherwise or on a reset. */
    fun chargeDeltaUah(prevUah: Long?, nowUah: Long?, charging: Boolean): Long {
        if (!charging || prevUah == null || nowUah == null || prevUah <= 0L || nowUah <= 0L) return 0L
        val d = nowUah - prevUah
        return if (d in 1L..MAX_CHARGE_STEP_UAH) d else 0L
    }

    /** Cycles = charge accepted since install ÷ the charge counter at the last 100%; null until a 100% was seen. */
    fun countedCycles(cumulativeUah: Long, peakFullUah: Long): Double? =
        if (peakFullUah <= 0L) null else cumulativeUah.toDouble() / peakFullUah

    // ── estimates ────────────────────────────────────────────────────────

    /** Anything slower than this is "no movement", not "a year". */
    const val MIN_RATE_PCT_H = 0.05

    /** Until 0% at [ratePctH] (signed, must be a drain). */
    fun msToEmpty(levelPct: Int, ratePctH: Double?): Long? {
        if (levelPct !in 0..100 || ratePctH == null || ratePctH > -MIN_RATE_PCT_H) return null
        return (levelPct / -ratePctH * 3_600_000.0).toLong()
    }

    /** Until 100% at [ratePctH] (signed, must be a gain). */
    fun msToFull(levelPct: Int, ratePctH: Double?): Long? {
        if (levelPct !in 0..100) return null
        if (levelPct >= 100) return 0L
        if (ratePctH == null || ratePctH < MIN_RATE_PCT_H) return null
        return ((100 - levelPct) / ratePctH * 3_600_000.0).toLong()
    }

    // ── the "now" smoothing ──────────────────────────────────────────────

    /** One exponential moving average of the signed current. */
    data class Ema(val ma: Double, val ts: Long)

    /**
     * Fold one reading into the EMA. Time-based: the weight of a sample grows
     * with the interval since the last one ([EMA_TAU_MS]), so a burst of
     * events does not outvote a minute of steady draw. Restarts on the first
     * sample, a gap over [EMA_MAX_GAP_MS], a clock that went back, or a
     * direction flip (a plug or unplug is a new regime, not noise).
     */
    fun ema(prev: Ema?, ma: Double, ts: Long, tauMs: Long = EMA_TAU_MS, maxGapMs: Long = EMA_MAX_GAP_MS): Ema {
        if (prev == null) return Ema(ma, ts)
        val dt = ts - prev.ts
        if (dt < 0 || dt > maxGapMs) return Ema(ma, ts)
        if (prev.ma != 0.0 && ma != 0.0 && (prev.ma > 0) != (ma > 0)) return Ema(ma, ts)
        val a = 1.0 - exp(-dt.toDouble() / tauMs)
        return Ema(prev.ma + a * (ma - prev.ma), ts)
    }
}
