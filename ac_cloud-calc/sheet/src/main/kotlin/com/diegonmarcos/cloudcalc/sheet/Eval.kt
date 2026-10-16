package com.diegonmarcos.cloudcalc.sheet

import java.math.BigDecimal
import java.math.RoundingMode

/** What the evaluator reads from the workbook. */
interface Source {
    /** The value of a cell of [sheet]. */
    fun cell(sheet: String, col: Int, row: Int): Value
    fun hasSheet(sheet: String): Boolean
    /** Now, as a serial (whole days + fraction of day). */
    fun now(): Double
}

/** Evaluates a parsed formula. [owner] is the sheet the formula lives on. */
class Evaluator(private val src: Source, private val owner: String) {
    /** A value, or an area of values (arguments of SUM etc.). */
    private sealed class R {
        class V(val v: Value) : R()
        class Area(val cells: List<Value>) : R()
    }

    fun eval(n: Node): Value = when (val r = go(n)) {
        is R.V -> if (r.v === Value.Blank) Value.Num(0.0) else r.v
        is R.Area -> Value.Err(Value.VALUE)
    }

    private fun go(n: Node): R = when (n) {
        is Node.Lit -> R.V(n.v)
        is Node.Ref -> R.V(read(n))
        is Node.Rng -> range(n)
        is Node.Neg -> R.V(unaryNum(n.e) { -it })
        is Node.Pct -> R.V(unaryNum(n.e) { it / 100 })
        is Node.Bin -> R.V(bin(n))
        is Node.Call -> R.V(call(n))
    }

    private fun unaryNum(e: Node, f: (Double) -> Double): Value {
        val (d, err) = num(go1(e))
        return err ?: Value.Num(f(d))
    }

    /** A scalar: an area in a scalar place is #VALUE!. */
    private fun go1(n: Node): Value = when (val r = go(n)) {
        is R.V -> r.v
        is R.Area -> Value.Err(Value.VALUE)
    }

    private fun read(r: Node.Ref): Value {
        val sh = r.sheet ?: owner
        if (!src.hasSheet(sh)) return Value.Err(Value.REF)
        if (r.col !in 0 until A1.MAX_COLS || r.row !in 0 until A1.MAX_ROWS) return Value.Err(Value.REF)
        return src.cell(sh, r.col, r.row)
    }

    private fun range(n: Node.Rng): R {
        val sh = n.a.sheet ?: owner
        if (!src.hasSheet(sh)) return R.V(Value.Err(Value.REF))
        val c1 = minOf(n.a.col, n.b.col); val c2 = maxOf(n.a.col, n.b.col)
        val r1 = minOf(n.a.row, n.b.row); val r2 = maxOf(n.a.row, n.b.row)
        if (c1 < 0 || r1 < 0 || c2 >= A1.MAX_COLS || r2 >= A1.MAX_ROWS) return R.V(Value.Err(Value.REF))
        if ((c2 - c1 + 1).toLong() * (r2 - r1 + 1) > 2_000_000L) return R.V(Value.Err(Value.VALUE))
        val out = ArrayList<Value>()
        for (r in r1..r2) for (c in c1..c2) out.add(src.cell(sh, c, r))
        return R.Area(out)
    }

    /** (number, null) or (0, error). Blank = 0, TRUE = 1, text that reads as a number. */
    private fun num(v: Value): Pair<Double, Value?> = when (v) {
        is Value.Num -> v.v to null
        is Value.Bool -> (if (v.b) 1.0 else 0.0) to null
        Value.Blank -> 0.0 to null
        is Value.Err -> 0.0 to v
        is Value.Str -> {
            val d = Fmt.number(v.s.trim())
            if (d == null) 0.0 to Value.Err(Value.VALUE) else d to null
        }
    }

    private fun fin(d: Double): Value = if (d.isNaN() || d.isInfinite()) Value.Err(Value.VALUE) else Value.Num(d)

