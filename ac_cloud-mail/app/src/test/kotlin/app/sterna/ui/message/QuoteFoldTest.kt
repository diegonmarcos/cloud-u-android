package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [foldTopLevelQuotes] EXECUTED — it is a pure String→String function precisely so this file can
 */
class QuoteFoldTest {

    private val label = "CITÉ-REPÈRE"

    private val open = """<details class="s-quote"><summary>"""

    // ── The bodies. Hand-written, one shape each. ────────────────────────────────────────────
    private val simple = """<p>My answer.</p><blockquote type="cite"><p>Original line.</p></blockquote>"""
    private val nested =
        """<p>My answer.</p><blockquote type="cite"><p>Outer.</p><blockquote><p>Inner.</p></blockquote></blockquote>"""
    private val siblings =
        """<p>Answer.</p><blockquote><p>One.</p></blockquote><p>More.</p><blockquote><p>Two.</p></blockquote>"""
    private val allQuote = """<blockquote><p>Forwarded bulletin.</p></blockquote>"""
    private val allQuoteNbsp = """<div>&nbsp;</div><p></p><blockquote><p>Forwarded bulletin.</p></blockquote>"""
    private val imageOnly = """<p><img src="cid:logo"></p><blockquote><p>Original.</p></blockquote>"""
    private val unclosed = """<p>Answer.</p><blockquote><p>An original that never closes.</p>"""
    private val orphan = """<p>Answer.</p></blockquote><p>Tail.</p>"""
    private val noQuote = """<p>Just an answer.</p>"""

    /** A forwarded bulletin as it really arrives: a whole document, quoted whole. */
    private val allQuoteDocument =
        """<html><head><style>body{margin:0}</style><title>Newsletter</title></head><body>""" +
            """<!-- generated --><blockquote type="cite"><p>Forwarded bulletin.</p></blockquote></body></html>"""

    /** The history first, the answer under it. */
    private val bottomPosted =
        """<blockquote type="cite"><p>Forty messages.</p></blockquote><p>My answer.</p>"""

    /** Question, answer, question, answer - the house style of technical mailing lists. */
    private val interleaved =
        """<p>Hi,</p><blockquote><p>Question one?</p></blockquote><p>Answer one.</p>""" +
            """<blockquote><p>Question two?</p></blockquote><p>Answer two.</p>"""

    private val everyBody = listOf(
        simple, nested, siblings, allQuote, allQuoteNbsp, imageOnly, unclosed, orphan, noQuote,
        allQuoteDocument, bottomPosted, interleaved,
    )

    // ── 1. The shape it produces ────────────────────────────────────────────────────────────
    @Test fun `a first-level quote after some text is wrapped in a details, quote untouched`() {
        val out = foldTopLevelQuotes(simple, label)

        assertTrue(
            "the quote must be wrapped in a native <details class=\"s-quote\"> with a <summary> " +
                "button — that is the whole feature.\nGot:\n$out",
            open in out,
        )
        assertTrue(
            "the <blockquote> element itself must be carried over VERBATIM, attributes and all: " +
                "the fold wraps the quote, it does not rewrite it.\nGot:\n$out",
            """<blockquote type="cite"><p>Original line.</p></blockquote>""" in out,
        )
        assertTrue("the text before the quote must still be there.\nGot:\n$out", "<p>My answer.</p>" in out)
        assertTrue("the wrapper must be closed.\nGot:\n$out", "</details>" in out)
    }

    // ── 2. The label that is SHOWN is the label that was PASSED ─────────────────────────────
    @Test fun `the summary carries the label handed in, not one the function chose`() {
        val out = foldTopLevelQuotes(simple, label)

        assertTrue(
            "the argument must reach the <summary>. This is the only thing that proves the " +
                "localised string travels all the way to the button; a hard-coded label here " +
                "would read 'Quoted text' in nine languages.\nGot:\n$out",
            "$open$label</summary>" in out,
        )
        assertTrue(
            "a second, different label must produce a second, different button — otherwise the " +
                "assertion above is satisfied by a constant that happens to match.\nGot:\n$out",
            "${open}AUTRE-REPÈRE</summary>" in foldTopLevelQuotes(simple, "AUTRE-REPÈRE"),
        )
    }

    // ── 3. …escaped ─────────────────────────────────────────────────────────────────────────
    @Test fun `the label is HTML-escaped into the summary`() {
        val out = foldTopLevelQuotes(simple, "a & <b>")

        assertTrue(
            "a label is TEXT and goes through htmlEscape: unescaped, a translation holding '<' " +
                "would open an element inside the button.\nGot:\n$out",
            "${open}a &amp; &lt;b&gt;</summary>" in out,
        )
    }

