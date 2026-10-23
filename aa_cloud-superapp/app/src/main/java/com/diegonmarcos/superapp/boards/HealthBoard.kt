package com.diegonmarcos.superapp.boards

import com.diegonmarcos.superapp.health.HealthBoardReader
import java.time.LocalDate
import java.util.Locale

/**
 * MyHealth board (Boards > My-PM): what Health Connect holds, boiled down to Today / 7-day / 30-day figures and a
 * 7-day chart for steps (in km), resting heart rate and sleep. [HealthAgg] is pure (no Android), so a JVM test holds
 * the rules: a day with no record is MISSING, never zero, and an average is taken over the days that have data.
 */
data class KmDay(val date: LocalDate, val km: Double?, val steps: Long?, val estimated: Boolean)

/** An average over [days] days that had data (of the window asked for). [estimated] = any contributing day was. */
data class KmAvg(val km: Double?, val steps: Long?, val days: Int, val estimated: Boolean)

data class StepsBlock(val week: List<KmDay>, val today: KmDay, val avg7: KmAvg, val avg30: KmAvg, val strideM: Double, val strideFromHeight: Boolean)

data class BpmDay(val date: LocalDate, val bpm: Double?)

data class RhrBlock(val week: List<BpmDay>, val today: Double?, val avg7: Double?, val avg30: Double?)

data class SleepDay(val date: LocalDate, val night: HealthBoardReader.Night?)

/** Average minutes per stage over the nights that had data. */
data class SleepAvg(val deepMin: Double, val remMin: Double, val lightMin: Double, val awakeMin: Double, val nights: Int) {
    val asleepMin: Double get() = deepMin + remMin + lightMin
}

data class SleepBlock(val week: List<SleepDay>, val lastNight: HealthBoardReader.Night?, val avg7: SleepAvg?, val avg30: SleepAvg?)

data class HealthBoard(
    val status: HealthBoardReader.Status,
    val missing: Set<String>,
    val steps: StepsBlock,
    val rhr: RhrBlock,
    val sleep: SleepBlock,
    val error: String?,
) {
    val ready: Boolean get() = status == HealthBoardReader.Status.READY
}

object HealthAgg {
    const val DEFAULT_STRIDE_M = 0.75
    /** Walking stride is about 0.415 x height (the usual rule of thumb). */
    private const val STRIDE_PER_HEIGHT = 0.415

    /** Stride length in metres and whether it came from the height record (a height outside 1.0..2.5 m is ignored). */
    fun stride(heightM: Double?): Pair<Double, Boolean> =
        if (heightM != null && heightM in 1.0..2.5) heightM * STRIDE_PER_HEIGHT to true else DEFAULT_STRIDE_M to false

    fun km(distanceM: Double?, steps: Long?, strideM: Double): Pair<Double, Boolean>? = when {
        distanceM != null && distanceM > 0.0 -> distanceM / 1000.0 to false
        steps != null && steps > 0L -> steps * strideM / 1000.0 to true
        else -> null
    }

    /** The [n] local days ending [today], oldest first. */
    fun window(today: LocalDate, n: Int): List<LocalDate> = (n - 1 downTo 0).map { today.minusDays(it.toLong()) }

