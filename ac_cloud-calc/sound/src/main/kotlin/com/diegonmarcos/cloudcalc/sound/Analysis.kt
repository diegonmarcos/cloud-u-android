package com.diegonmarcos.cloudcalc.sound

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Wave detection over a buffer: the signal is cut into events (a gate over short-block levels,
 * relative to the buffer's own noise floor), each event is measured (fundamental, harmonics,
 * bandwidth, duration, envelope, sweep) and named by its shape (impulse, chirp, noise, tone,
 * complex); onsets come from spectral flux, and a train of evenly spaced events is a period.
 * Every threshold is a [Params] field the app reads from build.json::ui.modes[…].sound.analysis.
 */
object Analysis {
    data class Params(
        val frame: Int = 2048,
        val hop: Int = 512,
        val block: Int = 256,
        val fmin: Double = 50.0,
        val fmax: Double = 4000.0,
        val yinThreshold: Double = 0.15,
        /** An event is this many dB over the buffer's noise floor (10th percentile of block levels). */
        val gateDb: Double = 12.0,
        /** Nothing quieter than this is ever an event, however quiet the floor. */
        val silenceDb: Double = -70.0,
        val minEventMs: Double = 5.0,
        val mergeGapMs: Double = 60.0,
        /** Shorter than this is an impulse; so is a sharp attack held under it that dies within [impulseDecayMs]. */
        val impulseMs: Double = 50.0,
        val impulseDecayMs: Double = 300.0,
        val noiseFlatness: Double = 0.2,
        /** A tone's peak frequency wanders by less than this fraction of itself. */
        val toneStability: Double = 0.03,
        /** A chirp's fitted frequency moves by more than this fraction of its mean. */
        val chirpChange: Double = 0.25,
        val periodicCv: Double = 0.15,
        val harmonics: Int = 8,
        val onsetK: Double = 1.5,
        val onsetWindow: Int = 8,
        /** Absolute part of the onset threshold, as a fraction of the buffer's largest flux. */
        val onsetDelta: Double = 0.15,
        /** An onset frame is no quieter than the one before by more than this: an ending is no onset. */
        val onsetRiseDb: Double = 0.5,
    )

    data class FrameInfo(
        val t: Double, val levelDb: Double, val peakHz: Double, val f0: Double?,
        val centroid: Double, val spread: Double, val flatness: Double, val flux: Double,
    )

    data class Event(
        val kind: String,
        val start: Double,
        val duration: Double,
        val peakDb: Double,
        val levelDb: Double,
        val attackMs: Double,
        val decayMs: Double,
        val f0: Double?,
        val peakHz: Double,
        val sweepHzPerS: Double,
        val bandwidth: Double,
        val centroid: Double,
        val flatness: Double,
        /** Harmonic k+1's level relative to the fundamental, dB (index 0 is the fundamental, 0 dB). */
        val harmonicsDb: List<Double>,
        /** Block levels over the event, at most [ENVELOPE_POINTS] of them (dBFS). */
        val envelope: List<Double>,
    )

    data class Periodicity(val regular: Boolean, val periodS: Double?, val rateHz: Double?, val count: Int)

    data class Result(
        val sampleRate: Int,
        val duration: Double,
        val levelDb: Double,
        val peakDb: Double,
        val f0: Double?,
        val peakHz: Double,
        val events: List<Event>,
        val onsets: List<Double>,
        val periodicity: Periodicity,
        val frames: List<FrameInfo>,
    )

    const val IMPULSE = "impulse"
    const val CHIRP = "chirp"
    const val NOISE = "noise"
    const val TONE = "tone"
    const val COMPLEX = "complex"
    const val ENVELOPE_POINTS = 32
    private const val EPS = 1e-20

    fun analyze(x: DoubleArray, sampleRate: Int, p: Params = Params()): Result {
        val frames = frames(x, sampleRate, p)
        val blocks = blockLevels(x, p.block)
        val events = segments(blocks, sampleRate, p).map { (b0, b1) -> event(x, sampleRate, p, frames, blocks, b0, b1) }
        val whole = averageSpectrum(x, 0, x.size, p)
        return Result(
            sampleRate,
            x.size.toDouble() / sampleRate,
            level(x, 0, x.size),
            Dsp.db(x.maxOfOrNull { abs(it) } ?: 0.0),
            median(frames.mapNotNull { it.f0 }),
            peakHz(whole, sampleRate),
            events,
            onsets(frames, p, sampleRate),
            periodicity(events, p),
            frames,
        )
    }

