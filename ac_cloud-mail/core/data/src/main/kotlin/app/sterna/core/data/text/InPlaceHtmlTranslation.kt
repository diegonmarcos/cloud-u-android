package app.sterna.core.data.text

/**
 * Translate the TEXT of an HTML fragment and nothing else.
 *
 * The fragment is walked as markup: every tag, attribute, link, image, style and comment is copied
 * through byte for byte, and only the text nodes between tags are replaced. That is what "in place"
 * means here - the layout, the links and the pictures of a newsletter survive a translation, which a
 * translated copy of its flattened text would not.
 *
 * WHAT IS NOT TRANSLATED
 *  - text inside `code`, `kbd`, `samp`, `tt`, `var`, `script`, `style`, `textarea`, `title`, `svg`
 *    and a `pre` the MESSAGE wrote (the `pre.plain` body the reader paints itself IS translated);
 *  - inside a line, URLs, e-mail addresses, numbers and backtick code: they are lifted out as
 *    placeholders before the engine sees the line and put back after, and a line whose placeholders
 *    did not all come back is re-translated piece by piece around them, so a mangled reply can cost
 *    that line its translation but never lose a link or a figure;
 *  - lines with no letter in them (separators, prices, `|`).
 *
 * THE ENGINE IS ONE FUNCTION, [TextTranslator]: text in, text out, throws [TranslationFailed] with a
 * reason a person can read. Lines are sent in BATCHES (joined by newlines; the reply must have the
 * same number of lines) so a newsletter is a handful of requests, not one per node. A batch that
 * comes back with the wrong number of lines is split in halves until the lines pair up; a single
 * line that still cannot be answered stays in its original language.
 */
object InPlaceHtmlTranslation {

    /** Up to this many characters / lines go into one request. */
    const val BATCH_CHARS = 3_000
    const val BATCH_LINES = 60

    fun interface TextTranslator {
        @Throws(TranslationFailed::class)
        fun translate(text: String): String
    }

    open class TranslationFailed(val reason: String) : Exception(reason)

    /**
     * No engine could translate because none could tell what language the text is in, and none
     * that needs no source was available. The reader answers it with a source-language picker, not a
     * dead end; [reason] is the engine's own wording.
     */
    class NeedsSource(reason: String) : TranslationFailed(reason)

    /** [html] with its text translated; [translatedUnits] of [totalUnits] translatable lines changed. */
    class Result(val html: String, val translatedUnits: Int, val totalUnits: Int)

    private val SKIP_ELEMENTS = setOf(
        "code", "kbd", "samp", "tt", "var", "script", "style", "textarea", "title", "svg", "head",
    )

    // ---- the walk ------------------------------------------------------------------------------

    /** One text node of the fragment: its span in the source and its decoded text. */
    internal class TextNode(val start: Int, val end: Int, val decoded: String)

    /** The translatable text nodes of [html], in document order. */
    internal fun textNodes(html: String): List<TextNode> {
        val out = ArrayList<TextNode>()
        val skipStack = ArrayList<String>()
        var i = 0
        var textStart = 0
        fun flush(upTo: Int) {
            if (upTo > textStart && skipStack.isEmpty()) {
                val raw = html.substring(textStart, upTo)
                val decoded = unescapeEntities(raw)
                if (decoded.any { it.isLetter() }) out += TextNode(textStart, upTo, decoded)
            }
        }
        while (i < html.length) {
            if (html[i] != '<') { i++; continue }
            flush(i)
            if (html.startsWith("<!--", i)) {
                val close = html.indexOf("-->", i + 4)
                i = if (close < 0) html.length else close + 3
                textStart = i
                continue
            }
            // A tag: read to the '>' that is not inside a quoted attribute value.
            var j = i + 1
            var quote = '\u0000'
            while (j < html.length) {
                val c = html[j]
                if (quote != '\u0000') { if (c == quote) quote = '\u0000' }
                else if (c == '"' || c == '\'') quote = c
                else if (c == '>') break
                j++
            }
            val tag = html.substring(i + 1, minOf(j, html.length))
            val closing = tag.startsWith("/")
            val name = tag.removePrefix("/").takeWhile { it.isLetterOrDigit() || it == ':' }.lowercase()
            val selfClosed = tag.endsWith("/")
            val skip = name in SKIP_ELEMENTS || (name == "pre" && !PLAIN_PRE.containsMatchIn(tag)) ||
                (!closing && HIDDEN.containsMatchIn(tag))
            val opens = !closing && !selfClosed && name !in VOID
            if (skipStack.isNotEmpty() && skipStack.last() == name) {
                // inside a skipped element: count nested elements of the same name, so the first
                // closing tag does not end the skip early
                if (opens) skipStack += name else if (closing) skipStack.removeAt(skipStack.lastIndex)
            } else if (skip && opens) skipStack += name
            i = minOf(j + 1, html.length)
            textStart = i
        }
        flush(html.length)
        return out
    }

