package app.sterna.core.data.text

import app.sterna.core.data.db.MessageTextCacheDao
import app.sterna.core.data.db.MessageTextCacheEntity
import app.sterna.core.data.db.MessageTextKind
import app.sterna.core.data.text.InPlaceHtmlTranslation.TranslationFailed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cache and the decisions in front of the engine, executed against an in-memory table. */
class ReaderTextAiTest {

    private class MemoryCache : MessageTextCacheDao {
        val rows = LinkedHashMap<List<String>, MessageTextCacheEntity>()
        override suspend fun get(accountId: String, emailId: String, kind: String, lang: String) =
            rows[listOf(accountId, emailId, kind, lang)]
        override suspend fun put(row: MessageTextCacheEntity) { rows[listOf(row.accountId, row.emailId, row.kind, row.lang)] = row }
        override suspend fun translationCount(accountId: String, emailId: String) =
            rows.values.count { it.accountId == accountId && it.emailId == emailId && it.kind == MessageTextKind.TRANSLATION }
        override suspend fun pruneOlderThan(before: Long): Int {
            val old = rows.filterValues { it.createdAt < before }.keys; old.forEach { rows.remove(it) }; return old.size
        }
        override suspend fun deleteForAccount(accountId: String) { rows.keys.removeAll { it[0] == accountId } }
        override suspend fun deleteAll() { rows.clear() }
    }

    private class FakeEngine : ReaderTextEngine {
        var translateCalls = 0
        var summariseCalls = 0
        var fail: String? = null
        override fun translate(text: String, targetTag: String): String {
            translateCalls++
            fail?.let { throw TranslationFailed(it) }
            return text.uppercase()
        }
        override fun summarise(text: String, languageTag: String): String {
            summariseCalls++
            fail?.let { throw TranslationFailed(it) }
            return "SUMMARY($languageTag): ${text.take(10)}"
        }
    }

    private val cache = MemoryCache()
    private val engine = FakeEngine()
    private var clock = 1_000L
    private fun ai(detector: LanguageDetector = LanguageGuess) = ReaderTextAi(cache, engine, detector) { clock }
    private fun saying(answer: () -> String?) = object : LanguageDetector {
        override suspend fun detect(text: String): String? = answer()
    }
    private val html = "<p>Hello there, this is the message</p>"

    @Test fun `first open misses and calls the engine, the second hits and does not`() = runBlocking {
        val ai = ai()
        assertNull(ai.cachedTranslation("a", "m1", "en", html))
        val first = ai.translate("a", "m1", "es", html) as TranslationOutcome.Done
        assertFalse(first.fromCache)
        assertEquals("<p>HELLO THERE, THIS IS THE MESSAGE</p>", first.fragment)
        assertEquals(1, engine.translateCalls)
        val second = ai.translate("a", "m1", "es", html) as TranslationOutcome.Done
        assertTrue(second.fromCache)
        assertEquals(first.fragment, second.fragment)
        assertEquals("no second engine call", 1, engine.translateCalls)
    }

    @Test fun `the cache is per message and per language`() = runBlocking {
        val ai = ai()
        ai.translate("a", "m1", "es", html)
        assertNotNull(ai.cachedTranslation("a", "m1", "es", html))
        assertNull("another language", ai.cachedTranslation("a", "m1", "de", html))
        assertNull("another message", ai.cachedTranslation("a", "m2", "es", html))
        assertNull("another account", ai.cachedTranslation("b", "m1", "es", html))
        assertTrue(ai.hasTranslation("a", "m1"))
        assertFalse(ai.hasTranslation("a", "m2"))
    }

    @Test fun `a body that changed underneath is a miss, not a stale answer`() = runBlocking {
        val ai = ai()
        ai.translate("a", "m1", "es", html)
        assertNull(ai.cachedTranslation("a", "m1", "es", "$html<p>and one more paragraph</p>"))
    }

