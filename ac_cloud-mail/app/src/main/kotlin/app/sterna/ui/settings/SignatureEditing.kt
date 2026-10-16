package app.sterna.ui.settings

import app.sterna.ui.settings.SignatureHtmlTools.Edit

/**
 * What the source editor does AS YOU TYPE and what its richer toolbar buttons insert. Pure functions of
 * (text, selection) -> [Edit], like [SignatureHtmlTools], so the behaviour is pinned by a JVM test and
 * the composable only forwards key events and clicks.
 */
internal object SignatureEditing {

    const val INDENT = "  "

    // -- typing assists ------------------------------------------------------

    /**
     * Post-processes one field change. [before] is the field the user was looking at, [after] what the
     * keyboard produced; the result is the edit to apply instead, or null to keep [after] as typed.
     * Only a single typed character with a collapsed caret is touched, so paste, delete and IME
     * composition of several characters are never second-guessed.
     */
    fun afterTyping(before: Edit, after: Edit): Edit? {
        if (after.text.length != before.text.length + 1 || after.start != after.end) return null
        val at = after.start
        if (at < 1 || before.start != before.end || before.start != at - 1) return null
        if (after.text.substring(0, at - 1) + after.text.substring(at) != before.text) return null
        val text = after.text
        return when (val c = text[at - 1]) {
            '\n' -> newline(text, at)
            '>' -> closeTag(text, at)
            '"', '\'' -> quote(text, at, c)
            else -> null
        }
    }

    /** Enter: keep the previous line's indent; between `>` and `</` open an indented line in between. */
    private fun newline(text: String, at: Int): Edit {
        val lineStart = text.lastIndexOf('\n', at - 2) + 1
        val prev = text.substring(lineStart, at - 1)
        val indent = prev.takeWhile { it == ' ' || it == '\t' }
        val opens = endsWithOpeningTag(prev)
        val between = opens && text.startsWith("</", at)
        val inner = if (opens) indent + INDENT else indent
        return if (between) {
            val caret = at + inner.length
            Edit(text.substring(0, at) + inner + "\n" + indent + text.substring(at), caret, caret)
        } else {
            Edit(text.substring(0, at) + inner + text.substring(at), at + inner.length, at + inner.length)
        }
    }

    /** `>` that ends an opening tag: add its closing tag after the caret. */
    private fun closeTag(text: String, at: Int): Edit? {
        val lt = text.lastIndexOf('<', at - 2)
        if (lt < 0 || text.lastIndexOf('>', at - 2) > lt) return null
        val inside = text.substring(lt + 1, at - 1)
        if (inside.isEmpty() || inside.endsWith("/") || !inside[0].isLetter()) return null
        if (quoteOpen(inside)) return null
        val name = inside.takeWhile { it.isLetterOrDigit() || it == '-' }.lowercase()
        if (name in SignatureValidator.VOID) return null
        val close = "</$name>"
        if (text.startsWith(close, at)) return null
        return Edit(text.substring(0, at) + close + text.substring(at), at, at)
    }

    /** A quote typed inside a tag: type over an existing closer, else insert the pair. */
    private fun quote(text: String, at: Int, q: Char): Edit? {
        val lt = text.lastIndexOf('<', at - 2)
        if (lt < 0 || text.lastIndexOf('>', at - 2) > lt) return null
        val inside = text.substring(lt + 1, at - 1)
        if (inside.isEmpty() || !(inside[0].isLetter())) return null
        val other = if (q == '"') '\'' else '"'
        if (quoteOpenOf(inside, other)) return null
        val open = inside.count { it == q } % 2 == 1
        if (open) {
            // This quote closes a value. If the next character is already that quote (we added it), step over it.
            return if (text.getOrNull(at) == q) Edit(text.removeRange(at - 1, at), at, at) else null
        }
        return Edit(text.substring(0, at) + q + text.substring(at), at, at)
    }

    private fun quoteOpen(s: String) = quoteOpenOf(s, '"') || quoteOpenOf(s, '\'')
    private fun quoteOpenOf(s: String, q: Char) = s.count { it == q } % 2 == 1

    private fun endsWithOpeningTag(line: String): Boolean {
        val trimmed = line.trimEnd()
        if (!trimmed.endsWith(">")) return false
        val lt = trimmed.lastIndexOf('<')
        if (lt < 0) return false
        val tag = trimmed.substring(lt + 1, trimmed.length - 1)
        if (tag.isEmpty() || !tag[0].isLetter() || tag.endsWith("/")) return false
        val name = tag.takeWhile { it.isLetterOrDigit() || it == '-' }.lowercase()
        return name !in SignatureValidator.VOID
    }

    // -- Tab ------------------------------------------------------------------

    /**
     * Tab inserts two spaces (never moves focus). With a selection spanning lines, every selected line is
     * indented; [outdent] (Shift+Tab) removes up to two leading spaces from the line(s) instead.
     */
    fun tab(text: String, start: Int, end: Int, outdent: Boolean = false): Edit {
        val (s, e) = order(text, start, end)
        val multi = text.substring(s, e).contains('\n')
        if (!multi && !outdent) {
            return Edit(text.substring(0, s) + INDENT + text.substring(e), s + INDENT.length, s + INDENT.length)
        }
        val from = text.lastIndexOf('\n', s - 1) + 1
        val block = text.substring(from, e)
        val lines = block.split('\n')
        var firstDelta = 0
        var total = 0
        val changed = lines.mapIndexed { k, line ->
            val out: String
            val delta: Int
            if (outdent) {
                val strip = line.takeWhile { it == ' ' }.length.coerceAtMost(INDENT.length)
                out = line.substring(strip)
                delta = -strip
            } else {
                out = INDENT + line
                delta = INDENT.length
            }
            if (k == 0) firstDelta = delta
            total += delta
            out
        }
        val result = text.substring(0, from) + changed.joinToString("\n") + text.substring(e)
        val newStart = (s + firstDelta).coerceAtLeast(from)
        return Edit(result, newStart, (e + total).coerceAtLeast(newStart))
    }

