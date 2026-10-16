package com.diegonmarcos.cloudcalc.sheet

/** The formula syntax tree. [sheet] null = the formula's own sheet. */
sealed class Node {
    data class Lit(val v: Value) : Node()
    data class Ref(val sheet: String?, val col: Int, val row: Int, val absCol: Boolean = false, val absRow: Boolean = false) : Node()
    data class Rng(val a: Ref, val b: Ref) : Node()
    data class Bin(val op: String, val l: Node, val r: Node) : Node()
    data class Neg(val e: Node) : Node()
    data class Pct(val e: Node) : Node()
    data class Call(val name: String, val args: List<Node>) : Node()
}

class ParseError(message: String) : Exception(message)

/** Text -> [Node] -> text, plus the rewrites a structure change or a paste applies to a formula. */
object Formula {
    fun parse(text: String): Node {
        val p = Parser(text.removePrefix("="))
        val n = p.expr()
        p.skip()
        if (!p.end()) throw ParseError("unexpected '${p.peek()}'")
        return n
    }

    fun parseOrNull(text: String): Node? = try { parse(text) } catch (e: ParseError) { null }

    fun render(n: Node): String = "=" + r(n, 0)

    private fun quoteSheet(s: String) =
        if (Regex("^[A-Za-z_][A-Za-z0-9_.]*$").matches(s) && A1.parseCell(s) == null && !s.equals("TRUE", true) && !s.equals("FALSE", true)) s else "'" + s.replace("'", "''") + "'"

    private fun refText(x: Node.Ref, withSheet: Boolean): String =
        (if (withSheet && x.sheet != null) quoteSheet(x.sheet) + "!" else "") +
            (if (x.absCol) "$" else "") + A1.colName(x.col) + (if (x.absRow) "$" else "") + (x.row + 1)

    private fun prec(op: String) = when (op) { "&" -> 2; "+", "-" -> 3; "*", "/" -> 4; "^" -> 5; else -> 1 }

    /** [ctx] is the binding power of the place [n] stands in; weaker operators get parentheses. */
    private fun r(n: Node, ctx: Int): String = when (n) {
        is Node.Lit -> when (val v = n.v) {
            is Value.Num -> if (v.v == Math.rint(v.v) && Math.abs(v.v) < 1e15) v.v.toLong().toString() else v.v.toString()
            is Value.Str -> "\"" + v.s.replace("\"", "\"\"") + "\""
            is Value.Bool -> if (v.b) "TRUE" else "FALSE"
            is Value.Err -> v.code
            Value.Blank -> ""
        }
        is Node.Ref -> refText(n, true)
        is Node.Rng -> refText(n.a, true) + ":" + refText(n.b, false)
        is Node.Bin -> {
            val p = prec(n.op)
            val s = r(n.l, p) + n.op + r(n.r, p + 1)
            if (p < ctx) "($s)" else s
        }
        is Node.Neg -> {
            val s = "-" + r(n.e, 6)
            if (ctx > 6) "($s)" else s
        }
        is Node.Pct -> r(n.e, 7) + "%"
        is Node.Call -> n.name + "(" + n.args.joinToString(",") { r(it, 0) } + ")"
    }

    /** Every cell reference and range of [n], as (sheet or null, area). */
    fun refs(n: Node): List<Pair<String?, Area>> = when (n) {
        is Node.Ref -> listOf(n.sheet to Area(n.col, n.row, n.col, n.row))
        is Node.Rng -> listOf(n.a.sheet to Area(minOf(n.a.col, n.b.col), minOf(n.a.row, n.b.row), maxOf(n.a.col, n.b.col), maxOf(n.a.row, n.b.row)))
        is Node.Bin -> refs(n.l) + refs(n.r)
        is Node.Neg -> refs(n.e)
        is Node.Pct -> refs(n.e)
        is Node.Call -> n.args.flatMap { refs(it) }
        is Node.Lit -> emptyList()
    }

    /** Whether [n] calls a function whose answer changes with the clock. */
    fun volatile(n: Node): Boolean = when (n) {
        is Node.Call -> n.name == "TODAY" || n.name == "NOW" || n.args.any { volatile(it) }
        is Node.Bin -> volatile(n.l) || volatile(n.r)
        is Node.Neg -> volatile(n.e)
        is Node.Pct -> volatile(n.e)
        else -> false
    }

    /** [text] with [f] applied to its references; the original text back when nothing changed. */
    private fun rewrite(text: String, f: (Node) -> Node?): String {
        if (!text.startsWith("=")) return text
        val n = parseOrNull(text) ?: return text
        val m = map(n, f)
        return if (m == n) text else render(m)
    }

