package com.diegonmarcos.superapp.wifi

import kotlin.math.pow
import kotlin.math.roundToInt

/** One candidate channel's verdict (#1263): [rating] 0 (unusable) .. 10 (clear), [aps] the APs that overlap it. */
data class ChannelScore(val band: Band, val channel: Int, val rating: Double, val load: Double, val aps: Int, val dfs: Boolean, val note: String)

/** A band's congestion: 0 (empty) .. 100 (saturated), how many APs and how many overlap the busiest channel. */
data class Congestion(val band: Band, val score: Int, val aps: Int, val busiest: Int)

/**
 * Channel rating and the best-channel recommendation (#1263), from what a scan sees. Each AP adds load to a 20 MHz candidate in
 * proportion to the MHz it overlaps (its full width, so a 160 MHz AP loads eight channels) times its received power on a linear-ish
 * scale ([weight]: -100 dBm = 0, -30 dBm = 1, squared so a near AP counts far more than a far one). Pure: JVM-tested.
 */
object ChannelRating {
    fun weight(rssi: Int): Double = (((rssi + 100).coerceIn(0, 70)) / 70.0).pow(2)

    fun load(aps: List<AccessPoint>, band: Band, channel: Int, exclude: String? = null): Pair<Double, Int> {
        val f = ChannelMath.freq(band, channel); if (f <= 0) return 0.0 to 0
        val cand = ChannelMath.range(f, if (band == Band.B24) 22 else 20)
        var load = 0.0; var n = 0
        for (ap in aps) {
            if (ap.band != band || ap.bssid == exclude) continue
            val ov = ap.spans.sumOf { ChannelMath.overlap(it, cand) }
            if (ov <= 0) continue
            n++
            load += weight(ap.smoothed.roundToInt()) * ov / (cand.last - cand.first).toDouble()
        }
        return load to n
    }

    /** Every channel the plan allows in [band], rated. DFS channels lose [dfsPenalty] (radar can force the AP off). */
    fun rate(aps: List<AccessPoint>, plan: ChannelPlan, band: Band, dfsPenalty: Double = 0.5): List<ChannelScore> =
        plan.channels(band).map { pc ->
            val (l, n) = load(aps, band, pc.ch)
            val r = (10.0 - l * 4.0 - (if (pc.dfs) dfsPenalty else 0.0)).coerceIn(0.0, 10.0)
            ChannelScore(band, pc.ch, (r * 10).roundToInt() / 10.0, l, n, pc.dfs, pc.note)
        }

    /**
     * The best channel of [band]: highest rating; ties go to a non-DFS, then the lower channel. 2.4 GHz only proposes the
     * non-overlapping set ([nonOverlap24], normally 1 / 6 / 11), because any other choice overlaps two of them.
     */
    fun best(scores: List<ChannelScore>, nonOverlap24: List<Int> = listOf(1, 6, 11)): ChannelScore? {
        val pool = scores.filter { it.band != Band.B24 || it.channel in nonOverlap24 }.ifEmpty { scores }
        return pool.sortedWith(compareByDescending<ChannelScore> { it.rating }.thenBy { it.dfs }.thenBy { it.channel }).firstOrNull()
    }

    fun congestion(aps: List<AccessPoint>, plan: ChannelPlan, band: Band): Congestion {
        val inBand = aps.filter { it.band == band }
        val scores = rate(aps, plan, band, 0.0)
        if (scores.isEmpty()) return Congestion(band, 0, inBand.size, 0)
        val mean = scores.sumOf { it.load } / scores.size
        val busiest = scores.maxOf { it.aps }
        return Congestion(band, (mean * 40 + busiest * 3).coerceIn(0.0, 100.0).roundToInt(), inBand.size, busiest)
    }

    /** How much the other APs interfere with [ap] on its own span: 0 clear .. 100. */
    fun interference(ap: AccessPoint, aps: List<AccessPoint>): Int {
        var l = 0.0
        val mine = ap.spans
        val width = mine.sumOf { it.last - it.first }.coerceAtLeast(1)
        for (o in aps) {
            if (o.bssid == ap.bssid || o.band != ap.band) continue
            val ov = o.spans.sumOf { s -> mine.sumOf { ChannelMath.overlap(s, it) } }
            if (ov > 0) l += weight(o.smoothed.roundToInt()) * ov / width.toDouble()
        }
        return (l * 50).coerceIn(0.0, 100.0).roundToInt()
    }
}