    /**
     * An element the reader cannot see: `display:none`, `visibility:hidden`, zero font size or height,
     * `opacity:0`, `mso-hide:all`, the `hidden` attribute, and the preheader / preview / tracking
     * wrappers newsletters hide their pre-text and pixels in. Its text is not the message.
     */
    private val HIDDEN = Regex(
        "display\\s*:\\s*none|visibility\\s*:\\s*hidden|font-size\\s*:\\s*0(?:px|pt|em|%)?\\s*(?:;|\"|'|!)|" +
            "max-height\\s*:\\s*0|opacity\\s*:\\s*0\\s*(?:;|\"|'|!)|mso-hide\\s*:\\s*all|\\shidden(?:\\s|=|/|$)|" +
            "class\\s*=\\s*[\"'][^\"']*(?:preheader|preview-?text|pre-?header|hidden|tracking)",
        RegexOption.IGNORE_CASE,
    )

    private val PLAIN_PRE = Regex("""class\s*=\s*["']plain["']""")
    private val VOID = setOf("br", "hr", "img", "wbr", "col")

    // ---- visible text ----------------------------------------------------------------------------------

    private val URL_OR_ADDRESS = Regex("(?:https?://|www\\.)\\S+|[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+", RegexOption.IGNORE_CASE)
    private val MARKUP_LEFTOVER = Regex("<[^>]*>|\\{[^}]*\\}|\\bstyle\\s*=\\s*\\S+", RegexOption.IGNORE_CASE)

    /**
     * What a reader SEES of [html], as one line: the text nodes only (never a tag, an attribute, a
     * style or script body, a comment, or an element hidden from view), entities decoded, links and
     * addresses dropped, whitespace collapsed. This - and nothing else - is what language detection
     * is given; detection reading markup would call a German newsletter English because of its CSS.
     */
    fun visibleText(html: String): String =
        forDetection(textNodes(html).joinToString(" ") { it.decoded })

