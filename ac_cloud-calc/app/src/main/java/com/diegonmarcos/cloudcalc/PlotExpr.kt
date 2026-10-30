package com.diegonmarcos.cloudcalc

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * #1336 the safe expression language of the plots, Kotlin side. The WebView evaluates plots with assets/latex/plot/expr.js; this
 * twin parses the same grammar so the app can validate a spec (and "Plot my formula") before the page ever sees it, and so the JVM
 * suite can hold both to data/latex/expr-vectors.json. There is no eval and no reflection: an identifier is a declared variable, a
 * bound sum/prod index or a constant; a call is one of the whitelisted functions; anything else is an [ExprException].
 *
 * Grammar: cmp := add [(< | > | <= | >=) add]; add := mul {(+|-) mul}; mul := unary {(*|/) unary}; unary := (-|+) unary | pow;
 * pow := primary [^ unary]; primary := number | name | name(args) | (cmp). sum(i, a, b, body) / prod(...) bind i over a..b (at most 100000 terms).
 */
object PlotExpr {
    class ExprException(message: String) : Exception(message)

    val constants = mapOf("pi" to Math.PI, "e" to Math.E, "tau" to 2 * Math.PI)
    const val STEP_BUDGET = 200000
    private const val MAX_TERMS = 100000

    internal class Ctx(val env: MutableMap<String, Double>) { var budget = STEP_BUDGET }
    internal fun interface Node { fun eval(c: Ctx): Double }

    /** A parsed expression: [names] are the declared variables it reads. */
    class Compiled internal constructor(private val root: Node, val names: Set<String>) {
        fun eval(env: Map<String, Double>): Double = root.eval(Ctx(HashMap(env)))
    }

