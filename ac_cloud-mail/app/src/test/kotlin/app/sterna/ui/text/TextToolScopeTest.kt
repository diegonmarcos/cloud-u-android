package app.sterna.ui.text

import app.sterna.core.data.text.Block
import app.sterna.core.data.text.BlockKind
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Enhance and Translate are allowed to touch. Every case here is a way a rewrite
 * could damage something the user did not offer it.
 */
class TextToolScopeTest {

    private fun sent(body: String): String {
        val scope = TextToolScope.draftScope(body, Span(0, 0))
        return body.substring(scope.start, scope.end)
    }

    @Test fun `a body with no history is sent whole`() {
        assertEquals("Hi Bob,\nThanks for this.", sent("Hi Bob,\nThanks for this."))
    }

    @Test fun `the trailing quoted thread is not sent`() {
        assertEquals("Sounds good.", sent("Sounds good.\n\n> On Mon you wrote:\n> the old thing\n"))
    }

    @Test fun `the signature above the thread is not sent either`() {
        assertEquals("Sounds good.", sent("Sounds good.\n\n-- \nDiego\n\n> old thing\n"))
    }

    @Test fun `a signature with no thread under it still bounds the scope`() {
        assertEquals("Sounds good.", sent("Sounds good.\n-- \nDiego"))
    }

    /**
     * The case that makes this a backwards walk. Quoted lines with the user's own answers
     * between them are an argument being had, not history — cutting at the FIRST quote
     * would throw away everything the user actually wrote.
     */
    @Test fun `an interleaved reply is left entirely in scope`() {
        val body = "> you asked X\nyes, X\n> and Y?\nno"
        assertEquals(body, sent(body))
    }

    @Test fun `a body that is nothing but quoted history sends nothing`() {
        assertEquals("", sent("> all of it\n> every line\n"))
        assertTrue(TextToolScope.draftScope("> all of it\n", Span(0, 0)).isEmpty)
    }

    @Test fun `deeper quote levels are still the thread`() {
        assertEquals("ok", sent("ok\n\n>> older\n> newer\n"))
    }

    /**
     * A "-- " that arrived INSIDE the quoted thread is the other party's signature. Letting
     * it set the boundary would cut the scope to the top of the message and send nothing.
     */
    @Test fun `a quoted signature delimiter does not move the boundary`() {
        assertEquals("New text.", sent("New text.\n\n> -- \n> Their Sig\n"))
    }

    @Test fun `trailing blank lines are not sent as content`() {
        assertEquals("Hello", sent("Hello\n\n\n"))
    }

    @Test fun `an explicit selection wins, quoted or not`() {
        val body = "Sounds good.\n\n> the old thing\n"
        val chosen = Span(14, 28)
        assertEquals(chosen, TextToolScope.draftScope(body, chosen))
    }

    /** A received message is flattened before it gets here, so the same scan applies to it. */
    @Test fun `a received message drops its quoted history too`() {
        assertEquals("Thanks!", TextToolScope.receivedScope("Thanks!\n\n> what you wrote\n"))
    }

    // ── splice: the styling held beside a draft's text must follow the edit ──

    @Test fun `styling before the edit stays put and styling after it shifts`() {
        val text = "Bold here.\nkeep me\nand a tail"
        val rich = RichBody(
            text,
            mapOf(Inline.BOLD to listOf(Span(0, 4), Span(20, 26))),
        )
        val out = TextToolScope.splice(rich, Span(0, 10), "A much better opening line.")
        assertEquals("A much better opening line.\nkeep me\nand a tail", out.text)
        // (0,4) sat INSIDE the replaced range: the words it made bold are gone, so it goes.
        // (20,26) sat after it and moves by the length delta, +17.
        assertEquals(listOf(Span(37, 43)), out.ranges[Inline.BOLD])
    }

    @Test fun `a link overlapping the rewritten range is dropped, not left pointing at new words`() {
        val text = "see the docs here\nregards"
        val rich = RichBody(text, emptyMap(), emptyList(), listOf(Link(Span(8, 12), "https://example.invalid")))
        val out = TextToolScope.splice(rich, Span(0, 17), "Please consult the documentation.")
        assertTrue("a link over rewritten text must not survive", out.links.isEmpty())
    }

    @Test fun `a list block after the edit follows the line delta`() {
        val text = "intro\none\ntwo"
        val rich = RichBody(text, emptyMap(), listOf(Block(BlockKind.BULLET, 1..2)))
        val out = TextToolScope.splice(rich, Span(0, 5), "a\nlonger\nintro")
        assertEquals("a\nlonger\nintro\none\ntwo", out.text)
        assertEquals(listOf(Block(BlockKind.BULLET, 3..4)), out.blocks)
    }

    @Test fun `splicing an empty replacement over the whole body leaves nothing dangling`() {
        val rich = RichBody("all of it", mapOf(Inline.ITALIC to listOf(Span(0, 3))))
        val out = TextToolScope.splice(rich, Span(0, 9), "")
        assertEquals("", out.text)
        assertTrue(out.isPlain)
    }
}