    /** [text] with anything that is not prose taken out: markup left over, braces, style=, links, addresses. */
    fun forDetection(text: String): String =
        text.replace(URL_OR_ADDRESS, " ")
            .replace(MARKUP_LEFTOVER, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---- protected tokens ------------------------------------------------------------------------

    private val PROTECTED = Regex(
        "`[^`\\n]+`" +
            "|(?:https?://|www\\.)[^\\s<>\"')\\]]+" +
            "|[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+" +
            "|\\d[\\d.,:/\\u2013-]*\\d|\\d",
    )

    private fun hasLetters(s: String) = s.any { it.isLetter() }
    private fun placeholder(n: Int) = "⟦$n⟧"
    private val PLACEHOLDER = Regex("⟦(\\d+)⟧")

    /** A line split into the part the engine sees and the tokens it must not touch. */
    private class Masked(val text: String, val tokens: List<String>)

    private fun mask(line: String): Masked {
        val tokens = ArrayList<String>()
        val text = PROTECTED.replace(line) { m -> tokens += m.value; placeholder(tokens.size - 1) }
        return Masked(text, tokens)
    }

    private fun unmask(translated: String, tokens: List<String>): String? {
        val seen = PLACEHOLDER.findAll(translated).map { it.groupValues[1].toInt() }.toList()
        if (seen.sorted() != tokens.indices.toList()) return null
        return PLACEHOLDER.replace(translated) { tokens[it.groupValues[1].toInt()] }
    }

    // ---- batches ---------------------------------------------------------------------------------

    /** Translate [units] (single-line strings) in order; null where a unit could not be answered. */
    private fun translateUnits(units: List<String>, engine: TextTranslator): List<String?> {
        val out = arrayOfNulls<String>(units.size)
        var from = 0
        while (from < units.size) {
            var to = from
            var chars = 0
            while (to < units.size && to - from < BATCH_LINES && (to == from || chars + units[to].length < BATCH_CHARS)) {
                chars += units[to].length + 1
                to++
            }
            translateRange(units, from, to, engine, out)
            from = to
        }
        return out.toList()
    }

    private fun translateRange(units: List<String>, from: Int, to: Int, engine: TextTranslator, out: Array<String?>) {
        val joined = (from until to).joinToString("\n") { units[it] }
        val reply = engine.translate(joined)
        val lines = reply.split("\n")
        if (lines.size == to - from) {
            for (k in lines.indices) out[from + k] = lines[k].trim().ifEmpty { null }
            return
        }
        // The engine merged or split lines: halve until they pair up, one line at a time at worst.
        if (to - from == 1) { out[from] = reply.replace("\n", " ").trim().ifEmpty { null }; return }
        val mid = (from + to) / 2
        translateRange(units, from, mid, engine, out)
        translateRange(units, mid, to, engine, out)
    }

    // ---- the whole fragment ------------------------------------------------------------------------

    fun translate(html: String, engine: TextTranslator): Result {
        val nodes = textNodes(html)
        // Lines of every node: (node, line index) -> leading space, core, trailing space.
        class Line(val lead: String, val core: String, val trail: String)
        val perNode = nodes.map { node ->
            node.decoded.split("\n").map { l ->
                if (l.isBlank()) Line(l, "", "")
                else Line(l.takeWhile { it.isWhitespace() }, l.trim(), l.takeLastWhile { it.isWhitespace() })
            }
        }
        val translatable = ArrayList<Pair<Int, Int>>()
        perNode.forEachIndexed { n, lines -> lines.forEachIndexed { k, l -> if (hasLetters(mask(l.core).text.replace(PLACEHOLDER, ""))) translatable += n to k } }
        if (translatable.isEmpty()) return Result(html, 0, 0)

        // Round 1: the masked lines.
        val masks = translatable.map { (n, k) -> mask(perNode[n][k].core) }
        val round1 = translateUnits(masks.map { it.text }, engine)
        val done = arrayOfNulls<String>(translatable.size)
        val retry = ArrayList<Int>()
        for (u in translatable.indices) {
            val r = round1[u]
            val restored = if (r == null) null else unmask(r, masks[u].tokens)
            if (restored != null) done[u] = restored else retry += u
        }
        // Round 2: a line whose tokens did not survive is translated piece by piece AROUND them.
        if (retry.isNotEmpty()) {
            class Piece(val unit: Int, val index: Int, val text: String)
            val pieces = ArrayList<Piece>()
            val shape = HashMap<Int, List<String>>()   // unit -> segments, tokens at odd positions
            for (u in retry) {
                val core = perNode[translatable[u].first][translatable[u].second].core
                val segs = ArrayList<String>()
                var at = 0
                for (m in PROTECTED.findAll(core)) { segs += core.substring(at, m.range.first); segs += m.value; at = m.range.last + 1 }
                segs += core.substring(at)
                shape[u] = segs
                segs.forEachIndexed { idx, s -> if (idx % 2 == 0 && hasLetters(s)) pieces += Piece(u, idx, s) }
            }
            val answered = translateUnits(pieces.map { it.text.trim() }, engine)
            val rebuilt = HashMap<Int, MutableList<String>>()
            for (u in retry) rebuilt[u] = shape.getValue(u).toMutableList()
            val any = HashMap<Int, Boolean>()
            pieces.forEachIndexed { p, piece ->
                val t = answered[p] ?: return@forEachIndexed
                val orig = piece.text
                val lead = orig.takeWhile { it.isWhitespace() }
                val trail = orig.takeLastWhile { it.isWhitespace() }
                rebuilt.getValue(piece.unit)[piece.index] = lead + t + trail
                any[piece.unit] = true
            }
            for (u in retry) if (any[u] == true) done[u] = rebuilt.getValue(u).joinToString("")
        }

        // Put the lines back and splice the nodes into the source.
        val newLines = perNode.map { lines -> lines.map { it.core }.toMutableList() }
        var changed = 0
        translatable.forEachIndexed { u, (n, k) -> done[u]?.let { newLines[n][k] = it; changed++ } }
        val sb = StringBuilder()
        var at = 0
        nodes.forEachIndexed { n, node ->
            sb.append(html, at, node.start)
            val text = perNode[n].mapIndexed { k, l -> l.lead + newLines[n][k] + l.trail }.joinToString("\n")
            sb.append(if (newLines[n] == perNode[n].map { it.core }) html.substring(node.start, node.end) else htmlEscape(text))
            at = node.end
        }
        sb.append(html, at, html.length)
        return Result(sb.toString(), changed, translatable.size)
    }
}
