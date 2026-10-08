package app.sterna.core.data.text

import app.sterna.core.data.db.MessageTextCacheDao
import app.sterna.core.data.db.MessageTextCacheEntity
import app.sterna.core.data.db.MessageTextKind
import app.sterna.core.data.text.InPlaceHtmlTranslation.NeedsSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two promises: language detection (and translation) only ever see what a reader SEES, and a tap on
 * Translate never fails on detection - the language is detected once on the whole message, passed to
 * every batch, and when no engine can place it the reader is asked.
 */
class DetectionSeesOnlyVisibleTextTest {

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/translate/$name")!!.bufferedReader().use { it.readText() }

    private val heavy = fixture("newsletter-de-heavy.html")

    // ---- visible text ------------------------------------------------------------------------------

    @Test fun `an HTML-heavy German newsletter detects as German`() {
        val seen = InPlaceHtmlTranslation.visibleText(heavy)
        assertEquals("de", LanguageGuess.guess(seen))
    }

    @Test fun `what detection is given contains no markup, CSS, hidden text, links or addresses`() {
        val seen = InPlaceHtmlTranslation.visibleText(heavy)
        assertFalse("no tag opener", seen.contains('<'))
        assertFalse("no CSS braces", seen.contains('{') || seen.contains('}'))
        assertFalse("no style attribute", seen.contains("style="))
        for (css in listOf("font-family", "padding", "#0b57d0", "text-decoration", "max-width")) assertFalse(css, seen.contains(css))
        assertFalse("the hidden preheader is not part of the message", seen.contains("Your order is on the way"))
        assertFalse("nor the zero-size one", seen.contains("Thanks for shopping"))
        assertFalse("no tracking url", seen.contains("track.example") || seen.contains("https://"))
        assertFalse("no address", seen.contains("hilfe@example"))
        assertTrue("entities are decoded", seen.contains("Vielen Dank für Ihre Bestellung") && seen.contains("Müller"))
        assertFalse("no raw entity left", seen.contains("&uuml;") || seen.contains("&amp;") || seen.contains("&nbsp;"))
        assertFalse("whitespace is collapsed", Regex("\\s{2,}").containsMatchIn(seen))
    }

    @Test fun `hidden elements are skipped even when nested and a closing tag does not end the skip early`() {
        val html = "<div style=\"display:none\"><div>inner hidden text</div>still hidden</div><p>visible text here</p>"
        assertEquals("visible text here", InPlaceHtmlTranslation.visibleText(html))
        // and translation never touches it either
        val out = InPlaceHtmlTranslation.translate(html) { it.uppercase() }.html
        assertTrue(out.contains("inner hidden text") && out.contains("still hidden"))
        assertTrue(out.contains("VISIBLE TEXT HERE"))
    }

    @Test fun `forDetection strips whatever markup is handed to it`() {
        val raw = "<p style=\"color:red\">Hallo {x} welt</p> style=abc https://a.example/x me@a.example"
        val clean = InPlaceHtmlTranslation.forDetection(raw)
        assertFalse(clean.contains('<') || clean.contains('{') || clean.contains("style="))
        assertFalse(clean.contains("https") || clean.contains("@"))
    }

    // ---- never fail on detection ---------------------------------------------------------------------

    private class MemoryCache : MessageTextCacheDao {
        val rows = LinkedHashMap<List<String>, MessageTextCacheEntity>()
        override suspend fun get(accountId: String, emailId: String, kind: String, lang: String) = rows[listOf(accountId, emailId, kind, lang)]
        override suspend fun put(row: MessageTextCacheEntity) { rows[listOf(row.accountId, row.emailId, row.kind, row.lang)] = row }
        override suspend fun translationCount(accountId: String, emailId: String) = rows.values.count { it.kind == MessageTextKind.TRANSLATION }
        override suspend fun pruneOlderThan(before: Long) = 0
        override suspend fun deleteForAccount(accountId: String) {}
        override suspend fun deleteAll() {}
    }

    private class Engine(val behave: (text: String, source: String?) -> String) : ReaderTextEngine {
        val sources = ArrayList<String?>()
        var runs = 0
        override fun newRun() { runs++ }
        override fun translate(text: String, targetTag: String, sourceTag: String?): String { sources += sourceTag; return behave(text, sourceTag) }
        override fun summarise(text: String, languageTag: String, replyTag: String?) = "s"
    }

    private class Spy(val answer: String?) : LanguageDetector {
        val inputs = ArrayList<String>()
        override suspend fun detect(text: String): String? { inputs += text; return answer }
    }

    @Test fun `the language is detected once, on the whole message, and passed to every batch`() = runBlocking {
        val spy = Spy("de")
        val engine = Engine { t, _ -> t.uppercase() }
        val cache = MemoryCache()
        val ai = ReaderTextAi(cache, engine, spy)
        val many = (1..130).joinToString("") { "<p>Zeile $it</p>" } + "<p>Vielen Dank für Ihre Bestellung und die schnelle Lieferung</p>"
        val out = ai.translate("a", "m1", "en", many)
        assertTrue(out is TranslationOutcome.Done)
        assertEquals("detected once", 1, spy.inputs.size)
        assertTrue("on the message, not on a short node", spy.inputs.single().length > 100)
        assertTrue("several batches", engine.sources.size >= 3)
        assertTrue("each carried the one detected source", engine.sources.all { it == "de" })
        assertEquals("kept with the message", "de", cache.rows[listOf("a", "m1", MessageTextKind.SOURCE, "-")]?.payload)
        // a second target for the same message does not ask the detector again
        ai.translate("a", "m1", "fr", many)
        assertEquals(1, spy.inputs.size)
    }

