package com.diegonmarcos.superapp.launcher

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Drawn-shape gestures on the Home screen (a circle, a "C"): a small deterministic template recogniser, no learning, no
 * randomness. The templates and every tolerance are DATA (build.json onehand.shape_gestures -> BuildConfig.HOME_SHAPE_GESTURES_B64).
 *
 * A stroke is resampled to [Config.resample] points, scaled uniformly into a unit box and centred on its centroid (so size and
 * position are free), then compared with each template under every rotation in [Config.rotationsDeg] (so tilt is tolerated within
 * that range only). The distance is the mean two-way nearest-point distance: every stroke point near some template point AND every
 * template point near some stroke point, so an open "C" never matches a closed circle and a line matches neither. Start point and
 * drawing direction do not matter. The best template is accepted when its distance is at most [Config.maxDistance] and it beats the
 * runner-up by [Config.minMargin].
 */
object ShapeRecogniser {
    data class Template(val id: String, val label: String, val action: String, val points: List<Pair<Float, Float>>)
    data class Config(
        val minLengthDp: Float, val maxStraightness: Float, val resample: Int, val maxDistance: Float,
        val minMargin: Float, val rotationsDeg: List<Float>, val templates: List<Template>,
    )
    data class Match(val id: String, val action: String, val distance: Float)

    /** Stroke class: a long, curved stroke is a SHAPE (recogniser); everything else is left to the swipe detector. */
    enum class Kind { SWIPE, SHAPE }

    fun pathLength(pts: List<Pair<Float, Float>>): Float {
        var l = 0f
        for (i in 1 until pts.size) l += hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
        return l
    }

    fun classify(pts: List<Pair<Float, Float>>, cfg: Config, density: Float): Kind {
        if (pts.size < 3) return Kind.SWIPE
        val len = pathLength(pts)
        if (len < cfg.minLengthDp * density) return Kind.SWIPE
        val gap = hypot(pts.last().first - pts.first().first, pts.last().second - pts.first().second)
        return if (gap / len < cfg.maxStraightness) Kind.SHAPE else Kind.SWIPE
    }

    fun recognise(stroke: List<Pair<Float, Float>>, cfg: Config): Match? {
        val s = normalise(stroke, cfg.resample) ?: return null
        val scored = cfg.templates.mapNotNull { t ->
            val tp = normalise(t.points, cfg.resample) ?: return@mapNotNull null
            t to cfg.rotationsDeg.minOf { r -> distance(rotate(s, r), tp) }
        }.sortedBy { it.second }
        val best = scored.firstOrNull() ?: return null
        if (best.second > cfg.maxDistance) return null
        val next = scored.getOrNull(1)
        if (next != null && next.second - best.second < cfg.minMargin) return null
        return Match(best.first.id, best.first.action, best.second)
    }

    /** The declared config, baked from build.json onehand.shape_gestures. */
    val declared: Config by lazy {
        parse(String(android.util.Base64.decode(com.diegonmarcos.superapp.BuildConfig.HOME_SHAPE_GESTURES_B64, android.util.Base64.NO_WRAP)))
    }

    fun parse(json: String): Config {
        val o = JSONObject(json)
        val rots = o.optJSONArray("rotations_deg")
        val gs = o.optJSONArray("gestures")
        return Config(
            minLengthDp = o.optDouble("min_length_dp", 120.0).toFloat(),
            maxStraightness = o.optDouble("max_straightness", 0.6).toFloat(),
            resample = o.optInt("resample", 32).coerceAtLeast(8),
            maxDistance = o.optDouble("max_distance", 0.085).toFloat(),
            minMargin = o.optDouble("min_margin", 0.015).toFloat(),
            rotationsDeg = if (rots == null) listOf(0f) else List(rots.length()) { rots.getDouble(it).toFloat() },
            templates = if (gs == null) emptyList() else List(gs.length()) { i ->
                val g = gs.getJSONObject(i); val p = g.getJSONArray("points")
                Template(g.getString("id"), g.optString("label", g.getString("id")), g.getString("action"),
                    List(p.length()) { j -> p.getJSONArray(j).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } })
            },
        )
    }

    private fun resample(pts: List<Pair<Float, Float>>, n: Int): List<Pair<Float, Float>>? {
        if (pts.size < 2) return null
        val cum = FloatArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
        val total = cum.last()
        if (total <= 0f) return null
        val out = ArrayList<Pair<Float, Float>>(n)
        var j = 0
        for (i in 0 until n) {
            val t = total * i / (n - 1)
            while (j < pts.size - 2 && cum[j + 1] < t) j++
            val seg = cum[j + 1] - cum[j]
            val f = if (seg <= 0f) 0f else ((t - cum[j]) / seg).coerceIn(0f, 1f)
            out += (pts[j].first + (pts[j + 1].first - pts[j].first) * f) to (pts[j].second + (pts[j + 1].second - pts[j].second) * f)
        }
        return out
    }

    private fun normalise(pts: List<Pair<Float, Float>>, n: Int): List<Pair<Float, Float>>? {
        val p = resample(pts, n) ?: return null
        val x0 = p.minOf { it.first }; val y0 = p.minOf { it.second }
        val size = max(p.maxOf { it.first } - x0, p.maxOf { it.second } - y0)
        if (size <= 0f) return null
        val u = p.map { (it.first - x0) / size to (it.second - y0) / size }
        val cx = u.sumOf { it.first.toDouble() }.toFloat() / n
        val cy = u.sumOf { it.second.toDouble() }.toFloat() / n
        return u.map { it.first - cx to it.second - cy }
    }

    private fun rotate(p: List<Pair<Float, Float>>, deg: Float): List<Pair<Float, Float>> {
        val a = deg * PI / 180.0
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return p.map { it.first * c - it.second * s to it.first * s + it.second * c }
    }

    private fun nearest(p: Pair<Float, Float>, set: List<Pair<Float, Float>>): Float {
        var m = Float.MAX_VALUE
        for (q in set) m = min(m, hypot(p.first - q.first, p.second - q.second))
        return m
    }

    private fun distance(a: List<Pair<Float, Float>>, b: List<Pair<Float, Float>>): Float =
        (a.sumOf { nearest(it, b).toDouble() } / a.size + b.sumOf { nearest(it, a).toDouble() } / b.size).toFloat() / 2f
}
