package com.diegonmarcos.superapp.bottomnav

/**
 * A strict JSON reader, because the declaration is parsed in common code and the platform JSON
 * (org.json) is Android's. Objects are Map, arrays are List, strings are String, numbers Double,
 * booleans Boolean, null is null. Malformed text throws; [NavDecl] turns that into an empty
 * declaration. It reads `ui.sections` and nothing else, so it carries no writer and no streaming.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val r = Reader(text)
        val v = r.value()
        r.skipSpace()
        require(r.atEnd()) { "trailing text at ${r.pos}" }
        return v
    }

    private class Reader(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipSpace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipSpace()
            require(pos < s.length) { "unexpected end" }
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        private fun word(w: String, v: Any?): Any? {
            require(s.startsWith(w, pos)) { "bad literal at $pos" }
            pos += w.length
            return v
        }

        private fun num(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start")
        }

        private fun expect(c: Char) {
            skipSpace()
            require(pos < s.length && s[pos] == c) { "expected '$c' at $pos" }
            pos++
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (true) {
                skipSpace()
                val k = str()
                expect(':')
                out[k] = value()
                skipSpace()
                require(pos < s.length) { "unexpected end" }
                if (s[pos] == ',') { pos++; continue }
                expect('}')
                return out
            }
        }

        private fun arr(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipSpace()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (true) {
                out += value()
                skipSpace()
                require(pos < s.length) { "unexpected end" }
                if (s[pos] == ',') { pos++; continue }
                expect(']')
                return out
            }
        }

        private fun str(): String {
            require(pos < s.length && s[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "unterminated escape" }
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "short \\u escape" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
