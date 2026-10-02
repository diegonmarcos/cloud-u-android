package com.diegonmarcos.cloudcalc.sound

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The fundamental of a frame, by YIN (de Cheveigné & Kawahara, JASA 2002): the cumulative-mean
 * normalised difference function, the first dip under [threshold] followed down to its minimum,
 * refined by a parabola. Written from the paper; phyphox's pitch tool (GPL-3.0) autocorrelates
 * instead and nothing of it is copied. Null means unvoiced: silence, noise, or a fundamental
 * outside [fmin, fmax].
 */
object Pitch {
    data class Estimate(val hz: Double, val clarity: Double)

    fun yin(x: DoubleArray, sampleRate: Int, fmin: Double, fmax: Double, threshold: Double): Estimate? {
        val tauMin = max(2, floor(sampleRate / fmax).toInt())
        val tauMax = min(x.size / 2, ceil(sampleRate / fmin).toInt())
        if (tauMax <= tauMin + 1) return null
        val w = x.size - tauMax
        val cmnd = DoubleArray(tauMax + 1)
        cmnd[0] = 1.0
        var running = 0.0
        for (tau in 1..tauMax) {
            var d = 0.0
            for (j in 0 until w) { val e = x[j] - x[j + tau]; d += e * e }
            running += d
            cmnd[tau] = if (running <= 0.0) 1.0 else d * tau / running
        }
        var t = tauMin
        while (t <= tauMax && cmnd[t] >= threshold) t++
        if (t > tauMax) return null
        while (t + 1 <= tauMax && cmnd[t + 1] < cmnd[t]) t++
        val refined = if (t < tauMax) {
            val a = cmnd[t - 1]; val b = cmnd[t]; val c = cmnd[t + 1]
            val den = a - 2 * b + c
            if (den > 0) t + (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else t.toDouble()
        } else t.toDouble()
        return Estimate(sampleRate / refined, (1 - cmnd[t]).coerceIn(0.0, 1.0))
    }
}

/** Equal-tempered note names against a reference A4 (the tuning knob; 440 Hz by default). */
object Notes {
    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    data class Note(val name: String, val octave: Int, val midi: Int, val cents: Double, val hz: Double) {
        val label: String get() = "$name$octave"
    }

    fun of(hz: Double, a4: Double): Note? {
        if (!(hz > 0) || hz.isInfinite() || !(a4 > 0)) return null
        val m = 69 + 12 * ln(hz / a4) / ln(2.0)
        val midi = m.roundToInt()
        return Note(NAMES[Math.floorMod(midi, 12)], Math.floorDiv(midi, 12) - 1, midi, (m - midi) * 100, a4 * 2.0.pow((midi - 69) / 12.0))
    }
}

/** Sound in air: the speed from the temperature, and a frequency's wavelength at that speed. */
object Air {
    /** m/s at [celsius], from the ideal-gas law around 331.3 m/s at 0 °C. */
    fun speedOfSound(celsius: Double): Double = 331.3 * sqrt(1 + celsius / 273.15)

    /** Metres; null for a frequency that is not a positive number. */
    fun wavelength(hz: Double, speed: Double): Double? = if (hz > 0 && !hz.isInfinite()) speed / hz else null
}
