package app.sterna.core.imap

/**
 * A readable text/plain rendering of [html], for the plain half of a multipart/alternative when the
 * caller supplied none: block ends become line breaks, a blockquote's lines get "> ", list items get
 * "- ", tags (and script/style content) are dropped and the common entities decoded.
 */
fun htmlToPlainText(html: String): String {
    var s = html.replace(Regex("(?is)<(script|style|head)\\b.*?</\\1\\s*>"), "")
        .replace(Regex("[\\r\\n\\t ]+"), " ")
    s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
    s = s.replace(Regex("(?i)<li\\b[^>]*>"), "\n- ")
    s = s.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|ul|ol|table)\\s*>"), "\n")
    val out = StringBuilder()
    var depth = 0
    var atLineStart = true
    val token = Regex("(?i)<(/?)blockquote\\b[^>]*>|<[^>]*>|[^<]+")
    for (t in token.findAll(s)) {
        val v = t.value
        if (v.startsWith("<")) {
            if (Regex("(?i)^</?blockquote").containsMatchIn(v)) {
                depth = if (t.groupValues[1] == "/") maxOf(0, depth - 1) else depth + 1
                if (!atLineStart) { out.append('\n'); atLineStart = true }
            }
            continue
        }
        for (c in decodeBasicEntities(v)) {
            if (atLineStart && depth > 0 && c != '\n') out.append("> ".repeat(depth))
            out.append(c)
            atLineStart = c == '\n'
        }
    }
    var text = out.toString()
    text = text.lines().joinToString("\n") { it.trim() }
    return text.replace(Regex("\n{3,}"), "\n\n").trim('\n')
}

private fun decodeBasicEntities(s: String): String =
    Regex("&(#\\d{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);").replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> {
                val cp = if (e[1] == 'x' || e[1] == 'X') e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull()
                if (cp == null || cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) m.value else String(Character.toChars(cp))
            }
        }
    }
