package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RESOURCE LINT — what the two sentences of a refused send are ALLOWED TO SAY, in the nine
 */
class LostBodyNoticeTextTest {

    /**
     * 56 characters, the plafond [DraftKeptToastFitTest] derives and
     */
    private val maxToastChars = 56

    /**
     * FAIL CLOSED. A rule that reads its subject can read NOTHING — a key renamed, a `<string>` tag
     */
    @Test fun `both sentences resolve in the nine languages`() {
        val strings = localeStrings()
        val missing = KEYS.sorted().flatMap { key ->
            strings.filterValues { key !in it }.keys.sorted().map { "$key: not read from $it" }
        }
        assertEquals(
            "every rule below reads these two keys out of the nine strings.xml files; one that " +
                "resolves nowhere is not a translation problem — parity is happy with a key " +
                "consistently absent from THIS rule's view — it is this rule going blind. Missing:\n" +
                missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
        assertEquals("nine locales carry a strings.xml, and each one is screened", 9, strings.size)
        assertEquals(
            "each of the nine locales must have a forbidden-fragment list of its own. A language " +
                "added without one would be screened by nothing, which is how a sentence " +
                "promising that the text is safe gets shipped in one language only.",
            emptyList<String>(),
            (strings.keys - FORBIDDEN.keys).sorted(),
        )
    }

    /**
     * THE MUTATION, refused: neither sentence may claim the message is SAFE, SAVED, KEPT or
     */
    @Test fun `neither sentence claims the text is safe, saved or recovered`() {
        val offenders = localeStrings().entries.sortedBy { it.key }.flatMap { (locale, texts) ->
            val forbidden = FORBIDDEN[locale].orEmpty()
            KEYS.sorted().flatMap { key ->
                val text = texts[key] ?: return@flatMap emptyList<String>()
                forbidden.filter { it in text.lowercase() }
                    .map { "$locale/$key says '$it' — \"$text\"" }
            }
        }
        assertEquals(
            "these two sentences are shown when the composer has LOST its body and the next tap " +
                "would destroy the only copy of the text. Nothing has been saved, nothing has been " +
                "recovered, and nothing is safe — a sentence saying so invites the very gesture " +
                "that loses the message. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /** The same two-line box as every other notice of this composer. */
    @Test fun `neither sentence is too long for a toast`() {
        val offenders = localeStrings().entries.sortedBy { it.key }.flatMap { (locale, texts) ->
            KEYS.sorted().mapNotNull { key ->
                val text = texts[key] ?: return@mapNotNull null
                if (text.length <= maxToastChars) null
                else "$locale/$key is ${text.length} chars — \"$text\""
            }
        }
        assertEquals(
            "a Toast renders TWO LINES beside the app icon and ellipsises the rest (measured on " +
                "emu at 411 dp on 2026-08-25, derivation in AccountToastFitTest). Cut in half, " +
                "the instruction — the only useful half — is what falls off. Too long:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The ENGLISH, whole, by equality — because it is the source the eight others are translated
     */
    @Test fun `the English of both sentences is the one that was reviewed`() {
        assertEquals(
            "the English text of these two sentences changed. They are the source the other " +
                "eight are translated from, so a reword is a deliberate gesture, not a tidy-up. " +
                "What each owes: with a draft stored behind the screen, a gesture that WORKS " +
                "(close, reopen) and no promise about what comes back; with no draft anywhere, " +
                "retyping, which is then the only thing left to do. Swap the two and the app hands " +
                "the destructive advice to exactly the user whose text is still on the phone.",
            ENGLISH,
            localeStrings().getValue("values").filterKeys { it in KEYS },
        )
    }

    // -- reading the resources -------------------------------------------------------------------

    /** The nine locales' strings, as the READER sees them. */
    private fun localeStrings(): Map<String, Map<String, String>> =
        locales().associate { dir ->
            dir.name to STRING.findAll(File(dir, "strings.xml").readText())
                .associate { it.groupValues[1] to unescaped(it.groupValues[2]) }
                .filterKeys { it in KEYS }
        }

    /** The nine shipped locales, as `DraftKeptToastFitTest.locales()` counts them. */
    private fun locales(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "strings.xml").isFile }
        .sortedBy { it.name }
        .also {
            check(it.size == 9) {
                "expected 9 locales carrying a strings.xml, found ${it.size} " +
                    "(${it.joinToString { d -> d.name }})"
            }
        }

    /** What the reader sees: `\'`, `\"`, `\\` and `\n` are two characters in the XML, one on
     *  screen. Copied from [DraftKeptToastFitTest], which says why the count is done this way. */
    private fun unescaped(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                out.append(if (text[i + 1] == 'n') '\n' else text[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    @Test fun `neither sentence is an alias`() {
        val aliases = localeStrings().entries.sortedBy { it.key }.flatMap { (locale, texts) ->
            texts.entries.sortedBy { it.key }
                .filter { it.value.trim().startsWith("@") }
                .map { "$locale/${it.key} is a reference — \"${it.value}\"" }
        }
        assertTrue(
            "everything here is XML text, never what Android renders, so an '@string/…' alias is " +
                "text this rule screens and the reader never sees. Aliases:\n" +
                aliases.joinToString("\n"),
            aliases.isEmpty(),
        )
    }

    private companion object {
        /** The refusal's two sentences, and nothing else in the file. */
        val KEYS = setOf("compose_body_lost_cannot_send", "compose_body_lost_reopen_draft")

        val ENGLISH = mapOf(
            "compose_body_lost_cannot_send" to "The text of this message was lost. Type it again.",
            "compose_body_lost_reopen_draft" to "Text lost. Close this screen and reopen the message.",
        )

        /**
         * The stems that carry the claim "your text is saved / safe / kept / recovered", per
         */
        val FORBIDDEN = mapOf(
            "values" to listOf("saved", "safe", "kept", "recovered", "restored", "in drafts"),
            "values-de" to listOf("gespeichert", "sicher", "wiederhergestellt", "aufbewahrt", "gerettet"),
            "values-es" to listOf("guardad", "salvo", "seguro", "recuperad", "conservad"),
            "values-fr" to listOf("enregistr", "sauvegard", "récupér", "conserv", "sécurité", "sûr"),
            "values-it" to listOf("salv", "sicuro", "recuperat", "conservat"),
            "values-nl" to listOf("opgeslagen", "veilig", "hersteld", "bewaard", "gered"),
            "values-pl" to listOf("zapisan", "bezpieczn", "odzyskan", "zachowan", "uratowan"),
            "values-pt" to listOf("guardad", "salvo", "seguro", "recuperad", "conservad"),
            "values-ru" to listOf("сохран", "безопасн", "восстанов"),
        )

        /** A `<string>` with its text, attributes allowed — the pattern [DraftKeptToastFitTest]
         *  uses, and it says why a stricter one goes blind in silence. */
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val res: File by lazy { File(root, "app/src/main/res") }
    }
}
