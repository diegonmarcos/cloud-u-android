package com.diegonmarcos.cloudcalc.measure

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Unit conversions, all declared (build.json::sensors.conversions): to = from * factor + offset. */
object Units {
    /** [v] in [from] as [to]: the declared conversion, else the inverse of the declared opposite one; null when none is declared. */
    fun convert(table: List<Sensors.Conversion>, v: Double, from: String, to: String): Double? {
        if (from == to) return v
        table.firstOrNull { it.from == from && it.to == to }?.let { return v * it.factor + it.offset }
        table.firstOrNull { it.from == to && it.to == from }?.let { return if (it.factor == 0.0) null else (v - it.offset) / it.factor }
        return null
    }
}

/** Words for a number: the first band whose max is at least the value, else the last. */
object Bands {
    fun label(bands: List<Sensors.Band>, v: Double): String? = (bands.firstOrNull { v <= it.max } ?: bands.lastOrNull())?.label
}

/** The named formulas build.json::sensors may use for a derived quantity. Each takes the values in the order its doc states. */
object Formulas {
    const val STANDARD_GRAVITY = 9.80665

    val names: Set<String> = setOf(
        "identity", "scale", "norm", "g_force", "tilt_deg", "altitude_from_pressure",
        "dew_point", "heat_index", "absolute_humidity", "signal_percent", "azimuth_deg", "pitch_deg", "roll_deg",
    )

    /** [v] in the order the formula documents; null when the inputs cannot give an answer (a missing value, a zero vector, a humidity of 0). */
    fun apply(fn: String, v: DoubleArray, args: Map<String, Double>): Double? = when (fn) {
        // v[0]
        "identity" -> v.getOrNull(0)
        // v[0] * factor + offset (steps to metres: factor = stride; steps to kcal)
        "scale" -> v.getOrNull(0)?.let { it * (args["factor"] ?: 1.0) + (args["offset"] ?: 0.0) }
        // the length of the vector v
        "norm" -> if (v.isEmpty()) null else norm(v)
        // |a| in g, from an acceleration in m/s2
        "g_force" -> if (v.isEmpty()) null else norm(v) / STANDARD_GRAVITY
        // angle in degrees between the gravity vector (x, y, z) and the screen normal: 0 flat face up, 90 on its edge, 180 face down
        "tilt_deg" -> if (v.size < 3 || norm(v) < 1e-9) null else Math.toDegrees(acos((v[2] / norm(v)).coerceIn(-1.0, 1.0)))
        // barometric altitude in metres: v[0] pressure hPa, args.sea_level_hpa
        "altitude_from_pressure" -> altitude(v.getOrNull(0) ?: return null, args["sea_level_hpa"] ?: 1013.25)
        // v[0] relative humidity %, v[1] temperature C: Magnus dew point in C
        "dew_point" -> if (v.size < 2) null else dewPoint(v[1], v[0])
        // v[0] relative humidity %, v[1] temperature C: Rothfusz heat index in C
        "heat_index" -> if (v.size < 2) null else heatIndex(v[1], v[0])
        // v[0] relative humidity %, v[1] temperature C: water vapour in g/m3
        "absolute_humidity" -> if (v.size < 2) null else absoluteHumidity(v[1], v[0])
        // v[0] dBm to 0..100 % between args.min and args.max
        "signal_percent" -> signalPercent(v.getOrNull(0) ?: return null, args["min"] ?: -100.0, args["max"] ?: -50.0)
        // the rotation vector (x, y, z[, w]) as the orientation Android's SensorManager.getOrientation reports, in degrees
        "azimuth_deg" -> orientationOf(v)?.let { wrap360(Math.toDegrees(it[0])) }
        "pitch_deg" -> orientationOf(v)?.let { Math.toDegrees(it[1]) }
        "roll_deg" -> orientationOf(v)?.let { Math.toDegrees(it[2]) }
        else -> null
    }

    fun norm(v: DoubleArray): Double = sqrt(v.sumOf { it * it })

    fun wrap360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** International barometric formula. */
    fun altitude(pHpa: Double, seaLevelHpa: Double): Double? =
        if (pHpa <= 0.0 || seaLevelHpa <= 0.0) null else 44330.0 * (1.0 - (pHpa / seaLevelHpa).pow(1.0 / 5.255))

    /** Magnus formula (a = 17.62, b = 243.12 C); null at 0 % or below. */
    fun dewPoint(tC: Double, rh: Double): Double? {
        if (rh <= 0.0) return null
        val g = ln(rh / 100.0) + 17.62 * tC / (243.12 + tC)
        return 243.12 * g / (17.62 - g)
    }

    /** Rothfusz regression (NWS) in Fahrenheit, applied from 80 F up; below that the heat index is the air temperature. */
    fun heatIndex(tC: Double, rh: Double): Double {
        val t = tC * 1.8 + 32.0
        if (t < 80.0) return tC
        val hi = -42.379 + 2.04901523 * t + 10.14333127 * rh - 0.22475541 * t * rh - 0.00683783 * t * t -
            0.05481717 * rh * rh + 0.00122874 * t * t * rh + 0.00085282 * t * rh * rh - 0.00000199 * t * t * rh * rh
        return (hi - 32.0) / 1.8
    }

