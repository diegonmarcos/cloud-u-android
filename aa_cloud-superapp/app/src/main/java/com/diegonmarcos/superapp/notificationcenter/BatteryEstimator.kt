package com.diegonmarcos.superapp.notificationcenter

import kotlin.math.abs
import kotlin.math.exp

/**
 * The Battery badge's estimators as pure functions: numbers in, numbers out, no
 * Android type and no clock (the caller passes `nowMs`), so every edge case -
 * full, unknown current, the vendors' opposite CURRENT_NOW signs, the
 * learning state - is a unit test rather than a day on a phone.
 *
 * Two questions, two estimators:
 *  - DISCHARGING: time until empty = level / drain, where drain is the average
 *    since the unplug (or the last full charge) anchor libs:battery persists,
 *    and, beside it, a short-term EMA of the recent drain ("current rate").
 *  - CHARGING: time until full from the system's own estimate when it has one
 *    (BatteryManager.computeChargeTimeRemaining), else the recent charge rate
 *    (an EMA fed by CHARGE_COUNTER deltas, or level deltas without a counter),
 *    else the rate implied by the instantaneous current and the capacity.
 *
 * Rates are percent of the battery per MINUTE unless the name says otherwise.
 */
object BatteryEstimator {

    // ── the learning gate ────────────────────────────────────────────────
    /** An average drain is believed once this much was used... */
    const val MIN_USED_PCT = 2
    /** ...or this long has passed with at least [MIN_USED_PCT_LONG] used. */
    const val MIN_ELAPSED_MS = 15 * 60_000L
    const val MIN_USED_PCT_LONG = 1

    /** EMA time constant: how far back "current rate" looks. */
    const val EMA_TAU_MS = 30 * 60_000L
    /** A reference older than this is forgotten (the process was dead, the clock moved). */
    const val MAX_SAMPLE_GAP_MS = 6 * 3_600_000L

    // Counter-based measurement: a real movement, long enough to not be noise.
    private const val COUNTER_MIN_PCT = 0.5
    private const val COUNTER_MIN_MS = 60_000L
    // Level-based fallback: whole percents are coarse, so wait for two of them.
    private const val LEVEL_MIN_PCT = 2
    private const val LEVEL_MIN_MS = 5 * 60_000L

    /** Below this |raw| a reading is milliamps (Samsung and kin), not microamps. */
    const val MA_VENDOR_BELOW = 50_000

    // ── CURRENT_NOW / CURRENT_AVERAGE ────────────────────────────────────

    /** The raw BatteryManager current in milliamps: microamps per the docs,
     *  milliamps on some vendors (told apart by magnitude, the same rule
     *  libs:battery applies). null when the device returned nothing
     *  (Integer.MIN_VALUE); 0 is a real reading. */
    fun rawToMa(raw: Int?): Int? = when {
        raw == null || raw == Int.MIN_VALUE -> null
        abs(raw) < MA_VENDOR_BELOW -> raw
        else -> raw / 1000
    }

    /**
     * The sign convention is the vendor's (Pixel: positive while charging;
     * Samsung and others: the opposite, or even flipping), so the SIGN of the
     * raw reading is not trusted: the direction comes from the battery
     * status, the magnitude from the reading. Result: positive = into the
     * battery, negative = out of it. A full battery or a plugged-but-idle one
     * (NOT_CHARGING) keeps the magnitude, positive.
     */
    fun intoBatteryMa(raw: Int?, status: Int): Int? {
        val ma = rawToMa(raw) ?: return null
        val mag = abs(ma)
        return if (status == BatteryBadgeModel.STATUS_DISCHARGING) -mag else mag
    }

    // ── discharge ────────────────────────────────────────────────────────

    enum class DischargeState { NO_ANCHOR, LEARNING, READY, EMPTY }

    data class Discharge(
        val state: DischargeState,
        /** Percent used since the anchor; 0 without one. */
        val usedPct: Int = 0,
        val elapsedMs: Long = 0,
        /** Average drain since the anchor in %/h; null until [READY]. */
        val avgPctPerHour: Double? = null,
        val avgRemainingMs: Long? = null,
        /** Short-term (EMA) drain in %/h; null when there is none yet. */
        val recentPctPerHour: Double? = null,
        val recentRemainingMs: Long? = null,
    )

    /**
     * @param anchorMs/anchorPct the unplug (or last full charge) moment and
     *   level, 0 / -1 when libs:battery has none.
     * @param recentPctPerMin the EMA drain, 0 or less when there is none.
     */
    fun discharge(
        levelPct: Int, nowMs: Long, anchorMs: Long, anchorPct: Int, recentPctPerMin: Double = 0.0,
    ): Discharge {
        if (levelPct !in 0..100) return Discharge(DischargeState.NO_ANCHOR)
        val recentH = recentPctPerMin.takeIf { it > MIN_RATE }?.let { it * 60.0 }
        val recentMs = recentPctPerMin.takeIf { it > MIN_RATE }?.let { remainingMs(levelPct, it) }
        if (levelPct == 0) return Discharge(DischargeState.EMPTY, recentPctPerHour = recentH, recentRemainingMs = 0L)
        if (anchorMs <= 0L || anchorPct !in 0..100) {
            return Discharge(DischargeState.NO_ANCHOR, recentPctPerHour = recentH, recentRemainingMs = recentMs)
        }
        val elapsed = (nowMs - anchorMs).coerceAtLeast(0L)
        val used = (anchorPct - levelPct).coerceAtLeast(0)
        val enough = used >= MIN_USED_PCT || (used >= MIN_USED_PCT_LONG && elapsed >= MIN_ELAPSED_MS)
        if (!enough || elapsed <= 0L) {
            return Discharge(DischargeState.LEARNING, used, elapsed,
                recentPctPerHour = recentH, recentRemainingMs = recentMs)
        }
        val perMin = used / (elapsed / 60_000.0)
        return Discharge(DischargeState.READY, used, elapsed,
            avgPctPerHour = perMin * 60.0, avgRemainingMs = remainingMs(levelPct, perMin),
            recentPctPerHour = recentH, recentRemainingMs = recentMs)
    }

