package app.sterna.ui.text

import app.sterna.core.data.text.Block
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span

/**
 * WHAT Enhance and Translate are allowed to touch, and nothing about how they run.
 *
 * An email body is not a keyboard field. The keyboard's tools work on a short editable
 * buffer behind an input connection; a message is long, is often HTML, and usually ends
 * in a thread the user did not write and does not want rewritten. Three rules follow,
 * and they are the whole of this file:
 *
 *  1. A RECEIVED message is READ-ONLY. Nothing here writes one back. What is sent is a
 *     flattened, quote-free copy, and the result is shown beside the message — the
 *     stored body is never the target of a write.
 *  2. HTML IS NEVER REWRITTEN. A model handed markup returns markup of its own choosing,
 *     and applying that would silently replace the sender's document with the model's.
 *     Only text is ever sent. On a received message that is safe because nothing is
 *     applied; a DRAFT's body is already plain text with its styling held beside it, so
 *     there is no markup to lose there either.
 *  3. QUOTED HISTORY AND THE SIGNATURE ARE OUT OF SCOPE unless the user selected them.
 *     Rewriting the thread below the signature is the failure that makes a tool worse
 *     than no tool.
 */
object TextToolScope {

    /** A line that is part of a quote. The composer writes exactly this prefix; see `deepenQuote`. */
    private fun String.isQuoted() = startsWith(">")

    /** The RFC 3676 signature separator, and the two sloppy spellings that reach the field. */
    private fun String.isSignatureDelimiter() =
        this == "-- " || this == "--" || this == "-- \r" || this == "--\r"

    /**
     * Where the part of [text] the user did not write for THIS message begins: the trailing
     * run of quoted lines, or the signature above it, whichever starts earlier.
     *
     * Walked from the END backwards, so an interleaved reply — quoted lines with the user's
     * own answers between them — is left entirely alone. Only a run that reaches the bottom
     * of the body is history; a quote with fresh text under it is part of what is being said.
     *
     * Returns [text].length when there is no such tail, and 0 when the body is nothing but
     * history (there is then nothing to rewrite, and [draftScope] answers an empty span).
     *
     * ponytail: the attribution line above a quote ("On Monday, X wrote:") is unquoted and
     * so stays in scope. It is one line, it reads as the user's own sentence, and telling it
     * apart from a real sentence needs per-locale patterns. Cut it if a rewrite ever mangles
     * one visibly.
     */
    fun historyStart(text: String): Int {
        val lines = text.split("\n")
        var i = lines.lastIndex
        // Blank lines at the very bottom are not history and not content; step over them so a
        // body ending in "> quoted\n\n" is still seen as ending in a quote.
        while (i >= 0 && lines[i].isBlank()) i--
        // The last line with anything on it decides. Not quoted -> the body ends in the user's
        // own words, so there is no trailing history however much is quoted further up. That is
        // what leaves an interleaved or bottom-posted reply untouched.
        var first = lines.size
        if (i >= 0 && lines[i].isQuoted()) {
            while (i >= 0 && (lines[i].isQuoted() || lines[i].isBlank())) i--
            first = i + 1
        }
        // A signature ABOVE the tail moves the boundary up: asked to rewrite a signature a model
        // improves it, and nobody asked. The last delimiter before the tail wins, so a quoted
        // "-- " from the original thread cannot pull the boundary to the top of the message.
        for (k in first - 1 downTo 0) {
            if (lines[k].isSignatureDelimiter()) { first = k; break }
        }
        if (first >= lines.size) return text.length
        // Character offset of line [first]: every earlier line, plus the newline after it.
        var offset = 0
        for (k in 0 until first) offset += lines[k].length + 1
        return offset
    }

    /**
     * The stretch of a DRAFT one run rewrites.
     *
     * An explicit [selection] wins outright, quote or not: the user pointed at it, and
     * second-guessing a selection is how a tool starts feeling like it has opinions. With no
     * selection the scope is everything above [historyStart], trailing blanks trimmed off so
     * the model is not handed whitespace to interpret.
     *
     * An empty span means there is nothing to send, and the caller says so instead of
     * calling out to the network to rewrite nothing.
     */
    fun draftScope(text: String, selection: Span): Span {
        if (!selection.isEmpty) return selection
        val end = historyStart(text)
        val body = text.substring(0, end)
        val trimmed = body.trimEnd()
        return Span(0, trimmed.length)
    }

    /**
     * What is SENT for a received message: its text, with the trailing history cut.
     *
     * [plainBody] is the message already flattened by the app's own `htmlToText` — the
     * flattening deliberately happens at the call site, where the reader's HTML-vs-text
     * mode already lives, so this file never has to decide what a message's text is.
     *
     * Quoting in a received message is markup, not ">" prefixes, so the caller flattens
     * first and the same [historyStart] scan then applies: `htmlToText` renders a
     * blockquote with "> " prefixes, which is precisely what this reads.
     */
    fun receivedScope(plainBody: String): String =
        plainBody.substring(0, historyStart(plainBody)).trimEnd()

    /**
     * Put [replacement] over [range] of [rich], keeping the styling that survives.
     *
     * This is the part an email has and a keyboard field does not. A draft's bold runs,
     * lists and links live BESIDE the text as character and line offsets into it (#131);
     * replacing a stretch of the text without moving them leaves every style after the edit
     * pointing at the wrong words, which reads as the formatting having scrambled itself.
     *
     * The rule, for spans and links alike:
     *   entirely before  -> unchanged
     *   entirely after   -> shifted by the length delta
     *   overlapping      -> DROPPED
     * Dropping is not a shortcut. The text a style covered no longer exists; the model
     * returned different words, and there is no honest way to say which of them were bold.
     * Blocks follow the same rule on LINE indices, because a block is a run of lines.
     */
    fun splice(rich: RichBody, range: Span, replacement: String): RichBody {
        val text = rich.text.substring(0, range.start) + replacement + rich.text.substring(range.end)
        val delta = replacement.length - range.length

        fun move(s: Span): Span? = when {
            s.end <= range.start -> s
            s.start >= range.end -> Span(s.start + delta, s.end + delta)
            else -> null
        }

        val ranges: Map<Inline, List<Span>> = rich.ranges
            .mapValues { (_, spans) -> spans.mapNotNull(::move) }
            .filterValues { it.isNotEmpty() }

        val links: List<Link> = rich.links.mapNotNull { link -> move(link.span)?.let { Link(it, link.url) } }

        // Lines, not characters: a block owns whole lines, so what matters is how many lines
        // the edit removed and how many it put back.
        val firstLine = rich.text.take(range.start).count { it == '\n' }
        val lastLine = rich.text.take(range.end).count { it == '\n' }
        val lineDelta = replacement.count { it == '\n' } - (lastLine - firstLine)
        val blocks: List<Block> = rich.blocks.mapNotNull { b ->
            when {
                b.lines.last < firstLine -> b
                b.lines.first > lastLine -> Block(b.kind, (b.lines.first + lineDelta)..(b.lines.last + lineDelta))
                else -> null
            }
        }

        // RichBody normalises on construction (clamping, merging, dropping what no longer
        // fits the text), so anything the arithmetic above got marginally wrong at an edge
        // is corrected by the model itself rather than stored crooked.
        return RichBody(text, ranges, blocks, links)
    }
}
