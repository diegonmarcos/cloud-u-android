package com.diegonmarcos.cloudcalc

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1336 "Plot my formula": the Editor's LaTeX turned into an expression of the plots' safe language with one or two free variables,
 * and the plot spec the viewer draws for it. Pure and declarative: the command table (\sin -> sin(...)), the Greek names, the ignored
 * spacing commands and the ranges are build.json::latex.graph's; nothing here spells a formula. A formula that needs anything else
 * (a sum, a relation, a third variable, a subscript) is reported with the reason, never guessed.
 */
object LatexPlot {
    sealed class Conversion {
        data class Ok(val expr: String, val free: List<String>) : Conversion()
        data class Unsupported(val reason: String) : Conversion()
    }

    private class Stop(val reason: String) : Exception(reason)

    private sealed class T {
        data class Num(val s: String) : T()
        data class Let(val c: Char) : T()
        data class Cmd(val n: String) : T()
        data class Sym(val c: Char) : T()
    }

    private fun lex(tex: String, ignored: Set<String>): MutableList<T> {
        val out = mutableListOf<T>()
        var i = 0
        while (i < tex.length) {
            val c = tex[i]
            if (c == '\\') {
                var j = i + 1
                if (j < tex.length && tex[j].isLetter()) { while (j < tex.length && tex[j].isLetter()) j++ } else if (j < tex.length) j++
                val name = tex.substring(i, j)
                if (name !in ignored) out.add(T.Cmd(name))
                i = j
            } else if (c.isWhitespace()) {
                i++
            } else if (c.isDigit() || (c == '.' && i + 1 < tex.length && tex[i + 1].isDigit())) {
                var j = i
                while (j < tex.length && (tex[j].isDigit() || tex[j] == '.')) j++
                out.add(T.Num(tex.substring(i, j)))
                i = j
            } else if (c.isLetter()) {
                out.add(T.Let(c))
                i++
            } else {
                out.add(T.Sym(c))
                i++
            }
        }
        return out
    }

    private class P(val t: MutableList<T>, val rules: Latex.GraphConfig) {
        var pos = 0
        val free = mutableListOf<String>()

        fun peek(): T? = t.getOrNull(pos)
        fun isSym(c: Char): Boolean { val p = peek(); return p is T.Sym && p.c == c }
        fun isCmd(n: String): Boolean { val p = peek(); return p is T.Cmd && p.n == n }

        /** Consume the closing [c], after an optional \right. */
        fun close(c: Char) {
            if (isCmd("\\right")) pos++
            if (!isSym(c)) throw Stop("unbalanced brackets: missing '$c'")
            pos++
        }

        fun expr(): String {
            var s = term()
            while (true) {
                if (isSym('+')) { pos++; s += "+" + term() }
                else if (isSym('-')) { pos++; s += "-" + term() }
                else break
            }
            return s
        }

        /** Whether [p] begins a factor, so that two factors side by side multiply (2x, x\sin x, (a)(b)). */
        fun startsFactor(p: T?): Boolean = when (p) {
            is T.Num, is T.Let -> true
            is T.Sym -> p.c == '(' || p.c == '[' || p.c == '{'
            is T.Cmd -> p.n == "\\frac" || p.n == "\\dfrac" || p.n == "\\tfrac" || p.n == "\\sqrt" || p.n == "\\left" || p.n == "\\pi" || p.n == "\\tau" ||
                p.n in rules.plotCommands || p.n.drop(1) in rules.greek
            null -> false
        }

        fun term(): String {
            var s = unary()
            while (true) {
                val p = peek()
                if (p is T.Sym && p.c == '*') { pos++; s += "*" + unary() }
                else if (p is T.Cmd && (p.n == "\\cdot" || p.n == "\\times")) { pos++; s += "*" + unary() }
                else if (p is T.Sym && p.c == '/') { pos++; s += "/" + unary() }
                else if (p is T.Cmd && p.n == "\\div") { pos++; s += "/" + unary() }
                else if (startsFactor(p)) { s += "*" + unary() }
                else return s
            }
        }

