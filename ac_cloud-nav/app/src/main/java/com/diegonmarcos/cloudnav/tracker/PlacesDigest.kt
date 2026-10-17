package com.diegonmarcos.cloudnav.tracker

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One place the tracker saw the phone stay at: centroid, first and last fix, how many fixes, measured or predicted. */
data class Stay(val startMs: Long, val endMs: Long, val lat: Double, val lon: Double, val fixes: Int, val predicted: Boolean)

/**
 * The places of a day, for the SuperApp's Boards ▸ My-PM ▸ Nav tracker card (op tracker/places). Pure, so a JVM test
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

    /** The [days] most recent local days ending today, newest first, each with its stays. */
    fun byDay(fixes: List<PlaceFix>, zone: ZoneId, today: LocalDate, days: Int): List<Pair<LocalDate, List<Stay>>> =
        (0 until days).map { today.minusDays(it.toLong()) }.map { d ->
            val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            d to stays(fixes.filter { it.ts in from until to })
        }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    fun span(s: Stay, zone: ZoneId): String {
        val a = Instant.ofEpochMilli(s.startMs).atZone(zone).format(HM)
        val b = Instant.ofEpochMilli(s.endMs).atZone(zone).format(HM)
        return if (a == b) a else "$a–$b"
    }
}
