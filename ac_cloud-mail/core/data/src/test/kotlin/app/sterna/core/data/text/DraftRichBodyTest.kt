package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three decisions a stored rich body rests on (#131), EXECUTED — what a save writes, what a
 */
class DraftRichBodyTest {

    private val bold = RichBody("hello world", mapOf(Inline.BOLD to listOf(Span(0, 5))))

    // --- what a save stores ---------------------------------------------------------------------

    /** The witness: a plain body stores NO html part, so the save is byte for byte what it was. */
    @Test fun `a body with no styling stores no html at all`() {
        assertNull(draftHtmlToSave(RichBody.plain("hello world")))
    }

    @Test fun `an empty body stores no html either`() {
        assertNull(draftHtmlToSave(RichBody.plain("")))
    }

    /** Multiple lines and an ampersand: still plain, so still nothing — the text column holds it. */
    @Test fun `a plain body with newlines and entities still stores no html`() {
        assertNull(draftHtmlToSave(RichBody.plain("one & two\nthree")))
    }

    @Test fun `a styled body stores the html the parser can read back`() {
        assertEquals("<b>hello</b> world", draftHtmlToSave(bold))
    }

    /** The round trip is the promise: what a save stores is what a reopen gets back, exactly. */
    @Test fun `what a save stores reopens as the very same body`() {
        val body = RichBody(
            "one & two\nthree",
            mapOf(Inline.BOLD to listOf(Span(0, 3)), Inline.ITALIC to listOf(Span(10, 15))),
        )
        val stored = draftHtmlToSave(body) ?: error("a styled body must store something")
        assertEquals(body, richBodyFrom(stored) { "the fallback must not be reached" })
    }

    // --- what a reopen reads --------------------------------------------------------------------

    @Test fun `our own html comes back with its spans`() {
        val reopened = richBodyFrom("<b>hello</b> world") { "hello world" }
        assertEquals("hello world", reopened.text)
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 5))), reopened.ranges)
    }

    @Test fun `no html at all is the stored text, plain`() {
        val reopened = richBodyFrom(null) { "hello world" }
        assertEquals("hello world", reopened.text)
        assertEquals(emptyMap<Inline, List<Span>>(), reopened.ranges)
    }

    /**
     * A draft written elsewhere (this one is a Thunderbird shape: a `<div>` and a `style=`
     */
    @Test fun `a foreign html falls back to the stored text`() {
        val reopened = richBodyFrom("""<div style="font-weight:bold">hello</div>""") { "hello" }
        assertEquals("hello", reopened.text)
        assertEquals(emptyMap<Inline, List<Span>>(), reopened.ranges)
    }

    /** An attribute on a tag we DO know is still not ours: the parser is strict on purpose. */
    @Test fun `a tag of ours carrying an attribute is still foreign`() {
        val reopened = richBodyFrom("""<b style="x">hello</b>""") { "hello" }
        assertEquals("hello", reopened.text)
        assertEquals(emptyMap<Inline, List<Span>>(), reopened.ranges)
    }

    /**
     * The fallback is a LAMBDA, and it is not called when the html parses. It matters: on the
     * server route it runs `originalPlainText`, which walks the message's parts.
     */
    @Test fun `the text fallback is not even read when the html parses`() {
        var asked = 0
        richBodyFrom("<i>hi</i>") { asked++; "hi" }
        assertEquals("a parseable html must not ask for the text at all", 0, asked)
    }

    // --- what an html body costs ----------------------------------------------------------------

    /** No html part: nothing is lost, and the reopen may replace the original. */
    @Test fun `no html body is not lossy`() {
        assertFalse(draftHtmlIsLossy(null))
    }

    /** The change of #131: our own html reopens whole, so it costs nothing either. */
    @Test fun `our own html is not lossy`() {
        assertFalse(
            "a draft this app styled comes back with its spans — calling it lossy keeps a " +
                "duplicate in Drafts on every single re-save",
            draftHtmlIsLossy("<b>hello</b> world"),
        )
    }

    @Test fun `a foreign html is lossy`() {
        assertTrue(
            "a draft written elsewhere is flattened to text on open: replacing the original " +
                "would destroy a formatting nobody was shown losing",
            draftHtmlIsLossy("""<p style="font-weight:bold">hello</p>"""),
        )
    }

    @Test fun `an html part that says nothing is lossy, and it is the one that loses data`() {
        // THE defect this rule exists for, and it is a destroy. `fromHtml("")` answers an EMPTY
        // body — not null — and so do a space, a tab and a lone `<br>`. Read as "it parses, so the
        for (html in listOf("", " ", "\t", "<br>")) {
            assertTrue(
                "an html part carrying no text is PRESENT but says nothing — an unknown, and an " +
                    "unknown keeps the original. Was not lossy for: '$html'",
                draftHtmlIsLossy(html),
            )
        }
    }

    @Test fun `an html part that says nothing sends the reopen back to the text part`() {
        // The other half of the same decision, and the half the user sees: the `text/plain` part
        // of that same draft is what the composer must open on. `RichBody.plain("")` here is a
        // blank composer over a draft that has text.
        for (html in listOf("", " ", "\t", "<br>")) {
            val reopened = richBodyFrom(html) { "see you at six" }
            assertEquals("for '$html'", "see you at six", reopened.text)
            assertEquals(emptyMap<Inline, List<Span>>(), reopened.ranges)
        }
    }

    @Test fun `an html part reduced to structure we cannot read is lossy`() {
        // `<p>` is not a tag this parser knows, so the whole body is refused — an html part
        // reduced to structure we cannot read is an unknown, and an unknown keeps the original.
        assertTrue(draftHtmlIsLossy("<p></p>"))
    }

    /** The two answers are the same rule read twice: what reopens whole is what is not lossy. */
    @Test fun `whatever is not lossy is whatever comes back with its styling`() {
        for (html in listOf("<b>a</b>", "<i>a</i><u>b</u>", "plain text", "a &amp; b")) {
            assertFalse("$html reopens whole, so it is not lossy", draftHtmlIsLossy(html))
        }
        for (html in listOf("<div>a</div>", "<b>a", "a\nb", "<b><i>a</b></i>", "", "<br><br>")) {
            assertTrue("$html cannot be read back, so it is lossy", draftHtmlIsLossy(html))
        }
    }
}
