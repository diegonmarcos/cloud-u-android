package app.sterna.ui.message

import app.sterna.core.data.text.htmlEscape
import app.sterna.ui.compose.BLOCKQUOTE_TAG

/**
 * Fold the TRAILING quoted history of a rich HTML body behind a native `<details>`.
 */
internal fun foldTopLevelQuotes(html: String, label: String): String {
    // Guard 1 — a mute button.
    if (label.isBlank()) return html

    // One pass: remember where each top-level blockquote opens and where it closes again.
    val spans = ArrayList<IntRange>()
    var depth = 0
    var openedAt = -1
    for (tag in BLOCKQUOTE_TAG.findAll(html)) {
        if (tag.value.startsWith("</")) {
            depth -= 1
            // Guard 3a — a closing tag with nothing open. The markup is not what this scan
            // assumes, so it keeps its hands off the whole body.
            if (depth < 0) return html
            if (depth == 0 && openedAt >= 0) {
                spans += openedAt..tag.range.last
                openedAt = -1
            }
        } else {
            // `depth == 0` is what makes the span TOP-LEVEL. Without it `openedAt` walks inwards to
            // the innermost quote while the span still ends on the outermost close, so the <details>
            // opens INSIDE the history and its first levels stay unfolded.
            if (depth == 0) openedAt = tag.range.first
            depth += 1
        }
    }
    // Guard 3b — a quote that never closes. Where it ends is a guess, and guessing here truncates.
    if (depth != 0 || openedAt >= 0) return html

    val quote = spans.lastOrNull() ?: return html
    // Only a TRAILING quote folds: anything the reader can see after it means this is an
    // interleaved or bottom-posted reply, and those read worse folded than open.
    if (hasVisibleContent(html.substring(quote.last + 1))) return html
    // Guard 2 — is there any message ABOVE it? If not, folding empties the screen.
    if (!hasVisibleContent(html.substring(0, quote.first))) return html

    return html.substring(0, quote.first) +
        "<details class=\"s-quote\"><summary>${htmlEscape(label)}</summary>" +
        html.substring(quote.first, quote.last + 1) +
        "</details>" +
        html.substring(quote.last + 1)
}

/**
 * Whether [fragment] holds anything the reader would SEE — the test behind guard 2.
 */
private fun hasVisibleContent(fragment: String): Boolean =
    fragment
        .replace(INVISIBLE_MARKUP, " ")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .isNotBlank()

/** Elements whose TEXT never reaches the screen, plus comments — dropped content and all. */
private val INVISIBLE_MARKUP = Regex(
    "<!--.*?-->|<(head|style|script|title)\\b[^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
