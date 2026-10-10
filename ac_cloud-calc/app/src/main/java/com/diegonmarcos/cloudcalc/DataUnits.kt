package com.diegonmarcos.cloudcalc

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/**
 * Convert ▸ Network / Data: data rates, data sizes and transfer time, in exact decimal arithmetic.
 * Every declared factor is a power of two times a power of ten, so a conversion between two of them
 * is a terminating decimal and is kept to its last digit (no four-decimal cut, no float). Pure, so the
 * JVM suite holds each rule; the units themselves are build.json::ui.modes[network].unit_sets.
 */
object DataUnits {
    /** Only a ratio that does not terminate (a factor someone declares with a 3 in it) is rounded, to 34 digits. */
    private val FALLBACK = MathContext.DECIMAL128
    private val THOUSANDS = Regex("""-?\d{1,3}(,\d{3})+(\.\d+)?""")
    private val SCI_HIGH = BigDecimal("1e21")
    private val SCI_LOW = BigDecimal("1e-12")
    private val MINUTE = BigDecimal(60)

    /** What the user typed as a number: spaces and _ ignored, 1,000,000 grouped, a lone comma a decimal comma. Null when it is not one. */
    fun parse(text: String): BigDecimal? {
        var t = text.trim().replace(" ", "").replace("_", "").replace(" ", "")
        if (t.isEmpty()) return null
        t = when {
            THOUSANDS.matches(t) -> t.replace(",", "")
            t.count { it == ',' } == 1 && '.' !in t -> t.replace(',', '.')
            else -> t
        }
        return runCatching { BigDecimal(t) }.getOrNull()
    }

    /** [x] / [y], exact when it terminates. */
    fun divide(x: BigDecimal, y: BigDecimal): BigDecimal =
        try { x.divide(y) } catch (_: ArithmeticException) { x.divide(y, FALLBACK) }

    /** [x] of [from] in [to]: through the base unit, exactly. */
    fun convert(x: BigDecimal, from: Declarations.DataUnit, to: Declarations.DataUnit): BigDecimal =
        divide(x.multiply(from.factor), to.factor)

    /** Every digit, no trailing zeros, no exponent - except past 1e21 or under 1e-12, where the digits stay but an exponent carries the size. */
    fun plain(x: BigDecimal): String {
        val s = x.stripTrailingZeros()
        if (s.signum() == 0) return "0"
        val a = s.abs()
        return if (a >= SCI_HIGH || a < SCI_LOW) s.toString() else s.toPlainString()
    }

    /** Seconds to move [size] of [sizeUnit] at [rate] of [rateUnit]; null when the rate is not above zero or the size is negative. */
    fun seconds(size: BigDecimal, sizeUnit: Declarations.DataUnit, rate: BigDecimal, rateUnit: Declarations.DataUnit): BigDecimal? {
        if (rate.signum() <= 0 || size.signum() < 0) return null
        return divide(size.multiply(sizeUnit.factor), rate.multiply(rateUnit.factor))
    }

    /**
     * A duration for reading: under a second in ms (3 decimals), under a minute in s (2 decimals),
     * from a minute on d / h / min / s rounded to the second with the zero parts left out ("5 min 20 s").
     */
    fun duration(s: BigDecimal): String {
        if (s.signum() <= 0) return "0 s"
        if (s < BigDecimal.ONE) {
            val ms = s.movePointRight(3).setScale(3, RoundingMode.HALF_UP)
            if (ms.signum() == 0) return "< 0.001 ms"
            if (ms < BigDecimal(1000)) return plain(ms) + " ms"
        }
        val two = s.setScale(2, RoundingMode.HALF_UP)
        if (two < MINUTE) return plain(two) + " s"
        var rest = s.setScale(0, RoundingMode.HALF_UP).toBigIntegerExact()
        val parts = mutableListOf<String>()
        for ((unit, len) in listOf("d" to 86_400L, "h" to 3_600L, "min" to 60L)) {
            val (q, r) = rest.divideAndRemainder(BigInteger.valueOf(len))
            if (q.signum() > 0) parts += "$q $unit"
            rest = r
        }
        if (rest.signum() > 0) parts += "$rest s"
        return parts.joinToString(" ")
    }

    /** The exact-seconds line under a duration of a minute or more ("320 s"), to the millisecond; "" below a minute. */
    fun secondsLine(s: BigDecimal): String =
        if (s < MINUTE) "" else plain(s.setScale(3, RoundingMode.HALF_UP)) + " s"
}
