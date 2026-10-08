package com.diegonmarcos.cloudlib.calc

import org.json.JSONObject

/**
 * How one `eval` is evaluated and printed — the second argument of the `eval` method, as JSON.
 * Every field is optional and an out-of-range value is clamped to the nearest one the engine
 * accepts, so a client can never put libqalculate into a state it was not tested in (the
 * golden table in native/golden.tsv uses exactly these knobs).
 */
data class EvalOptions(
    val inBase: Int = 10,
    val outBase: Int = 10,
    val precision: Int = 10,
    /** 0 none, 1 radians, 2 degrees, 3 gradians — libqalculate's AngleUnit order. */
    val angle: Int = 1,
    /** 0 exact (CAS keeps ln(3)/ln(5)), 1 exact where possible, 2 always decimal. */
    val approx: Int = 1,
    /** "1 kg to lb" as "2 lb + 3.27 oz" (true) or "2.204622622 lb" (false). */
    val mixedUnits: Boolean = true,
    val unicode: Boolean = false,
    val timeoutMs: Int = 5000,
) {
    companion object {
        /** Operator glyphs a keyboard or paste may carry become what libqalculate parses: ÷ ∕ ／ ⁄ to /, × ✕ ⋅ · to *, − to -. */
        fun normalize(expr: String): String = expr.map { c ->
            when (c) { '÷', '∕', '／', '⁄' -> '/'; '×', '✕', '⋅', '·' -> '*'; '−' -> '-'; else -> c }
        }.joinToString("")

        val BASES = setOf(2, 8, 10, 16)
        const val MAX_PRECISION = 100
        const val MAX_TIMEOUT_MS = 30_000

        fun parse(json: String?): EvalOptions {
            if (json.isNullOrBlank()) return EvalOptions()
            val o = JSONObject(json)
            val d = EvalOptions()
            fun base(key: String, def: Int) = o.optInt(key, def).let { if (it in BASES) it else def }
            return EvalOptions(
                inBase = base("in_base", d.inBase),
                outBase = base("out_base", d.outBase),
                precision = o.optInt("precision", d.precision).coerceIn(1, MAX_PRECISION),
                angle = o.optInt("angle", d.angle).coerceIn(0, 3),
                approx = o.optInt("approx", d.approx).coerceIn(0, 2),
                mixedUnits = o.optBoolean("mixed_units", d.mixedUnits),
                unicode = o.optBoolean("unicode", d.unicode),
                timeoutMs = o.optInt("timeout_ms", d.timeoutMs).coerceIn(100, MAX_TIMEOUT_MS),
            )
        }
    }
}