    // ── 4. Nesting: one button, not one per level ───────────────────────────────────────────
    @Test fun `a nested quote produces one fold, and the inner quote is left alone`() {
        val out = foldTopLevelQuotes(nested, label)

        assertEquals(
            "only the FIRST level folds, and the button opens BEFORE the outermost <blockquote>. " +
                "Counting buttons is not enough: with the top-level test dropped, `openedAt` " +
                "walks in to the INNERMOST quote while the span still ends on the outermost " +
                "close, so the <details> opens inside the history and the first levels of the " +
                "chain stay unfolded on screen. Pinned WHOLE for that reason.",
            """<p>My answer.</p>""" + open + label +
                """</summary><blockquote type="cite"><p>Outer.</p>""" +
                """<blockquote><p>Inner.</p></blockquote></blockquote></details>""",
            out,
        )
    }

    // ── 5. Two sibling quotes ───────────────────────────────────────────────────────────────
    @Test fun `only the trailing quote folds, never every quote in the body`() {
        val out = foldTopLevelQuotes(siblings, label)

        assertEquals(
            "ONE button, on the LAST quote. Folding every top-level quote is what breaks an " +
                "interleaved reply, and it is not what K-9 or Gmail do.\nGot:\n$out",
            1,
            occurrences(out, open),
        )
        assertTrue(
            "the earlier quote stays open.\nGot:\n$out",
            "<blockquote><p>One.</p></blockquote>" in out,
        )
        assertTrue(
            "and it is the LAST one that got the button.\nGot:\n$out",
            (open + label + "</summary><blockquote><p>Two.</p>") in out,
        )
        assertTrue("the text between them must survive.\nGot:\n$out", "<p>More.</p>" in out)
    }

    @Test fun `an interleaved reply folds nothing`() {
        assertEquals(
            "question / answer / question / answer: folding the quotes would leave a column of " +
                "answers to nothing. Something the reader can see follows the last quote, so " +
                "there is no trailing history to fold.",
            interleaved,
            foldTopLevelQuotes(interleaved, label),
        )
    }

    @Test fun `a bottom-posted reply folds nothing`() {
        assertEquals(
            "the history comes FIRST here. Folding it would hide the top of the page and leave " +
                "the bottom, which is not what any reference client does.",
            bottomPosted,
            foldTopLevelQuotes(bottomPosted, label),
        )
    }

    // ── 6. GUARD: the quote IS the body ─────────────────────────────────────────────────────
    @Test fun `a body that is nothing but the quote is left alone`() {
        assertEquals(
            "a forwarded bulletin whose whole body is one <blockquote> would fold to an EMPTY " +
                "screen with a button on it. Nothing before the quote means nothing to fold.",
            allQuote,
            foldTopLevelQuotes(allQuote, label),
        )
        assertEquals(
            "&nbsp; and empty elements are not content either — same empty screen.",
            allQuoteNbsp,
            foldTopLevelQuotes(allQuoteNbsp, label),
        )
    }

    // ── 7. …but an image IS body ────────────────────────────────────────────────────────────
    @Test fun `a whole-quote body still folds nothing when it arrives as a full document`() {
        assertEquals(
            "a forwarded bulletin comes as <html><head><style>..., and `body{margin:0}` is text " +
                "to any regex. Counting it as content satisfies guard 2 and folds the ONLY thing " +
                "on the page away: a button on a blank screen. <head>, <style>, <title> and " +
                "comments carry nothing TO the screen and must not count as body.",
            allQuoteDocument,
            foldTopLevelQuotes(allQuoteDocument, label),
        )
    }

    @Test fun `an image alone before the quote does not count as body`() {
        assertEquals(
            "a remote image is BLOCKED by default in this reader, so an <img> above a quote can " +
                "be nothing at all on screen - a tracking pixel is exactly that shape. The cost " +
                "is asymmetric: counting it wrongly empties the page, ignoring it wrongly costs " +
                "a fold, and not folding is never a failure here.",
            imageOnly,
            foldTopLevelQuotes(imageOnly, label),
        )
    }

    // ── 8/9. GUARD: markup the scan cannot balance ──────────────────────────────────────────
    @Test fun `an unclosed quote is left exactly as it came in`() {
        val out = foldTopLevelQuotes(unclosed, label)

        assertEquals("depth never returns to 0: fold nothing rather than guess where it ends.", unclosed, out)
        assertTrue("and above all, lose nothing.\nGot:\n$out", "An original that never closes." in out)
    }

