package com.diegonmarcos.cloudcalc.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The sound generator: tones in four waveforms, linear or logarithmic sweeps, white and pink
 * noise, a dual tone and beats, with short fades so nothing clicks. [guard] is the safe-volume
 * rule, applied before anything is rendered: every limit is build.json data, and every clamp it
 * makes is reported back so the screen and the debug route can say what was changed.
 */
object Generator {
    const val TONE = "tone"
    const val SWEEP = "sweep"
    const val NOISE = "noise"
    const val DUAL = "dual"
    const val BEATS = "beats"
    val KINDS = listOf(TONE, SWEEP, NOISE, DUAL, BEATS)
    val WAVES = listOf("sine", "square", "triangle", "saw")
    val COLOURS = listOf("white", "pink")

    /**
     * What to play. [f2] is the sweep's end, the dual tone's second frequency, or — for beats —
     * the beat rate (the two tones are f1 and f1 + f2). [amplitude] is a fraction of full scale.
     */
    data class Spec(
        val kind: String,
        val wave: String,
        val f1: Double,
        val f2: Double,
        val amplitude: Double,
        val ms: Int,
        val colour: String = "white",
        val logSweep: Boolean = true,
    )

    data class Limits(
        val maxAmplitude: Double,
        val minHz: Double,
        val maxHz: Double,
        val maxBeatHz: Double,
        val maxMs: Int,
        val fadeMs: Int,
        /** Above this fraction of the device's media volume, [loudAmplitude] caps the output. */
        val loudVolume: Double,
        val loudAmplitude: Double,
    )

    data class Guarded(val spec: Spec, val notes: List<String>)

    /** The spec made safe; IllegalArgumentException for a kind, wave or colour that does not exist. */
    fun guard(spec: Spec, limits: Limits, sampleRate: Int, deviceVolume: Double): Guarded {
        require(spec.kind in KINDS) { "unknown kind ${spec.kind} (one of ${KINDS.joinToString()})" }
        require(spec.wave in WAVES) { "unknown wave ${spec.wave} (one of ${WAVES.joinToString()})" }
        require(spec.colour in COLOURS) { "unknown noise colour ${spec.colour} (one of ${COLOURS.joinToString()})" }
        val notes = mutableListOf<String>()
        val top = min(limits.maxHz, 0.45 * sampleRate)
        fun hz(v: Double, what: String): Double {
            val c = if (v.isNaN()) limits.minHz else v.coerceIn(limits.minHz, top)
            if (c != v) notes += "$what ${fmt(v)} Hz → ${fmt(c)} Hz"
            return c
        }
        var amp = if (spec.amplitude.isNaN()) 0.0 else spec.amplitude.coerceIn(0.0, limits.maxAmplitude)
        if (amp != spec.amplitude) notes += "amplitude ${fmt(spec.amplitude)} → ${fmt(amp)} (safe-volume limit ${fmt(limits.maxAmplitude)})"
        if (deviceVolume > limits.loudVolume && amp > limits.loudAmplitude) {
            notes += "device volume ${(deviceVolume * 100).roundToInt()}% is high: amplitude ${fmt(amp)} → ${fmt(limits.loudAmplitude)}"
            amp = limits.loudAmplitude
        }
        val ms = spec.ms.coerceIn(10, limits.maxMs)
        if (ms != spec.ms) notes += "duration ${spec.ms} ms → $ms ms"
        val f1 = if (spec.kind == NOISE) spec.f1 else hz(spec.f1, "f1")
        val f2 = when (spec.kind) {
            SWEEP, DUAL -> hz(spec.f2, "f2")
            BEATS -> {
                val c = if (spec.f2.isNaN()) 1.0 else spec.f2.coerceIn(0.1, limits.maxBeatHz)
                if (c != spec.f2) notes += "beat ${fmt(spec.f2)} Hz → ${fmt(c)} Hz"
                if (f1 + c > top) notes += "beats need f1 + beat under ${fmt(top)} Hz"
                c
            }
            else -> spec.f2
        }
        return Guarded(spec.copy(f1 = f1, f2 = f2, amplitude = amp, ms = ms), notes)
    }

    /** One period of [wave] at phase [ph] (any real; only its fraction counts), in [-1, 1]. */
    fun wave(wave: String, ph: Double): Double {
        val f = ph - floor(ph)
        return when (wave) {
            "square" -> if (f < 0.5) 1.0 else -1.0
            "triangle" -> 4 * abs(f - 0.5) - 1
            "saw" -> 2 * f - 1
            else -> sin(2 * PI * f)
        }
    }

    /**
     * Samples in [-amplitude, amplitude] for an already [guard]ed spec, with linear fades of
     * [fadeMs] at both ends. Noise is seeded, so a render is reproducible.
     */
    fun render(spec: Spec, sampleRate: Int, fadeMs: Int, seed: Long = 1L): DoubleArray {
        val n = (sampleRate.toLong() * spec.ms / 1000).toInt()
        val out = DoubleArray(n)
        val rnd = java.util.Random(seed)
        val b = DoubleArray(7)
        var ph1 = 0.0; var ph2 = 0.0
        val second = if (spec.kind == BEATS) spec.f1 + spec.f2 else spec.f2
        for (i in 0 until n) {
            val y = when (spec.kind) {
                TONE -> wave(spec.wave, ph1).also { ph1 += spec.f1 / sampleRate }
                SWEEP -> {
                    val t = i.toDouble() / n
                    val f = if (spec.logSweep && spec.f1 > 0 && spec.f2 > 0) spec.f1 * (spec.f2 / spec.f1).pow(t)
                            else spec.f1 + (spec.f2 - spec.f1) * t
                    wave(spec.wave, ph1).also { ph1 += f / sampleRate }
                }
                DUAL, BEATS -> ((wave(spec.wave, ph1) + wave(spec.wave, ph2)) / 2).also {
                    ph1 += spec.f1 / sampleRate; ph2 += second / sampleRate
                }
                NOISE -> {
                    val w = rnd.nextDouble() * 2 - 1
                    if (spec.colour == "pink") pink(b, w) else w
                }
                else -> 0.0
            }
            out[i] = spec.amplitude * y
        }
        val nf = min(n / 2, sampleRate * fadeMs / 1000)
        for (i in 0 until nf) {
            val g = i.toDouble() / nf
            out[i] *= g; out[n - 1 - i] *= g
        }
        return out
    }

    /** Paul Kellet's refined pink-noise filter over one white sample; [b] is its state. */
    private fun pink(b: DoubleArray, w: Double): Double {
        b[0] = 0.99886 * b[0] + w * 0.0555179
        b[1] = 0.99332 * b[1] + w * 0.0750759
        b[2] = 0.96900 * b[2] + w * 0.1538520
        b[3] = 0.86650 * b[3] + w * 0.3104856
        b[4] = 0.55000 * b[4] + w * 0.5329522
        b[5] = -0.7616 * b[5] - w * 0.0168980
        val y = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + w * 0.5362) * 0.11
        b[6] = w * 0.115926
        return y.coerceIn(-1.0, 1.0)
    }

    /** Doubles in [-1, 1] as 16-bit PCM, clipped. */
    fun pcm16(x: DoubleArray): ShortArray =
        ShortArray(x.size) { (x[it].coerceIn(-1.0, 1.0) * 32767).roundToInt().toShort() }

    private fun fmt(v: Double): String = if (v == floor(v) && abs(v) < 1e9) v.toLong().toString() else "%.3g".format(java.util.Locale.ROOT, v)
}