        fun unary(): String {
            if (isSym('-')) { pos++; return "-" + unary() }
            if (isSym('+')) { pos++; return unary() }
            return power()
        }

        fun power(): String {
            var base = atom()
            while (isSym('!')) { pos++; base = "fact($base)" }
            if (isSym('^')) { pos++; base = "$base^(" + exponent() + ")" }
            if (isSym('\'')) throw Stop("a derivative mark is not plotted")
            return base
        }

        fun exponent(): String {
            if (isSym('{')) { pos++; val e = expr(); close('}'); return e }
            if (isSym('-')) { pos++; return "-" + exponentAtom() }
            return exponentAtom()
        }

        /** The single token LaTeX puts after ^: one digit, one letter, a command, or a bracket group. */
        fun exponentAtom(): String {
            val p = peek() ?: throw Stop("a power needs an exponent")
            if (p is T.Num && p.s.length > 1 && !p.s.contains('.')) {
                t[pos] = T.Num(p.s.substring(1))
                return p.s.substring(0, 1)
            }
            return atom()
        }

        fun variable(name: String): String {
            if (name in PlotExpr.functions || name in PlotExpr.constants) throw Stop("$name is also a function or constant name; use another letter")
            if (name !in free) free.add(name)
            return name
        }

        fun letter(c: Char): String {
            if (c == 'e') return "e"
            if (c == 'i') throw Stop("complex numbers are not plotted")
            if (c == 'π') return "pi"
            if (!(c in 'a'..'z' || c in 'A'..'Z')) throw Stop("the symbol $c is not supported")
            if (isSym('_')) throw Stop("a subscript is not supported")
            return variable(c.toString())
        }

        fun group(): String {
            if (isSym('{')) { pos++; val e = expr(); close('}'); return e }
            return atom()
        }

        fun atom(): String {
            val p = peek() ?: throw Stop("the expression ends too early")
            pos++
            if (p is T.Num) {
                if (p.s.count { it == '.' } > 1) throw Stop("'${p.s}' is not a number")
                return p.s
            }
            if (p is T.Let) return letter(p.c)
            if (p is T.Sym) {
                if (p.c == '(') { val e = expr(); close(')'); return "($e)" }
                if (p.c == '[') { val e = expr(); close(']'); return "($e)" }
                if (p.c == '{') { val e = expr(); close('}'); return "($e)" }
                if (p.c == '|') { val e = expr(); close('|'); return "abs($e)" }
                if (p.c == '_') throw Stop("a subscript is not supported")
                throw Stop("'${p.c}' is not supported")
            }
            return command((p as T.Cmd).n)
        }

        /** The argument of a function written without brackets: \sin 2x is sin(2x), \sin x \cos x is sin(x) cos(x). */
        fun funArg(): String {
            if (isSym('(') || isCmd("\\left")) return atom()
            var s = power()
            while (peek() is T.Num || peek() is T.Let) s += "*" + power()
            return s
        }

        fun command(n: String): String {
            if (n == "\\pi") return "pi"
            if (n == "\\tau") return "tau"
            if (n == "\\frac" || n == "\\dfrac" || n == "\\tfrac") { val a = group(); val b = group(); return "(($a)/($b))" }
            if (n == "\\sqrt") {
                if (isSym('[')) { pos++; val k = expr(); close(']'); val a = group(); return "(($a)^(1/($k)))" }
                return "sqrt(" + group() + ")"
            }
            if (n == "\\left") return atom()
            val template = rules.plotCommands[n]
            if (template != null) {
                var pw: String? = null
                if (isSym('^')) { pos++; pw = exponent() }
                val call = template.replace("{}", funArg())
                return if (pw != null) "($call)^($pw)" else call
            }
            val name = n.drop(1)
            if (name in rules.greek) return variable(name)
            throw Stop("$n is not supported")
        }
    }