    /** RMS level of x[from, to) in dBFS, sine-referenced (a full-scale sine reads 0). */
    fun level(x: DoubleArray, from: Int, to: Int): Double {
        if (to <= from) return Dsp.FLOOR_DB
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return Dsp.db(sqrt(s / (to - from)) * sqrt(2.0))
    }

    /** Levels of consecutive [block]-sample blocks (the last one may be short). */
    fun blockLevels(x: DoubleArray, block: Int): DoubleArray {
        val n = (x.size + block - 1) / block
        return DoubleArray(n) { level(x, it * block, min(x.size, (it + 1) * block)) }
    }

    /**
     * Power spectrum of x[from, from+len), Hann-windowed over its own length and zero-padded to
     * [nfft], scaled so a full-scale sine on a bin centre has power 1. Index k is k·sr/nfft.
     */
    fun powerSpectrum(x: DoubleArray, from: Int, len: Int, nfft: Int): DoubleArray {
        val l = min(len, nfft)
        val w = if (l > 1) Dsp.hann(l) else DoubleArray(l) { 1.0 }
        val re = DoubleArray(nfft) { if (it < l) x[from + it] * w[it] else 0.0 }
        val im = DoubleArray(nfft)
        Dsp.fft(re, im)
        val gain = w.sum() / 2
        return DoubleArray(nfft / 2) { (re[it] * re[it] + im[it] * im[it]) / (gain * gain) }
    }

    fun frames(x: DoubleArray, sampleRate: Int, p: Params): List<FrameInfo> {
        val starts = if (x.size >= p.frame) (0..x.size - p.frame step p.hop).toList() else listOf(0)
        var prev: DoubleArray? = null
        return starts.map { s ->
            val len = min(p.frame, x.size - s)
            val pw = powerSpectrum(x, s, len, p.frame)
            val mag = DoubleArray(pw.size) { sqrt(pw[it]) }
            val flux = prev?.let { pm -> mag.indices.sumOf { max(0.0, mag[it] - pm[it]) } } ?: mag.sum()
            prev = mag
            val lvl = level(x, s, s + len)
            val f0 = if (lvl >= p.silenceDb) Pitch.yin(x.copyOfRange(s, s + len), sampleRate, p.fmin, p.fmax, p.yinThreshold)?.hz else null
            val (c, sp) = centroidSpread(pw, sampleRate)
            FrameInfo(s.toDouble() / sampleRate, lvl, peakHz(pw, sampleRate), f0, c, sp, flatness(pw), flux)
        }
    }

    /** The strongest bin's frequency, refined by a parabola over the neighbouring dB values. */
    fun peakHz(power: DoubleArray, sampleRate: Int): Double =
        Dsp.peakHzInterpolated(DoubleArray(power.size) { 10 * Math.log10(power[it] + EPS) }, sampleRate)

    /** Power-weighted mean frequency and the spread around it (the bandwidth), Hz; DC excluded. */
    fun centroidSpread(power: DoubleArray, sampleRate: Int): Pair<Double, Double> {
        val binHz = sampleRate.toDouble() / (power.size * 2)
        var tot = 0.0; var m = 0.0
        for (k in 1 until power.size) { tot += power[k]; m += k * binHz * power[k] }
        if (tot <= 0) return 0.0 to 0.0
        val c = m / tot
        var v = 0.0
        for (k in 1 until power.size) { val d = k * binHz - c; v += d * d * power[k] }
        return c to sqrt(v / tot)
    }

    /** Wiener entropy: geometric over arithmetic mean of the power, 1 for white noise, ~0 for a tone. */
    fun flatness(power: DoubleArray): Double {
        if (power.size < 2) return 0.0
        var lg = 0.0; var ar = 0.0
        for (k in 1 until power.size) { lg += ln(power[k] + EPS); ar += power[k] }
        val n = power.size - 1
        return (exp(lg / n) / (ar / n + EPS)).coerceIn(0.0, 1.0)
    }