    /** Rebuild [n] bottom-up, handing every Ref/Rng to [f] (null = keep). */
    fun map(n: Node, f: (Node) -> Node?): Node {
        val inner: Node = when (n) {
            is Node.Bin -> Node.Bin(n.op, map(n.l, f), map(n.r, f))
            is Node.Neg -> Node.Neg(map(n.e, f))
            is Node.Pct -> Node.Pct(map(n.e, f))
            is Node.Call -> Node.Call(n.name, n.args.map { map(it, f) })
            else -> n
        }
        return if (inner is Node.Ref || inner is Node.Rng) f(inner) ?: inner else inner
    }

    /** [text] as pasted [dCol] columns and [dRow] rows away: relative references move, $absolute ones stay. */
    fun shift(text: String, dCol: Int, dRow: Int): String {
        if (!text.startsWith("=")) return text
        fun mv(x: Node.Ref): Node.Ref {
            val c = if (x.absCol) x.col else x.col + dCol
            val rw = if (x.absRow) x.row else x.row + dRow
            return if (c in 0 until A1.MAX_COLS && rw in 0 until A1.MAX_ROWS) x.copy(col = c, row = rw) else x
        }
        fun bad(x: Node.Ref) = (!x.absCol && x.col + dCol !in 0 until A1.MAX_COLS) || (!x.absRow && x.row + dRow !in 0 until A1.MAX_ROWS)
        return rewrite(text) { x ->
            when (x) {
                is Node.Ref -> if (bad(x)) Node.Lit(Value.Err(Value.REF)) else mv(x)
                is Node.Rng -> if (bad(x.a) || bad(x.b)) Node.Lit(Value.Err(Value.REF)) else Node.Rng(mv(x.a), mv(x.b))
                else -> null
            }
        }
    }

    /** Structure edit applied to references: [axisCol] true = columns. insert when [count] > 0 at [at]; delete when [count] < 0 (|count| items from [at]). */
    fun adjust(text: String, owner: String, target: String, axisCol: Boolean, at: Int, count: Int): String {
        if (!text.startsWith("=")) return text
        fun hits(x: Node.Ref) = (x.sheet ?: owner) == target
        fun pos(x: Node.Ref) = if (axisCol) x.col else x.row
        fun with(x: Node.Ref, p: Int) = if (axisCol) x.copy(col = p) else x.copy(row = p)
        return rewrite(text) { x ->
            when (x) {
                is Node.Ref -> {
                    if (!hits(x)) null
                    else if (count > 0) { if (pos(x) >= at) with(x, pos(x) + count) else null }
                    else {
                        val n2 = -count
                        val p = pos(x)
                        if (p < at) null else if (p >= at + n2) with(x, p - n2) else Node.Lit(Value.Err(Value.REF))
                    }
                }
                is Node.Rng -> {
                    if (!hits(x.a)) null
                    else {
                        val lo = minOf(pos(x.a), pos(x.b)); val hi = maxOf(pos(x.a), pos(x.b))
                        val (nlo, nhi) = if (count > 0) {
                            (if (lo >= at) lo + count else lo) to (if (hi >= at) hi + count else hi)
                        } else {
                            val n2 = -count
                            val l2 = if (lo < at) lo else if (lo >= at + n2) lo - n2 else at
                            val h2 = if (hi < at) hi else if (hi >= at + n2) hi - n2 else at - 1
                            l2 to h2
                        }
                        if (nlo > nhi) Node.Lit(Value.Err(Value.REF))
                        else {
                            val a = if (pos(x.a) <= pos(x.b)) with(x.a, nlo) else with(x.a, nhi)
                            val b = if (pos(x.a) <= pos(x.b)) with(x.b, nhi) else with(x.b, nlo)
                            Node.Rng(a, b)
                        }
                    }
                }
                else -> null
            }
        }
    }

    /** A sheet renamed: references to [old] now say [new]. */
    fun renameSheet(text: String, old: String, new: String): String {
        if (!text.startsWith("=")) return text
        return rewrite(text) { x ->
            when (x) {
                is Node.Ref -> if (x.sheet == old) x.copy(sheet = new) else null
                is Node.Rng -> if (x.a.sheet == old) Node.Rng(x.a.copy(sheet = new), x.b) else null
                else -> null
            }
        }
    }

