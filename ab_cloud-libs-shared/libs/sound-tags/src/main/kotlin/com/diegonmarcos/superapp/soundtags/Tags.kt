package com.diegonmarcos.superapp.soundtags

import org.json.JSONArray
import org.json.JSONObject

data class Tag(val index: Int, val label: String, val p: Double)
data class Segment(val label: String, val p: Double, val startMs: Long, val endMs: Long)
class Tagging(val labels: List<Tag>, val segments: List<Segment>, val windows: Int)

/**
 * #798 a model's per-window class scores turned into what the apps show: the mean score of every
 * class over the clip, the top [topK] at or above [minScore] (YAMNet's own recipe: average the
 * frames, then rank), and a timeline — each window's best class at or above [segmentMin], merged
 * while it repeats and the windows touch.
 */
object Tags {
    fun aggregate(
        scores: List<FloatArray>, names: List<String>, topK: Int, minScore: Double,
        hopMs: Long, windowMs: Long, durationMs: Long, segmentMin: Double,
    ): Tagging {
        require(scores.isNotEmpty()) { "no window was scored" }
        val n = names.size
        for (s in scores) require(s.size == n) { "a window scored ${s.size} classes; the model names $n" }
        val mean = DoubleArray(n)
        for (s in scores) for (i in 0 until n) mean[i] += s[i].toDouble()
        for (i in 0 until n) mean[i] /= scores.size
        val top = (0 until n).filter { mean[it] >= minScore }.sortedByDescending { mean[it] }.take(topK)
            .map { Tag(it, names[it], round3(mean[it])) }
        val segments = ArrayList<Segment>()
        scores.forEachIndexed { w, s ->
            val best = s.indices.maxByOrNull { s[it] } ?: return@forEachIndexed
            val p = round3(s[best].toDouble())
            if (p < segmentMin) return@forEachIndexed
            val start = w * hopMs
            val end = minOf(start + windowMs, maxOf(durationMs, start + 1))
            val last = segments.lastOrNull()
            if (last != null && last.label == names[best] && start <= last.endMs) {
                segments[segments.size - 1] = last.copy(p = maxOf(last.p, p), endMs = end)
            } else {
                segments.add(Segment(names[best], p, start, end))
            }
        }
        return Tagging(top, segments, scores.size)
    }

    /**
     * The answer in the shape libs:ml-l-image's Recognition parses — the fleet's ONE result type
     * for image and sound alike — plus the timeline and what was heard.
     */
    fun json(t: Tagging, model: String, latencyMs: Long, durationMs: Long, peak: Float): JSONObject = JSONObject()
        .put("ok", true).put("route", "ml").put("requested", "ml").put("fell_back", false).put("reason", "")
        .put("labels", JSONArray().apply { t.labels.forEach { put(JSONObject().put("label", it.label).put("p", it.p).put("index", it.index)) } })
        .put("segments", JSONArray().apply {
            t.segments.forEach { put(JSONObject().put("label", it.label).put("p", it.p).put("start_ms", it.startMs).put("end_ms", it.endMs)) }
        })
        .put("boxes", JSONArray()).put("text", "").put("colours", JSONArray()).put("barcode", JSONObject.NULL)
        .put("answers", JSONObject()).put("cost", JSONObject.NULL).put("width", 0).put("height", 0)
        .put("model", model).put("latency_ms", latencyMs).put("duration_ms", durationMs)
        .put("windows", t.windows).put("peak", round3(peak.toDouble()))

    fun round3(v: Double): Double = Math.round(v * 1000) / 1000.0
}
