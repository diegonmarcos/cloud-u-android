package com.diegonmarcos.clouddrive.files.convert

/**
 * Markdown → HTML, bundled and offline (no library, no network): ATX and setext headings,
 * paragraphs, fenced code, block quotes, nested bullet and numbered lists, GFM tables, rules, and
 * inline code, links, images, bold, italic and strike. Everything is HTML-escaped BEFORE any
 * markup is introduced, so a document cannot inject a tag, and `javascript:` links are neutered.
 * Pure JVM; MarkdownTest runs it on the CI runner.
 */
object Markdown {

    fun toHtml(src: String, title: String): String =
        "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n" +
            "<title>" + escape(title) + "</title>\n<style>" + STYLE + "</style>\n</head>\n<body>\n" +
            render(src) + "</body>\n</html>\n"

    private const val STYLE = "body{font:16px/1.55 system-ui,sans-serif;max-width:46em;margin:1.5em auto;padding:0 1em;color:#222}" +
        "pre{background:#f4f4f4;padding:.7em;overflow:auto}code{background:#f4f4f4;padding:0 .2em}pre code{padding:0}" +
        "blockquote{margin:0;padding-left:1em;border-left:3px solid #ccc;color:#555}table{border-collapse:collapse}" +
        "td,th{border:1px solid #ccc;padding:.25em .6em}img{max-width:100%}"

    fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> append(c)
        }
    }

    /** The body fragment for [src]. */
    fun render(src: String): String = blocks(src.replace("\r\n", "\n").replace('\r', '\n').split("\n"))

    private val HEADING = Regex("""^ {0,3}(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$""")
    private val FENCE = Regex("""^ {0,3}(```|~~~)\s*([\w+-]*).*$""")
    private val RULE = Regex("""^ {0,3}([-*_])([ \t]*\1){2,}[ \t]*$""")
    private val ITEM = Regex("""^(\s*)([-*+]|\d{1,9}[.)])[ \t]+(.*)$""")
    private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")

    private fun blocks(lines: List<String>): String {
        val out = StringBuilder()
        val para = ArrayList<String>()
        fun flush() {
            if (para.isNotEmpty()) { out.append("<p>").append(inline(para.joinToString("\n"))).append("</p>\n"); para.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            when {
                line.isBlank() -> { flush(); i++ }
                fence != null -> {
                    flush()
                    val mark = fence.groupValues[1]
                    val lang = fence.groupValues[2]
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(mark)) { body += lines[i]; i++ }
                    i++ // the closing fence (or the end)
                    out.append("<pre><code").append(if (lang.isEmpty()) "" else " class=\"language-${escape(lang)}\"").append(">")
                        .append(escape(body.joinToString("\n"))).append("</code></pre>\n")
                }
                HEADING.matches(line) -> {
                    flush()
                    val m = HEADING.matchEntire(line)!!
                    val n = m.groupValues[1].length
                    out.append("<h$n>").append(inline(m.groupValues[2])).append("</h$n>\n"); i++
                }
                para.isNotEmpty() && line.trim().matches(Regex("=+")) -> {
                    out.append("<h1>").append(inline(para.joinToString(" "))).append("</h1>\n"); para.clear(); i++
                }
                para.isNotEmpty() && line.trim().matches(Regex("-+")) -> {
                    out.append("<h2>").append(inline(para.joinToString(" "))).append("</h2>\n"); para.clear(); i++
                }
                RULE.matches(line) -> { flush(); out.append("<hr>\n"); i++ }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val quote = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].trimStart().startsWith(">")) {
                        quote += lines[i].trimStart().removePrefix(">").removePrefix(" "); i++
                    }
                    out.append("<blockquote>\n").append(blocks(quote)).append("</blockquote>\n")
                }
                ITEM.matches(line) -> { flush(); i = list(lines, i, out) }
                line.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) && lines[i + 1].contains('-') -> {
                    flush(); i = table(lines, i, out)
                }
                else -> { para += line.trim(); i++ }
            }
        }
        flush()
        return out.toString()
    }

    /** A run of list items starting at [from]; returns the index of the first line after it. */
    private fun list(lines: List<String>, from: Int, out: StringBuilder): Int {
        val first = ITEM.matchEntire(lines[from])!!
        val base = first.groupValues[1].length
        val ordered = first.groupValues[2][0].isDigit()
        val tag = if (ordered) "ol" else "ul"
        out.append("<$tag>\n")
        var i = from
        while (i < lines.size) {
            val m = ITEM.matchEntire(lines[i]) ?: break
            if (m.groupValues[1].length != base || m.groupValues[2][0].isDigit() != ordered) break
            val body = ArrayList<String>()
            body += m.groupValues[3]
            i++
            // Indented lines (and blank lines followed by an indented line) belong to this item.
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    if (i + 1 < lines.size && lines[i + 1].isNotBlank() && indentOf(lines[i + 1]) > base) { body += ""; i++; continue }
                    break
                }
                if (indentOf(l) > base) { body += l.substring(minOf(base + 2, indentOf(l))); i++ } else break
            }
            val nested = body.indexOfFirst { ITEM.matches(it) }
            val text = if (nested >= 0) body.subList(0, nested) else body
            val rest = if (nested >= 0) body.subList(nested, body.size) else emptyList()
            out.append("<li>").append(inline(text.joinToString("\n").trim()))
            if (rest.isNotEmpty()) out.append("\n").append(blocks(rest))
            out.append("</li>\n")
        }
        out.append("</$tag>\n")
        return i
    }

    private fun indentOf(s: String) = s.length - s.trimStart().length

    private fun cells(row: String): List<String> = row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun table(lines: List<String>, from: Int, out: StringBuilder): Int {
        val head = cells(lines[from])
        out.append("<table>\n<thead><tr>")
        head.forEach { out.append("<th>").append(inline(it)).append("</th>") }
        out.append("</tr></thead>\n<tbody>\n")
        var i = from + 2
        while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
            out.append("<tr>")
            cells(lines[i]).forEach { out.append("<td>").append(inline(it)).append("</td>") }
            out.append("</tr>\n"); i++
        }
        out.append("</tbody>\n</table>\n")
        return i
    }

    private val CODE = Regex("`([^`]+)`")
    private val IMAGE = Regex("""!\[([^\]]*)\]\(([^)\s]+)(?:\s+&quot;([^)]*?)&quot;)?\)""")
    private val LINK = Regex("""\[([^\]]+)\]\(([^)\s]+)(?:\s+&quot;([^)]*?)&quot;)?\)""")
    private val AUTOLINK = Regex("""&lt;(https?://[^\s&]+(?:&amp;[^\s&]+)*)&gt;""")
    private val BOLD = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val ITALIC_STAR = Regex("""\*(?=\S)([^*]+?)(?<=\S)\*""")
    private val ITALIC_UNDER = Regex("""(?<![A-Za-z0-9])_(?=\S)([^_]+?)(?<=\S)_(?![A-Za-z0-9])""")
    private val STRIKE = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")

    private fun safeUrl(u: String): String = if (u.trim().lowercase().startsWith("javascript:")) "#" else u

    /** Inline markup over text that is escaped first; code spans are lifted out so nothing inside them is touched. */
    fun inline(text: String): String {
        val spans = ArrayList<String>()
        var s = escape(text)
        s = CODE.replace(s) { m -> spans += "<code>" + m.groupValues[1] + "</code>"; "\u0000${spans.size - 1}\u0000" }
        s = IMAGE.replace(s) { m -> "<img src=\"" + safeUrl(m.groupValues[2]) + "\" alt=\"" + m.groupValues[1] + "\">" }
        s = LINK.replace(s) { m -> "<a href=\"" + safeUrl(m.groupValues[2]) + "\">" + m.groupValues[1] + "</a>" }
        s = AUTOLINK.replace(s) { m -> "<a href=\"" + m.groupValues[1] + "\">" + m.groupValues[1] + "</a>" }
        s = BOLD.replace(s) { m -> "<strong>" + m.groupValues[2] + "</strong>" }
        s = ITALIC_STAR.replace(s) { m -> "<em>" + m.groupValues[1] + "</em>" }
        s = ITALIC_UNDER.replace(s) { m -> "<em>" + m.groupValues[1] + "</em>" }
        s = STRIKE.replace(s) { m -> "<del>" + m.groupValues[1] + "</del>" }
        s = s.replace("  \n", "<br>\n")
        return Regex("\u0000(\\d+)\u0000").replace(s) { m -> spans[m.groupValues[1].toInt()] }
    }
}