    @Test fun `a blank target means English and shares its row`() = runBlocking {
        val ai = ai()
        ai.translate("a", "m1", "", html)
        assertNotNull(ai.cachedTranslation("a", "m1", "en", html))
        assertNotNull(ai.cachedTranslation("a", "m1", "EN", html))
    }

    @Test fun `a failure is reported and writes nothing`() = runBlocking {
        engine.fail = "no network"
        val out = ai().translate("a", "m1", "es", html)
        assertEquals(TranslationOutcome.Failed("no network"), out)
        assertTrue("a half result must never be what reopening shows", cache.rows.isEmpty())
    }

    @Test fun `a picture-only message is not a failure and not a row`() = runBlocking {
        val out = ai().translate("a", "m1", "es", "<img src=\"x.png\">") as TranslationOutcome.Done
        assertEquals("<img src=\"x.png\">", out.fragment)
        assertEquals(0, engine.translateCalls)
        assertTrue(cache.rows.isEmpty())
    }

    // ---- auto-translate ------------------------------------------------------------------------

    private val english = "Thank you for your order. We will send you an email when it has shipped and you can track it from your account."
    private val spanish = "Gracias por su pedido. Le enviaremos un correo cuando haya sido enviado y podrá seguirlo desde su cuenta."

    @Test fun `auto-translate skips a message already in the target language`() = runBlocking {
        val ai = ai()
        assertFalse(ai.needsTranslation(english, "en"))
        assertFalse("a regional target is the same language", ai.needsTranslation(english, "en-GB"))
        assertTrue(ai.needsTranslation(spanish, "en"))
        assertFalse(ai.needsTranslation(spanish, "es"))
    }

    @Test fun `auto-translate does not guess when the language is unknown`() = runBlocking {
        assertFalse("detector says und", ai(saying { "und" }).needsTranslation(spanish, "en"))
        assertFalse("detector has no answer", ai(saying { null }).needsTranslation(spanish, "en"))
        assertFalse("detector crashed", ai(saying { error("boom") }).needsTranslation(spanish, "en"))
        assertFalse("too little text", ai().needsTranslation("ok", "en"))
    }

    @Test fun `same language compares the primary subtag only`() {
        assertTrue(ReaderTextAi.sameLanguage("pt-BR", "pt"))
        assertTrue(ReaderTextAi.sameLanguage("EN", "en-US"))
        assertFalse(ReaderTextAi.sameLanguage("es", "en"))
    }

    // ---- summary -----------------------------------------------------------------------------------

    @Test fun `the summary is cached per message and language`() = runBlocking {
        val ai = ai()
        assertNull(ai.cachedSummary("a", "m1", "en", "the message text"))
        val first = ai.summarise("a", "m1", "en", "the message text") as SummaryOutcome.Done
        assertFalse(first.fromCache)
        val again = ai.summarise("a", "m1", "en", "the message text") as SummaryOutcome.Done
        assertTrue(again.fromCache)
        assertEquals(first.text, again.text)
        assertEquals(1, engine.summariseCalls)
        assertNull("another language is another summary", ai.cachedSummary("a", "m1", "es", "the message text"))
        assertNull("another body is another summary", ai.cachedSummary("a", "m1", "en", "different text"))
    }

    @Test fun `a failed or empty summary is not cached`() = runBlocking {
        engine.fail = "model cut the reply off"
        assertEquals(SummaryOutcome.Failed("model cut the reply off"), ai().summarise("a", "m1", "en", "text"))
        assertTrue(cache.rows.isEmpty())
    }

    @Test fun `old rows are pruned and fresh ones kept`() = runBlocking {
        val ai = ai()
        ai.translate("a", "old", "es", html)
        clock += ReaderTextAi.KEEP_MILLIS + 1
        ai.translate("a", "new", "es", html)
        ai.prune()
        assertNull(cache.get("a", "old", MessageTextKind.TRANSLATION, "es"))
        assertNotNull(cache.get("a", "new", MessageTextKind.TRANSLATION, "es"))
    }
}
