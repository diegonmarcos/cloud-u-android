package app.sterna.core.data.text

import app.sterna.core.data.db.MessageTextCacheDao
import app.sterna.core.data.db.MessageTextCacheEntity
import app.sterna.core.data.db.MessageTextKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** The two engine calls the reader needs, behind one seam so the logic below runs without a device. */
interface ReaderTextEngine {
    /**
     * Translate [text] into [targetTag]; throws [InPlaceHtmlTranslation.TranslationFailed]. BLOCKING.
     * [sourceTag] is the language the WHOLE message was detected (or chosen) to be in, or null when
     * unknown; the same value goes with every batch of a message.
     */
    @Throws(InPlaceHtmlTranslation.TranslationFailed::class)
    fun translate(text: String, targetTag: String, sourceTag: String?): String

    /** Called once before each message's batches, so an engine can forget what it learned on the last. */
    fun newRun() {}

    /** Summarise [text], writing the summary in [languageTag]; throws the same. BLOCKING. */
    @Throws(InPlaceHtmlTranslation.TranslationFailed::class)
    fun summarise(text: String, languageTag: String): String
}

/** What language a text is in: a BCP-47 tag, or null / "und" when it cannot tell. */
fun interface LanguageDetector {
    suspend fun detect(text: String): String?
}

sealed interface TranslationOutcome {
    /** [fragment] is the translated HTML; [fromCache] says no engine call was made. */
    data class Done(val fragment: String, val fromCache: Boolean) : TranslationOutcome
    data class Failed(val reason: String) : TranslationOutcome

    /** Nothing could tell the source language and nothing needs none: ask the reader which it is. */
    data class NeedsSource(val reason: String) : TranslationOutcome
}

sealed interface SummaryOutcome {
    data class Done(val text: String, val fromCache: Boolean) : SummaryOutcome
    data class Failed(val reason: String) : SummaryOutcome
}

/**
 * The reader's translation and summary, with the cache in front of both.
 *
 * Every result is keyed by message AND language and carries the hash of what it was made from, so
 * reopening a message is a row read, a different target language is a different row, and a body that
 * changed underneath a row is a miss. A failed run writes NOTHING: a half-translated page cached as
 * the translation would be shown again, instantly, on every reopen.
 */
