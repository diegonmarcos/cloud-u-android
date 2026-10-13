package app.sterna.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.TEXT_COLOR_OF
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span

/** A `TextFieldValue` selection as a [Span]: a reversed selection still reads left to right. */
internal fun span(selection: TextRange): Span = Span(selection.min, selection.max)

/**
 * The body as the text field draws it: the text cut at EVERY span boundary, and one `SpanStyle` per
 */
fun toAnnotatedString(rich: RichBody, linkColor: Color): AnnotatedString {
    if (rich.isPlain) return AnnotatedString(rich.text)
    val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
    val cuts = sortedSetOf(0, rich.text.length)
    for (spans in rich.ranges.values) for (s in spans) { cuts.add(s.start); cuts.add(s.end) }
    val positions = cuts.toList()
    return buildAnnotatedString {
        append(rich.text)
        for (i in 0 until positions.size - 1) {
            val p = positions[i]
            val q = positions[i + 1]
            val active = rich.ranges.filterValues { spans -> spans.any { it.start <= p && q <= it.end } }.keys
            if (active.isEmpty()) continue
            val decorations = listOfNotNull(
                TextDecoration.Underline.takeIf { Inline.UNDERLINE in active },
                TextDecoration.LineThrough.takeIf { Inline.STRIKE in active },
            )
            // The innermost colour wins, as in the HTML (colours nest after b/i/u/s, in declaration order).
            val color = Inline.entries.lastOrNull { it in active && it in TEXT_COLOR_OF }
                ?.let { Color(0xFF000000L or TEXT_COLOR_OF.getValue(it).removePrefix("#").toLong(16)) }
            addStyle(
                SpanStyle(
                    fontWeight = if (Inline.BOLD in active) FontWeight.Bold else null,
                    fontStyle = if (Inline.ITALIC in active) FontStyle.Italic else null,
                    textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                    color = color ?: Color.Unspecified,
                ),
                p, q,
            )
        }
        // Headings and quotations are LINE styles: a heading line larger and bold, a quoted line italic.
        val starts = lineStartOffsets(rich.text)
        for (block in rich.blocks) {
            val style = when (block.kind) {
                BlockKind.HEADING -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = HEADING_SCALE.em)
                BlockKind.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic)
                else -> null
            } ?: continue
            val from = starts.getOrNull(block.lines.first) ?: continue
            val to = starts.getOrNull(block.lines.last + 1)?.minus(1) ?: rich.text.length
            if (from < to) addStyle(style, from, to)
        }
        for (link in rich.links) addStyle(linkStyle, link.span.start, link.span.end)
    }
}

/** A heading line's size, relative to the body text. */
private const val HEADING_SCALE = 1.3f

private fun lineStartOffsets(text: String): List<Int> {
    val out = ArrayList<Int>()
    out.add(0)
    for (i in text.indices) if (text[i] == '\n') out.add(i + 1)
    return out
}

/**
 * [text] with the body's styling laid on it, ready for the TEXT LAYER — the only place a span may live
 */
fun styledBody(text: AnnotatedString, rich: RichBody, linkColor: Color): AnnotatedString = when {
    rich.ranges.isEmpty() && rich.links.isEmpty() &&
        rich.blocks.none { it.kind == BlockKind.HEADING || it.kind == BlockKind.QUOTE } -> text
    rich.text != text.text -> text
    else -> toAnnotatedString(rich, linkColor)
}
