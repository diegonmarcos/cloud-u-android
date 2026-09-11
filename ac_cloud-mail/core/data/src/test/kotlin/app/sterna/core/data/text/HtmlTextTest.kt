package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    // --- htmlToText: block structure survives the flattening ------------------------------------

    @Test fun blockEndsBecomeLineBreaks() {
        assertEquals("one\ntwo", htmlToText("<p>one</p><p>two</p>"))
        assertEquals("one\ntwo", htmlToText("<div>one</div><div>two</div>"))
        assertEquals("one\ntwo", htmlToText("one<br>two"))
    }

    @Test fun tableSignatureKeepsOneLinePerRow() {
        // The classic corporate signature: a one-column-per-field table. Rows must not collapse
        // onto a single line (which is what a <br>-only flattening did).
        val html = """
            <table><tr><td>Alex Rivera</td></tr>
            <tr><td>Acme</td></tr>
            <tr><td>+33 1 23 45 67 89</td></tr></table>
        """.trimIndent()
        assertEquals("Alex Rivera\nAcme\n+33 1 23 45 67 89", htmlToText(html))
    }

    @Test fun deliberateBlankLinesSurvive() {
        assertEquals("one\n\ntwo", htmlToText("one<br><br>two"))
    }

    @Test fun cellsOnTheSameRowStaySeparated() {
        assertEquals("Phone +33 1 23", htmlToText("<table><tr><td>Phone</td><td>+33 1 23</td></tr></table>"))
    }

    @Test fun linksFlattenToTheirLabelOnly() {
        assertEquals("acme.fr", htmlToText("""<a href="https://acme.fr">acme.fr</a>"""))
        assertEquals(
            "Alex Rivera\nacme.fr",
            htmlToText("""<div>Alex Rivera</div><div><a href="https://acme.fr/team">acme.fr</a></div>"""),
        )
    }

    // --- keepLinkTargets: the flattening a SIGNATURE gets ------------------------------------------

    @Test fun keepLinkTargetsSpellsTheAddressOutBesideTheLabel() {
        // A link whose label is a WORD loses everything when the href goes: this is the text/plain
        // half of a sent message, so "Whatsapp" alone reaches a recipient who cannot render HTML.
        assertEquals(
            "Whatsapp <https://wa.me/34600000000>",
            htmlToText("""<a href="https://wa.me/34600000000">Whatsapp</a>""", keepLinkTargets = true),
        )
        assertEquals(
            "E-mail <mailto:me@example.test>",
            htmlToText("""<a href="mailto:me@example.test">E-mail</a>""", keepLinkTargets = true),
        )
    }

    @Test fun keepLinkTargetsDoesNotPrintAnAddressTwice() {
        // The label already IS the address, give or take the parts a reader does not need to see.
        assertEquals("acme.fr", htmlToText("""<a href="https://acme.fr">acme.fr</a>""", keepLinkTargets = true))
        assertEquals(
            "https://acme.fr",
            htmlToText("""<a href="https://acme.fr">https://acme.fr</a>""", keepLinkTargets = true),
        )
        assertEquals(
            "acme.fr",
            htmlToText("""<a href="https://www.acme.fr/">acme.fr</a>""", keepLinkTargets = true),
        )
    }

    @Test fun keepLinkTargetsSurvivesTheTagStripThatFollowsIt() {
        // ⛔ The address is emitted as `&lt;…&gt;` precisely because the next pass deletes anything
        // shaped like `<…>`. Emitted literally, the URL is destroyed one line downstream and this
        // whole feature silently does nothing.
        assertEquals(
            "Docs <https://acme.fr/a?x=1>",
            htmlToText("""<a href="https://acme.fr/a?x=1">Docs</a>""", keepLinkTargets = true),
        )
    }

    @Test fun keepLinkTargetsLeavesAnchorsItCannotImproveAlone() {
        // No href, and an href with no label: nothing to add, and nothing may be removed either.
        assertEquals("Whatsapp", htmlToText("""<a name="x">Whatsapp</a>""", keepLinkTargets = true))
        assertEquals("", htmlToText("""<a href="https://acme.fr"></a>""", keepLinkTargets = true))
    }

    @Test fun keepLinkTargetsRefusesAnAddressTheSanitiserWouldStrip() {
        // ⛔ The html half drops these hrefs. Writing them into the text half instead would make the
        // two alternatives of one message disagree, and would put a `javascript:` URL in a body the
        // owner sends — past the one pass that exists to remove it.
        assertEquals("click", htmlToText("""<a href="javascript:alert(1)">click</a>""", keepLinkTargets = true))
        assertEquals(
            "click",
            htmlToText("""<a href="data:text/html;base64,PHNjcmlwdD4=">click</a>""", keepLinkTargets = true),
        )
        // …while the ordinary schemes a signature actually uses are still spelled out.
        assertEquals("Call <tel:+33123456789>", htmlToText("""<a href="tel:+33123456789">Call</a>""", keepLinkTargets = true))
    }

    @Test fun keepLinkTargetsFlattensMarkupInsideTheLabel() {
        assertEquals(
            "Whatsapp <https://wa.me/1>",
            htmlToText("""<a href="https://wa.me/1"><b>Whatsapp</b></a>""", keepLinkTargets = true),
        )
    }

    @Test fun theDefaultFlatteningIsUnchangedByTheOptIn() {
        // The read/quote path must keep behaving exactly as `linksFlattenToTheirLabelOnly` pins it:
        // a quoted original still has its html part, so a dropped href loses nothing there.
        assertEquals("Whatsapp", htmlToText("""<a href="https://wa.me/34600000000">Whatsapp</a>"""))
    }

    @Test fun scriptAndStyleAreDropped() {
        assertEquals("hi", htmlToText("<style>p{color:red}</style><p>hi</p><script>x()</script>"))
    }

    // --- unescapeEntities ------------------------------------------------------------------------

    @Test fun decodesNumericEntitiesDecimalAndHex() {
        assertEquals("café", unescapeEntities("caf&#233;"))
        assertEquals("café", unescapeEntities("caf&#xE9;"))
        assertEquals("café", unescapeEntities("caf&#Xe9;"))
        assertEquals("€10", unescapeEntities("&#8364;10"))
    }

    @Test fun decodesTheCommonNamedEntities() {
        assertEquals("a — b", unescapeEntities("a &mdash; b"))
        assertEquals("a – b", unescapeEntities("a &ndash; b"))
        assertEquals("café", unescapeEntities("caf&eacute;"))
        assertEquals("École", unescapeEntities("&Eacute;cole"))
        assertEquals("« oui »", unescapeEntities("&laquo; oui &raquo;"))
        assertEquals("© 2026 Acme…", unescapeEntities("&copy; 2026 Acme&hellip;"))
        assertEquals("straße", unescapeEntities("stra&szlig;e"))
    }

    @Test fun keepsTheOldCoreEntities() {
        assertEquals("<>\"'&", unescapeEntities("&lt;&gt;&quot;&apos;&amp;"))
        assertEquals("a b", unescapeEntities("a&nbsp;b"))
    }

    @Test fun doubleEscapedEntityIsDecodedOnce() {
        // One pass only: "&amp;lt;" is the literal text "&lt;", not "<".
        assertEquals("&lt;", unescapeEntities("&amp;lt;"))
    }

    @Test fun unknownOrOutOfRangeReferencesAreLeftAlone() {
        assertEquals("&notanentity;", unescapeEntities("&notanentity;"))
        assertEquals("&#0;", unescapeEntities("&#0;"))
        assertEquals("&#xD800;", unescapeEntities("&#xD800;"))
        assertEquals("100 & 200", unescapeEntities("100 & 200"))
    }

    @Test fun entitiesAreDecodedWhenFlatteningHtml() {
        assertEquals("Alex — Acme, café", htmlToText("<p>Alex &mdash; Acme, caf&eacute;</p>"))
    }

    // --- misc ------------------------------------------------------------------------------------

    @Test fun looksLikeHtmlOnlyOnTags() {
        assertTrue(looksLikeHtml("<p>hi</p>"))
        assertTrue(looksLikeHtml("<!-- c -->"))
        assertFalse(looksLikeHtml("Alex Rivera\n-- \n1 < 2 > 0"))
    }

    @Test fun escapingIsReversedByTheDecoder() {
        val raw = "a < b & c > d"
        assertEquals(raw, unescapeEntities(htmlEscape(raw)))
        assertEquals("a<br>b", htmlEscapeMultiline("a\nb"))
    }
}
