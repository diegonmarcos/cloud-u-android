package com.diegonmarcos.superapp.battery

/**
 * THE battery Source of Truth. One pure function, [compute], turns what the
 * battery says right now ([BatteryReading]) plus the recorded history
 * ([BatterySample]s, battery_sot.db) into one [BatteryReport]; every battery
 * surface draws that report and computes nothing of its own:
 *
 *   - the persistent Battery badge (notificationcenter.BatteryBadgeModel),
 *   - the home-screen top-right popup ([BatteryEstimatePopup]),
 *   - Configs › About › Battery, the full stats page, and About's summary.
 *
 * Words and units come from [BatteryRows], so a value reads the same in all
 * three. Android gathering and recording is [BatteryRepository]'s; nothing in
 * this file touches an Android type or the clock.
 */
data class BatteryReading(
    val nowMs: Long,
    /** 0..100, -1 unknown. */
    val levelPct: Int,
    val status: Int,
    val plugged: Int = 0,
    val health: Int = 1,
    val technology: String? = null,
    val tempDc: Int? = null,
    val voltageMv: Int? = null,
    /** BATTERY_PROPERTY_CURRENT_NOW / CURRENT_AVERAGE as the device reports them (µA or mA, either sign). */
    val rawCurrentNow: Int? = null,
    val rawCurrentAvg: Int? = null,
    val counterUah: Long? = null,
    /** EXTRA_CYCLE_COUNT (API 34+), null where the system has none. */
    val systemCycles: Int? = null,
    /** BatteryManager.computeChargeTimeRemaining(), -1 when the system cannot say. */
    val systemChargeRemainingMs: Long = -1,
    val screenOn: Boolean? = null,
) {
    val tempC: Double? get() = tempDc?.let { it / 10.0 }
    val onPower: Boolean get() = plugged != 0
    val discharging: Boolean get() = BatteryMath.isDischarging(status, plugged)
    val charging: Boolean get() = status == BatteryMath.STATUS_CHARGING
    val full: Boolean get() = status == BatteryMath.STATUS_FULL || (levelPct >= 100 && onPower)
}

/** What the SoT knows beyond the battery's own broadcast: capacity sources and counters. */
data class BatteryExtras(
    /** The nameplate capacity (PowerProfile / sysfs design), null when unreadable. */
    val ratedMah: Int? = null,
    /** libs:battery's cycle count from CHARGE_COUNTER deltas, null/negative while uncalibrated. */
    val cycleEstimate: Double? = null,
    /** The CURRENT_NOW unit this device has shown. */
    val scale: BatteryMath.CurrentScale = BatteryMath.CurrentScale.UNKNOWN,
)

enum class RateSource { CURRENT, LEVEL, NONE }
enum class FullSource { FULL, SYSTEM, RATE, NONE }

data class BatteryReport(
    val reading: BatteryReading,
    /** Instantaneous signed current, normalised. */
    val currentNowMa: Int?,
    val currentAvgMa: Int?,
    /** The EMA-smoothed signed current ("now" without the jumps). */
    val smoothedMa: Double?,
    /** Signed watts from the smoothed current × EXTRA_VOLTAGE. */
    val powerW: Double?,
    /** Signed %/h right now, and the power it is (both units, always together). */
    val ratePctH: Double?,
    val rateW: Double?,
    val rateSource: RateSource,
    /** Full capacity in mAh: CHARGE_COUNTER / level where the gauge has it, else the rated one. */
    val capacityMah: Int?,
    val capacityFromCounter: Boolean,
    val ratedMah: Int?,
    /** At the current rate. */
    val toEmptyMs: Long?,
    val toFullMs: Long?,
    val toFullSource: FullSource,
    /** Every run on battery since the last real charge (null while on power). */
    val since: SinceCharge?,
    /** The charge in progress (null on battery). */
    val chargeNow: BatterySession?,
    /** At the average since charge. */
    val toEmptyAtAvgMs: Long?,
    val toFullAtAvgMs: Long?,
    /** The whole history, cut into runs, oldest first (the last one is ongoing). */
    val sessions: List<BatterySession>,
    val systemCycles: Int?,
    val cycleEstimate: Double?,
) {
    val levelPct: Int get() = reading.levelPct
    /** Since-charge average as watts, from V×I where measured, else from %/h × capacity. */
    val sinceAvgW: Double?
        get() = since?.avgW ?: BatteryMath.wattsFromPctPerHour(since?.avgPctPerHour, capacityMah, reading.voltageMv)
    val chargeAvgPctH: Double? get() = chargeNow?.pctPerHour
    val chargeAvgW: Double?
        get() = chargeNow?.avgW ?: BatteryMath.wattsFromPctPerHour(chargeAvgPctH, capacityMah, reading.voltageMv)
    /** Estimated capacity vs design, %, when both are known. */
    val healthPct: Int?
        get() = if (capacityFromCounter && capacityMah != null && ratedMah != null && ratedMah > 0)
            (capacityMah * 100 / ratedMah) else null
}

