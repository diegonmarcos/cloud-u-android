package com.diegonmarcos.cloudcalc.sheet

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** A confidence interval of the mean at the two-sided [level] (0.67 = 67%): [low, high]. */
data class Ci(val level: Double, val low: Double, val high: Double)

/**
 * What the status bar shows for a selection: numbers only (text, booleans and empty cells are ignored; errors are
 * not numbers and are only counted in [errors]). [stdev] is the SAMPLE standard deviation (n-1), null for one value.
 * [ci] is the interval of the mean, mean ± q·s/√n, empty for one value; q is Student's t with n-1 degrees of
 * freedom when n < [Stat.T_BELOW] ([dist] = "t") and the normal z otherwise ([dist] = "z").
 */
data class Stats(
    val n: Int, val errors: Int,
    val sum: Double, val avg: Double, val median: Double, val stdev: Double?, val min: Double, val max: Double,
    val ci: List<Ci>, val dist: String,
    /** The number format of the first numeric cell (top-left): the status bar shows its stats the way the cell does. */
    val format: Format = Format.GENERAL,
)

object Stat {
    /** Below this many values the interval uses Student's t; from it on, the normal distribution. */
    const val T_BELOW = 30
    val LEVELS = listOf(0.67, 0.975, 0.99)

    /** Stats of [values] (only [Value.Num] counts), or null when there is no number. */
    fun of(values: List<Value>, format: Format = Format.GENERAL): Stats? {
        val xs = values.mapNotNull { (it as? Value.Num)?.v?.takeIf { v -> !v.isNaN() && !v.isInfinite() } }
        val errors = values.count { it is Value.Err }
        val n = xs.size
        if (n == 0) return null
        val sum = xs.sum()
        val avg = sum / n
        val sorted = xs.sorted()
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        val sd = if (n > 1) sqrt(xs.sumOf { (it - avg) * (it - avg) } / (n - 1)) else null
        val useT = n < T_BELOW
        val ci = if (sd == null) emptyList() else LEVELS.map { level ->
            val p = 1 - (1 - level) / 2
            val q = if (useT) tQuantile(p, n - 1) else zQuantile(p)
            val half = q * sd / sqrt(n.toDouble())
            Ci(level, avg - half, avg + half)
        }
        return Stats(n, errors, sum, avg, median, sd, sorted.first(), sorted.last(), ci, if (useT) "t" else "z", format)
    }

    /** Stats of the cells of [area] on [sheet]; walks the sparse cells, so a whole column costs what it holds. */
    fun ofArea(wb: Workbook, sheet: String, area: Area): Stats? {
        val s = wb.find(sheet) ?: return null
        val values = ArrayList<Value>()
        var first: Long? = null
        for ((k, cell) in s.cells) {
            if (cell.raw.isEmpty()) continue
            val c = Sheet.colOf(k); val r = Sheet.rowOf(k)
            if (!area.contains(c, r)) continue
            val v = wb.value(sheet, c, r)
            values.add(v)
            if (v is Value.Num && (first == null || k < first)) first = k   // key = row-major: the top-left numeric cell
        }
        val fmt = first?.let { wb.format(sheet, Sheet.colOf(it), Sheet.rowOf(it)) } ?: Format.GENERAL
        return of(values, fmt)
    }

    // ── distributions ───────────────────────────────────────────────────────────────────────

    /** The standard normal quantile (Acklam's rational approximation, relative error 1.2e-9). */
    fun zQuantile(p: Double): Double {
        require(p > 0 && p < 1) { "p must be in (0,1)" }
        val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
        val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
        val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
        val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
        val low = 0.02425
        return when {
            p < low -> { val q = sqrt(-2 * ln(p)); tail(c, d, q) }
            p <= 1 - low -> {
                val q = p - 0.5; val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
            }
            else -> { val q = sqrt(-2 * ln(1 - p)); -tail(c, d, q) }
        }
    }

    private fun tail(c: DoubleArray, d: DoubleArray, q: Double) =
        (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)

    /** Student's t quantile for [df] degrees of freedom, by bisection on the exact CDF. */
    fun tQuantile(p: Double, df: Int): Double {
        require(p > 0 && p < 1 && df >= 1)
        if (p == 0.5) return 0.0
        if (p < 0.5) return -tQuantile(1 - p, df)
        var lo = 0.0; var hi = 1.0
        while (tCdf(hi, df) < p && hi < 1e12) hi *= 2
        repeat(200) {
            val mid = (lo + hi) / 2
            if (tCdf(mid, df) < p) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** Student's t CDF at [t] for [df] degrees of freedom. */
    fun tCdf(t: Double, df: Int): Double {
        val x = df / (df + t * t)
        val tail = 0.5 * betaI(x, df / 2.0, 0.5)
        return if (t >= 0) 1 - tail else tail
    }

    private fun lnGamma(x: Double): Double {
        val g = doubleArrayOf(76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5)
        var y = x
        var tmp = x + 5.5
        tmp -= (x + 0.5) * ln(tmp)
        var ser = 1.000000000190015
        for (j in 0..5) { y += 1; ser += g[j] / y }
        return -tmp + ln(2.5066282746310005 * ser / x)
    }

    /** The regularised incomplete beta function I_x(a, b) (continued fraction, Lentz). */
    private fun betaI(x: Double, a: Double, b: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        val bt = exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * ln(x) + b * ln(1 - x))
        return if (x < (a + 1) / (a + b + 2)) bt * betaCf(x, a, b) / a else 1 - bt * betaCf(1 - x, b, a) / b
    }

    private fun betaCf(x: Double, a: Double, b: Double): Double {
        val tiny = 1e-300
        val qab = a + b; val qap = a + 1; val qam = a - 1
        var c = 1.0
        var d = 1 - qab * x / qap
        if (abs(d) < tiny) d = tiny
        d = 1 / d
        var h = d
        for (m in 1..300) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1 + aa * d; if (abs(d) < tiny) d = tiny
            c = 1 + aa / c; if (abs(c) < tiny) c = tiny
            d = 1 / d; h *= d * c
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1 + aa * d; if (abs(d) < tiny) d = tiny
            c = 1 + aa / c; if (abs(c) < tiny) c = tiny
            d = 1 / d
            val del = d * c
            h *= del
            if (abs(del - 1) < 3e-16) break
        }
        return h
    }
}