    private const val MIN_RATE = 1e-4 // %/min; anything slower is "no drain", not "a year"

    private fun remainingMs(levelPct: Int, pctPerMin: Double): Long =
        (levelPct / pctPerMin * 60_000.0).toLong()

    // ── charge ───────────────────────────────────────────────────────────

    enum class ChargeSource { FULL, SYSTEM, RECENT_RATE, CURRENT, UNKNOWN }

    data class Charge(val source: ChargeSource, val remainingMs: Long? = null, val pctPerHour: Double? = null)

    /**
     * @param platformRemainingMs BatteryManager.computeChargeTimeRemaining(),
     *   -1 when the system cannot say.
     * @param recentPctPerMin the EMA charge rate, 0 or less when none.
     * @param currentMa magnitude of the instantaneous current (mA), null unknown.
     * @param capacityMah the full capacity, null/0 unknown.
     */
    fun charge(
        levelPct: Int, full: Boolean, platformRemainingMs: Long,
        recentPctPerMin: Double, currentMa: Int?, capacityMah: Int?,
    ): Charge {
        if (full || levelPct >= 100) return Charge(ChargeSource.FULL, 0L)
        if (levelPct !in 0..99) return Charge(ChargeSource.UNKNOWN)
        val left = 100 - levelPct
        if (platformRemainingMs > 0L) return Charge(ChargeSource.SYSTEM, platformRemainingMs)
        if (recentPctPerMin > MIN_RATE) {
            return Charge(ChargeSource.RECENT_RATE, (left / recentPctPerMin * 60_000.0).toLong(), recentPctPerMin * 60.0)
        }
        val ma = currentMa?.let(::abs) ?: 0
        if (ma > 0 && capacityMah != null && capacityMah > 0) {
            val perMin = ma / capacityMah.toDouble() * 100.0 / 60.0
            return Charge(ChargeSource.CURRENT, (left / perMin * 60_000.0).toLong(), perMin * 60.0)
        }
        return Charge(ChargeSource.UNKNOWN)
    }

    /** The drain implied by an average current: %/h of the battery. */
    fun pctPerHourFromMa(ma: Int?, capacityMah: Int?): Double? =
        if (ma == null || capacityMah == null || capacityMah <= 0) null
        else abs(ma) / capacityMah.toDouble() * 100.0

    // ── the short-term rate (EMA) ────────────────────────────────────────

    /** The reference point of the rate tracker, persisted between events. */
    data class RateState(
        val ts: Long, val levelPct: Int,
        /** CHARGE_COUNTER in microamp-hours, 0 when the device has none. */
        val counterUah: Long,
        val charging: Boolean,
        /** The EMA in %/min in the direction of travel (drain or gain); 0 = none yet. */
        val emaPctPerMin: Double = 0.0,
    )

    /**
     * Advance the tracker with one battery event. A new reference starts
     * whenever the direction flips (plug or unplug), the level moves the wrong
     * way, the gap is too long, or there is no state; a measurement is taken
     * only once the battery really moved (see the constants), and then the
     * reference moves up to it, so every sample covers a disjoint interval.
     */
    fun track(
        prev: RateState?, nowMs: Long, levelPct: Int, counterUah: Long,
        capacityUah: Long, charging: Boolean,
    ): RateState? {
        if (levelPct !in 0..100) return prev
        val fresh = RateState(nowMs, levelPct, counterUah.coerceAtLeast(0L), charging)
        if (prev == null || prev.charging != charging) return fresh
        val dt = nowMs - prev.ts
        if (dt < 0 || dt > MAX_SAMPLE_GAP_MS) return fresh

        val sign = if (charging) 1 else -1
        val dLevel = (levelPct - prev.levelPct) * sign
        if (dLevel < 0) return fresh.copy(emaPctPerMin = prev.emaPctPerMin) // wrong way

        val useCounter = prev.counterUah > 0L && counterUah > 0L && capacityUah > 0L
        val dPct: Double
        val minPct: Double
        val minMs: Long
        if (useCounter) {
            dPct = (counterUah - prev.counterUah) * sign * 100.0 / capacityUah
            minPct = COUNTER_MIN_PCT; minMs = COUNTER_MIN_MS
        } else {
            dPct = dLevel.toDouble(); minPct = LEVEL_MIN_PCT.toDouble(); minMs = LEVEL_MIN_MS
        }
        if (dPct < minPct || dt < minMs) return prev.copy(ts = prev.ts) // keep the reference
        val sample = dPct / (dt / 60_000.0)
        val ema = ema(prev.emaPctPerMin, sample, dt)
        return fresh.copy(emaPctPerMin = ema)
    }

    /** One EMA step: the weight of the sample grows with the interval it covers. */
    fun ema(prev: Double, sample: Double, dtMs: Long): Double {
        if (prev <= 0.0) return sample
        val a = 1.0 - exp(-dtMs.toDouble() / EMA_TAU_MS)
        return prev + a * (sample - prev)
    }
}