    /** A sheet deleted: references to it become #REF!. */
    fun dropSheet(text: String, name: String): String {
        if (!text.startsWith("=")) return text
        return rewrite(text) { x ->
            when (x) {
                is Node.Ref -> if (x.sheet == name) Node.Lit(Value.Err(Value.REF)) else null
                is Node.Rng -> if (x.a.sheet == name) Node.Lit(Value.Err(Value.REF)) else null
                else -> null
            }
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun end() = i >= s.length
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun eat(str: String): Boolean {
            skip()
            if (s.startsWith(str, i)) { i += str.length; return true }
            return false
        }

        fun expr(): Node = compare()

        private fun compare(): Node {
            var l = concat()
            while (true) {
                skip()
                val op = listOf("<>", "<=", ">=", "=", "<", ">").firstOrNull { s.startsWith(it, i) } ?: return l
                i += op.length
                l = Node.Bin(op, l, concat())
            }
        }

        private fun concat(): Node {
            var l = add()
            while (eat("&")) l = Node.Bin("&", l, add())
            return l
        }

        private fun add(): Node {
            var l = mul()
            while (true) {
                skip()
                val c = peek()
                if (c == '+' || c == '-') { i++; l = Node.Bin(c.toString(), l, mul()) } else return l
            }
        }

        private fun mul(): Node {
            var l = pow()
            while (true) {
                skip()
                val c = peek()
                if (c == '*' || c == '/') { i++; l = Node.Bin(c.toString(), l, pow()) } else return l
            }
        }

        private fun pow(): Node {
            var l = unary()
            while (eat("^")) l = Node.Bin("^", l, unary())
            return l
        }

        private fun unary(): Node {
            skip()
            if (peek() == '-') { i++; return Node.Neg(unary()) }
            if (peek() == '+') { i++; return unary() }
            var e = primary()
            while (eat("%")) e = Node.Pct(e)
            return e
        }

        private fun primary(): Node {
            skip()
            if (end()) throw ParseError("unexpected end")
            val c = peek()
            if (c == '(') {
                i++
                val e = expr()
                if (!eat(")")) throw ParseError("missing )")
                return e
            }
            if (c == '"') return Node.Lit(Value.Str(string()))
            if (c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit())) return number()
            if (c == '#') {
                val code = Value.ERRORS.firstOrNull { s.startsWith(it, i, ignoreCase = true) } ?: throw ParseError("unknown error literal")
                i += code.length
                return Node.Lit(Value.Err(code))
            }
            if (c == '\'' || c == '$' || c.isLetter() || c == '_') return word()
            throw ParseError("unexpected '$c'")
        }

        private fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (end()) throw ParseError("unterminated string")
                val ch = s[i++]
                if (ch == '"') {
                    if (peek() == '"') { sb.append('"'); i++ } else return sb.toString()
                } else sb.append(ch)
            }
        }

        private fun number(): Node {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                var j = i + 1
                if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
                if (j < s.length && s[j].isDigit()) { while (j < s.length && s[j].isDigit()) j++; i = j }
            }
            val v = s.substring(st, i).toDoubleOrNull() ?: throw ParseError("bad number")
            return Node.Lit(Value.Num(v))
        }

        private fun quotedSheet(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (end()) throw ParseError("unterminated sheet name")
                val ch = s[i++]
                if (ch == '\'') { if (peek() == '\'') { sb.append('\''); i++ } else break } else sb.append(ch)
            }
            return sb.toString()
        }

        private fun word(): Node {
            var sheet: String? = null
            if (peek() == '\'') {
                sheet = quotedSheet()
                if (peek() != '!') throw ParseError("expected ! after sheet name")
                i++
            } else {
                val st = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.' || s[i] == '$')) i++
                val w = s.substring(st, i)
                if (peek() == '!') { sheet = w; i++ }
                else if (peek() == '(' && w.isNotEmpty() && !w.contains('$')) {
                    i++
                    return Node.Call(w.uppercase(), args())
                } else {
                    A1.parseCell(w)?.let { (cc, rr) ->
                        val ref = Node.Ref(null, cc, rr, w.startsWith("$"), Regex("\\$[0-9]+$").containsMatchIn(w))
                        return range(ref)
                    }
                    if (w.equals("TRUE", true)) return Node.Lit(Value.Bool(true))
                    if (w.equals("FALSE", true)) return Node.Lit(Value.Bool(false))
                    return Node.Lit(Value.Err(Value.NAME))
                }
            }
            // after "Sheet!"
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '$')) i++
            val w = s.substring(st, i)
            val (cc, rr) = A1.parseCell(w) ?: return Node.Lit(Value.Err(Value.REF))
            return range(Node.Ref(sheet, cc, rr, w.startsWith("$"), Regex("\\$[0-9]+$").containsMatchIn(w)))
        }

        private fun range(a: Node.Ref): Node {
            if (peek() != ':') return a
            val save = i
            i++
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '$')) i++
            val w = s.substring(st, i)
            val p = A1.parseCell(w)
            if (p == null) { i = save; return a }
            return Node.Rng(a, Node.Ref(a.sheet, p.first, p.second, w.startsWith("$"), Regex("\\$[0-9]+$").containsMatchIn(w)))
        }

        private fun args(): List<Node> {
            val out = ArrayList<Node>()
            skip()
            if (eat(")")) return out
            while (true) {
                skip()
                if (peek() == ',' || peek() == ';') out.add(Node.Lit(Value.Blank)) else out.add(expr())
                skip()
                if (eat(",") || eat(";")) continue
                if (eat(")")) return out
                throw ParseError("expected , or )")
            }
        }
    }
}
