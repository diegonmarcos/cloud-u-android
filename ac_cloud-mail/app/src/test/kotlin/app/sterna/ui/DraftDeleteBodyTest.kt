package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * RESOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument as [TranslationParityTest]: it reads
 */
class DraftDeleteBodyTest {

    /**
     * The rule of #127. 140 is a GROWTH CEILING, not a measurement of the slot and not a
     */
    @Test
    fun `the draft-delete body fits the dialog in every language`() {
        val overlong = bodies()
            .filterValues { it.length > CAP }
            .map { (locale, text) -> "$locale: ${text.length} chars (cap $CAP) — $text" }
        assertEquals(
            "the composer's delete confirmation renders in an AlertDialog text slot, a bounded " +
                "box. It scrolls, so past the cap nothing is cut any more — but the end of the " +
                "sentence goes under the fold, reachable only by a scroll gesture nothing on " +
                "screen announces, and what sits at the end is the fact that unsaved typing is " +
                "lost (#127). Shorten the translation, do not truncate it — both facts must survive",
            emptyList<String>(),
            overlong,
        )
    }

    /**
     * The cap alone would be satisfied by an empty string, so each language states, in order, the
     * destination of the draft and the fate of what was typed. One row per language, by hand.
     */
    @Test
    fun `every language still states where the draft goes and what happens to the typing`() {
        assertEquals("a language ships without a row in the expected-words table", LOCALES, REQUIRED.keys.sorted())
        val broken = bodies().flatMap { (locale, text) ->
            val required = REQUIRED.getValue(locale)
            val positions = required.map { text.indexOf(it, ignoreCase = true) }
            val missing = required.zip(positions).filter { it.second < 0 }.map { it.first }
            when {
                missing.isNotEmpty() -> listOf("$locale: missing $missing in “$text”")
                positions != positions.sorted() ->
                    listOf("$locale: $required out of order (found at $positions) in “$text”")
                else -> emptyList()
            }
        }
        assertEquals(
            "the body must still say (a) that the draft goes to the Trash, or is deleted when this " +
                "account has no folder recognised as a Trash, then (b) that anything typed since the " +
                "composer opened is not saved. Shortening may not cost a fact",
            emptyList<String>(),
            broken,
        )
    }

    /**
     * The words this dialog may not borrow, per language: the outbox's ("isn't saved anywhere else",
     */
    @Test
    fun `no language borrows the outbox's words, or promises the future`() {
        assertEquals("a language ships without a row in the forbidden-words table", LOCALES, FORBIDDEN.keys.sorted())
        val borrowed = bodies().flatMap { (locale, text) ->
            FORBIDDEN.getValue(locale)
                .filter { text.contains(it, ignoreCase = true) }
                .map { "$locale: “$it” in “$text”" }
        }
        assertEquals(
            "this draft goes to the Trash and can be brought back; the outbox's message cannot, and " +
                "its wording is a lie here. Nor may the dialog promise a move that fails offline",
            emptyList<String>(),
            borrowed,
        )
    }

    /** There is no format argument in this string, and a translator must not invent one. */
    @Test
    fun `the draft-delete body takes no format argument`() {
        val withArgs = bodies()
            .filterValues { PLACEHOLDER.containsMatchIn(it) }
            .map { (locale, text) -> "$locale: $text" }
        assertEquals(
            "compose_delete_draft_body is read with a plain stringResource(): a %1\$s added in one " +
                "language renders as itself, or throws",
            emptyList<String>(),
            withArgs,
        )
    }

    // -- reading the resources ----------------------------------------------------------------

    /** `compose_delete_draft_body` as the reader sees it, per `values*` directory. */
    private fun bodies(): Map<String, String> {
        val files = (res.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name.startsWith("values") }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.parentFile.name }
        val found = files.mapNotNull { file ->
            BODY.find(file.readText())?.let { file.parentFile.name to rendered(it.groupValues[1]) }
        }.toMap()
        assertEquals("compose_delete_draft_body is missing from a language", LOCALES, found.keys.sorted())
        return found
    }

    /**
     * The XML text as it reaches the screen: entities resolved, Android's backslash escapes dropped
     * — `\'` is one apostrophe on screen and must be counted as one character, not two.
     */
    private fun rendered(raw: String): String = raw
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&#160;", " ").replace("&amp;", "&")
        .replace(Regex("""\\(.)"""), "$1")
        .trim()

    private companion object {
        const val CAP = 140

        val LOCALES = listOf(
            "values", "values-de", "values-es", "values-fr", "values-it",
            "values-nl", "values-pl", "values-pt", "values-ru",
        )

        /**
         * Per language, in this order: the destination, the deletion verb, the NEGATION that
         */
        val REQUIRED = mapOf(
            "values" to listOf("Trash", "deleted", "no Trash folder", "isn't saved"),
            "values-de" to listOf("Papierkorb", "gelöscht", "Konto keinen hat", "nicht gespeichert"),
            "values-es" to listOf("Papelera", "se elimina", "no tiene carpeta", "no se guarda"),
            "values-fr" to listOf("Corbeille", "supprimé", "n'a pas de dossier", "n'est pas enregistré"),
            "values-it" to listOf("Cestino", "eliminata", "non ha una cartella", "non viene salvato"),
            "values-nl" to listOf("Prullenbak", "verwijderd", "account er geen heeft", "niet opgeslagen"),
            "values-pl" to listOf("Kosz", "usunięta", "nie ma folderu", "nie jest zapisywane"),
            "values-pt" to listOf("Lixeira", "eliminado", "não tiver pasta", "não é guardado"),
            "values-ru" to listOf("Корзин", "удаляется", "нет папки", "не сохраняется"),
        )

        /**
         * Per language: outbox calques, unreserved destruction, possessive Trash, plain future.
         */
        val FORBIDDEN = mapOf(
            "values" to listOf("anywhere else", "will be lost", "for good", "forever", "your Trash", "will "),
            "values-de" to listOf("nirgendwo sonst", "gehen verloren", "endgültig", "für immer", "deinen Papierkorb"),
            "values-es" to listOf(
                "ningún otro", "perderán", "definitivamente", "para siempre", "tu carpeta", "se eliminará", "irá",
            ),
            "values-fr" to listOf(
                "nulle part ailleurs", "perdus", "définitivement", "pour de bon", "votre dossier", "sera", "ira",
            ),
            "values-it" to listOf(
                "altra parte", "andranno persi", "definitivamente", "per sempre", "tua cartella", "verrà", "andrà",
            ),
            "values-nl" to listOf("nergens anders", "gaan verloren", "definitief", "voorgoed", "je Prullenbak", "zal "),
            "values-pl" to listOf("nigdzie indziej", "utracon", "na dobre", "na zawsze", "twoj", "zostanie"),
            "values-pt" to listOf(
                "nenhum outro", "serão perdidos", "definitivamente", "de vez", "sua pasta", "será", "irá",
            ),
            "values-ru" to listOf("нигде больше", "будут потеряны", "навсегда", "вашей", "будет"),
        )

        val BODY = Regex(
            """<string\s+name="compose_delete_draft_body"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val PLACEHOLDER = Regex("""%(?:\d+\$)?[sd]""")

        /** Repo root, found by walking up from the module's working directory. */
        val res: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/res") }
                .firstOrNull { File(it, "values/strings.xml").isFile }
                ?: error(
                    "cannot locate app/src/main/res from ${File("").absolutePath} — this test reads " +
                        "the resources as text and needs a working directory inside the checkout",
                )
        }
    }
}