    private fun lhsName(lhs: List<T>): String? {
        val first = lhs.firstOrNull() as? T.Let ?: return null
        if (lhs.size == 1) return first.c.toString()
        val rest = lhs.drop(1)
        val okCall = rest.size >= 3 && rest.first() is T.Sym && (rest.first() as T.Sym).c == '(' && rest.last() is T.Sym && (rest.last() as T.Sym).c == ')' &&
            rest.drop(1).dropLast(1).all { it is T.Let || (it is T.Sym && it.c == ',') }
        return if (okCall) first.c.toString() else null
    }

    /** Convert [tex] (an expression, or `y = ...` / `f(x) = ...`) to a safe-language expression and its free variables, at most [Latex.CustomPlot.maxVars]. */
    fun convert(tex: String, rules: Latex.GraphConfig): Conversion {
        return try {
            val toks = lex(tex, rules.ignored)
            if (toks.isEmpty()) throw Stop("nothing to plot")
            var body: MutableList<T> = toks
            var named: String? = null
            val eq = toks.indexOfFirst { it is T.Sym && it.c == '=' }
            if (eq >= 0) {
                if (toks.drop(eq + 1).any { it is T.Sym && it.c == '=' }) throw Stop("a chain of equalities is not plotted")
                val lhs = toks.subList(0, eq).toList()
                val rhs = toks.subList(eq + 1, toks.size).toMutableList()
                if (rhs.isEmpty()) body = lhs.toMutableList()
                else {
                    named = lhsName(lhs) ?: throw Stop("write it as y = ... (one name on the left)")
                    body = rhs
                }
            }
            val p = P(body, rules)
            val e = p.expr()
            if (p.pos != body.size) throw Stop("cannot read the formula near token ${p.pos + 1}")
            if (named != null && named in p.free) throw Stop("$named appears on both sides")
            if (p.free.isEmpty()) throw Stop("nothing varies: the formula has no free variable")
            if (p.free.size > rules.custom.maxVars) throw Stop("at most ${rules.custom.maxVars} free variables can be plotted, found " + p.free.joinToString(", "))
            val order = if ("x" in p.free) listOf("x") + p.free.filter { it != "x" } else p.free.toList()
            try { PlotExpr.compile(e, order) } catch (x: PlotExpr.ExprException) { throw Stop(x.message ?: "not a plottable expression") }
            Conversion.Ok(e, order)
        } catch (s: Stop) {
            Conversion.Unsupported(s.reason)
        }
    }

    /**
     * The plot spec of a converted formula: the expression against its first variable (2D, the other as a slider) and as a surface over
     * both (3D; with one variable the surface is a ridge along a dummy axis). [caption] is the localized one-liner.
     */
    fun spec(ok: Conversion.Ok, title: String, caption: String, c: Latex.CustomPlot): Latex.PlotSpec {
        val trig = c.trigFunctions.any { "$it(" in ok.expr }
        val r = if (trig) c.trigRange else c.range
        val step = if (trig) c.trigStep else c.step
        val vars = JSONArray()
        val names = if (ok.free.size >= 2) ok.free else ok.free + c.dummy
        names.forEachIndexed { i, n ->
            val def = if (n == c.dummy) 0.0 else 1.0.coerceIn(-r, r)
            vars.put(JSONObject().put("symbol", n).put("label", n).put("default", def).put("min", -r).put("max", r).put("step", step))
        }
        val d2 = JSONObject().put("x", names[0]).put("layers", JSONArray().put(JSONObject().put("t", "line").put("y", ok.expr).put("label", title)))
        val d3 = JSONObject().put("x", names[0]).put("y", names[1]).put("layers", JSONArray().put(JSONObject().put("t", "surface").put("z", ok.expr).put("label", title)))
        val o = JSONObject().put("caption", caption).put("kind", "function").put("vars", vars).put("d2", d2).put("d3", d3)
        return Latex.parsePlot(o)
    }
}
