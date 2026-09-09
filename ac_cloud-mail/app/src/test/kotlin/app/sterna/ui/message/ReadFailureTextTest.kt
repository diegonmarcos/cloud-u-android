package app.sterna.ui.message

import app.sterna.R
import app.sterna.core.data.mail.MessageUnavailableException
import app.sterna.core.jmap.ContentTooLargeException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the reader says when a message won't open.
 */
class ReadFailureTextTest {

    @Test fun unavailableMessageGetsATranslatedSentence() {
        // #159: the text this exception carries names the account UUID, the folder and the uid,
        // in English. It must not reach the screen — the resource REPLACES it.
        val t = MessageUnavailableException(
            "Message imap:acc-4f1e:Archive/2024:4242 is not in the cache and the server did not return it.",
        )
        assertEquals(R.string.status_message_unavailable, readFailureStringRes(t))
    }

    @Test fun tooLargeKeepsItsOwnSentence() {
        // The witness: the one case that was already translated must not move.
        val t = ContentTooLargeException("body 40 MB > 8 MB", bytes = 40_000_000, maxBytes = 8_000_000)
        assertEquals(R.string.status_message_too_large, readFailureStringRes(t))
    }

    @Test fun anyOtherFailureKeepsItsTechnicalText() {
        // null = "no sentence of ours", i.e. `t.message` survives — what a bug report needs.
        assertNull(readFailureStringRes(IllegalStateException("Not an IMAP message.")))
    }

    @Test fun theSentenceIsDeclaredInEveryLanguage() {
        // A German reader is the one who reported this. Name the languages that are MISSING it,
        // in one assertion, so a forgotten file says which.
        val expected = listOf("values", "values-de", "values-es", "values-fr", "values-it",
            "values-nl", "values-pl", "values-pt", "values-ru")
        val declared = expected.filter { dir ->
            val xml = File(repoRoot, "app/src/main/res/$dir/strings.xml").readText()
            val body = Regex("""<string name="status_message_unavailable">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)
            !body.isNullOrBlank()
        }
        assertEquals(expected, declared)
    }

    /**
     * SOURCE RULE — the branching, which nothing executable holds.
     */
    @Test fun theScreenGoesThroughTheDecision() {
        val expected = listOf(
            "private fun readFailureText(t: Throwable): String =",
            "readFailureStringRes(t)?.let { getApplication<Application>().getString(it) }",
            "?: (t.message ?: t.javaClass.simpleName)",
        )
        assertEquals(
            "readFailureText no longer reads as \"the sentence readFailureStringRes picks, and " +
                "the technical text only when it picks none\" — either the decision is bypassed, " +
                "or the technical text is back in front of it, or it is appended to the sentence",
            expected,
            codeLines(functionBody("private fun readFailureText")),
        )
    }

    /**
     * SOURCE RULE — the throwable, which now reaches nobody.
     */
    @Test fun theFailureToOpenIsWrittenDown() {
        val logged = codeLines(functionBody("fun load(")).filter {
            Regex("""^android\.util\.Log\.[wet]\("[^"]+", "[^"]*", t\)$""").matches(it)
        }
        assertTrue(
            "load()'s catch swallows its throwable without logging it: the reader is shown a " +
                "translated sentence that names no cause, and the cause exists nowhere else. " +
                "Expected one android.util.Log call carrying t, as unsubscribe() already does.",
            logged.size == 1,
        )
    }

    /** The text of a member of `MessageViewModel`, up to the next one declared at class indentation. */
    private fun functionBody(declaration: String): String {
        val source = File(repoRoot, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt").readText()
        val start = source.indexOf("    $declaration")
        assertTrue("MessageViewModel has no `$declaration` any more", start > 0)
        val end = Regex("""\n {4}(private )?(suspend )?fun \w""").find(source, start + 1)
            ?.range?.first ?: source.length
        return source.substring(start, end)
    }

    /** The lines that are code: trimmed, blanks and comments dropped. */
    private fun codeLines(body: String): List<String> =
        body.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private companion object {
        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