    @Test fun `an orphan closing tag is left exactly as it came in`() {
        assertEquals(
            "depth would go negative — the markup is not what the scan assumes, so it keeps its " +
                "hands off the whole body.",
            orphan,
            foldTopLevelQuotes(orphan, label),
        )
    }

    // ── 10. Nothing to do, and the mute-button guard ────────────────────────────────────────
    @Test fun `a body with no quote, or a blank label, is returned unchanged`() {
        assertEquals("no <blockquote>, nothing to fold.", noQuote, foldTopLevelQuotes(noQuote, label))
        assertEquals(
            "a blank label would render an empty <summary>: a button with no word in it, which " +
                "TalkBack cannot announce. No label, no fold.",
            simple,
            foldTopLevelQuotes(simple, "   "),
        )
        assertEquals("same for the empty string.", simple, foldTopLevelQuotes(simple, ""))
    }

    // ── 11. THE property: nothing of the body is ever lost ─────────────────────────────────
    @Test fun `folding only ever ADDS the wrapper - the body always comes back whole`() {
        for (body in everyBody) {
            val out = foldTopLevelQuotes(body, label)
            assertEquals(
                "removing the two things this function is allowed to add — the opening " +
                    "<details><summary>…</summary> and the closing </details> — must give the " +
                    "input back CHARACTER FOR CHARACTER. Anything else means a rewrite lost or " +
                    "altered part of the message, which is the one outcome this branch may not " +
                    "have.\nInput:\n$body\nGot:\n$out",
                body,
                stripWrapper(out),
            )
        }
    }

    // ── 12/13/14. The document — buildHtmlDocument EXECUTED ────────────────────────────────
    private val noContent = "NO-CONTENT-SENTINEL"

    private fun richEmail(html: String): Email = Email(
        id = "1",
        htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
        bodyValues = mapOf("h" to EmailBodyValue(value = html)),
    )

    private val light = EmailTheme("#ffffff", "#111111", "#0b5fff", dark = false)
    private val dark = EmailTheme("#101418", "#e3e3e3", "#a8c7fa", dark = true)

    @Test fun `both templates fold the quote and carry the rule that styles it`() {
        for ((name, theme) in listOf("light" to light, "dark" to dark)) {
            val doc = buildHtmlDocument(
                richEmail(simple), theme = theme, noContent = noContent, quoteLabel = label,
            )

            assertTrue(
                "the $name document must carry the folded quote — the fold has to run on the " +
                    "rich body, not somewhere the page never reaches.\nDocument was:\n$doc",
                """<details class="s-quote">""" in doc,
            )
            assertTrue(
                "the $name template must carry the details.s-quote rule. There are TWO templates " +
                    "and they share no CSS: a rule added to one only leaves the other with an " +
                    "unstyled, browser-default button.\nDocument was:\n$doc",
                "details.s-quote" in doc,
            )
            assertTrue(
                "the label must reach the page in the $name template.\nDocument was:\n$doc",
                "<summary>$label</summary>" in doc,
            )
        }
    }

    @Test fun `plain-text mode folds nothing`() {
        val doc = buildHtmlDocument(
            richEmail(simple),
            theme = light,
            plainText = true,
            derivedNotice = "DERIVED",
            noContent = noContent,
            quoteLabel = label,
        )

        assertFalse(
            "plain-text mode paints a body WE escaped; rewriting it would put our own markup " +
                "into the one mode whose promise is to show the text as it is.\nDocument was:\n$doc",
            """<details class="s-quote">""" in doc,
        )
    }

    @Test fun `a caller that omits the label gets a document with no fold, never an empty one`() {
        val doc = buildHtmlDocument(richEmail(simple), theme = light, noContent = noContent)

        assertFalse(
            "the default is the blank-label guard seen from outside: forgetting the parameter " +
                "costs the fold, and nothing else.\nDocument was:\n$doc",
            """<details class="s-quote">""" in doc,
        )
        assertTrue(
            "the message itself must still be on the page.\nDocument was:\n$doc",
            "<p>My answer.</p>" in doc && "<p>Original line.</p>" in doc,
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────
    private fun occurrences(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    /**
     * Remove the two literal strings the fold is allowed to insert. Deliberately NOT a re-run of
     */
    private fun stripWrapper(s: String): String =
        s.replace(Regex("""<details class="s-quote"><summary>.*?</summary>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace("</details>", "")
}
