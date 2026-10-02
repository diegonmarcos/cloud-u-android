package com.diegonmarcos.superapp.image.mlkit

import kotlin.math.sqrt

/**
 * #772 the dominant colours of an image, on device and with no model: pixels are binned at 4
 * bits per channel (4096 buckets), the most populated buckets win, and each answers the mean
 * colour of its pixels and its share of the image. A colour is named by the nearest entry of the
 * declared vocabulary (recognition.json::colours.names) under the "redmean" distance, a cheap
 * approximation of perceived difference. Pure, so its suite runs on golden images off-device.
 */
internal object Colours {
    data class Swatch(val rgb: Int, val share: Double) {
        val hex: String get() = "#%06X".format(rgb and 0xFFFFFF)
    }

    /** The [max] most common colours of ARGB [pixels]; fully transparent pixels are not counted. */
    fun dominant(pixels: IntArray, max: Int): List<Swatch> {
        val count = IntArray(4096)
        val sum = LongArray(4096 * 3)
        var total = 0
        for (p in pixels) {
            if ((p ushr 24) == 0) continue
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            count[k]++; sum[k * 3] += r.toLong(); sum[k * 3 + 1] += g.toLong(); sum[k * 3 + 2] += b.toLong()
            total++
        }
        if (total == 0) return emptyList()
        return (0 until 4096).filter { count[it] > 0 }.sortedWith(compareByDescending<Int> { count[it] }.thenBy { it }).take(max).map { k ->
            val n = count[k].toLong()
            val rgb = ((sum[k * 3] / n).toInt() shl 16) or ((sum[k * 3 + 1] / n).toInt() shl 8) or (sum[k * 3 + 2] / n).toInt()
            Swatch(rgb, count[k].toDouble() / total)
        }
    }

    /** The name of the [names] entry nearest to [rgb]; null when there are no names. */
    fun name(rgb: Int, names: Map<String, Int>): String? = names.minByOrNull { distance(rgb, it.value) }?.key

    /** The redmean colour distance (CompuPhase), in 8-bit units. */
    fun distance(a: Int, b: Int): Double {
        val r1 = (a shr 16) and 0xff; val r2 = (b shr 16) and 0xff
        val rm = (r1 + r2) / 2.0
        val dr = (r1 - r2).toDouble(); val dg = (((a shr 8) and 0xff) - ((b shr 8) and 0xff)).toDouble(); val db = ((a and 0xff) - (b and 0xff)).toDouble()
        return sqrt((2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db)
    }

    /** "#RRGGBB" → 0xRRGGBB, or null for anything else. */
    fun parseHex(hex: String): Int? =
        hex.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)
}
