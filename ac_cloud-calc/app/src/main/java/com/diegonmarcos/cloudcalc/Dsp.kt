package com.diegonmarcos.cloudcalc

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sound meter's signal processing, pure so the JVM suite checks it against reference values.
 * Textbook algorithms, written here rather than copied: an in-place radix-2 Cooley–Tukey FFT,
 * a Hann window, and the A-weighting curve of IEC 61672-1 (the formula, normalised to 0 dB at
 * 1 kHz). Levels are dBFS: a full-scale sine reads 0 dB. A phone microphone is not calibrated,
 * so the meter adds a declared offset (build.json::ui.modes[meter].meter.calibration_db).
 */
object Dsp {
    const val FLOOR_DB = -120.0

    fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

    /** In place; [re].size must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 0 && (n and (n - 1)) == 0 && im.size == n) { "fft size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(ang * k); val wi = sin(ang * k)
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
            }
            len = len shl 1
        }
    }

    fun db(x: Double): Double = if (x <= 0) FLOOR_DB else max(FLOOR_DB, 20 * log10(x))

    /** RMS level of 16-bit PCM in dBFS (a full-scale sine is 0 dB). */
    fun levelDbfs(pcm: ShortArray, count: Int = pcm.size): Double {
        if (count <= 0) return FLOOR_DB
        var sum = 0.0
        for (i in 0 until count) { val s = pcm[i] / 32768.0; sum += s * s }
        return db(sqrt(sum / count) * sqrt(2.0))
    }

    /**
     * Single-sided amplitude spectrum in dBFS of the first n samples (n a power of two), Hann
     * windowed and corrected for the window's gain, so a full-scale sine on a bin centre reads
     * ~0 dB. Index k is frequency k * sampleRate / n.
     */
    fun spectrumDb(pcm: ShortArray, n: Int): DoubleArray {
        val w = hann(n)
        val re = DoubleArray(n) { if (it < pcm.size) pcm[it] / 32768.0 * w[it] else 0.0 }
        val im = DoubleArray(n)
        fft(re, im)
        val gain = w.sum() / 2
        return DoubleArray(n / 2) { db(sqrt(re[it] * re[it] + im[it] * im[it]) / gain) }
    }

    /** IEC 61672-1 A-weighting in dB at [f] Hz (0 dB at 1 kHz). */
    fun aWeightingDb(f: Double): Double {
        if (f <= 0) return FLOOR_DB
        val f2 = f * f
        val ra = (12194.0 * 12194.0 * f2 * f2) /
            ((f2 + 20.6 * 20.6) * sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194.0))
        return 20 * log10(ra) + 2.0
    }

    /**
     * The A-weighted level from an unweighted level and its spectrum: the unweighted level plus
     * the share of spectral power that survives the weighting.
     */
    fun aWeightedDbfs(levelDbfs: Double, spectrumDb: DoubleArray, sampleRate: Int): Double {
        val n = spectrumDb.size * 2
        var total = 0.0; var weighted = 0.0
        for (k in 1 until spectrumDb.size) {
            val p = Math.pow(10.0, spectrumDb[k] / 10)
            total += p
            weighted += p * Math.pow(10.0, aWeightingDb(k.toDouble() * sampleRate / n) / 10)
        }
        if (total <= 0 || weighted <= 0) return FLOOR_DB
        return max(FLOOR_DB, levelDbfs + 10 * log10(weighted / total))
    }

    /** The frequency of the strongest bin above DC. */
    fun peakHz(spectrumDb: DoubleArray, sampleRate: Int): Double {
        var best = 1
        for (k in 2 until spectrumDb.size) if (spectrumDb[k] > spectrumDb[best]) best = k
        return best.toDouble() * sampleRate / (spectrumDb.size * 2)
    }
}
