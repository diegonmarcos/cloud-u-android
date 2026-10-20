package com.diegonmarcos.cloudcalc.web

/**
 * The Standard keypad's arithmetic: + - × ÷ over decimals, unary minus, % as /100. No engine (the
 * Android app computes through libqalculate; the web page has none), so this is the page's own
 * small evaluator, total: bad input is a null, never an exception.
 */
internal object Eval {
    /** The text of [expr] as a number, or null when it is not an expression (also on ÷ 0). */
    fun of(expr: String): Double? {
        val p = Parser(expr.filter { !it.isWhitespace() })
        val v = p.sum() ?: return null
        return if (p.atEnd() && v.isFinite()) v else null
    }

    /** 12.0 -> "12", 0.1 + 0.2 -> "0.3": ten significant digits, trailing zeros dropped. */
    fun show(v: Double): String {
        if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        var s = ((v * 1e10).let { kotlin.math.round(it) } / 1e10).toString()
        if (s.contains('E') || s.contains('e')) return v.toString()
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    private class Parser(private val s: String) {
        private var i = 0
        fun atEnd() = i >= s.length

        fun sum(): Double? {
            var acc = product() ?: return null
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = product() ?: return null
                acc = if (op == '+') acc + r else acc - r
            }
            return acc
        }

        private fun product(): Double? {
            var acc = unary() ?: return null
            while (i < s.length && (s[i] == '×' || s[i] == '÷')) {
                val op = s[i++]
                val r = unary() ?: return null
                if (op == '÷' && r == 0.0) return null
                acc = if (op == '×') acc * r else acc / r
            }
            return acc
        }

        private fun unary(): Double? {
            if (i < s.length && s[i] == '-') { i++; return unary()?.let { -it } }
            var v = number() ?: return null
            while (i < s.length && s[i] == '%') { i++; v /= 100.0 }
            return v
        }

        private fun number(): Double? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return if (i > start) s.substring(start, i).toDoubleOrNull() else null
        }
    }
}
