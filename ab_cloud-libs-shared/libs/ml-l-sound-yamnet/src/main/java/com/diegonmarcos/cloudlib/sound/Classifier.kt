package com.diegonmarcos.cloudlib.sound

import com.diegonmarcos.superapp.soundtags.Signal
import com.diegonmarcos.superapp.soundtags.Tags
import com.diegonmarcos.superapp.soundtags.Wav
import org.json.JSONObject

/**
 * #798 one clip in, one Recognition-shaped answer out: the WAV decoded to mono, resampled to the
 * model's rate, capped at the request's max_ms, cut into the model's windows, each window scored
 * by [scorer], and the scores averaged and ranked (libs:sound-tags). Everything the answer is
 * filtered by arrives in the request (libs:ml-l-sound/sound.json via SoundConfig.request), so the
 * engine holds no thresholds of its own. Never throws: a failure is {"ok": false, "error"}.
 */
internal class Classifier(
    private val scorer: Scorer,
    private val labels: () -> List<String>,
    private val model: String,
    private val window: Int,
    private val rate: Int,
    private val hop: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The model's half, behind an interface so the suite can stand in for the TFLite interpreter. */
    fun interface Scorer {
        fun score(window: FloatArray): FloatArray
    }

    fun json(wav: ByteArray, request: String): String =
        runCatching { classify(wav, JSONObject(request)).toString() }
            .getOrElse {
                JSONObject().put("ok", false).put("route", "ml").put("requested", "ml")
                    .put("error", it.message ?: it.javaClass.simpleName).toString()
            }

    fun classify(wav: ByteArray, req: JSONObject): JSONObject {
        val ml = req.optJSONObject("ml") ?: JSONObject()
        val t0 = clock()
        val pcm = Wav.decode(wav)
        require(pcm.samples.isNotEmpty()) { "the clip has no samples" }
        val all = Signal.resample(pcm.samples, pcm.rate, rate)
        val cap = (ml.optLong("max_ms", 30_000L) * rate / 1000).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val x = if (all.size > cap) all.copyOf(cap) else all
        val names = labels()
        val scores = Signal.windows(x, window, hop).map { scorer.score(it) }
        val durationMs = x.size * 1000L / rate
        val tagging = Tags.aggregate(
            scores, names, ml.optInt("top_k", 5), ml.optDouble("min_score", 0.05),
            hopMs = hop * 1000L / rate, windowMs = window * 1000L / rate, durationMs = durationMs,
            segmentMin = ml.optDouble("segment_min", 0.3),
        )
        return Tags.json(tagging, model, clock() - t0, durationMs, Signal.peak(x))
    }
}
