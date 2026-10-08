package app.sterna.core.data.text

import app.sterna.core.data.text.InPlaceHtmlTranslation.TextTranslator
import app.sterna.core.data.text.InPlaceHtmlTranslation.TranslationFailed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-place translation EXECUTED over HTML newsletters. The engine is a fake that upper-cases (so a
 * URL, a number or a code that reached it would come back shouted and the test would see it), and
 * every assertion is about what survives: the markup, the links, the pictures, the untranslated
 * tokens - and that a batch is one request, not one per text node.
 */
class InPlaceHtmlTranslationTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/translate/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("fixture /translate/$name is missing")

    private class Upper : TextTranslator {
        val requests = ArrayList<String>()
        override fun translate(text: String): String { requests += text; return text.uppercase() }
    }

    /** Everything that is not text: the tags, in order, with their attributes. */
    private fun skeleton(html: String): List<String> =
        Regex("<[^>]*>").findAll(html).map { it.value }.toList()

    private val a = fixture("newsletter-a.html")
    private val b = fixture("newsletter-b.html")

    @Test fun `the markup is untouched - tags, attributes, links, images, styles, comments`() {
        for (src in listOf(a, b)) {
            val out = InPlaceHtmlTranslation.translate(src, Upper())
            assertEquals("the sequence of tags must be byte-identical", skeleton(src), skeleton(out.html))
            assertTrue("the image survives", out.html.contains("<img src=\"https://example.invalid/logo.png\"") || !src.contains("logo.png"))
        }
        val out = InPlaceHtmlTranslation.translate(a, Upper()).html
        assertTrue(out.contains("href=\"https://example.invalid/track?id=48213&amp;utm=mail\""))
        assertTrue(out.contains("<style>.x { color: red }</style>"))
        assertTrue(out.contains("<!-- SYNTHETIC newsletter"))
    }

    @Test fun `the words are translated`() {
        val out = InPlaceHtmlTranslation.translate(a, Upper()).html
        assertTrue(out.contains("WELCOME TO THE SPRING SALE"))
        assertTrue(out.contains("FREE RETURNS WITHIN 30 DAYS"))
        assertTrue("an entity-encoded ampersand stays an entity", out.contains("&amp; MORE"))
        assertFalse("the original sentence is gone", out.contains("Welcome to the spring sale"))
    }

    @Test fun `URLs, addresses, numbers and code reach the engine as placeholders and come back intact`() {
        val engine = Upper()
        val out = InPlaceHtmlTranslation.translate(a, engine).html
        // the shouting engine never saw them, so they are still lower case and unchanged
        assertTrue(out.contains(">https://example.invalid/track?id=48213</a>"))
        assertTrue(out.contains("support@example.invalid"))
        assertTrue(out.contains("2026-03-14"))
        assertTrue(out.contains("#48213") || out.contains("#<"))
        // what the engine saw, minus the placeholders that stand in for the lifted tokens
        val sent = engine.requests.joinToString("\n").replace(Regex("\u27E6\\d+\u27E7"), "")
        assertFalse("a URL was sent to the engine", sent.contains("https://"))
        assertFalse("an e-mail address was sent to the engine", sent.contains("@"))
        assertFalse("a number was sent to the engine", Regex("\\d").containsMatchIn(sent))
    }

    @Test fun `code and pre the message wrote are not translated, pre dot plain is`() {
        val out = InPlaceHtmlTranslation.translate(a, Upper()).html
        assertTrue(out.contains("<code>SPRING-2026</code>"))      // was already upper: unchanged either way
        assertTrue(out.contains("<pre>keep   this   spacing</pre>"))
        val plain = InPlaceHtmlTranslation.translate(b, Upper()).html
        assertTrue(plain.contains("DEAR CUSTOMER,"))
        assertTrue("blank lines of the plain body survive", plain.contains("DEAR CUSTOMER,\n\nYOUR INVOICE"))
        assertTrue(plain.contains("2026-0042"))
    }

    @Test fun `a whole newsletter is one request, not one per text node`() {
        val engine = Upper()
        val r = InPlaceHtmlTranslation.translate(a, engine)
        assertEquals(1, engine.requests.size)
        assertTrue("many units went in that one request", r.totalUnits > 6)
        assertEquals(r.totalUnits, r.translatedUnits)
        assertEquals("one line per unit", r.totalUnits, engine.requests.single().split("\n").size)
    }

    @Test fun `a batch is bounded`() {
        val many = (1..150).joinToString("") { "<p>Line number $it of the message</p>" }
        val engine = Upper()
        InPlaceHtmlTranslation.translate(many, engine)
        assertTrue("150 lines at 60 per request", engine.requests.size in 3..4)
        assertTrue(engine.requests.all { it.split("\n").size <= InPlaceHtmlTranslation.BATCH_LINES })
    }

    @Test fun `an engine that merges lines is retried in halves until they pair up`() {
        var calls = 0
        val merging = TextTranslator { text ->
            calls++
            // collapses any multi-line request into one line, answers a single line fine
            if (text.contains('\n')) text.replace('\n', ' ').uppercase() else text.uppercase()
        }
        val out = InPlaceHtmlTranslation.translate("<p>first line</p><p>second line</p><p>third line</p>", merging)
        assertTrue(calls > 1)
        assertEquals("<p>FIRST LINE</p><p>SECOND LINE</p><p>THIRD LINE</p>", out.html)
    }

    @Test fun `a reply that lost a placeholder is retranslated around the token, the link text survives`() {
        val dropping = TextTranslator { text ->
            // eats every placeholder, like an engine that does not know the token
            text.replace(Regex("⟦\\d+⟧"), "").uppercase()
        }
        val out = InPlaceHtmlTranslation.translate("<p>see https://example.invalid/x for 42 details</p>", dropping).html
        assertTrue("the URL is still there: $out", out.contains("https://example.invalid/x"))
        assertTrue("and the figure: $out", out.contains("42"))
        assertTrue("the words around them are translated: $out", out.contains("SEE") && out.contains("DETAILS"))
    }

    @Test fun `text with nothing to translate is returned untouched`() {
        val html = "<img src=\"x.png\"><p>12:30 | 5,00</p><p> </p>"
        val engine = Upper()
        val out = InPlaceHtmlTranslation.translate(html, engine)
        assertEquals(html, out.html)
        assertEquals(0, out.totalUnits)
        assertTrue(engine.requests.isEmpty())
    }

    @Test fun `an engine failure surfaces with its reason and nothing is half-applied`() {
        val failing = TextTranslator { throw TranslationFailed("no network") }
        try {
            InPlaceHtmlTranslation.translate(a, failing)
            org.junit.Assert.fail("a failing engine must not look like a translation")
        } catch (e: TranslationFailed) {
            assertEquals("no network", e.reason)
        }
    }

    @Test fun `a node the engine could not answer keeps its original language`() {
        val blank = TextTranslator { text -> text.split("\n").joinToString("\n") { if ("keep" in it) "" else it.uppercase() } }
        val out = InPlaceHtmlTranslation.translate("<p>keep me</p><p>translate me</p>", blank).html
        assertEquals("<p>keep me</p><p>TRANSLATE ME</p>", out)
    }

    @Test fun `leading and trailing spaces of a text node are kept`() {
        val out = InPlaceHtmlTranslation.translate("<p>Hello <b>big</b> world</p>", Upper()).html
        assertEquals("<p>HELLO <b>BIG</b> WORLD</p>", out)
    }
}
