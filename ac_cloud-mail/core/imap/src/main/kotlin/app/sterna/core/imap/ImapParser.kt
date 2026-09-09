package app.sterna.core.imap

import java.io.InputStream

    /**
     * Streaming parser for IMAP server responses. Reads one response at a time and returns it as a
     */
internal class ImapParser(private val input: InputStream) {
    private var peeked = NONE

    private fun read(): Int {
        if (peeked != NONE) {
            val c = peeked
            peeked = NONE
            return c
        }
        return input.read()
    }

    private fun peek(): Int {
        if (peeked == NONE) peeked = input.read()
        return peeked
    }

        /**
         * Read one full response line (following any literals) into a token list.
         */
    fun readResponse(maxTokens: Int = Int.MAX_VALUE): List<Any?> {
        val tokens = mutableListOf<Any?>()
        while (true) {
            when (peek()) {
                -1 -> throw ImapException("Connection closed")
                '\r'.code -> { read(); if (peek() == '\n'.code) read(); return tokens }
                '\n'.code -> { read(); return tokens }
                ' '.code -> read()
                else -> if (tokens.size < maxTokens) tokens.add(readToken()) else skipToken()
            }
        }
    }

    /** Consume one token without building it (the surplus past a caller's cap). */
    private fun skipToken() {
        when (peek()) {
            '('.code, '"'.code, '{'.code -> readToken() // rare here; read and drop
            else -> consumeAtom(null)
        }
    }

    private fun readToken(): Any? = when (peek()) {
        '('.code -> readList()
        '"'.code -> readQuoted()
        '{'.code -> readLiteral()
        else -> readAtom()
    }

    private fun readList(): List<Any?> {
        read() // '('
        val list = mutableListOf<Any?>()
        while (true) {
            when (peek()) {
                ')'.code -> { read(); return list }
                ' '.code -> read()
                -1 -> return list
                else -> list.add(readToken())
            }
        }
    }

    private fun readQuoted(): String {
        read() // opening quote
        val sb = StringBuilder()
        while (true) {
            val c = read()
            when (c) {
                -1, '"'.code -> return sb.toString()
                '\\'.code -> { val n = read(); if (n != -1) sb.append(n.toChar()) }
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun readLiteral(): String {
        read() // '{'
        val num = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1 || c == '}'.code) break
            num.append(c.toChar())
        }
        // The literal data starts right after the CRLF that follows '}'.
        if (peek() == '\r'.code) read()
        if (peek() == '\n'.code) read()
        val declared = num.toString().trim().toIntOrNull() ?: 0
            // A hostile/buggy server can announce a huge {N}; allocating it eagerly would OOM the
            // process. Cap the buffer, drain the excess so the connection stays in sync, answer empty.
        if (declared > MAX_LITERAL) {
            var remaining = declared.toLong()
            val skip = ByteArray(8192)
            while (remaining > 0) {
                val r = input.read(skip, 0, minOf(skip.size.toLong(), remaining).toInt())
                if (r == -1) break
                remaining -= r
            }
            return ""
        }
        val n = declared.coerceAtLeast(0)
        val bytes = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(bytes, off, n - off)
            if (r == -1) break
            off += r
        }
            // ISO-8859-1, one char per byte, per the charset convention in the class KDoc. Literals
            // were the one exception, and the one place a message's bytes could be lost.
        return String(bytes, 0, off, Charsets.ISO_8859_1)
    }

    private fun readAtom(): Any? {
        val sb = StringBuilder()
        consumeAtom(sb)
        val s = sb.toString()
        return if (s.equals("NIL", ignoreCase = true)) null else s
    }

        /**
         * Consume one atom off the stream, appending it to [into] when a caller wants it built.
         */
    private fun consumeAtom(into: StringBuilder?) {
        var seen = 0 // chars consumed so far — a `[` is a section only after at least one
        var inSection = false
        var depth = 0 // parentheses opened inside the brackets and not yet closed
        while (true) {
            val c = peek()
            if (c == -1 || c == '\r'.code || c == '\n'.code) return
            if (inSection) {
                when (c) {
                    '('.code -> depth++
                    ')'.code -> if (depth == 0) return else depth--
                    ']'.code -> { inSection = false; depth = 0 }
                }
            } else {
                if (c == ' '.code || c == '('.code || c == ')'.code) return
                if (c == '['.code && seen > 0) inSection = true
            }
            read()
            into?.append(c.toChar())
            seen++
        }
    }

    private companion object {
        const val NONE = -2

        /** Largest IMAP literal we will buffer in memory (32 MiB) — covers real messages
         *  and attachments while refusing absurd attacker-announced sizes. */
        const val MAX_LITERAL = 32 * 1024 * 1024
    }
}
