package app.sterna.ui.settings

/**
 * The validation panel's brain: what is wrong with, or fragile about, a signature's HTML source.
 *
 * A scan, not a parser, like [SignatureHtmlTools.highlight]: half-typed markup yields findings and
 * never throws. Every [Issue] carries the `[start, end)` range it is about, so the editor can mark
 * the gutter line and jump to it on tap.
 */
internal object SignatureValidator {

    enum class Severity { ERROR, WARNING, INFO }

    enum class Code {
        UNCLOSED_TAG, MISMATCHED_CLOSE, STRAY_CLOSE, UNTERMINATED_TAG, UNTERMINATED_COMMENT,
        EXTERNAL_IMAGE, DATA_IMAGE, MISSING_ALT, STYLE_BLOCK, CLASS_ATTRIBUTE,
        STRIPPED_ELEMENT, STRIPPED_ATTRIBUTE, SIZE_WARNING, SIZE_LIMIT,
    }

    /** [detail] is the tag / attribute / value the finding is about; [line] is 1-based. */
    data class Issue(
        val severity: Severity,
        val code: Code,
        val start: Int,
        val end: Int,
        val line: Int,
        val detail: String = "",
    )

    /** Mail clients clip or reject a signature beyond roughly this; Gmail clips at 10 000 characters. */
    const val SIZE_WARN_BYTES = 10_000

    /** Above this the editor refuses to save: the stored pair goes through settings backup and the fleet store. */
    const val SIZE_LIMIT_BYTES = 64 * 1024

    fun validate(source: String): List<Issue> {
        val issues = ArrayList<Issue>()
        val lines = LineIndex(source)
        fun add(sev: Severity, code: Code, start: Int, end: Int, detail: String = "") {
            issues += Issue(sev, code, start, end.coerceAtLeast(start), lines.lineOf(start), detail)
        }

        val bytes = source.toByteArray(Charsets.UTF_8).size
        when {
            bytes > SIZE_LIMIT_BYTES -> add(Severity.ERROR, Code.SIZE_LIMIT, 0, 0, bytes.toString())
            bytes > SIZE_WARN_BYTES -> add(Severity.WARNING, Code.SIZE_WARNING, 0, 0, bytes.toString())
        }

        class Open(val name: String, val start: Int, val end: Int)
        val stack = ArrayList<Open>()
        val n = source.length
        var i = 0
        while (i < n) {
            val lt = source.indexOf('<', i)
            if (lt < 0) break
            if (source.startsWith("<!--", lt)) {
                val close = source.indexOf("-->", lt + 4)
                if (close < 0) {
                    add(Severity.ERROR, Code.UNTERMINATED_COMMENT, lt, n)
                    i = n
                } else {
                    i = close + 3
                }
                continue
            }
            val next = source.getOrNull(lt + 1)
            if (next == null || !(next.isLetter() || next == '/')) {
                i = lt + 1
                continue
            }
            val tag = readTag(source, lt)
            if (tag == null) {
                add(Severity.ERROR, Code.UNTERMINATED_TAG, lt, n, source.substring(lt, minOf(n, lt + 12)))
                break
            }
            i = tag.end
            val name = tag.name
            if (tag.closing) {
                val at = stack.indexOfLast { it.name == name }
                when {
                    at < 0 -> add(Severity.ERROR, Code.STRAY_CLOSE, lt, tag.end, name)
                    else -> {
                        for (k in stack.size - 1 downTo at + 1) {
                            val skipped = stack[k]
                            if (skipped.name !in OPTIONAL_END) {
                                add(Severity.ERROR, Code.MISMATCHED_CLOSE, skipped.start, skipped.end, "${skipped.name}|$name")
                            }
                        }
                        while (stack.size > at) stack.removeAt(stack.size - 1)
                    }
                }
                continue
            }
            checkTag(tag, ::add)
            if (name == "style" || name == "script") {
                // Raw text: its content is not markup, so skip to the matching close.
                val close = source.indexOf("</$name", tag.end, ignoreCase = true)
                if (close < 0) {
                    add(Severity.ERROR, Code.UNCLOSED_TAG, lt, tag.end, name)
                    i = n
                } else {
                    val gt = source.indexOf('>', close)
                    i = if (gt < 0) n else gt + 1
                }
                continue
            }
            if (!tag.selfClosing && name !in VOID) stack += Open(name, lt, tag.end)
        }
        for (open in stack) {
            if (open.name !in OPTIONAL_END) add(Severity.ERROR, Code.UNCLOSED_TAG, open.start, open.end, open.name)
        }
        return issues.sortedWith(compareBy({ it.line }, { it.start }))
    }

