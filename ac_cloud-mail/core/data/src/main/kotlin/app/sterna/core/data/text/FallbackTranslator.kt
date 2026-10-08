package app.sterna.core.data.text

import app.sterna.core.data.text.InPlaceHtmlTranslation.NeedsSource
import app.sterna.core.data.text.InPlaceHtmlTranslation.TranslationFailed

/**
 * Translate one batch so that a tap on Translate never fails on language detection.
 *
 * In order: (1) the translation library, which detects the source itself from the text of ONE request
 * and answers "could not detect the language" for a batch of short lines; (2) a translator that needs
 * no source - the model chosen on AI Routing, told the source when it is known and to work it out when
 * it is not; (3) only when neither worked AND the complaint was the language, [NeedsSource], which the
 * reader answers with a language picker. Once the library has said it cannot place a message, the rest
 * of that message's batches skip it, so a newsletter is not retried against it forty times.
 */
class FallbackTranslator(
    private val library: (text: String, target: String) -> Reply,
    private val model: (text: String, target: String, source: String?) -> Reply,
) {
    class Reply(val text: String?, val error: String?)

    @Volatile private var libraryCannotDetect = false

    /** Call before each message: what was learned about the last one does not apply. */
    fun newRun() { libraryCannotDetect = false }

    fun translate(text: String, target: String, source: String?): String {
        var libraryReason: String? = null
        if (!libraryCannotDetect) {
            val r = library(text, target)
            r.text?.let { return it }
            libraryReason = r.error
            if (libraryReason != null && DETECTION_FAILURE.containsMatchIn(libraryReason)) libraryCannotDetect = true
        }
        val viaModel = model(text, target, source)
        viaModel.text?.let { return it }
        val why = libraryReason ?: viaModel.error ?: "Translation failed"
        if (libraryCannotDetect && source == null) throw NeedsSource(why)
        throw TranslationFailed(if (libraryReason != null && viaModel.error != null) "$libraryReason - ${viaModel.error}" else why)
    }

    private companion object {
        val DETECTION_FAILURE = Regex("detect|source language|\\bund\\b", RegexOption.IGNORE_CASE)
    }
}