    /** Runs of blocks over the gate, near runs merged, too-short ones dropped: [first, last] block. */
    fun segments(blocks: DoubleArray, sampleRate: Int, p: Params): List<Pair<Int, Int>> {
        if (blocks.isEmpty()) return emptyList()
        val maxL = blocks.max()
        if (maxL < p.silenceDb) return emptyList()
        val floor = percentile(blocks, 0.10)
        val gate = max(p.silenceDb, min(floor + p.gateDb, maxL - p.gateDb))
        val blockMs = p.block * 1000.0 / sampleRate
        val runs = mutableListOf<IntArray>()
        var i = 0
        while (i < blocks.size) {
            if (blocks[i] >= gate) {
                var j = i
                while (j + 1 < blocks.size && blocks[j + 1] >= gate) j++
                val last = runs.lastOrNull()
                if (last != null && (i - last[1] - 1) * blockMs < p.mergeGapMs) last[1] = j else runs += intArrayOf(i, j)
                i = j + 1
            } else i++
        }
        return runs.filter { (it[1] - it[0] + 1) * blockMs >= p.minEventMs }.map { it[0] to it[1] }
    }

    fun event(x: DoubleArray, sampleRate: Int, p: Params, frames: List<FrameInfo>, blocks: DoubleArray, b0: Int, b1: Int): Event {
        val s0 = b0 * p.block
        val s1 = min(x.size, (b1 + 1) * p.block)
        val blockMs = p.block * 1000.0 / sampleRate
        var loud = b0
        for (b in b0..b1) if (blocks[b] > blocks[loud]) loud = b
        val peakDb = blocks[loud]
        // A flat envelope has no single loudest block: attack runs to the FIRST block within 1 dB
        // of the peak, decay from the LAST one until the level is 20 dB down (or the event ends).
        val peakB = (b0..b1).first { blocks[it] >= peakDb - 1 }
        val holdB = (b0..b1).last { blocks[it] >= peakDb - 1 }
        var decayB = holdB
        while (decayB < b1 && blocks[decayB + 1] > peakDb - 20) decayB++
        val inside = frames.filter { it.t * sampleRate >= s0 && it.t * sampleRate + p.frame <= s1 }
        val spec = averageSpectrum(x, s0, s1, p)
        val (centroid, spread) = centroidSpread(spec, sampleRate)
        val flat = flatness(spec)
        val f0 = median(inside.mapNotNull { it.f0 })
            ?: Pitch.yin(x.copyOfRange(s0, s1), sampleRate, p.fmin, p.fmax, p.yinThreshold)?.hz
        val track = inside.map { it.t to it.peakHz }
        val (slope, r2) = regression(track)
        val meanHz = track.map { it.second }.average()
        val span = if (track.size >= 2) abs(slope * (track.last().first - track.first().first)) else 0.0
        val wander = if (track.size >= 2 && meanHz > 0) stddev(track.map { it.second }) / meanHz else 0.0
        val duration = (s1 - s0).toDouble() / sampleRate
        val attackMs = (peakB - b0) * blockMs
        val decayMs = (decayB - holdB + 1) * blockMs
        val holdMs = (holdB - peakB + 1) * blockMs
        val kind = when {
            duration * 1000 < p.impulseMs || (attackMs < 10 && holdMs < p.impulseMs && decayMs < p.impulseDecayMs) -> IMPULSE
            track.size >= 3 && meanHz > 0 && span / meanHz > p.chirpChange && r2 > 0.8 -> CHIRP
            flat > p.noiseFlatness -> NOISE
            track.size < 3 || wander < p.toneStability -> if (f0 != null || flat < p.noiseFlatness / 3) TONE else COMPLEX
            else -> COMPLEX
        }
        return Event(
            kind, s0.toDouble() / sampleRate, duration, peakDb, level(x, s0, s1), attackMs, decayMs,
            f0, peakHz(spec, sampleRate), slope, spread, centroid, flat,
            harmonics(spec, sampleRate, f0, p.harmonics), envelope(blocks, b0, b1),
        )
    }

    /** Mean power spectrum of x[s0, s1): frame-sized windows at the hop, or one window if shorter. */
    fun averageSpectrum(x: DoubleArray, s0: Int, s1: Int, p: Params): DoubleArray {
        val len = s1 - s0
        if (len <= 0) return DoubleArray(p.frame / 2)
        if (len < p.frame) return powerSpectrum(x, s0, len, p.frame)
        val acc = DoubleArray(p.frame / 2)
        var n = 0
        var s = s0
        while (s + p.frame <= s1) {
            val pw = powerSpectrum(x, s, p.frame, p.frame)
            for (k in acc.indices) acc[k] += pw[k]
            n++; s += p.hop
        }
        for (k in acc.indices) acc[k] /= n
        return acc
    }