    /** The worst severity per 1-based line, for the gutter markers. */
    fun markers(issues: List<Issue>): Map<Int, Severity> {
        val out = HashMap<Int, Severity>()
        for (issue in issues) {
            if (issue.code == Code.SIZE_WARNING || issue.code == Code.SIZE_LIMIT) continue
            val seen = out[issue.line]
            if (seen == null || issue.severity < seen) out[issue.line] = issue.severity
        }
        return out
    }

    /** Whether saving must be refused: only a size beyond the limit. Markup problems are repaired on send. */
    fun blocksSave(issues: List<Issue>): Boolean = issues.any { it.code == Code.SIZE_LIMIT }

    private fun checkTag(tag: Tag, add: (Severity, Code, Int, Int, String) -> Unit) {
        val name = tag.name
        when (name) {
            "style" -> add(Severity.WARNING, Code.STYLE_BLOCK, tag.start, tag.end, name)
            in DROPPED -> add(Severity.WARNING, Code.STRIPPED_ELEMENT, tag.start, tag.end, name)
        }
        for (attr in tag.attributes) {
            val a = attr.name
            when {
                a.startsWith("on") && a.length > 2 -> add(Severity.WARNING, Code.STRIPPED_ATTRIBUTE, attr.start, attr.end, a)
                a == "class" -> add(Severity.INFO, Code.CLASS_ATTRIBUTE, attr.start, attr.end, a)
            }
        }
        if (name == "img") {
            val src = tag.attributes.firstOrNull { it.name == "src" }
            val value = src?.value?.trim().orEmpty()
            when {
                value.startsWith("http://", true) || value.startsWith("https://", true) ->
                    add(Severity.WARNING, Code.EXTERNAL_IMAGE, tag.start, tag.end, value)
                value.startsWith("data:", true) ->
                    add(Severity.WARNING, Code.DATA_IMAGE, tag.start, tag.end, "")
            }
            if (tag.attributes.none { it.name == "alt" }) add(Severity.INFO, Code.MISSING_ALT, tag.start, tag.end, "")
        }
    }

    // -- tag reading ---------------------------------------------------------

    private class Attr(val name: String, val value: String?, val start: Int, val end: Int)

    private class Tag(
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val attributes: List<Attr>,
        val start: Int,
        val end: Int,
    )

    /** The tag at [from] (a `<`), or null when the input ends before its `>`. */
    private fun readTag(s: String, from: Int): Tag? {
        val n = s.length
        var i = from + 1
        val closing = s[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < n && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == ':')) i++
        val name = s.substring(nameStart, i).lowercase()
        val attrs = ArrayList<Attr>()
        var selfClosing = false
        while (i < n && s[i] != '>') {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '/' -> { selfClosing = true; i++ }
                c == '"' || c == '\'' -> {
                    val close = s.indexOf(c, i + 1)
                    if (close < 0) return null
                    i = close + 1
                }
                else -> {
                    selfClosing = false
                    val a = i
                    while (i < n && !s[i].isWhitespace() && s[i] !in "=/>\"'") i++
                    if (i == a) { i++; continue }
                    val attrName = s.substring(a, i).lowercase()
                    var j = i
                    while (j < n && s[j].isWhitespace()) j++
                    var value: String? = null
                    var end = i
                    if (j < n && s[j] == '=') {
                        j++
                        while (j < n && s[j].isWhitespace()) j++
                        if (j < n && (s[j] == '"' || s[j] == '\'')) {
                            val close = s.indexOf(s[j], j + 1)
                            if (close < 0) return null
                            value = s.substring(j + 1, close)
                            end = close + 1
                        } else {
                            val v = j
                            while (j < n && !s[j].isWhitespace() && s[j] != '>') j++
                            value = s.substring(v, j)
                            end = j
                        }
                        i = end
                    }
                    attrs += Attr(attrName, value, a, end)
                }
            }
        }
        if (i >= n) return null
        return Tag(name, closing, selfClosing, attrs, from, i + 1)
    }

    private class LineIndex(source: String) {
        private val starts = ArrayList<Int>().apply {
            add(0)
            source.forEachIndexed { k, c -> if (c == '\n') add(k + 1) }
        }

        fun lineOf(offset: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo + 1
        }
    }

    val VOID: Set<String> = setOf("br", "hr", "img", "col", "wbr", "input", "link", "meta", "base", "param", "source", "track", "embed", "area")

    /** Elements HTML lets you leave open; mail clients cope, so they are not findings. */
    private val OPTIONAL_END: Set<String> = setOf("p", "li", "dt", "dd", "tr", "td", "th", "thead", "tbody", "tfoot", "colgroup", "option")

    /** What the send-path sanitiser removes whole, so the owner is told before the preview shows a hole. */
    private val DROPPED: Set<String> = setOf(
        "script", "noscript", "iframe", "frame", "object", "embed", "applet", "form", "input", "button", "select",
        "textarea", "svg", "math", "link", "meta", "base", "title", "video", "audio", "canvas",
    )
}
