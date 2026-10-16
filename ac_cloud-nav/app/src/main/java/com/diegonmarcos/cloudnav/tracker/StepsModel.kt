package com.diegonmarcos.cloudnav.tracker

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class StepsSource { HEALTH_CONNECT, DEVICE }

/** Why the Steps page is on its data source, and what the user can do about it. */
enum class SourceStatus { READY, NEEDS_PERMISSION, HEALTH_CONNECT_MISSING, DEVICE_FALLBACK }

data class PlaceView(val label: String, val predicted: Boolean, val radiusM: Int)

data class DayCard(
    val date: LocalDate,
    val steps: Long,
    val places: Map<DayPart, PlaceView?>,
    val segments: List<Segment>,
)

data class StepsUi(
    val source: StepsSource = StepsSource.DEVICE,
    val status: SourceStatus = SourceStatus.DEVICE_FALLBACK,
    val loading: Boolean = false,
    val totals: Totals = Totals(0, 0, 0),
    val daily: Map<LocalDate, Long> = emptyMap(),
    val days: List<DayCard> = emptyList(),
    val today: LocalDate = LocalDate.now(),
)

object StepsModel {
    const val CARD_DAYS = 14

    /** Walked distance of [a]..[b] from at least three measured fixes in it; null when location can't say. */
    fun pathDistance(fixes: List<PlaceFix>, a: Long, b: Long): Double? {
        val pts = fixes.filter { !it.predicted && it.accuracyM <= 100 && it.ts in a..b }.sortedBy { it.ts }
        if (pts.size < 3) return null
        return pts.zipWithNext().sumOf { (p, q) -> DeadReckoning.haversineM(LatLon(p.lat, p.lon), LatLon(q.lat, q.lon)) }
    }

    /**
     * Everything the Steps page shows.
     * @param daily per-day totals (Health Connect's de-duplicated aggregate, or the device samples)
     * @param slices raw step intervals for the event segmentation (the last [CARD_DAYS] days)
     * @param label the place name of a fix (geocoder, else coordinates)
     */
    fun build(
        source: StepsSource, status: SourceStatus, daily: Map<LocalDate, Long>, slices: List<StepSlice>,
        fixes: List<PlaceFix>, zone: ZoneId, nowMs: Long, stepM: Double, label: (PlaceFix) -> String,
    ): StepsUi {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val cards = (0 until CARD_DAYS).map { today.minusDays(it.toLong()) }.map { day ->
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = minOf(nowMs, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            val segs = Segmenter.segment(slices.filter { it.startMs in from until to }, from, to, stepM) { a, b -> pathDistance(fixes, a, b) }
            val picks = DayPlacePicker.pick(day, zone, fixes).mapValues { (_, f) ->
                f?.let { PlaceView(label(it), it.predicted, it.accuracyM.toInt()) }
            }
            DayCard(day, daily[day] ?: segs.sumOf { it.steps }, picks, segs)
        }
        return StepsUi(source, status, false, StepStats.totals(daily, today), daily, cards, today)
    }
}
