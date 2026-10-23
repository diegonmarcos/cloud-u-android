package com.diegonmarcos.cloudnav.tracker

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One place the tracker saw the phone stay at: centroid, first and last fix, how many fixes, measured or predicted. */
data class Stay(val startMs: Long, val endMs: Long, val lat: Double, val lon: Double, val fixes: Int, val predicted: Boolean, val label: String? = null)

/** A stop the tracker's stop-detector recorded (the table Cloud Nav's own Stops / Daily pages read): [endMs] null while it lasts. */
data class StopFix(val startMs: Long, val endMs: Long?, val lat: Double, val lon: Double, val name: String?)

/**
 * The places of a day, for the SuperApp's Boards ▸ ME ▸ MyTrips places (op tracker/places). Pure, so a JVM test
 * holds it. Consecutive fixes within [RADIUS_M] of the running centroid are one stay; a stay shorter than [MIN_DWELL_MS]
 * is a pass-through and dropped, unless it is the day's only one. Accuracy worse than [MAX_ACCURACY_M] is ignored.
 */
object PlacesDigest {
    const val RADIUS_M = 150.0
    const val MIN_DWELL_MS = 10 * 60_000L
    const val MAX_ACCURACY_M = 200.0

    fun stays(fixes: List<PlaceFix>): List<Stay> {
        val pts = fixes.filter { it.accuracyM <= MAX_ACCURACY_M }.sortedBy { it.ts }
        val out = ArrayList<Stay>()
        var cur = ArrayList<PlaceFix>()
        fun flush() {
            if (cur.isEmpty()) return
            out += Stay(cur.first().ts, cur.last().ts, cur.sumOf { it.lat } / cur.size, cur.sumOf { it.lon } / cur.size,
                cur.size, cur.all { it.predicted })
            cur = ArrayList()
        }
        for (f in pts) {
            if (cur.isNotEmpty()) {
                val cLat = cur.sumOf { it.lat } / cur.size; val cLon = cur.sumOf { it.lon } / cur.size
                if (DeadReckoning.haversineM(LatLon(cLat, cLon), LatLon(f.lat, f.lon)) > RADIUS_M) flush()
            }
            cur.add(f)
        }
        flush()
        val kept = out.filter { it.endMs - it.startMs >= MIN_DWELL_MS }
        return if (kept.isEmpty() && out.isNotEmpty()) listOf(out.maxBy { it.fixes }) else kept
    }

    /** A recorded stop as a stay. Still open (or ended before it began) it runs to [nowMs]. */
    fun stay(s: StopFix, nowMs: Long) = Stay(s.startMs, (s.endMs ?: nowMs).coerceAtLeast(s.startMs), s.lat, s.lon, 1, false, s.name)

    /**
     * The [days] most recent local days ending today, newest first, each with its stays. The stop-detector's [stops] are
     * the tracker's own record of where the phone stayed (and the only record when the raw points were never kept or
     * were cleared), so they come first; a stay derived from raw fixes is added only where no stop covers that time.
     */
    fun byDay(fixes: List<PlaceFix>, zone: ZoneId, today: LocalDate, days: Int,
              stops: List<StopFix> = emptyList(), nowMs: Long = System.currentTimeMillis()): List<Pair<LocalDate, List<Stay>>> =
        (0 until days).map { today.minusDays(it.toLong()) }.map { d ->
            val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val recorded = stops.filter { it.startMs in from until to }.map { stay(it, nowMs) }
            val derived = stays(fixes.filter { it.ts in from until to })
                .filter { f -> recorded.none { r -> f.startMs <= r.endMs && r.startMs <= f.endMs } }
            d to (recorded + derived).sortedBy { it.startMs }
        }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    fun span(s: Stay, zone: ZoneId): String {
        val a = Instant.ofEpochMilli(s.startMs).atZone(zone).format(HM)
        val b = Instant.ofEpochMilli(s.endMs).atZone(zone).format(HM)
        return if (a == b) a else "$a–$b"
    }
}