    /** Mean of the non-null values, null when there are none (never 0 for "no data"). */
    fun mean(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()

    private fun kmDay(d: LocalDate, raw: HealthBoardReader.Raw, stride: Double): KmDay {
        val steps = raw.steps[d]
        val k = km(raw.distanceM[d], steps, stride)
        return KmDay(d, k?.first, steps, k?.second ?: false)
    }

    private fun kmAvg(days: List<KmDay>): KmAvg {
        val withKm = days.filter { it.km != null }
        val withSteps = days.mapNotNull { it.steps }
        return KmAvg(mean(withKm.map { it.km }), withSteps.takeIf { it.isNotEmpty() }?.let { Math.round(it.average()) },
            withKm.size, withKm.any { it.estimated })
    }

    fun steps(raw: HealthBoardReader.Raw, today: LocalDate): StepsBlock {
        val (stride, fromHeight) = stride(raw.heightM)
        val d30 = window(today, 30).map { kmDay(it, raw, stride) }
        return StepsBlock(d30.takeLast(7), d30.last(), kmAvg(d30.takeLast(7)), kmAvg(d30), stride, fromHeight)
    }

    fun rhr(raw: HealthBoardReader.Raw, today: LocalDate): RhrBlock {
        val d30 = window(today, 30).map { BpmDay(it, raw.restingHr[it]?.toDouble()) }
        return RhrBlock(d30.takeLast(7), d30.last().bpm, mean(d30.takeLast(7).map { it.bpm }), mean(d30.map { it.bpm }))
    }

    private fun sleepAvg(days: List<SleepDay>): SleepAvg? {
        val n = days.mapNotNull { it.night }
        if (n.isEmpty()) return null
        return SleepAvg(n.map { it.deepMin }.average(), n.map { it.remMin }.average(), n.map { it.lightMin }.average(),
            n.map { it.awakeMin }.average(), n.size)
    }

    fun sleep(raw: HealthBoardReader.Raw, today: LocalDate): SleepBlock {
        val d30 = window(today, 30).map { SleepDay(it, raw.sleep[it]) }
        return SleepBlock(d30.takeLast(7), d30.last().night, sleepAvg(d30.takeLast(7)), sleepAvg(d30))
    }

    /** Permissions the board wants and does not have (height is optional and never listed as missing). */
    fun missing(granted: Set<String>): Set<String> =
        setOf(HealthBoardReader.STEPS, HealthBoardReader.DISTANCE, HealthBoardReader.RESTING_HR, HealthBoardReader.SLEEP) - granted

    fun board(raw: HealthBoardReader.Raw, today: LocalDate): HealthBoard =
        HealthBoard(raw.status, missing(raw.granted), steps(raw, today), rhr(raw, today), sleep(raw, today), raw.error)

    // ── text ────────────────────────────────────────────────────────

    /** "8.4km" ("~8.4km" when estimated), "--" when missing. */
    fun kmText(km: Double?, estimated: Boolean = false): String =
        if (km == null) "--" else (if (estimated) "~" else "") + String.format(Locale.US, "%.1fkm", km)

    /** "8.4km (11,234 steps)": distance first, steps in brackets. */
    fun kmStepsText(km: Double?, steps: Long?, estimated: Boolean = false): String =
        kmText(km, estimated) + (steps?.let { String.format(Locale.US, " (%,d steps)", it) } ?: "")

    /** "7h 12m" from minutes, "--" when missing. */
    fun hm(min: Double?): String {
        if (min == null) return "--"
        val t = Math.round(min).toInt()
        return "${t / 60}h ${"%02d".format(t % 60)}m"
    }

    /** "7.2h" for a chart label. */
    fun hours(min: Double): String = String.format(Locale.US, "%.1fh", min / 60.0)

    fun bpmText(v: Double?): String = if (v == null) "--" else String.format(Locale.US, "%d", Math.round(v))
}

/** Sleep and walking from Cloud Nav's tracker/daily (v1), boiled down for the MyHealth card. A day with no sleep is MISSING, never 0 h. */
data class TrackerHealth(val lastNightH: Double?, val avgSleepH: Double?, val walkTodayKm: Double?, val avgWalkKm: Double?, val sleepCity: String?)

object TrackerAgg {
    fun summarize(days: List<TrackerDay>, today: LocalDate): TrackerHealth {
        val week = days.filter { !it.date.isAfter(today) }.sortedBy { it.date }.takeLast(7)
        val slept = week.filter { it.sleepH > 0.0 }
        val last = slept.lastOrNull { it.date == today } ?: slept.lastOrNull()
        val walk = week.filter { it.walkM > 0L }
        return TrackerHealth(last?.sleepH, slept.takeIf { it.isNotEmpty() }?.map { it.sleepH }?.average(),
            week.lastOrNull { it.date == today }?.walkM?.takeIf { it > 0 }?.let { it / 1000.0 },
            walk.takeIf { it.isNotEmpty() }?.map { it.walkM / 1000.0 }?.average(), last?.sleepCity?.takeIf { it.isNotBlank() })
    }

    fun hoursText(h: Double?): String = if (h == null) "--" else String.format(Locale.US, "%.1fh", h)
}
