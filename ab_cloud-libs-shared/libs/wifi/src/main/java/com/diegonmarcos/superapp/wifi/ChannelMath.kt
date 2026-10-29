package com.diegonmarcos.superapp.wifi

/** Frequency / channel / width arithmetic of 802.11 (#1263). Pure: JVM-tested. */
object ChannelMath {
    fun band(freqMhz: Int): Band = when (freqMhz) {
        in 2400..2500 -> Band.B24
        in 4900..5924 -> Band.B5
        in 5925..7125 -> Band.B6
        in 57000..71000 -> Band.B60
        else -> Band.Unknown
    }

    /** IEEE channel number of a 20 MHz primary (2.4: 1-14, 4.9 / 5: 36.., 6: 1-233, 60: 1-6). -1 when it is none. */
    fun channel(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 4910..4980 -> (freqMhz - 4000) / 5
        freqMhz in 5000..5924 -> (freqMhz - 5000) / 5
        freqMhz == 5935 -> 2
        freqMhz in 5950..7115 -> (freqMhz - 5950) / 5
        freqMhz in 58320..70200 -> (freqMhz - 56160) / 2160
        else -> -1
    }

    /** The centre frequency of [channel] in [band]; -1 when there is no such channel. */
    fun freq(band: Band, channel: Int): Int = when (band) {
        Band.B24 -> if (channel == 14) 2484 else if (channel in 1..13) 2407 + 5 * channel else -1
        Band.B5 -> if (channel in 1..196) 5000 + 5 * channel else -1
        Band.B6 -> if (channel == 2) 5935 else if (channel in 1..233) 5950 + 5 * channel else -1
        Band.B60 -> if (channel in 1..6) 56160 + 2160 * channel else -1
        Band.Unknown -> -1
    }

    /** ScanResult.CHANNEL_WIDTH_* code to MHz: 0 20, 1 40, 2 80, 3 160, 4 80+80 (160 in two segments), 5 320. */
    fun widthMhz(code: Int): Int = when (code) { 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 160; 5 -> 320; else -> 20 }

    fun widthLabel(code: Int): String = if (code == 4) "80+80 MHz" else "${widthMhz(code)} MHz"

    /**
     * The occupied frequency ranges. [cf0] is the centre of the whole channel (or of segment one for 80+80), [cf1] segment two of 80+80;
     * older firmware reports a 160 MHz channel as cf0 = the primary 80 and cf1 = the whole centre, which is read the same way here.
     * 2.4 GHz 20 MHz is drawn 22 MHz wide (the DSSS mask), so neighbours 1..4 channels away overlap as they really do.
     */
    fun spans(freqMhz: Int, cf0: Int, cf1: Int, widthCode: Int, band: Band): List<IntRange> {
        val w = widthMhz(widthCode)
        if (widthCode == 4 && cf0 > 0 && cf1 > 0) return listOf(range(cf0, 80), range(cf1, 80))
        val centre = when {
            w == 20 -> freqMhz
            w == 160 && cf1 > 0 && kotlin.math.abs(cf1 - cf0) == 40 -> cf1
            cf0 > 0 -> cf0
            else -> freqMhz
        }
        return listOf(range(centre, if (band == Band.B24 && w == 20) 22 else w))
    }

    /** Same as above for a tracked AP whose width is already in MHz. */
    fun spansMhz(freqMhz: Int, cf0: Int, cf1: Int, widthMhz: Int, band: Band): List<IntRange> =
        spans(freqMhz, cf0, cf1, codeOf(widthMhz, cf0, cf1), band)

    fun codeOf(widthMhz: Int, cf0: Int = 0, cf1: Int = 0): Int = when (widthMhz) {
        40 -> 1; 80 -> 2; 160 -> if (cf1 > 0 && kotlin.math.abs(cf1 - cf0) > 40) 4 else 3; 320 -> 5; else -> 0
    }

    /** The centre of the whole channel, for labels and the spectrum graph. */
    fun centre(freqMhz: Int, cf0: Int, cf1: Int, widthCode: Int, band: Band): Int {
        val s = spans(freqMhz, cf0, cf1, widthCode, band)
        return (s.first().first + s.last().last) / 2
    }

    fun range(centre: Int, width: Int): IntRange = (centre - width / 2)..(centre + width / 2)

    /** MHz two ranges share. */
    fun overlap(a: IntRange, b: IntRange): Int = (minOf(a.last, b.last) - maxOf(a.first, b.first)).coerceAtLeast(0)

    /** The channels (20 MHz primaries of [band]) an AP's spans cover: what it occupies on the channel graph. */
    fun coveredChannels(spans: List<IntRange>, band: Band): List<Int> {
        val (lo, hi, step) = when (band) { Band.B24 -> Triple(1, 14, 1); Band.B5 -> Triple(32, 177, 4); Band.B6 -> Triple(1, 233, 4); else -> return emptyList() }
        return (lo..hi step step).filter { c -> val f = freq(band, c); f > 0 && spans.any { s -> overlap(s, range(f, 20)) >= 10 } }
    }

    /** The graph's x range for a band. */
    fun axis(band: Band): IntRange = when (band) {
        Band.B24 -> 2397..2497
        Band.B5 -> 5150..5895
        Band.B6 -> 5925..7125
        Band.B60 -> 57240..70200
        Band.Unknown -> 0..1
    }
}
