package app.sterna.ui.settings

import app.sterna.core.data.text.htmlToText
import app.sterna.core.data.text.sanitiseReceivedHtml

/**
 * The pure half of the signature HTML editor: what the toolbar does to the SOURCE, what the source
 * is painted like, what the preview is handed, and the undo history.
 *
 * Everything here is a function of strings, so the editor's behaviour is pinned by a plain JVM test
 * and the composable stays a thin skin. The toolbar edits the source and never a second model: the
 * owner sees the tag they just made, can change it by hand, and the stored value is exactly the text
 * in the field.
 */
internal object SignatureHtmlTools {

    /** The field after an edit: the whole [text] and the selection `[start, end)`. */
    data class Edit(val text: String, val start: Int, val end: Int)

    fun bold(text: String, start: Int, end: Int) = wrap(text, start, end, "<b>", "</b>")
    fun italic(text: String, start: Int, end: Int) = wrap(text, start, end, "<i>", "</i>")
    fun underline(text: String, start: Int, end: Int) = wrap(text, start, end, "<u>", "</u>")

    /**
     * [open] and [close] around the selection, or removed again when the selection is already
     * wrapped by exactly that pair, so a button press toggles. With nothing selected the pair is
     * inserted with the cursor between.
     */
    fun wrap(text: String, start: Int, end: Int, open: String, close: String): Edit {
        val (s, e) = ordered(text, start, end)
        val before = text.substring(0, s)
        val inner = text.substring(s, e)
        val after = text.substring(e)
        if (inner.isNotEmpty() && before.endsWith(open) && after.startsWith(close)) {
            val from = s - open.length
            return Edit(before.dropLast(open.length) + inner + after.drop(close.length), from, from + inner.length)
        }
        val from = s + open.length
        return Edit(before + open + inner + close + after, from, from + inner.length)
    }

    /**
     * A link around the selection (its label), or around the address itself when nothing is
     * selected. Null when [url] is not an address a signature may link to; the field is untouched.
     */
    fun link(text: String, start: Int, end: Int, url: String): Edit? {
        val href = safeLinkUrl(url) ?: return null
        val (s, e) = ordered(text, start, end)
        val label = text.substring(s, e).ifEmpty { escapeAttribute(href) }
        val open = "<a href=\"${escapeAttribute(href)}\">"
        val from = s + open.length
        return Edit(text.substring(0, s) + open + label + "</a>" + text.substring(e), from, from + label.length)
    }

    /** An image at the cursor (the selection, if any, becomes its alt text). Null for an unusable address. */
    fun image(text: String, start: Int, end: Int, url: String): Edit? {
        val src = safeImageUrl(url) ?: return null
        val (s, e) = ordered(text, start, end)
        val alt = escapeAttribute(text.substring(s, e))
        val tag = "<img src=\"${escapeAttribute(src)}\" alt=\"$alt\">"
        val at = s + tag.length
        return Edit(text.substring(0, s) + tag + text.substring(e), at, at)
    }

    /** The selection in [hex] (`#rrggbb`). Null for anything else, so no free-form CSS reaches the field. */
    fun colour(text: String, start: Int, end: Int, hex: String): Edit? {
        val value = hex.trim().takeIf { COLOUR.matches(it) }?.lowercase() ?: return null
        return wrap(text, start, end, "<span style=\"color:$value\">", "</span>")
    }