object BatteryTruth {

    /**
     * The one computation. [ema] is the smoothed current the repository keeps
     * (folded with [BatteryMath.ema] from the same reading), [samples] the
     * recorded history including the sample for this reading.
     */
    fun compute(
        r: BatteryReading,
        samples: List<BatterySample>,
        ema: BatteryMath.Ema?,
        extras: BatteryExtras = BatteryExtras(),
    ): BatteryReport {
        val nowMa = BatteryMath.signedMa(r.rawCurrentNow, r.status, r.plugged, extras.scale)
        val avgMa = BatteryMath.signedMa(r.rawCurrentAvg, r.status, r.plugged, extras.scale)
        val smoothed = ema?.ma ?: nowMa?.toDouble()
        val counterCap = BatteryMath.capacityMah(r.counterUah, r.levelPct)
        val rated = extras.ratedMah?.takeIf { it > 0 }
        val cap = counterCap ?: rated
        val power = BatteryMath.powerW(smoothed, r.voltageMv)

        // "Now": the smoothed current over the capacity; without a current
        // (or a capacity), the level's own slope over the last half hour.
        val fromCurrent = BatteryMath.pctPerHour(smoothed, cap)
        val fromLevel = if (fromCurrent == null) BatteryHistory.recentLevelRate(samples, r.nowMs) else null
        val rate = fromCurrent ?: fromLevel
        val rateSource = when {
            fromCurrent != null -> RateSource.CURRENT
            fromLevel != null -> RateSource.LEVEL
            else -> RateSource.NONE
        }
        val rateW = power?.takeIf { fromCurrent != null } ?: BatteryMath.wattsFromPctPerHour(rate, cap, r.voltageMv)

        val sessions = BatteryHistory.segment(samples, cap)
        val since = if (r.onPower) null else BatteryHistory.sinceLastCharge(sessions, r.nowMs)
        val chargeNow = sessions.lastOrNull()?.takeIf { it.charging && it.ongoing && r.onPower }

        val toEmpty = if (r.discharging) BatteryMath.msToEmpty(r.levelPct, rate) else null
        val (toFull, fullSource) = when {
            !r.onPower -> null to FullSource.NONE
            r.full -> 0L to FullSource.FULL
            !r.charging -> null to FullSource.NONE
            r.systemChargeRemainingMs > 0L -> r.systemChargeRemainingMs to FullSource.SYSTEM
            else -> BatteryMath.msToFull(r.levelPct, rate).let { it to if (it != null) FullSource.RATE else FullSource.NONE }
        }
        return BatteryReport(
            reading = r, currentNowMa = nowMa, currentAvgMa = avgMa, smoothedMa = smoothed, powerW = power,
            ratePctH = rate, rateW = rateW, rateSource = rateSource,
            capacityMah = cap, capacityFromCounter = counterCap != null, ratedMah = rated,
            toEmptyMs = toEmpty, toFullMs = toFull, toFullSource = fullSource,
            since = since, chargeNow = chargeNow,
            toEmptyAtAvgMs = since?.let { BatteryMath.msToEmpty(r.levelPct, it.avgPctPerHour) },
            toFullAtAvgMs = chargeNow?.let { if (r.full) 0L else BatteryMath.msToFull(r.levelPct, it.pctPerHour) },
            sessions = sessions,
            systemCycles = r.systemCycles?.takeIf { it > 0 },
            cycleEstimate = extras.cycleEstimate?.takeIf { it >= 0.0 },
        )
    }

    /** The sample a reading is recorded as (the history and the report share one normalisation). */
    fun sampleOf(r: BatteryReading, scale: BatteryMath.CurrentScale): BatterySample = BatterySample(
        ts = r.nowMs, levelPct = r.levelPct, status = r.status, plugged = r.plugged,
        currentMa = BatteryMath.signedMa(r.rawCurrentNow, r.status, r.plugged, scale),
        voltageMv = r.voltageMv?.takeIf { it > 0 }, tempDc = r.tempDc, counterUah = r.counterUah?.takeIf { it > 0 },
        screenOn = r.screenOn,
    )
}