    /** Levels of harmonics 1..[count] of [f0] relative to the first, dB; empty when unvoiced. */
    fun harmonics(power: DoubleArray, sampleRate: Int, f0: Double?, count: Int): List<Double> {
        if (f0 == null || f0 <= 0) return emptyList()
        val binHz = sampleRate.toDouble() / (power.size * 2)
        fun at(hz: Double): Double {
            val k = Math.round(hz / binHz).toInt()
            var m = 0.0
            for (j in max(1, k - 2)..min(power.size - 1, k + 2)) m = max(m, power[j])
            return m
        }
        val first = at(f0)
        // f0 must be a real spectral line (within 20 dB of the strongest): a periodicity pitch
        // with no energy of its own, like 87.5 Hz under a 350 + 440 Hz dual tone, has no harmonics.
        if (first <= 0 || first < 0.01 * power.max()) return emptyList()
        return (1..count).takeWhile { it * f0 < sampleRate / 2.0 - 2 * binHz }.map { 10 * Math.log10((at(it * f0) + EPS) / first) }
    }

    /** At most [ENVELOPE_POINTS] values: the loudest block of each equal slice of the event. */
    fun envelope(blocks: DoubleArray, b0: Int, b1: Int): List<Double> {
        val n = b1 - b0 + 1
        val pts = min(n, ENVELOPE_POINTS)
        return (0 until pts).map { i ->
            val a = b0 + i * n / pts
            val b = b0 + (i + 1) * n / pts - 1
            (a..max(a, b)).maxOf { blocks[it] }
        }
    }

    /**
     * Onset times (s, the frame's centre) by spectral flux: a frame whose flux is a local maximum
     * over [Params.onsetK] × the median of its ±[Params.onsetWindow] neighbourhood plus
     * [Params.onsetDelta] × the largest flux, not quiet, and not falling in level (a tone that
     * stops smears its spectrum too, and that is an ending, not an onset).
     */
    fun onsets(frames: List<FrameInfo>, p: Params, sampleRate: Int): List<Double> {
        val f = frames.map { it.flux }
        val peak = f.maxOrNull() ?: return emptyList()
        if (peak <= 0) return emptyList()
        return frames.indices.filter { i ->
            val lo = max(0, i - p.onsetWindow); val hi = min(f.size - 1, i + p.onsetWindow)
            val thr = p.onsetK * median(f.subList(lo, hi + 1))!! + p.onsetDelta * peak
            frames[i].levelDb >= p.silenceDb && f[i] > thr &&
                (i == 0 || frames[i].levelDb > frames[i - 1].levelDb - p.onsetRiseDb) &&
                (i == 0 || f[i] >= f[i - 1]) && (i == f.size - 1 || f[i] > f[i + 1])
        }.map { frames[it].t + p.frame / 2.0 / sampleRate }
    }

    /** Three or more events evenly spaced (coefficient of variation of the gaps under the limit). */
    fun periodicity(events: List<Event>, p: Params): Periodicity {
        if (events.size < 3) return Periodicity(false, null, null, events.size)
        val gaps = events.zipWithNext { a, b -> b.start - a.start }
        val mean = gaps.average()
        val regular = mean > 0 && stddev(gaps) / mean < p.periodicCv
        return if (regular) Periodicity(true, mean, 1 / mean, events.size) else Periodicity(false, null, null, events.size)
    }

    /** Least-squares slope of (t, y) and its r²; (0, 0) under two points or no spread in t. */
    fun regression(pts: List<Pair<Double, Double>>): Pair<Double, Double> {
        if (pts.size < 2) return 0.0 to 0.0
        val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for ((t, y) in pts) { sxx += (t - mx) * (t - mx); sxy += (t - mx) * (y - my); syy += (y - my) * (y - my) }
        if (sxx <= 0) return 0.0 to 0.0
        val slope = sxy / sxx
        val r2 = if (syy <= 0) 0.0 else (sxy * sxy) / (sxx * syy)
        return slope to r2
    }

    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun percentile(v: DoubleArray, q: Double): Double {
        val s = v.sorted()
        return s[((s.size - 1) * q).toInt()]
    }

    fun stddev(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }
}
