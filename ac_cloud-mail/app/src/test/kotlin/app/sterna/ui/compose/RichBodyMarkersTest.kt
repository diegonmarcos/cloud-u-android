package app.sterna.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.TextDecoration
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.toggleBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The markers a list is DRAWN with (#131), and the offset arithmetic that goes with them —
 */
class RichBodyMarkersTest {

    // -- what is drawn ---------------------------------------------------------------------------

    @Test fun `every line of a bullet block is drawn with its marker, the others untouched`() {
        val text = AnnotatedString("shopping\nmilk\neggs\nbye")

        val out = markedText(text, listOf(Block(BlockKind.BULLET, 1..2)))

        assertEquals("shopping\n• milk\n• eggs\nbye", out.text.text)
    }

    @Test fun `a numbered block is numbered from 1, and the next block starts again at 1`() {
        val text = AnnotatedString("a\nb\nc\nd\ne")

        val out = markedText(text, listOf(Block(BlockKind.NUMBER, 0..1), Block(BlockKind.NUMBER, 3..4)))

        assertEquals(
            "the numbering is per BLOCK: a second list is a second list, not the continuation of " +
                "the first",
            "1. a\n2. b\nc\n1. d\n2. e",
            out.text.text,
        )
    }

    @Test fun `an empty line inside a list still gets its marker`() {
        val out = markedText(AnnotatedString("milk\n\neggs"), listOf(Block(BlockKind.BULLET, 0..2)))

        assertEquals("• milk\n• \n• eggs", out.text.text)
    }

    /**
     * The reason the text is rebuilt from `subSequence` and not from its plain characters: the
     */
    @Test fun `the inline styling rides along, shifted by the markers`() {
        val text = AnnotatedString.Builder().apply {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("milk")
            pop()
            append("\neggs")
        }.toAnnotatedString()

        val out = markedText(text, listOf(Block(BlockKind.BULLET, 0..1)))

        assertEquals("• milk\n• eggs", out.text.text)
        assertEquals(
            listOf(AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 2, 6)),
            out.text.spanStyles,
        )
    }

    // -- the frequent path costs nothing -----------------------------------------------------------

    @Test fun `a body with no block is handed back untouched, with the identity mapping`() {
        val text = AnnotatedString("no list here\nat all")

        val out = markedText(text, emptyList())

        assertSame("the very same AnnotatedString must come back — no copy, no rebuild", text, out.text)
        assertSame(
            "⛔ `OffsetMapping.Identity` and nothing else: this is the path every plain body takes " +
                "on every keystroke",
            OffsetMapping.Identity,
            out.offsetMapping,
        )
    }

    // -- the mapping -------------------------------------------------------------------------------

    @Test fun `an offset at the start of a line lands after its marker, never inside it`() {
        val out = markedText(AnnotatedString("milk\neggs"), listOf(Block(BlockKind.BULLET, 0..1)))
        val map = out.offsetMapping

        assertEquals("the first character of the first item", 2, map.originalToTransformed(0))
        assertEquals("the first character of the second item", 9, map.originalToTransformed(5))
        assertEquals("the end of the text", 13, map.originalToTransformed(9))
    }

    @Test fun `a position inside a marker maps back to the first character of its line`() {
        val out = markedText(AnnotatedString("milk\neggs"), listOf(Block(BlockKind.BULLET, 0..1)))
        val map = out.offsetMapping

        assertEquals("between the bullet and the space", 0, map.transformedToOriginal(1))
        assertEquals("between the bullet and the space of the second item", 5, map.transformedToOriginal(8))
    }

    /**
     * THE property. Compose asks both directions on every caret move, drag and IME commit; a
     * pair that does not round trip is a caret that walks away from the finger.
     */
    @Test fun `the round trip is the identity for every offset, and both directions are monotonic`() {
        val raw = "shopping\nmilk\n\neggs\nbye"
        val blocks = RichBody(raw, emptyMap(), listOf(Block(BlockKind.BULLET, 1..3))).blocks
        val out = markedText(AnnotatedString(raw), blocks)
        val map = out.offsetMapping

        var previous = -1
        for (o in 0..raw.length) {
            val t = map.originalToTransformed(o)
            assertTrue("originalToTransformed must be strictly increasing at $o: $previous then $t", t > previous)
            previous = t
            assertEquals("the round trip lost offset $o", o, map.transformedToOriginal(t))
        }
        assertEquals(
            "the last original offset must land at the very end of the transformed text",
            out.text.length,
            map.originalToTransformed(raw.length),
        )
        var back = -1
        for (t in 0..out.text.length) {
            val o = map.transformedToOriginal(t)
            assertTrue("transformedToOriginal must never go backwards at $t: $back then $o", o >= back)
            assertTrue("…nor outside the text at $t: $o", o in 0..raw.length)
            back = o
        }
    }

    @Test fun `the round trip holds on a numbered list, whose markers are three characters`() {
        val raw = "a\nbb\nccc"
        val out = markedText(AnnotatedString(raw), listOf(Block(BlockKind.NUMBER, 0..2)))
        val map = out.offsetMapping

        assertEquals("1. a\n2. bb\n3. ccc", out.text.text)
        for (o in 0..raw.length) assertEquals("lost offset $o", o, map.transformedToOriginal(map.originalToTransformed(o)))
    }

    // -- the body's ONE transformation: the styling AND the markers, together ----------------------

    /**
     * Why these run `bodyTransformation` and not just `markedText`: the body field has exactly
     */
    @Test fun `a styled body with no list is drawn with its spans, and nothing moves`() {
        val rich = RichBody("abcdef", mapOf(Inline.BOLD to listOf(Span(2, 4))))

        val out = bodyTransformation(rich, LINK).filter(AnnotatedString("abcdef"))

        assertEquals("not a character is added: there is no list here", "abcdef", out.text.text)
        assertEquals(
            "the bold of the MODEL must be on the drawn text — it is the only place it can be " +
                "now, the field's value carries none",
            listOf(AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 2, 4)),
            out.text.spanStyles,
        )
        for (o in 0..6) {
            assertEquals("no marker, so offset $o must not move", o, out.offsetMapping.originalToTransformed(o))
        }
    }

    /**
     * The two halves at once, which is the whole point: a bold word INSIDE a bullet item. The
     */
    @Test fun `a styled body inside a list is drawn with its spans shifted by the markers`() {
        val rich = RichBody(
            "abc\ndef",
            mapOf(Inline.BOLD to listOf(Span(4, 7))),
            listOf(Block(BlockKind.BULLET, 0..1)),
        )

        val out = bodyTransformation(rich, LINK).filter(AnnotatedString("abc\ndef"))

        assertEquals("• abc\n• def", out.text.text)
        assertEquals(
            "⛔ \"def\" is 4..7 in the MODEL and 8..11 on screen: two bullets of two characters " +
                "stand before it. A span laid after the markers were inserted, or markers " +
                "inserted into a text that carried no span, would put the bold four characters " +
                "to the left of the word",
            listOf(AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 8, 11)),
            out.text.spanStyles,
        )
        val map = out.offsetMapping
        for (o in 0..7) {
            assertEquals("the round trip lost offset $o — the styling must not touch the mapping", o, map.transformedToOriginal(map.originalToTransformed(o)))
        }
        assertEquals("the first character of the second item", 8, map.originalToTransformed(4))
    }

    /**
     * The case that catches a free path widened by mistake to `ranges.isEmpty()` alone: no
     */
    @Test fun `a body whose only styling is a link is still drawn as a link`() {
        val rich = RichBody("site", emptyMap(), emptyList(), listOf(Link(Span(0, 4), "https://x")))

        val out = bodyTransformation(rich, LINK).filter(AnnotatedString("site"))

        assertEquals(
            listOf(AnnotatedString.Range(SpanStyle(color = LINK, textDecoration = TextDecoration.Underline), 0, 4)),
            out.text.spanStyles,
        )
    }

    /**
     * The free path, and it is not a nicety: this runs on every keystroke of a body this screen
     */
    @Test fun `a plain body is handed back as the very same instance, not a copy`() {
        val text = AnnotatedString("abcd")

        assertSame(
            "⛔ the very same AnnotatedString — no allocation at all on the frequent path",
            text,
            styledBody(text, RichBody.plain("abcd"), LINK),
        )
    }

    /**
     * The free path is `ranges` AND `links`, NOT [RichBody.isPlain] — which also counts the
     */
    @Test fun `a body that is only a list, with no styling at all, is still handed straight back`() {
        val text = AnnotatedString("abc\ndef")
        val listOnly = RichBody("abc\ndef", emptyMap(), listOf(Block(BlockKind.BULLET, 0..1)))

        assertSame(
            "⛔ blocks are not styling: nothing is laid on this text, so it must come back as the " +
                "very same instance. `isPlain` on this line copies the whole body every frame",
            text,
            styledBody(text, listOnly, LINK),
        )
    }

    /**
     * …and the same thing seen from the SITE OF THE CALL, which is what actually runs. An
     */
    @Test fun `a plain body crosses the whole transformation without being copied once`() {
        val text = AnnotatedString("abcd")

        assertSame(
            "⛔ end to end — `styledBody` hands it back, then `markedText`'s own free path hands " +
                "it back. Not one copy of the body between the field and the screen",
            text,
            bodyTransformation(RichBody.plain("abcd"), LINK).filter(text).text,
        )
    }

    /**
     * The defensive guard. The field's value and the model it is drawn from are two pieces of
     */
    @Test fun `a model whose text has drifted from the field's draws no style, and does not throw`() {
        val text = AnnotatedString("abcdef")
        // THE SAME LENGTH, deliberately: a guard comparing `rich.text.length` instead of
        // `rich.text` would pass a body of the right size and the wrong characters, and the bold
        // would land on whatever happens to sit at 0..2 of the text on screen.
        val stale = RichBody("zzzzzz", mapOf(Inline.BOLD to listOf(Span(0, 2))))

        assertSame(
            "a body carrying spans, but for ANOTHER text OF THE SAME LENGTH: hand the field's " +
                "own text back untouched rather than lay 0..2 of \"zzzzzz\" over \"abcdef\"",
            text,
            styledBody(text, stale, LINK),
        )
    }

    // -- which button is lit ------------------------------------------------------------------------

    @Test fun `the cursor's own line decides, with nothing selected`() {
        val body = RichBody("shopping\nmilk\neggs", emptyMap(), listOf(Block(BlockKind.BULLET, 1..2)))

        assertNull("the line above the list carries nothing", listKindAt(body, Span(0, 0)))
        assertEquals(BlockKind.BULLET, listKindAt(body, Span(9, 9)))
        assertEquals("the caret at the very end of the last item", BlockKind.BULLET, listKindAt(body, Span(18, 18)))
    }

    @Test fun `a selection is lit only when EVERY line it touches carries the same kind`() {
        val body = RichBody(
            "shopping\nmilk\neggs\n1\n2",
            emptyMap(),
            listOf(Block(BlockKind.BULLET, 1..2), Block(BlockKind.NUMBER, 3..4)),
        )

        assertEquals("both lines of the bullet block", BlockKind.BULLET, listKindAt(body, Span(9, 18)))
        assertNull("a selection reaching out of the block", listKindAt(body, Span(0, 13)))
        assertNull("a selection across two kinds", listKindAt(body, Span(9, 21)))
        assertEquals(
            "a selection stopping exactly at the start of the next line does not reach into it",
            BlockKind.BULLET,
            listKindAt(body, Span(9, 14)),
        )
        // …and the case that actually CROSSES a kind at that boundary: offset 19 is the first
        // character of the numbered block. Read with `end` instead of `end - 1`, dragging the
        // handle from the first bullet down to the line under the list puts the lit button out of
        // step with what the tap does — the button says "no list" while the tap removes one.
        assertEquals(
            "a selection stopping at the start of a line of ANOTHER kind still does not reach it",
            BlockKind.BULLET,
            listKindAt(body, Span(9, 19)),
        )
    }

    /**
     * The lit button must be the one that TURNS THE LIST OFF. `listKindAt` and `toggleBlock`
     */
    @Test fun `the lit button is exactly the one whose tap removes the list`() {
        val plain = RichBody("shopping\nmilk\neggs", emptyMap())
        val bullets = RichBody("shopping\nmilk\neggs", emptyMap(), listOf(Block(BlockKind.BULLET, 1..2)))
        val cases = listOf(
            plain to Span(0, 0), plain to Span(9, 9), plain to Span(0, 18),
            bullets to Span(9, 9), bullets to Span(9, 18), bullets to Span(0, 18), bullets to Span(0, 0),
            // The boundary case, and the only one that makes the `- 1` of both rules load
            // bearing: the selection stops exactly where the line AFTER the list begins.
            bullets to Span(9, 14), bullets to Span(0, 9), bullets to Span(13, 18),
        )

        for ((body, selection) in cases) {
            for (kind in BlockKind.entries) {
                val lit = listKindAt(body, selection) == kind
                val after = toggleBlock(body, selection, kind)
                if (lit) {
                    assertNull(
                        "lit for $kind on $selection, so the tap must REMOVE it: ${after.blocks}",
                        listKindAt(after, selection),
                    )
                } else {
                    assertEquals(
                        "not lit for $kind on $selection, so the tap must APPLY it: ${after.blocks}",
                        kind,
                        listKindAt(after, selection),
                    )
                }
            }
        }
    }

    private companion object {
        /** Any colour: what matters is that the one the caller passes is the one drawn. */
        val LINK = Color(0xFF0066CC)
    }
}