class ReaderTextAi(
    private val cache: MessageTextCacheDao,
    private val engine: ReaderTextEngine,
    private val detector: LanguageDetector,
    private val now: () -> Long = System::currentTimeMillis,
) {

    // ---- translation -----------------------------------------------------------------------------

    suspend fun cachedTranslation(accountId: String, emailId: String, lang: String, fragment: String): String? =
        cache.get(accountId, emailId, MessageTextKind.TRANSLATION, norm(lang))
            ?.takeIf { it.sourceHash == hash(fragment) }
            ?.payload

    /** Whether the message has a translation kept in any language: the toggle's enabled state. */
    suspend fun hasTranslation(accountId: String, emailId: String): Boolean =
        cache.translationCount(accountId, emailId) > 0

    /**
     * The language of the WHOLE message: asked of the detector once, on the message's text and not on
     * the short text nodes the batches are made of (a node like "Sale" detects as unknown), and kept
     * with the message so the next open does not ask again. Null when it cannot be told.
     */
    suspend fun sourceLanguage(accountId: String, emailId: String, fragment: String): String? {
        val key = hash(fragment)
        cache.get(accountId, emailId, MessageTextKind.SOURCE, SOURCE_LANG)?.takeIf { it.sourceHash == key }
            ?.let { return it.payload.ifEmpty { null } }
        val sample = InPlaceHtmlTranslation.visibleText(fragment).take(DETECT_CHARS * 4)
        if (sample.length < MIN_DETECT_CHARS) return null   // nothing to read: not asked, not kept
        val found = runCatching { detector.detect(sample) }.getOrNull()?.takeIf { it.isNotBlank() && !it.equals("und", true) }
        cache.put(MessageTextCacheEntity(accountId, emailId, MessageTextKind.SOURCE, SOURCE_LANG, key, found.orEmpty(), now()))
        return found
    }

    /** [chosenSource] is the language the reader picked when asked; it beats detection. */
    suspend fun translate(
        accountId: String, emailId: String, lang: String, fragment: String, chosenSource: String? = null,
    ): TranslationOutcome {
        cachedTranslation(accountId, emailId, lang, fragment)?.let { return TranslationOutcome.Done(it, fromCache = true) }
        val target = norm(lang)
        val source = chosenSource?.let { norm(it) } ?: sourceLanguage(accountId, emailId, fragment)
        val result = try {
            withContext(Dispatchers.IO) {
                engine.newRun()
                InPlaceHtmlTranslation.translate(fragment) { engine.translate(it, target, source) }
            }
        } catch (e: InPlaceHtmlTranslation.NeedsSource) {
            return TranslationOutcome.NeedsSource(e.reason)
        } catch (e: InPlaceHtmlTranslation.TranslationFailed) {
            return TranslationOutcome.Failed(e.reason)
        }
        // Nothing translatable in it (a picture-only mail): not a failure, and not worth a row.
        if (result.totalUnits == 0) return TranslationOutcome.Done(fragment, fromCache = false)
        if (result.translatedUnits == 0) return TranslationOutcome.Failed("The translation engine returned no usable text")
        cache.put(
            MessageTextCacheEntity(accountId, emailId, MessageTextKind.TRANSLATION, target, hash(fragment), result.html, now()),
        )
        return TranslationOutcome.Done(result.html, fromCache = false)
    }

    /**
     * Auto-translate's question: is this message NOT already in [target]? Unknown ("und", no text, a
     * detector that failed) answers false - guessing wrong costs an unwanted engine call on mail that
     * was fine, where not translating costs the reader one tap.
     */
    suspend fun needsTranslation(text: String, target: String): Boolean {
        // whatever the caller passes, the detector is only ever handed prose
        val sample = InPlaceHtmlTranslation.forDetection(text).take(DETECT_CHARS)
        if (sample.length < MIN_DETECT_CHARS) return false
        val found = runCatching { detector.detect(sample) }.getOrNull() ?: return false
        if (found.isBlank() || found.equals("und", ignoreCase = true)) return false
        return !sameLanguage(found, target)
    }

    // ---- summary ---------------------------------------------------------------------------------

    suspend fun cachedSummary(accountId: String, emailId: String, lang: String, source: String): String? =
        cache.get(accountId, emailId, MessageTextKind.SUMMARY, norm(lang))
            ?.takeIf { it.sourceHash == hash(source) }
            ?.payload

    suspend fun summarise(accountId: String, emailId: String, lang: String, source: String): SummaryOutcome {
        cachedSummary(accountId, emailId, lang, source)?.let { return SummaryOutcome.Done(it, fromCache = true) }
        val target = norm(lang)
        val text = try {
            withContext(Dispatchers.IO) { engine.summarise(source, target) }
        } catch (e: InPlaceHtmlTranslation.TranslationFailed) {
            return SummaryOutcome.Failed(e.reason)
        }
        if (text.isBlank()) return SummaryOutcome.Failed("The summary came back empty")
        cache.put(MessageTextCacheEntity(accountId, emailId, MessageTextKind.SUMMARY, target, hash(source), text, now()))
        return SummaryOutcome.Done(text, fromCache = false)
    }

    suspend fun prune(olderThanMillis: Long = KEEP_MILLIS) {
        cache.pruneOlderThan(now() - olderThanMillis)
    }

    companion object {
        const val KEEP_MILLIS = 90L * 24 * 60 * 60 * 1000
        const val DETECT_CHARS = 600
        const val MIN_DETECT_CHARS = 12
        private const val SOURCE_LANG = "-"

        /** The language a stored / typed tag means: lower case, "" -> English. */
        fun norm(tag: String): String = tag.trim().ifEmpty { "en" }.lowercase().replace('_', '-')

        /** Same language, whatever the region: en-GB is English for a target of en. */
        fun sameLanguage(a: String, b: String): Boolean =
            norm(a).substringBefore('-') == norm(b).substringBefore('-')

        fun hash(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
