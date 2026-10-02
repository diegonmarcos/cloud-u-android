package com.diegonmarcos.cloudcalc.sound

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * A sound session: the readings taken over time (the History mode's graphs and its CSV), the
 * spectrogram rows (its waterfall), and the summary of a buffer's analysis that the "What is this
 * sound?" question sends as state — a decision model cannot hear, so it is told what was measured.
 */
object Session {
    data class Sample(
        val t: Double,
        val levelDb: Double,
        val aDb: Double,
        val cDb: Double,
        val peakHz: Double,
        val f0: Double?,
        val note: String?,
        val wavelengthM: Double?,
    )

    val CSV_HEADER = listOf("t_s", "level_db", "level_dba", "level_dbc", "peak_hz", "f0_hz", "note", "wavelength_m")

    fun csv(samples: List<Sample>): String = Csv.write(CSV_HEADER, samples.map {
        listOf(rd(it.t, 3), rd(it.levelDb, 2), rd(it.aDb, 2), rd(it.cDb, 2), rd(it.peakHz, 2), it.f0?.let { f -> rd(f, 2) }, it.note, it.wavelengthM?.let { w -> rd(w, 4) })
    })

    /**
     * [bands] log-spaced bands from [fmin] to Nyquist, each the loudest FFT bin it covers (dB):
     * one waterfall row. A band narrower than a bin takes the bin under its centre.
     */
    fun logBands(spectrumDb: DoubleArray, sampleRate: Int, bands: Int, fmin: Double): DoubleArray {
        val binHz = sampleRate.toDouble() / (spectrumDb.size * 2)
        val lo = ln(fmin); val hi = ln(sampleRate / 2.0)
        return DoubleArray(bands) { i ->
            val f0 = Math.exp(lo + (hi - lo) * i / bands)
            val f1 = Math.exp(lo + (hi - lo) * (i + 1) / bands)
            val k0 = max(1, (f0 / binHz).toInt())
            val k1 = min(spectrumDb.size - 1, max(k0, (f1 / binHz).toInt()))
            var m = Dsp.FLOOR_DB
            for (k in k0..k1) m = max(m, spectrumDb[k])
            m
        }
    }

    /**
     * What a decision model is told about a buffer: overall level (calibrated), pitch and note,
     * the dominant frequency, every event's shape and features, onsets and periodicity. Numbers
     * are rounded — the model reads text, and six decimals of a Hz say nothing more.
     */
    fun describe(r: Analysis.Result, calibrationDb: Double, a4: Double): JSONObject {
        val note = r.f0?.let { Notes.of(it, a4) }
        val events = JSONArray()
        r.events.forEach { e ->
            events.put(JSONObject()
                .put("kind", e.kind)
                .put("start_s", rd(e.start, 3))
                .put("duration_s", rd(e.duration, 3))
                .put("level_db", rd(e.levelDb + calibrationDb, 1))
                .put("fundamental_hz", e.f0?.let { rd(it, 1) } ?: JSONObject.NULL)
                .put("dominant_hz", rd(e.peakHz, 1))
                .put("sweep_hz_per_s", rd(e.sweepHzPerS, 1))
                .put("bandwidth_hz", rd(e.bandwidth, 1))
                .put("centroid_hz", rd(e.centroid, 1))
                .put("noisiness", rd(e.flatness, 3))
                .put("harmonics_db", JSONArray(e.harmonicsDb.map { rd(it, 1) }))
                .put("attack_ms", rd(e.attackMs, 0))
                .put("decay_ms", rd(e.decayMs, 0)))
        }
        return JSONObject()
            .put("duration_s", rd(r.duration, 3))
            .put("level_db", rd(r.levelDb + calibrationDb, 1))
            .put("peak_db", rd(r.peakDb, 1))
            .put("calibrated", calibrationDb != 0.0)
            .put("fundamental_hz", r.f0?.let { rd(it, 1) } ?: JSONObject.NULL)
            .put("note", note?.label ?: JSONObject.NULL)
            .put("dominant_hz", rd(r.peakHz, 1))
            .put("events", events)
            .put("onsets", r.onsets.size)
            .put("periodic", r.periodicity.regular)
            .put("period_s", r.periodicity.periodS?.let { rd(it, 3) } ?: JSONObject.NULL)
    }

    private fun rd(v: Double, places: Int): Double {
        if (v.isNaN() || v.isInfinite()) return 0.0
        val m = Math.pow(10.0, places.toDouble())
        return Math.round(v * m) / m
    }
}