    private fun bin(n: Node.Bin): Value {
        val l = go1(n.l)
        val r = go1(n.r)
        if (l is Value.Err) return l
        if (r is Value.Err) return r
        return when (n.op) {
            "&" -> Value.Str(Fmt.text(l) + Fmt.text(r))
            "=", "<>", "<", ">", "<=", ">=" -> {
                val c = compare(l, r)
                Value.Bool(when (n.op) { "=" -> c == 0; "<>" -> c != 0; "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 })
            }
            else -> {
                val (a, ea) = num(l); if (ea != null) return ea
                val (b, eb) = num(r); if (eb != null) return eb
                when (n.op) {
                    "+" -> fin(a + b)
                    "-" -> fin(a - b)
                    "*" -> fin(a * b)
                    "/" -> if (b == 0.0) Value.Err(Value.DIV0) else fin(a / b)
                    "^" -> if (a == 0.0 && b < 0) Value.Err(Value.DIV0) else fin(Math.pow(a, b))
                    else -> Value.Err(Value.VALUE)
                }
            }
        }
    }

    /** Numbers < text < booleans; blank counts as 0, "" or FALSE against the other side. */
    private fun compare(l: Value, r: Value): Int {
        fun rank(v: Value) = when (v) { is Value.Num -> 0; is Value.Str -> 1; is Value.Bool -> 2; else -> 0 }
        fun fill(v: Value, other: Value): Value =
            if (v !== Value.Blank) v else if (other is Value.Str) Value.Str("") else if (other is Value.Bool) Value.Bool(false) else Value.Num(0.0)
        val a = fill(l, r)
        val b = fill(r, l)
        if (rank(a) != rank(b)) return rank(a).compareTo(rank(b))
        return when {
            a is Value.Num && b is Value.Num -> a.v.compareTo(b.v)
            a is Value.Str && b is Value.Str -> a.s.lowercase().compareTo(b.s.lowercase())
            a is Value.Bool && b is Value.Bool -> a.b.compareTo(b.b)
            else -> 0
        }
    }

    // ── functions ───────────────────────────────────────────────────────────────────────────

    /** The numbers an aggregate sees: direct arguments are coerced, cells of an area only count when they are numbers. */
    private fun numbers(args: List<Node>): Pair<List<Double>, Value?> {
        val out = ArrayList<Double>()
        for (a in args) {
            when (val r = go(a)) {
                is R.V -> {
                    if (r.v === Value.Blank && a is Node.Ref) continue
                    val (d, e) = num(r.v)
                    if (e != null) return out to e
                    out.add(d)
                }
                is R.Area -> for (v in r.cells) {
                    if (v is Value.Err) return out to v
                    if (v is Value.Num) out.add(v.v)
                }
            }
        }
        return out to null
    }

    /** Every value of the arguments, areas flattened. */
    private fun flat(args: List<Node>): List<Value> = args.flatMap { a ->
        when (val r = go(a)) {
            is R.V -> listOf(r.v)
            is R.Area -> r.cells
        }
    }

    private fun arity(n: Node.Call, min: Int, max: Int = min): Value? =
        if (n.args.size < min || n.args.size > max) Value.Err(Value.VALUE) else null

    private fun one(n: Node.Call, f: (Double) -> Value): Value {
        arity(n, 1)?.let { return it }
        val (d, e) = num(go1(n.args[0]))
        return e ?: f(d)
    }

    private fun two(n: Node.Call, f: (Double, Double) -> Value): Value {
        arity(n, 2)?.let { return it }
        val (a, ea) = num(go1(n.args[0])); if (ea != null) return ea
        val (b, eb) = num(go1(n.args[1])); if (eb != null) return eb
        return f(a, b)
    }

    private fun text1(n: Node.Call, f: (String) -> Value): Value {
        arity(n, 1)?.let { return it }
        val v = go1(n.args[0])
        return if (v is Value.Err) v else f(Fmt.text(v))
    }

    private fun truth(v: Value): Pair<Boolean?, Value?> = when (v) {
        is Value.Bool -> v.b to null
        is Value.Num -> (v.v != 0.0) to null
        is Value.Err -> null to v
        Value.Blank -> false to null
        is Value.Str -> when (v.s.uppercase()) {
            "TRUE" -> true to null
            "FALSE" -> false to null
            else -> null to Value.Err(Value.VALUE)
        }
    }

    private fun countOf(args: List<Node>): Int {
        var c = 0
        for (a in args) {
            when (val r = go(a)) {
                is R.V -> {
                    val v = r.v
                    val direct = a !is Node.Ref
                    if (v is Value.Num || (direct && (v is Value.Bool || (v is Value.Str && Fmt.number(v.s.trim()) != null)))) c++
                }
                is R.Area -> c += r.cells.count { it is Value.Num }
            }
        }
        return c
    }

    private fun call(n: Node.Call): Value {
        when (n.name) {
            "SUM" -> { val (l, e) = numbers(n.args); return e ?: fin(l.sum()) }
            "AVERAGE" -> { val (l, e) = numbers(n.args); return e ?: if (l.isEmpty()) Value.Err(Value.DIV0) else fin(l.sum() / l.size) }
            "MIN" -> { val (l, e) = numbers(n.args); return e ?: Value.Num(l.minOrNull() ?: 0.0) }
            "MAX" -> { val (l, e) = numbers(n.args); return e ?: Value.Num(l.maxOrNull() ?: 0.0) }
            "COUNT" -> return Value.Num(countOf(n.args).toDouble())
            "COUNTA" -> return Value.Num(flat(n.args).count { it !== Value.Blank }.toDouble())
            "IF" -> {
                arity(n, 2, 3)?.let { return it }
                val (b, e) = truth(go1(n.args[0]))
                if (e != null) return e
                return when {
                    b == true -> blankToZero(go1(n.args[1]))
                    n.args.size == 3 -> blankToZero(go1(n.args[2]))
                    else -> Value.Bool(false)
                }
            }
            "AND", "OR" -> {
                if (n.args.isEmpty()) return Value.Err(Value.VALUE)
                val isAnd = n.name == "AND"
                var acc = isAnd
                var seen = false
                for (v in flat(n.args)) {
                    if (v === Value.Blank) continue
                    val (b, e) = truth(v)
                    if (e != null) return e
                    seen = true
                    acc = if (isAnd) acc && b == true else acc || b == true
                }
                return if (seen) Value.Bool(acc) else Value.Err(Value.VALUE)
            }
            "NOT" -> {
                arity(n, 1)?.let { return it }
                val (b, e) = truth(go1(n.args[0]))
                return e ?: Value.Bool(b != true)
            }
            "ROUND" -> return two(n) { x, d ->
                Value.Num(BigDecimal(x.toString()).setScale(d.toInt().coerceIn(-15, 15), RoundingMode.HALF_UP).toDouble())
            }
            "ABS" -> return one(n) { Value.Num(Math.abs(it)) }
            "SQRT" -> return one(n) { if (it < 0) Value.Err(Value.VALUE) else Value.Num(Math.sqrt(it)) }
            "POWER" -> return two(n) { a, b -> if (a == 0.0 && b < 0) Value.Err(Value.DIV0) else fin(Math.pow(a, b)) }
            "MOD" -> return two(n) { a, b -> if (b == 0.0) Value.Err(Value.DIV0) else fin(a - b * Math.floor(a / b)) }
            "CONCAT", "CONCATENATE" -> {
                val sb = StringBuilder()
                for (v in flat(n.args)) {
                    if (v is Value.Err) return v
                    sb.append(Fmt.text(v))
                }
                return Value.Str(sb.toString())
            }
            "LEN" -> return text1(n) { Value.Num(it.length.toDouble()) }
            "UPPER" -> return text1(n) { Value.Str(it.uppercase()) }
            "LOWER" -> return text1(n) { Value.Str(it.lowercase()) }
            "TODAY" -> return arity(n, 0) ?: Value.Num(Math.floor(src.now()))
            "NOW" -> return arity(n, 0) ?: Value.Num(src.now())
        }
        return Value.Err(Value.NAME)
    }

    private fun blankToZero(v: Value) = if (v === Value.Blank) Value.Num(0.0) else v
}
