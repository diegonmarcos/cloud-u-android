package app.sterna.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the body field DRAWS for a rich body (#131): the text cut at every span boundary, one
 */
class RichBodyStyleTest {

    @Test
    fun `overlapping spans become one style per segment, decorations combined`() {
        val rich = RichBody("abcdefghij", mapOf(
            Inline.BOLD to listOf(Span(0, 6)),
            Inline.UNDERLINE to listOf(Span(3, 9)),
            Inline.STRIKE to listOf(Span(3, 9)),
        ))

        val out = toAnnotatedString(rich, LINK)

        assertEquals("the text itself must be untouched", "abcdefghij", out.text)
        val both = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        assertEquals(
            "three segments: bold alone, bold with both decorations, both decorations alone",
            listOf(
                AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 0, 3),
                AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = both), 3, 6),
                AnnotatedString.Range(SpanStyle(textDecoration = both), 6, 9),
            ),
            out.spanStyles,
        )
    }

    @Test
    fun `italic is a font style, and a plain body carries no span at all`() {
        val out = toAnnotatedString(RichBody("abcd", mapOf(Inline.ITALIC to listOf(Span(1, 3)))), LINK)
        assertEquals(
            listOf(AnnotatedString.Range(SpanStyle(fontStyle = FontStyle.Italic), 1, 3)),
            out.spanStyles,
        )
        assertEquals(emptyList<AnnotatedString.Range<SpanStyle>>(), toAnnotatedString(RichBody.plain("abcd"), LINK).spanStyles)
    }

    @Test
    fun `a link is drawn in the link colour, underlined, over whatever else is there`() {
        val rich = RichBody(
            "see the site now",
            mapOf(Inline.BOLD to listOf(Span(0, 8))),
            emptyList(),
            listOf(Link(Span(8, 12), "https://x")),
        )

        val out = toAnnotatedString(rich, LINK)

        assertEquals("the text itself must be untouched", "see the site now", out.text)
        assertEquals(
            "the families are laid per segment as before, and the link's own style goes ON TOP of " +
                "them — an underline already there changes nothing visible, which is accepted",
            listOf(
                AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 0, 8),
                AnnotatedString.Range(SpanStyle(color = LINK, textDecoration = TextDecoration.Underline), 8, 12),
            ),
            out.spanStyles,
        )
    }

    @Test
    fun `a body whose only styling is a link is still drawn as a link`() {
        val out = toAnnotatedString(RichBody("site", emptyMap(), emptyList(), listOf(Link(Span(0, 4), "https://x"))), LINK)
        assertEquals(
            listOf(AnnotatedString.Range(SpanStyle(color = LINK, textDecoration = TextDecoration.Underline), 0, 4)),
            out.spanStyles,
        )
    }

    /**
     * A list AND a link on one body. The two never meet and that is the point: the markers are
     */
    @Test
    fun `a body with a list and a link draws the link at the model's own offsets`() {
        val rich = RichBody(
            "milk\nthe site",
            emptyMap(),
            listOf(Block(BlockKind.BULLET, 0..1)),
            listOf(Link(Span(9, 13), "https://x")),
        )

        val out = toAnnotatedString(rich, LINK)

        assertEquals("no marker character may be in the drawn text", "milk\nthe site", out.text)
        assertEquals(
            "⛔ 9..13 is \"site\" in the MODEL's text. Were the markers characters of it, the two " +
                "bullets before it would have pushed the anchor four characters to the left.",
            listOf(AnnotatedString.Range(SpanStyle(color = LINK, textDecoration = TextDecoration.Underline), 9, 13)),
            out.spanStyles,
        )
    }

    @Test
    fun `a reversed selection reads left to right`() {
        assertEquals(Span(2, 7), span(TextRange(7, 2)))
        assertEquals(Span(4, 4), span(TextRange(4)))
    }

    private companion object {
        /** Any colour at all: what matters is that the one the caller passes is the one applied. */
        val LINK = Color(0xFF0066CC)
    }
}