    // -- toolbar ---------------------------------------------------------------

    fun strike(text: String, start: Int, end: Int) = SignatureHtmlTools.wrap(text, start, end, "<s>", "</s>")

    /** The selection in a `font-size` span; [px] 8..48, else null. */
    fun fontSize(text: String, start: Int, end: Int, px: Int): Edit? {
        if (px !in 8..48) return null
        return SignatureHtmlTools.wrap(text, start, end, "<span style=\"font-size:${px}px\">", "</span>")
    }

    enum class Align(val css: String) { LEFT("left"), CENTER("center"), RIGHT("right") }

    /** The selection in a block with `text-align`; an existing identical wrapper toggles off. */
    fun align(text: String, start: Int, end: Int, align: Align) =
        SignatureHtmlTools.wrap(text, start, end, "<div style=\"text-align:${align.css}\">", "</div>")

    /** A horizontal rule with inline styling, because email clients do not honour `<style>` rules. */
    fun divider(text: String, start: Int, end: Int): Edit =
        insert(text, start, end, "<hr style=\"border:0;border-top:1px solid #cccccc;margin:12px 0\">")

    /** A [rows] x [cols] table with inline styles, caret in the first cell. */
    fun table(text: String, start: Int, end: Int, rows: Int, cols: Int): Edit? {
        if (rows !in 1..12 || cols !in 1..8) return null
        val cell = "<td style=\"padding:4px 8px;vertical-align:top\"></td>"
        val row = "  <tr>" + cell.repeat(cols) + "</tr>\n"
        val html = "<table cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse\">\n" + row.repeat(rows) + "</table>"
        val (s, _) = order(text, start, end)
        val edit = insert(text, start, end, html)
        val firstCell = edit.text.indexOf("<td", s)
        val caret = edit.text.indexOf('>', firstCell) + 1
        return Edit(edit.text, caret, caret)
    }

    data class Social(val label: String, val url: String)

    val SOCIAL_NETWORKS: List<String> = listOf("LinkedIn", "GitHub", "X", "Instagram", "YouTube", "Mastodon", "Website")

    /**
     * A row of text links, one per network, styled inline. Text rather than icon images: an icon is a
     * remote picture, which most clients block until the reader allows it, so the links would vanish.
     * Entries whose address is not a usable link are skipped; null when none is.
     */
    fun social(text: String, start: Int, end: Int, entries: List<Social>): Edit? {
        val links = entries.mapNotNull { e ->
            val href = SignatureHtmlTools.safeLinkUrl(e.url) ?: return@mapNotNull null
            "<a href=\"${SignatureHtmlTools.escapeAttribute(href)}\" style=\"color:#1565c0;text-decoration:none\">" +
                SignatureHtmlTools.escapeAttribute(e.label) + "</a>"
        }
        if (links.isEmpty()) return null
        return insert(text, start, end, "<div style=\"font-size:13px\">" + links.joinToString(" &middot; ") + "</div>")
    }

    /** Replaces the selection with [html], caret after it. */
    fun insert(text: String, start: Int, end: Int, html: String): Edit {
        val (s, e) = order(text, start, end)
        val at = s + html.length
        return Edit(text.substring(0, s) + html + text.substring(e), at, at)
    }

    // -- import ------------------------------------------------------------------

    /**
     * The fragment worth keeping from a file the owner imported: a whole page (a corporate template, an
     * "Export as HTML" from another client) is reduced to the inside of its `<body>`, and a bare
     * fragment is only trimmed. Nothing is sanitised here - that happens, once, on the way out.
     */
    fun importFragment(raw: String): String {
        val text = raw.trim().removePrefix("\uFEFF").trim()
        val open = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE).find(text)
        if (open != null) {
            val from = open.range.last + 1
            val close = text.lastIndexOf("</body", ignoreCase = true)
            return text.substring(from, if (close >= from) close else text.length).trim()
        }
        return text.replace(Regex("<!doctype[^>]*>|</?html\\b[^>]*>", RegexOption.IGNORE_CASE), "").trim()
    }

    // -- find / replace --------------------------------------------------------

    /** Every start offset of [query] in [text]; empty for a blank query. Matches do not overlap. */
    fun find(text: String, query: String, matchCase: Boolean = false): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var i = text.indexOf(query, 0, ignoreCase = !matchCase)
        while (i >= 0) {
            out += i
            i = text.indexOf(query, i + query.length, ignoreCase = !matchCase)
        }
        return out
    }

    /** The match to move to from [caret]: the next one at or after it (wrapping), or the previous one before it. */
    fun step(matches: List<Int>, caret: Int, forward: Boolean): Int? {
        if (matches.isEmpty()) return null
        return if (forward) matches.firstOrNull { it >= caret } ?: matches.first()
        else matches.lastOrNull { it < caret } ?: matches.last()
    }

    fun replaceAll(text: String, query: String, with: String, matchCase: Boolean = false): String {
        if (query.isEmpty()) return text
        return text.replace(query, with, ignoreCase = !matchCase)
    }

    private fun order(text: String, start: Int, end: Int): Pair<Int, Int> {
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(0, text.length)
        return minOf(a, b) to maxOf(a, b)
    }
}