    /** A link target: http, https, mailto or tel; a bare domain gets https. Null for the rest. */
    fun safeLinkUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty() || url.any { it.isWhitespace() || it == '"' || it == '<' || it == '>' }) return null
        val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase()
        return when {
            scheme == null -> if ('.' in url && !url.startsWith(".") && !url.endsWith(".")) "https://$url" else null
            scheme in LINK_SCHEMES -> url
            else -> null
        }
    }

    /** An image address: http or https only. A bare domain gets https. */
    fun safeImageUrl(raw: String): String? =
        safeLinkUrl(raw)?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

    fun escapeAttribute(s: String): String =
        s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    /** The plain-text half, derived the way the stored one is (link targets spelled out). */
    fun plainText(source: String): String = htmlToText(source, keepLinkTargets = true)

    /**
     * The document the preview WebView is given: the SANITISED fragment (the string that goes on the
     * wire) inside a page that forbids scripts, forms and every request but pictures. Defence in
     * depth over a WebView that already has JavaScript off.
     */
    fun previewDocument(source: String, textColour: String = "#000000", background: String = "#ffffff"): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'\">" +
            "<style>body{margin:8px;font:14px sans-serif;color:$textColour;background:$background;word-wrap:break-word}" +
            "img{max-width:100%;height:auto}</style></head><body>" +
            sanitiseReceivedHtml(source) +
            "</body></html>"

    // ── highlighting ──────────────────────────────────────────────────────

    enum class Kind { TAG, ATTRIBUTE, VALUE, COMMENT, ENTITY }

    /** `[start, end)` painted as [kind]. Spans never overlap and are in order. */
    data class Span(val start: Int, val end: Int, val kind: Kind)

    /**
     * Lightweight highlighting: comments, tag names and brackets, attribute names, quoted values and
     * entities. A scan, not a parser, so half-typed markup still paints sensibly and never throws.
     */
    fun highlight(source: String): List<Span> {
        val spans = ArrayList<Span>()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            if (c == '<') {
                if (source.startsWith("<!--", i)) {
                    val close = source.indexOf("-->", i + 4)
                    val end = if (close < 0) n else close + 3
                    spans += Span(i, end, Kind.COMMENT)
                    i = end
                    continue
                }
                val next = source.getOrNull(i + 1)
                if (next == null || !(next.isLetter() || next == '/' || next == '!')) {
                    i++
                    continue
                }
                i = scanTag(source, i, spans)
                continue
            }
            if (c == '&') {
                val semi = source.indexOf(';', i)
                if (semi > i && semi - i <= 33 && ENTITY.matches(source.substring(i, semi + 1))) {
                    spans += Span(i, semi + 1, Kind.ENTITY)
                    i = semi + 1
                    continue
                }
            }
            i++
        }
        return spans
    }

    private fun scanTag(source: String, from: Int, spans: MutableList<Span>): Int {
        val n = source.length
        var i = from + 1
        if (i < n && (source[i] == '/' || source[i] == '!')) i++
        val nameStart = i
        while (i < n && (source[i].isLetterOrDigit() || source[i] == '-' || source[i] == ':')) i++
        spans += Span(from, i, Kind.TAG)
        if (i == nameStart && i >= n) return n
        while (i < n && source[i] != '>') {
            val c = source[i]
            when {
                c.isWhitespace() || c == '/' -> i++
                c == '"' || c == '\'' -> {
                    val close = source.indexOf(c, i + 1)
                    val end = if (close < 0) n else close + 1
                    spans += Span(i, end, Kind.VALUE)
                    i = end
                }
                c == '=' -> i++
                else -> {
                    val s = i
                    while (i < n && !source[i].isWhitespace() && source[i] !in "=/>\"'") i++
                    if (i == s) i++ else spans += Span(s, i, Kind.ATTRIBUTE)
                }
            }
        }
        if (i < n) {
            spans += Span(i, i + 1, Kind.TAG)
            i++
        }
        return i
    }

    // ── undo / redo ───────────────────────────────────────────────────────

    /**
     * A bounded undo history of whole-field snapshots. Typing is coalesced through [record]'s `merge`
     * flag, so one undo takes back a burst of keystrokes, while a toolbar action is a step of its own.
     */
    class History(initial: Edit, private val limit: Int = 100) {
        private val past = ArrayList<Edit>().apply { add(initial) }
        private var at = 0
        private var coalescing = false

        val canUndo: Boolean get() = at > 0
        val canRedo: Boolean get() = at < past.size - 1
        val current: Edit get() = past[at]

        /**
         * Records [edit] as the new present and drops any redo branch. With [merge], and only when the
         * previous record was a merge too, it replaces the present instead of stacking: a burst of
         * typing is one step, and the first keystroke after a toolbar action starts a new one.
         */
        fun record(edit: Edit, merge: Boolean = false) {
            if (edit.text == past[at].text) {
                past[at] = edit
                return
            }
            while (past.size > at + 1) past.removeAt(past.size - 1)
            if (merge && coalescing && at > 0) {
                past[at] = edit
            } else {
                past.add(edit)
                at++
            }
            coalescing = merge
            while (past.size > limit) {
                past.removeAt(0)
                at--
            }
        }

        fun undo(): Edit? {
            if (!canUndo) return null
            coalescing = false
            return past[--at]
        }

        fun redo(): Edit? {
            if (!canRedo) return null
            coalescing = false
            return past[++at]
        }
    }

    private fun ordered(text: String, start: Int, end: Int): Pair<Int, Int> {
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(0, text.length)
        return minOf(a, b) to maxOf(a, b)
    }

    private val COLOUR = Regex("#[0-9a-fA-F]{6}")
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    private val LINK_SCHEMES = setOf("http", "https", "mailto", "tel")
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});")
}