    @Test fun `detection is handed visible text only, whatever the message is made of`() = runBlocking {
        val spy = Spy("de")
        ReaderTextAi(MemoryCache(), Engine { t, _ -> t }, spy).translate("a", "m1", "en", heavy)
        val input = spy.inputs.single()
        assertFalse(input.contains('<') || input.contains('{') || input.contains("style="))
    }

    @Test fun `short-node newsletters and an unsure detector still translate - the source is simply unknown`() = runBlocking {
        val shortNodes = "<p>Sale</p><p>New</p><p>Shop</p><p>Hot</p>"
        val engine = Engine { t, _ -> t.uppercase() }
        val out = ReaderTextAi(MemoryCache(), engine, Spy(null)).translate("a", "m1", "en", shortNodes)
        assertEquals("<p>SALE</p><p>NEW</p><p>SHOP</p><p>HOT</p>", (out as TranslationOutcome.Done).fragment)
        assertEquals(listOf<String?>(null), engine.sources)
    }

    @Test fun `mixed-language mail goes to the engine with whatever the whole message says`() = runBlocking {
        val mixed = "<p>Guten Tag, vielen Dank für Ihre Nachricht und die Unterlagen.</p><p>Thank you very much, we will reply soon.</p>"
        val engine = Engine { t, _ -> t.uppercase() }
        val out = ReaderTextAi(MemoryCache(), engine, LanguageGuess).translate("a", "m1", "en", mixed)
        assertTrue(out is TranslationOutcome.Done)
        assertEquals(1, engine.sources.size)
    }

    @Test fun `when no engine can place the language the reader is asked, and the answer is used and nothing is cached before`() = runBlocking {
        val cache = MemoryCache()
        val engine = Engine { t, src -> if (src == null) throw NeedsSource("Could not detect the language") else t.uppercase() }
        val ai = ReaderTextAi(cache, engine, Spy(null))
        val first = ai.translate("a", "m1", "en", "<p>Hej, tack för mailet</p>")
        assertEquals(TranslationOutcome.NeedsSource("Could not detect the language"), first)
        assertNull("nothing cached for a question", cache.get("a", "m1", MessageTextKind.TRANSLATION, "en"))
        val second = ai.translate("a", "m1", "en", "<p>Hej, tack för mailet</p>", chosenSource = "sv") as TranslationOutcome.Done
        assertEquals("<p>HEJ, TACK FÖR MAILET</p>", second.fragment)
        assertEquals("sv", engine.sources.last())
        assertNotNull(cache.get("a", "m1", MessageTextKind.TRANSLATION, "en"))
    }

    // ---- the fallback chain ----------------------------------------------------------------------------

    private fun reply(text: String?, error: String? = null) = FallbackTranslator.Reply(text, error)

    @Test fun `the library answering is the whole story`() {
        var modelCalls = 0
        val t = FallbackTranslator({ x, _ -> reply(x.uppercase()) }, { _, _, _ -> modelCalls++; reply("no") })
        assertEquals("A", t.translate("a", "en", null))
        assertEquals(0, modelCalls)
    }

    @Test fun `the engine returning und falls back to the model translating from auto`() {
        val seenSources = ArrayList<String?>()
        val t = FallbackTranslator(
            { _, _ -> reply(null, "Could not detect the language - choose a source language") },
            { x, _, src -> seenSources += src; reply("T:$x") },
        )
        assertEquals("T:a", t.translate("a", "en", null))
        assertEquals(listOf<String?>(null), seenSources)
    }

    @Test fun `a known source goes to the model, and the library is not asked again once it cannot detect`() {
        var libraryCalls = 0
        val srcs = ArrayList<String?>()
        val t = FallbackTranslator(
            { _, _ -> libraryCalls++; reply(null, "could not detect the language") },
            { x, _, src -> srcs += src; reply("T:$x") },
        )
        t.translate("a", "en", "de"); t.translate("b", "en", "de"); t.translate("c", "en", "de")
        assertEquals("the library is asked once per message", 1, libraryCalls)
        assertEquals(listOf<String?>("de", "de", "de"), srcs)
        t.newRun()
        t.translate("d", "en", "de")
        assertEquals("a new message asks again", 2, libraryCalls)
    }

    @Test fun `no model and no detection asks for a source, and with a source it is an ordinary failure`() {
        val t = FallbackTranslator(
            { _, _ -> reply(null, "Could not detect the language") },
            { _, _, _ -> reply(null, "No API key for OpenRouter") },
        )
        try { t.translate("a", "en", null); org.junit.Assert.fail() } catch (e: NeedsSource) { assertTrue(e.reason.contains("detect")) }
        try { t.translate("a", "en", "de"); org.junit.Assert.fail() } catch (e: InPlaceHtmlTranslation.TranslationFailed) {
            assertFalse("not a question once a source is known", e is NeedsSource)
            assertTrue(e.reason.contains("OpenRouter"))
        }
    }

    @Test fun `an ordinary engine failure is not turned into a language question`() {
        val t = FallbackTranslator({ _, _ -> reply(null, "no network") }, { _, _, _ -> reply(null, "no network either") })
        try { t.translate("a", "en", null); org.junit.Assert.fail() } catch (e: NeedsSource) { org.junit.Assert.fail("a network error is not a language question") } catch (e: InPlaceHtmlTranslation.TranslationFailed) { assertTrue(e.reason.contains("no network")) }
    }
}