    /** Grams of water vapour per cubic metre of air. */
    fun absoluteHumidity(tC: Double, rh: Double): Double =
        6.112 * exp(17.67 * tC / (tC + 243.5)) * rh * 2.1674 / (273.15 + tC)

    fun signalPercent(dbm: Double, minDbm: Double, maxDbm: Double): Double? =
        if (maxDbm <= minDbm) null else ((dbm - minDbm) / (maxDbm - minDbm)).coerceIn(0.0, 1.0) * 100.0

    /** Row-major 3x3 rotation matrix of the rotation vector (x, y, z[, w]); w is rebuilt from x, y, z when absent. Null for a vector shorter than 3. */
    fun rotationMatrix(v: DoubleArray): DoubleArray? {
        if (v.size < 3) return null
        val x = v[0]; val y = v[1]; val z = v[2]
        val w = if (v.size >= 4) v[3] else sqrt(max(0.0, 1.0 - x * x - y * y - z * z))
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }

    /** (azimuth, pitch, roll) in radians from a rotation matrix, as SensorManager.getOrientation. */
    fun orientation(m: DoubleArray): DoubleArray = doubleArrayOf(atan2(m[1], m[4]), asin((-m[7]).coerceIn(-1.0, 1.0)), atan2(-m[6], m[8]))

    private fun orientationOf(v: DoubleArray): DoubleArray? = rotationMatrix(v)?.let { orientation(it) }

    /**
     * Compass heading in degrees (0 = magnetic north, clockwise) from gravity (ax, ay, az) and the magnetic field (mx, my, mz) in the
     * phone's axes, tilt-compensated like SensorManager.getRotationMatrix. Null when the field is parallel to gravity or either is zero.
     */
    fun heading(ax: Double, ay: Double, az: Double, mx: Double, my: Double, mz: Double): Double? {
        val hx = my * az - mz * ay
        val hy = mz * ax - mx * az
        val hz = mx * ay - my * ax
        val normH = sqrt(hx * hx + hy * hy + hz * hz)
        if (normH < 0.1) return null
        val inv = 1.0 / normH
        val nhx = hx * inv; val nhy = hy * inv; val nhz = hz * inv
        val normA = sqrt(ax * ax + ay * ay + az * az)
        if (normA < 1e-9) return null
        val nax = ax / normA; val nay = ay / normA; val naz = az / normA
        val mY = naz * nhx - nax * nhz
        return wrap360(Math.toDegrees(atan2(nhy, mY)))
    }
}

/** A rolling window of samples and what they say about shaking (the Seismometer). */
object Vibration {
    /** [rms] and [peak] are taken about the window's mean, so gravity or any steady offset cancels. */
    data class Stats(val mean: Double, val rms: Double, val peak: Double, val count: Int)

    fun stats(samples: List<Double>): Stats? {
        if (samples.isEmpty()) return null
        val mean = samples.sum() / samples.size
        val dev = samples.map { it - mean }
        return Stats(mean, sqrt(dev.sumOf { it * it } / dev.size), dev.maxOf { abs(it) }, samples.size)
    }

    class Window(private val size: Int) {
        private val buf = ArrayDeque<Double>()
        fun add(v: Double) { buf.addLast(v); while (buf.size > size) buf.removeFirst() }
        fun stats(): Stats? = stats(buf.toList())
        fun clear() = buf.clear()
        val count: Int get() = buf.size
    }
}

/** The Metal detector's reference: the mean of the first [n] samples, then the difference of each later one from it. */
class Baseline(private val n: Int) {
    private var sum = 0.0
    private var seen = 0
    val ready: Boolean get() = seen >= n
    val value: Double? get() = if (ready) sum / n else null
    fun add(v: Double) { if (!ready) { sum += v; seen++ } }
    fun delta(v: Double): Double? = value?.let { v - it }
    fun reset() { sum = 0.0; seen = 0 }
}

/** Step cadence from the instants of detected steps. */
object Cadence {
    /** Steps per minute over the events within [windowMs] before [nowMs]; null with fewer than two. */
    fun perMinute(timesMs: List<Long>, nowMs: Long, windowMs: Long = 10_000L): Double? {
        val recent = timesMs.filter { it >= nowMs - windowMs && it <= nowMs }
        if (recent.size < 2) return null
        val span = recent.last() - recent.first()
        return if (span <= 0L) null else (recent.size - 1) * 60_000.0 / span
    }
}

/** On-screen geometry for the Ruler and the Protractor. */
object Screen {
    const val MM_PER_INCH = 25.4
    fun mmToPx(mm: Double, dpi: Double): Double = mm / MM_PER_INCH * dpi
    fun pxToMm(px: Double, dpi: Double): Double = px / dpi * MM_PER_INCH

    /** Angle of the point (x, y) about (cx, cy), counter-clockwise from the +x axis with y up (screen y grows down), 0 until 360. */
    fun angleDeg(cx: Double, cy: Double, x: Double, y: Double): Double = Formulas.wrap360(Math.toDegrees(atan2(cy - y, x - cx)))

    /** The smaller angle between two arms, 0 to 180. */
    fun between(a: Double, b: Double): Double {
        val d = abs(Formulas.wrap360(a) - Formulas.wrap360(b))
        return min(d, 360.0 - d)
    }
}
