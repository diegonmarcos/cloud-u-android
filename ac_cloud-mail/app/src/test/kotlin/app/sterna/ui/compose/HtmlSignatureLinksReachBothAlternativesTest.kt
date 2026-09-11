package app.sterna.ui.compose

import app.sterna.core.data.account.StoredSignature
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.toPlainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE OWNER'S OWN SIGNATURE, through the two body parts a send actually builds (#193, #206).
 *
 * The reported loss was not "the box does not accept HTML" — it does, and has since #206. It was that
 * four of his lines (`Whatsapp`, `LinkedIn`, `E-mail`, `GitHub`) are ANCHOR TEXTS, and everything
 * downstream of the editor turned them into four dead words.
 *
 * So this pins the GENERATED PARTS, never the stored string. Asserting that the signature field holds
 * something containing `<a href` proves a text box holds a string; it says nothing about whether a
 * message was built carrying a live link, which is the only claim the owner cares about.
 *
 * BOTH parts, because they fail separately and for different reasons:
 *  - the `text/html` part loses the anchors if the sanitiser's policy ever stops allowing `href`;
 *  - the `text/plain` part loses the ADDRESSES to the flattener, which is what actually happened —
 *    `htmlToText` dropped every href and left the label, so a recipient with no HTML got exactly the
 *    four words the owner saw in his preview.
 */
class HtmlSignatureLinksReachBothAlternativesTest {

    /** As he wrote it: two plain lines, his site as a bare URL, then four labelled links. */
    private val source =
        "Sincerely,<br>" +
            "Diego C. Marcos<br>" +
            """<a href="https://linktree.diegonmarcos.com">https://linktree.diegonmarcos.com</a><br>""" +
            """<a href="https://wa.me/34600000000">Whatsapp</a><br>""" +
            """<a href="https://www.linkedin.com/in/diegonmarcos">LinkedIn</a><br>""" +
            """<a href="mailto:me@diegonmarcos.com">E-mail</a><br>""" +
            """<a href="https://github.com/diegonmarcos">GitHub</a>"""

    /** What the editor stores for that source: the HTML kept whole, plus its flattened half. */
    private val stored = StoredSignature.of(id = "s1", name = "Personal", source = source)

    /** The composed message, signature block inserted as ordinary text exactly as compose opens it. */
    private val body = "Hola Ana," + bodyWithSignature(quoted = "", signature = stored.text, delimiter = true)

    @Test fun theEditorKeepsTheSourceAndClassifiesItAsHtml() {
        assertTrue("a signature of seven anchored lines must classify as HTML", stored.isHtml)
        assertEquals("the SOURCE is kept verbatim — this is what goes on the wire", source, stored.html)
    }

    // --- text/plain: the half that actually lost his links ----------------------------------------

    @Test fun theTextAlternativeSpellsEveryAddressOut() {
        assertEquals(
            "⛔ the text/plain part is the WHOLE message for a recipient whose client renders no " +
                "HTML. Every anchor must arrive as `label <address>`. Drop the address and they " +
                "receive the word `Whatsapp` and no way to reach it — the exact defect reported, " +
                "and invisible to the sender, whose own client renders the html part instead.",
            "Hola Ana,\n" +
                "\n" +
                "-- \n" +
                "Sincerely,\n" +
                "Diego C. Marcos\n" +
                "https://linktree.diegonmarcos.com\n" +
                "Whatsapp <https://wa.me/34600000000>\n" +
                "LinkedIn <https://www.linkedin.com/in/diegonmarcos>\n" +
                "E-mail <mailto:me@diegonmarcos.com>\n" +
                "GitHub <https://github.com/diegonmarcos>",
            toPlainText(RichBody.plain(body)),
        )
    }

    @Test fun aBareUrlLineIsNotPrintedTwice() {
        // His linktree line is an anchor whose LABEL already is the address. Spelling it out would
        // put the same URL on the line twice; the skip is what keeps that from being the cure's cost.
        assertEquals(
            "https://linktree.diegonmarcos.com",
            stored.text.lines().first { "linktree" in it },
        )
    }

    // --- text/html: the anchors themselves ---------------------------------------------------------

    @Test fun theHtmlAlternativeCarriesEveryAnchorWithItsHref() {
        val html = htmlBodyWithSignature(
            RichBody.plain(body),
            signature = stored.text,
            signatureHtml = stored.html,
            delimiter = true,
        )
        // Each assertion pins the href AND the label AND their adjacency, so a sanitiser that kept
        // `<a>` but dropped `href` (leaving the word) fails here rather than passing on a `contains`.
        listOf(
            """<a href="https://wa.me/34600000000">Whatsapp</a>""",
            """<a href="https://www.linkedin.com/in/diegonmarcos">LinkedIn</a>""",
            """<a href="mailto:me@diegonmarcos.com">E-mail</a>""",
            """<a href="https://github.com/diegonmarcos">GitHub</a>""",
        ).forEach { anchor ->
            assertTrue("⛔ the html part must carry $anchor — it was:\n$html", anchor in html)
        }
        assertEquals(
            "…and the whole part, so the body, the divider and the signature keep their order and " +
                "nothing is appended twice",
            "Hola Ana,<br><br>-- <br>$source",
            html,
        )
    }

    /** #207 / #192: signature ABOVE the quote, under its `-- ` divider — in the HTML half too. */
    @Test fun theSignatureStaysAboveTheQuotedTextInHtml() {
        val quote = "\n\nOn …, Ana wrote:\n> hola"
        val replyBody = "Gracias Ana." + bodyWithSignature(quote, stored.text, delimiter = true)
        val html = htmlBodyWithSignature(
            RichBody.plain(replyBody),
            signature = stored.text,
            signatureHtml = stored.html,
            delimiter = true,
        )
        assertTrue("the `-- ` delimiter must precede the signature", html.indexOf("-- <br>") < html.indexOf("Sincerely,"))
        assertTrue("the signature must precede the `---` divider", html.indexOf("Sincerely,") < html.indexOf("<br>---<br>"))
        assertTrue("the divider must precede the quote", html.indexOf("<br>---<br>") < html.indexOf("Ana wrote"))
        assertTrue("and the links must survive being above a quote", """<a href="https://github.com/diegonmarcos">GitHub</a>""" in html)
    }
}
