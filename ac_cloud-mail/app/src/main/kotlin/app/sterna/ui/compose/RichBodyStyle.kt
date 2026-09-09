package app.sterna.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.sterna.core.data.text.Inline
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
            addStyle(
                SpanStyle(
                    fontWeight = if (Inline.BOLD in active) FontWeight.Bold else null,
                    fontStyle = if (Inline.ITALIC in active) FontStyle.Italic else null,
                    textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                ),
                p, q,
            )
        }
        for (link in rich.links) addStyle(linkStyle, link.span.start, link.span.end)
    }
}

/**
 * [text] with the body's styling laid on it, ready for the TEXT LAYER — the only place a span may live
 */
fun styledBody(text: AnnotatedString, rich: RichBody, linkColor: Color): AnnotatedString = when {
    rich.ranges.isEmpty() && rich.links.isEmpty() -> text
    rich.text != text.text -> text
    else -> toAnnotatedString(rich, linkColor)
}
