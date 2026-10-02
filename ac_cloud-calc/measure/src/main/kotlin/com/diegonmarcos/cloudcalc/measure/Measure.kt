package com.diegonmarcos.cloudcalc.measure

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** A point on a photo, in pixels. */
data class Px(val x: Double, val y: Double)

/** A point in ARCore's world, in metres; +y is up. */
data class Vec(val x: Double, val y: Double, val z: Double)

/**
 * Measuring on a photo by a reference object of known size (#772, the route when ARCore is not
 * there): the user marks the reference's known edge, which gives millimetres per pixel, then the
 * thing to measure. Only true in the reference's own plane, square to the camera — the screen
 * says so; a declared length the user measured (a ruler) calibrates it instead of an object.
 */
object Reference {
    fun distance(a: Px, b: Px): Double = hypot(b.x - a.x, b.y - a.y)

    fun pathLength(ps: List<Px>): Double = ps.zipWithNext { a, b -> distance(a, b) }.sum()

    /** The shoelace area of a simple polygon, whatever its winding. */
    fun polygonArea(ps: List<Px>): Double {
        if (ps.size < 3) return 0.0
        var s = 0.0
        for (i in ps.indices) {
            val a = ps[i]; val b = ps[(i + 1) % ps.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2
    }

    /** Millimetres per pixel from a reference segment [a]–[b] that is [mm] long. */
    fun mmPerPx(a: Px, b: Px, mm: Double): Double {
        val px = distance(a, b)
        require(px > 0) { "the two reference marks are the same point" }
        require(mm > 0) { "the reference length must be positive" }
        return mm / px
    }

    data class Result(val lengthMm: Double, val pathMm: Double, val areaMm2: Double?)

    /**
     * The marks [ps] measured at [mmPerPx]: the straight length first-to-last, the path through
     * every mark, and — with three marks or more — the area they enclose.
     */
    fun measure(ps: List<Px>, mmPerPx: Double): Result {
        require(ps.size >= 2) { "mark at least two points" }
        return Result(
            distance(ps.first(), ps.last()) * mmPerPx,
            pathLength(ps) * mmPerPx,
            if (ps.size >= 3) polygonArea(ps) * mmPerPx * mmPerPx else null,
        )
    }
}

/** The AR route's geometry over anchors in world space (metres). */
object Space {
    fun distance(a: Vec, b: Vec): Double {
        val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun pathLength(ps: List<Vec>): Double = ps.zipWithNext { a, b -> distance(a, b) }.sum()

    /** The vertical distance between two anchors: ARCore's world +y is up, against gravity. */
    fun height(a: Vec, b: Vec): Double = abs(b.y - a.y)

    /**
     * The area of a planar polygon in 3-D by Newell's method: half the length of the summed
     * cross products, so it holds on a floor, a wall or a tilted table alike.
     */
    fun polygonArea(ps: List<Vec>): Double {
        if (ps.size < 3) return 0.0
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (i in ps.indices) {
            val a = ps[i]; val b = ps[(i + 1) % ps.size]
            nx += (a.y - b.y) * (a.z + b.z)
            ny += (a.z - b.z) * (a.x + b.x)
            nz += (a.x - b.x) * (a.y + b.y)
        }
        return sqrt(nx * nx + ny * ny + nz * nz) / 2
    }
}

/**
 * The inclinometer, from the gravity vector in the phone's own axes (x right, y up the screen,
 * z out of the screen; a phone lying face up reads g = (0, 0, +9.81)). Every angle is degrees.
 */
object Level {
    data class Tilt(
        /** Away from lying flat, whichever way (0 flat, 90 standing). */
        val tiltDeg: Double,
        /** Rotation about the phone's x axis: top edge up (+) or down. */
        val pitchDeg: Double,
        /** Rotation about the phone's y axis: right edge down (+) or up. */
        val rollDeg: Double,
        /** Standing on an edge: the screen plane's slope from vertical, for a wall or a door frame. */
        val edgeDeg: Double,
    )

    /** Null when there is no gravity to read (a vector shorter than [minG], e.g. free fall). */
    fun tilt(gx: Double, gy: Double, gz: Double, minG: Double = 1.0): Tilt? {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g < minG) return null
        return Tilt(
            Math.toDegrees(acos((gz / g).coerceIn(-1.0, 1.0))),
            Math.toDegrees(atan2(gy, gz)),
            Math.toDegrees(atan2(gx, gz)),
            Math.toDegrees(atan2(gx, gy)),
        )
    }

    /** [t] against a stored [zero] (the user's "set zero" on a reference surface), each angle wrapped to ±180. */
    fun relative(t: Tilt, zero: Tilt): Tilt = Tilt(
        t.tiltDeg - zero.tiltDeg, wrap(t.pitchDeg - zero.pitchDeg), wrap(t.rollDeg - zero.rollDeg), wrap(t.edgeDeg - zero.edgeDeg),
    )

    fun wrap(deg: Double): Double {
        var d = deg % 360
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    /** Level within [toleranceDeg] about both axes (a bubble in its ring). */
    fun isLevel(t: Tilt, toleranceDeg: Double): Boolean = abs(t.pitchDeg) <= toleranceDeg && abs(t.rollDeg) <= toleranceDeg
}

/** The numbers a recognised text holds, ready for the calculator (#772 OCR → Calc). */
object Numbers {
    /** A minus sign counts only where it cannot be a hyphen (not right after a letter or digit). */
    private val NUMBER = Regex("""(?:(?<![\w])-)?\d+(?:[.,]\d+)*""")

    /** In reading order, each normalised to the calculator's decimal point. */
    fun of(text: String): List<String> = NUMBER.findAll(text).map { normalise(it.value) }.toList()

    /**
     * Both a point and a comma: the last is the decimal mark ("1,234.50", "1.234,50"). One kind,
     * repeated: thousands ("1.234.567"). One separator: the decimal mark ("12,50", "3.14159"),
     * except exactly three digits after a non-zero integer part, which reads as thousands
     * ("1,234") — ponytail: the one ambiguous shape, read the way receipts and prices print it.
     */
    fun normalise(s: String): String {
        val neg = s.startsWith("-")
        val t = s.removePrefix("-")
        val dots = t.count { it == '.' }
        val commas = t.count { it == ',' }
        val body = when {
            dots > 0 && commas > 0 -> {
                val dec = if (t.lastIndexOf('.') > t.lastIndexOf(',')) '.' else ','
                t.replace((if (dec == '.') ',' else '.').toString(), "").replace(dec, '.')
            }
            dots + commas == 0 -> t
            dots + commas > 1 -> t.replace(".", "").replace(",", "")
            else -> {
                val i = t.indexOfFirst { it == '.' || it == ',' }
                if (t.length - i - 1 == 3 && !t.startsWith("0")) t.removeRange(i, i + 1) else t.replace(',', '.')
            }
        }
        return if (neg) "-$body" else body
    }

    /** "a + b + c": the numbers as one sum the calculator evaluates. */
    fun sum(numbers: List<String>): String = numbers.joinToString(" + ")
}
