package com.diegonmarcos.cloudcalc.audio

import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.cloudcalc.sound.Analysis
import com.diegonmarcos.cloudcalc.sound.Generator
import org.json.JSONObject

/**
 * build.json::sound (#772), decoded once: the analysis thresholds, the three per-device knobs'
 * defaults, how long to listen, the history session and the generator's safe-volume guard.
 * [parse] is pure so DeclarationsTest runs it against this repository's own build.json.
 */
object SoundDecl {
    data class History(val refreshMs: Int, val maxSamples: Int, val rows: Int, val bands: Int, val fminHz: Double, val maxWavSeconds: Int)

    data class Config(
        val sampleRate: Int,
        val analysis: Analysis.Params,
        val calibrationDb: Double,
        val a4Hz: Double,
        val temperatureC: Double,
        val recordMs: Int,
        val maxRecordMs: Int,
        val history: History,
        val limits: Generator.Limits,
        val generatorDefaults: Generator.Spec,
        val spectrogramWidth: Int,
        val spectrogramHeight: Int,
    )

    val config: Config by lazy { parse(String(java.util.Base64.getDecoder().decode(BuildConfig.SOUND_CONFIG_B64), Charsets.UTF_8)) }

    fun parse(json: String): Config {
        val o = JSONObject(json)
        val a = o.getJSONObject("analysis")
        val d = Analysis.Params()
        val def = o.getJSONObject("defaults")
        val h = o.getJSONObject("history")
        val g = o.getJSONObject("generator")
        val gd = g.getJSONObject("defaults")
        val sp = o.getJSONObject("identify").getJSONObject("spectrogram")
        return Config(
            sampleRate = o.getInt("sample_rate"),
            analysis = Analysis.Params(
                frame = a.optInt("frame", d.frame), hop = a.optInt("hop", d.hop), block = a.optInt("block", d.block),
                fmin = a.optDouble("fmin", d.fmin), fmax = a.optDouble("fmax", d.fmax), yinThreshold = a.optDouble("yin_threshold", d.yinThreshold),
                gateDb = a.optDouble("gate_db", d.gateDb), silenceDb = a.optDouble("silence_db", d.silenceDb),
                minEventMs = a.optDouble("min_event_ms", d.minEventMs), mergeGapMs = a.optDouble("merge_gap_ms", d.mergeGapMs),
                impulseMs = a.optDouble("impulse_ms", d.impulseMs), impulseDecayMs = a.optDouble("impulse_decay_ms", d.impulseDecayMs),
                noiseFlatness = a.optDouble("noise_flatness", d.noiseFlatness), toneStability = a.optDouble("tone_stability", d.toneStability),
                chirpChange = a.optDouble("chirp_change", d.chirpChange), periodicCv = a.optDouble("periodic_cv", d.periodicCv),
                harmonics = a.optInt("harmonics", d.harmonics), onsetK = a.optDouble("onset_k", d.onsetK),
                onsetWindow = a.optInt("onset_window", d.onsetWindow), onsetDelta = a.optDouble("onset_delta", d.onsetDelta),
                onsetRiseDb = a.optDouble("onset_rise_db", d.onsetRiseDb),
            ),
            calibrationDb = def.getDouble("calibration_db"),
            a4Hz = def.getDouble("a4_hz"),
            temperatureC = def.getDouble("temperature_c"),
            recordMs = o.getInt("record_ms"),
            maxRecordMs = o.getInt("max_record_ms"),
            history = History(h.getInt("refresh_ms"), h.getInt("max_samples"), h.getInt("waterfall_rows"), h.getInt("bands"), h.getDouble("fmin_hz"), h.getInt("max_wav_seconds")),
            limits = Generator.Limits(
                maxAmplitude = g.getDouble("max_amplitude"), minHz = g.getDouble("min_hz"), maxHz = g.getDouble("max_hz"),
                maxBeatHz = g.getDouble("max_beat_hz"), maxMs = g.getInt("max_ms"), fadeMs = g.getInt("fade_ms"),
                loudVolume = g.getDouble("loud_volume"), loudAmplitude = g.getDouble("loud_amplitude"),
            ),
            generatorDefaults = Generator.Spec(
                gd.getString("kind"), gd.getString("wave"), gd.getDouble("f1"), gd.getDouble("f2"), gd.getDouble("amplitude"), gd.getInt("ms"),
            ),
            spectrogramWidth = sp.getInt("width"),
            spectrogramHeight = sp.getInt("height"),
        )
    }
}
