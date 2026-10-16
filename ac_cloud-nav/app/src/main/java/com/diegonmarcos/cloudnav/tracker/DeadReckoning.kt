package com.diegonmarcos.cloudnav.tracker

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** One stretch of walking: [steps] taken while heading [headingDeg] (0 = north, clockwise). */
data class Leg(val steps: Int, val headingDeg: Double)

/** A position estimate: [radiusM] is the uncertainty circle; [predicted] marks it as not measured. */
data class Estimate(val lat: Double, val lon: Double, val radiusM: Double, val predicted: Boolean = true)

/**
 * Dead reckoning from the last accurate fix: position = anchor + sum(steps x stepLength along the
 * heading of each leg). The step length is calibrated from accurate fixes ([calibrate]); the
 * uncertainty grows with the distance walked ([radiusM]); the next accurate fix replaces the
 * estimate (snap back), so errors never accumulate across fixes.
 */
object DeadReckoning {
    const val DEFAULT_STEP_M = 0.72
    private const val EARTH_M = 6_371_000.0
    private const val MIN_STEP_M = 0.4
    private const val MAX_STEP_M = 1.1
    private const val EMA = 0.3

    /** Uncertainty added per metre walked: heading noise plus step-length error. */
    const val ERROR_PER_METRE = 0.15

    fun haversineM(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_M * asin(min(1.0, sqrt(h)))
    }

    /** The point [distM] metres from [from] along [headingDeg]. */
    fun displace(from: LatLon, headingDeg: Double, distM: Double): LatLon {
        val d = distM / EARTH_M; val th = Math.toRadians(headingDeg)
        val p1 = Math.toRadians(from.lat); val l1 = Math.toRadians(from.lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(th))
        val l2 = l1 + atan2(sin(th) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), ((Math.toDegrees(l2) + 540.0) % 360.0) - 180.0)
    }

    /**
     * Step length measured between two accurate fixes: straight-line distance / steps. Null when
     * the sample can't be trusted (too few steps, or an implausible stride).
     */
    fun measuredStepM(from: LatLon, to: LatLon, steps: Long): Double? {
        if (steps < 300) return null
        val s = haversineM(from, to) / steps
        return if (s in MIN_STEP_M..MAX_STEP_M) s else null
    }

    /** Folds a [sample] into the running step length (exponential average), clamped to a human stride. */
    fun calibrate(current: Double, sample: Double?): Double =
        if (sample == null) current else (current * (1 - EMA) + sample * EMA).coerceIn(MIN_STEP_M, MAX_STEP_M)

    /** Uncertainty radius: the anchor's own accuracy plus [ERROR_PER_METRE] of the distance walked. */
    fun radiusM(anchorAccuracyM: Double, steps: Int, stepM: Double): Double =
        max(anchorAccuracyM, 0.0) + ERROR_PER_METRE * steps * stepM

    fun predict(anchor: LatLon, anchorAccuracyM: Double, legs: List<Leg>, stepM: Double): Estimate {
        var at = anchor
        var total = 0
        for (l in legs) {
            if (l.steps <= 0) continue
            at = displace(at, l.headingDeg, l.steps * stepM)
            total += l.steps
        }
        return Estimate(at.lat, at.lon, radiusM(anchorAccuracyM, total, stepM))
    }
}
