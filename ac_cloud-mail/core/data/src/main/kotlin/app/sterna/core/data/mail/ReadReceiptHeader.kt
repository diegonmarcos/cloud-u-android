package app.sterna.core.data.mail

/** Reads RFC 8098's `Disposition-Notification-To:`. Both protocols feed it the same text. */
object ReadReceiptHeader {

    /** An EMPTY list is the only way of saying "nothing here": never throws, never a placeholder. */
    fun recipients(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return splitTopLevel(header.replace(FOLDING, " ")).mapNotNull { addressIn(it) }
    }

    /** One space, not removed: gluing `Ann Lee` to `<ann@example.org>` makes one unreadable token. */
    private val FOLDING = Regex("[ \t]*\r?\n[ \t]*")

    /** A `<…>` route-addr; the last one in a token is what an address parser reaches. */
    private val ANGLED = Regex("<([^<>]*)>")

    /** The quote tracking is load-bearing: `"Doe, John" <j@x>` is one address, `split(',')` two. */
    private fun splitTopLevel(header: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        var depth = 0
        for (c in header) {
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' && quoted -> { current.append(c); escaped = true }
                c == '"' -> { quoted = !quoted; current.append(c) }
                quoted -> current.append(c)
                c == '<' -> { depth++; current.append(c) }
                c == '>' -> { if (depth > 0) depth--; current.append(c) }
                (c == ',' || c == ';') && depth == 0 -> { out.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
        }
        out.add(current.toString())
        return out
    }

    /**
     * The quoted display name is removed FIRST: a name may legally contain `<x@elsewhere.example>`,
     */
    private fun addressIn(token: String): String? {
        val unnamed = token.replace(QUOTED, " ")
        val angled = ANGLED.findAll(unnamed).lastOrNull()?.groupValues?.get(1)
        val candidate = (angled ?: unnamed.split(' ', '\t').lastOrNull { '@' in it })
            ?.substringAfterLast(':')
        return candidate?.trim()?.trim('"')?.takeIf { isAddress(it) }
    }

    /** A quoted display name, backslash escapes included, as one unit to be thrown away. */
    private val QUOTED = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

    /** Not a validator: an over-strict one drops a deliverable address and the request with it. */
    private fun isAddress(value: String): Boolean {
        if (value.isEmpty() || value.any { it.isWhitespace() || it in DELIMITERS }) return false
        val at = value.lastIndexOf('@')
        return at > 0 && at < value.length - 1
    }

    /** The characters that separate addresses or wrap them; never part of one, here. */
    private const val DELIMITERS = "<>,;\""
}