    // ── the whitelist ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun lanczos(z: Double): Double {
        val c = doubleArrayOf(0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7)
        var x = c[0]
        for (i in 1 until 9) x += c[i] / (z + i)
        return x
    }
    private fun gamma(z0: Double): Double {
        if (z0 < 0.5) return Math.PI / (sin(Math.PI * z0) * gamma(1 - z0))
        val z = z0 - 1
        val t = z + 7.5
        return sqrt(2 * Math.PI) * t.pow(z + 0.5) * exp(-t) * lanczos(z)
    }
    private fun lgamma(z0: Double): Double {
        if (z0 < 0.5) return ln(Math.PI / abs(sin(Math.PI * z0))) - lgamma(1 - z0)
        val z = z0 - 1
        val t = z + 7.5
        return 0.5 * ln(2 * Math.PI) + (z + 0.5) * ln(t) - t + ln(lanczos(z))
    }
    private fun fact(n: Double): Double {
        if (n >= 0 && n <= 170 && n == floor(n)) { var r = 1.0; var i = 2; while (i <= n) { r *= i; i++ }; return r }
        return if (n < 0 && n == floor(n)) Double.NaN else gamma(n + 1)
    }
    private fun erf(x: Double): Double {
        val a = abs(x)
        if (a >= 4) return if (x < 0) -1.0 else 1.0
        var term = a; var sum = a
        for (n in 1 until 120) { term *= -a * a / n; sum += term / (2 * n + 1); if (abs(term) < 1e-17) break }
        val r = 2 / sqrt(Math.PI) * sum
        return if (x < 0) -r else r
    }
    private fun comb(n: Double, k0: Double): Double {
        if (k0 < 0 || k0 > n || n != floor(n) || k0 != floor(k0)) return 0.0
        val k = min(k0, n - k0)
        var r = 1.0
        var i = 1
        while (i <= k) { r = r * (n - k + i) / i; i++ }
        return if (Math.round(r).toDouble() == r || r > 1e15) r else Math.round(r).toDouble()
    }
    @Volatile private var sieve: IntArray = intArrayOf(0, 0)
    private fun primepi(x: Double): Double {
        val n = floor(x)
        if (n.isNaN() || n < 0) return Double.NaN
        if (n < 2) return 0.0
        if (n > 2000000) return Double.NaN
        val ni = n.toInt()
        var counts = sieve
        if (ni >= counts.size) {
            val lim = min(2000000, max(ni, (counts.size - 1) * 2))
            val comp = BooleanArray(lim + 1)
            val fresh = IntArray(lim + 1)
            var c = 0
            for (i in 2..lim) {
                if (!comp[i]) { c++; var j = i.toLong() * i; while (j <= lim) { comp[j.toInt()] = true; j += i } }
                fresh[i] = c
            }
            sieve = fresh; counts = fresh
        }
        return counts[ni].toDouble()
    }
    private fun rnd(k: Double): Double { val s = sin(k * 12.9898 + 78.233) * 43758.5453; return s - floor(s) }
    private fun agm(a0: Double, b0: Double, n0: Double, second: Boolean): Double {
        var a = a0; var b = b0
        repeat(max(0.0, min(60.0, floor(n0))).toInt()) { val na = (a + b) / 2; val nb = sqrt(a * b); a = na; b = nb }
        return if (second) b else a
    }
    private fun cfan(x: Double, n0: Double): Double {
        val n = max(0.0, min(200.0, floor(n0))).toInt()
        var t = (2 * n + 1).toDouble()
        for (k in n downTo 1) t = (2 * k - 1) - x * x / t
        return x / t
    }
    private fun logistic(r: Double, x0: Double, n0: Double): Double {
        var x = x0
        repeat(max(0.0, min(5000.0, floor(n0))).toInt()) { x = r * x * (1 - x) }
        return x
    }

    private val f1: Map<String, (Double) -> Double> = mapOf(
        "sin" to { x: Double -> sin(x) }, "cos" to { x: Double -> cos(x) }, "tan" to { x: Double -> Math.tan(x) },
        "asin" to { x: Double -> Math.asin(x) }, "acos" to { x: Double -> Math.acos(x) }, "atan" to { x: Double -> Math.atan(x) },
        "sinh" to { x: Double -> Math.sinh(x) }, "cosh" to { x: Double -> Math.cosh(x) }, "tanh" to { x: Double -> Math.tanh(x) },
        "asinh" to { x: Double -> ln(x + sqrt(x * x + 1)) }, "acosh" to { x: Double -> ln(x + sqrt(x * x - 1)) },
        "atanh" to { x: Double -> 0.5 * ln((1 + x) / (1 - x)) },
        "exp" to { x: Double -> exp(x) }, "ln" to { x: Double -> ln(x) }, "log10" to { x: Double -> Math.log10(x) },
        "log2" to { x: Double -> ln(x) / ln(2.0) }, "sqrt" to { x: Double -> sqrt(x) }, "cbrt" to { x: Double -> Math.cbrt(x) },
        "abs" to { x: Double -> abs(x) }, "sign" to { x: Double -> if (x > 0) 1.0 else if (x < 0) -1.0 else 0.0 },
        "floor" to { x: Double -> floor(x) }, "ceil" to { x: Double -> ceil(x) }, "round" to { x: Double -> floor(x + 0.5) },
        "trunc" to { x: Double -> if (x < 0) ceil(x) else floor(x) }, "frac" to { x: Double -> x - floor(x) },
        "fact" to { x: Double -> fact(x) }, "gamma" to { x: Double -> gamma(x) }, "lgamma" to { x: Double -> lgamma(x) }, "erf" to { x: Double -> erf(x) },
        "sinc" to { x: Double -> if (x == 0.0) 1.0 else sin(x) / x }, "step" to { x: Double -> if (x >= 0) 1.0 else 0.0 },
        "rnd" to { x: Double -> rnd(x) }, "primepi" to { x: Double -> primepi(x) },
    )
    private val f2: Map<String, (Double, Double) -> Double> = mapOf(
        "atan2" to { a: Double, b: Double -> atan2(a, b) }, "pow" to { a: Double, b: Double -> a.pow(b) },
        "hypot" to { a: Double, b: Double -> hypot(a, b) }, "min" to { a: Double, b: Double -> Math.min(a, b) },
        "max" to { a: Double, b: Double -> Math.max(a, b) }, "mod" to { a: Double, b: Double -> a - b * floor(a / b) },
        "comb" to { a: Double, b: Double -> comb(a, b) }, "cfan" to { a: Double, b: Double -> cfan(a, b) },
    )
    private val f3: Map<String, (Double, Double, Double) -> Double> = mapOf(
        "clamp" to { x: Double, lo: Double, hi: Double -> Math.min(hi, Math.max(lo, x)) },
        "agma" to { a: Double, b: Double, n: Double -> agm(a, b, n, false) }, "agmb" to { a: Double, b: Double, n: Double -> agm(a, b, n, true) },
        "logistic" to { r: Double, x: Double, n: Double -> logistic(r, x, n) },
    )
    private val binders = setOf("sum", "prod")

    /** Every function name the language knows (the declared-variable check refuses these as variable names). */
    val functions: Set<String> = f1.keys + f2.keys + f3.keys + binders + "if"

    // ── tokens ────────────────────────────────────────────────────────────────────────────────────────────────────

    private sealed class Tok { data class Num(val v: Double) : Tok(); data class Id(val v: String) : Tok(); data class Op(val v: String) : Tok() }

    private fun tokenize(src: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < src.length) {
            val ch = src[i]
            when {
                ch == ' ' || ch == '\t' || ch == '\n' -> i++
                ch.isDigit() || (ch == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    var j = i
                    while (j < src.length && src[j].isDigit()) j++
                    if (j < src.length && src[j] == '.') { j++; while (j < src.length && src[j].isDigit()) j++ }
                    if (j < src.length && (src[j] == 'e' || src[j] == 'E')) {
                        var k = j + 1
                        if (k < src.length && (src[k] == '+' || src[k] == '-')) k++
                        if (k < src.length && src[k].isDigit()) { while (k < src.length && src[k].isDigit()) k++; j = k }
                    }
                    out += Tok.Num(src.substring(i, j).toDouble()); i = j
                }
                ch.isAsciiLetter() || ch == '_' -> {
                    var j = i
                    while (j < src.length && (src[j].isAsciiLetter() || src[j].isDigit() || src[j] == '_')) j++
                    out += Tok.Id(src.substring(i, j)); i = j
                }
                (ch == '<' || ch == '>') && i + 1 < src.length && src[i + 1] == '=' -> { out += Tok.Op(src.substring(i, i + 2)); i += 2 }
                ch in "+-*/^(),<>" -> { out += Tok.Op(ch.toString()); i++ }
                else -> throw ExprException("unexpected character '$ch'")
            }
        }
        return out
    }
    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    // ── parser ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Parse [src] against the declared variable names [vars]; throws [ExprException] naming the problem. */
    fun compile(src: String, vars: Collection<String>): Compiled = Parser(tokenize(src), vars.toSet()).run()

    private class Parser(val toks: List<Tok>, val declared: Set<String>) {
        var pos = 0
        val scope = mutableListOf<String>()
        val used = mutableSetOf<String>()

        fun run(): Compiled {
            val n = cmp()
            if (pos < toks.size) throw ExprException("unexpected '${show(toks[pos])}'")
            return Compiled(n, used)
        }
        fun show(t: Tok) = when (t) { is Tok.Num -> t.v.toString(); is Tok.Id -> t.v; is Tok.Op -> t.v }
        fun isOp(v: String) = (toks.getOrNull(pos) as? Tok.Op)?.v == v
        fun take(v: String) { if (!isOp(v)) throw ExprException("expected '$v'"); pos++ }

        fun cmp(): Node {
            val a = add()
            val t = toks.getOrNull(pos) as? Tok.Op
            if (t != null && (t.v == "<" || t.v == ">" || t.v == "<=" || t.v == ">=")) {
                pos++
                val b = add()
                val op = t.v
                return Node { c ->
                    val x = a.eval(c); val y = b.eval(c)
                    if (when (op) { "<" -> x < y; ">" -> x > y; "<=" -> x <= y; else -> x >= y }) 1.0 else 0.0
                }
            }
            return a
        }
        fun add(): Node {
            var a = mul()
            while (isOp("+") || isOp("-")) {
                val plus = (toks[pos++] as Tok.Op).v == "+"; val b = mul(); val l = a
                a = if (plus) Node { c -> l.eval(c) + b.eval(c) } else Node { c -> l.eval(c) - b.eval(c) }
            }
            return a
        }
        fun mul(): Node {
            var a = unary()
            while (isOp("*") || isOp("/")) {
                val times = (toks[pos++] as Tok.Op).v == "*"; val b = unary(); val l = a
                a = if (times) Node { c -> l.eval(c) * b.eval(c) } else Node { c -> l.eval(c) / b.eval(c) }
            }
            return a
        }
        fun unary(): Node {
            if (isOp("-")) { pos++; val a = unary(); return Node { c -> -a.eval(c) } }
            if (isOp("+")) { pos++; return unary() }
            return pow()
        }
        fun pow(): Node {
            val base = primary()
            if (isOp("^")) { pos++; val ex = unary(); return Node { c -> base.eval(c).pow(ex.eval(c)) } }
            return base
        }
        fun args(): List<Node> {
            val list = mutableListOf<Node>()
            take("(")
            if (!isOp(")")) { list += cmp(); while (isOp(",")) { pos++; list += cmp() } }
            take(")")
            return list
        }
        fun primary(): Node {
            val t = toks.getOrNull(pos) ?: throw ExprException("unexpected end of expression")
            if (t is Tok.Num) { pos++; val v = t.v; return Node { v } }
            if (t is Tok.Op && t.v == "(") { pos++; val inner = cmp(); take(")"); return inner }
            if (t !is Tok.Id) throw ExprException("unexpected '${show(t)}'")
            pos++
            val name = t.v
            if ((toks.getOrNull(pos) as? Tok.Op)?.v == "(") {
                if (name in binders) return binder(name)
                val a = args()
                f1[name]?.let { f -> if (a.size == 1) { val x = a[0]; return Node { c -> f(x.eval(c)) } } }
                f2[name]?.let { f -> if (a.size == 2) { val x = a[0]; val y = a[1]; return Node { c -> f(x.eval(c), y.eval(c)) } } }
                if (name == "if" && a.size == 3) { val k = a[0]; val p = a[1]; val q = a[2]; return Node { c -> if (k.eval(c) != 0.0) p.eval(c) else q.eval(c) } }
                f3[name]?.let { f -> if (a.size == 3) { val x = a[0]; val y = a[1]; val z = a[2]; return Node { c -> f(x.eval(c), y.eval(c), z.eval(c)) } } }
                if (name in f1 || name in f2 || name in f3 || name == "if") throw ExprException("$name takes a different number of arguments")
                throw ExprException("unknown function $name")
            }
            if (name in scope || name in declared) {
                used += name
                return Node { c -> c.env[name] ?: Double.NaN }
            }
            constants[name]?.let { v -> return Node { v } }
            throw ExprException("unknown identifier $name")
        }
        fun binder(kind: String): Node {
            take("(")
            val idt = toks.getOrNull(pos) as? Tok.Id ?: throw ExprException("$kind needs an index name first")
            if (idt.v in declared || idt.v in scope || idt.v in constants || idt.v in f1) throw ExprException("the index ${idt.v} is already a name")
            pos++; take(",")
            val lo = cmp(); take(","); val hi = cmp(); take(",")
            scope += idt.v
            val body = cmp()
            scope.removeAt(scope.size - 1)
            take(")")
            val ix = idt.v
            val isSum = kind == "sum"
            return Node { c ->
                val a = ceil(lo.eval(c)); val b = floor(hi.eval(c))
                if (a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite() || b - a + 1 > MAX_TERMS) Double.NaN
                else {
                    val had = c.env.containsKey(ix); val old = c.env[ix]
                    var acc = if (isSum) 0.0 else 1.0
                    var i = a
                    while (i <= b) {
                        if (--c.budget < 0) { acc = Double.NaN; break }
                        c.env[ix] = i
                        val v = body.eval(c)
                        acc = if (isSum) acc + v else acc * v
                        i += 1.0
                    }
                    if (had) c.env[ix] = old!! else c.env.remove(ix)
                    acc
                }
            }
        }
    }
}
