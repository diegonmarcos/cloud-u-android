package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RESOURCE LINT, NOT A BEHAVIOUR TEST — and a lint on a MARK, not on a meaning.
 */
class PendingDeletionLabelTest {

    /**
     * The mark itself, in the nine languages: a label posted before the write leaves the phone ends
     */
    @Test
    fun `the three labels of a delete that has not happened yet say it is under way`() {
        val stale = eachValue { locale, key, value ->
            when {
                value == null -> "$locale/$key: no such string, so this screen has no label at all"
                !value.endsWith(ELLIPSIS) ->
                    "$locale/$key: <$value>"
                else -> null
            }
        }
        assertEquals(
            "these labels are posted while the mail is still on the server — the rows leave the " +
                "screen at once, the destroy is WorkManager work delayed by at least 6 s, and " +
                "Undo is on the screen and works for all of it. A sentence that announces the " +
                "deed as done tells the reader her mail is gone when it is not, and invites her " +
                "to undo something that already happened. It has to read as an order under way, " +
                "ending in “…” the way “Sending…” does. Announced as done in:",
            emptyList<String>(),
            stale,
        )
    }

    /**
     * The falsification this file answers to: the 27 sentences the app shipped, by locale and by
     */
    @Test
    fun `no label went back to announcing the delete as already done`() {
        val relapsed = eachValue { locale, key, value ->
            val announced = ANNOUNCED_AS_DONE.getValue(locale).getValue(key)
            if (value == announced) "$locale/$key: <$value>" else null
        }
        assertEquals(
            "this is the exact sentence the app used to show while the message, the Trash or the " +
                "folder was still on the server, with Undo next to it. Back to announcing a " +
                "delete that has not happened in:",
            emptyList<String>(),
            relapsed,
        )
    }

    /**
     * THE #99 GUARD: a second tap has to repost the SAME text.
     */
    @Test
    fun `the message and trash labels take no argument, so a second tap reposts the same text`() {
        val parameterised = eachValue(keys = listOf(DESTROY, EMPTY)) { locale, key, value ->
            val args = FORMAT.findAll(value.orEmpty()).map { it.value }.toList()
            if (args.isEmpty()) null else "$locale/$key: $args in <$value>"
        }
        assertEquals(
            "a second tap on Empty trash reposts this label, and the screen keeps one snackbar " +
                "only because the text is identical. A count or a name in it makes the repost a " +
                "different string: two snackbars, and the later one's Undo outlives the destroy " +
                "it claims to undo (#99). Argument found in:",
            emptyList<String>(),
            parameterised,
        )
    }

    /**
     * And the opposite for the folder: it names the folder it is deleting, exactly once.
     */
    @Test
    fun `the folder label still names the folder it is deleting`() {
        val wrong = eachValue(keys = listOf(FOLDER)) { locale, key, value ->
            val args = FORMAT.findAll(value.orEmpty()).map { it.value }.toList()
            if (args == listOf("%1\$s")) null else "$locale/$key: $args in <$value>"
        }
        assertEquals(
            "the folder delete is confirmed from a drawer full of folders and takes the " +
                "subfolders with it; the snackbar has to say WHICH one, so exactly one “%1\$s” " +
                "and nothing else. Wrong arguments in:",
            emptyList<String>(),
            wrong,
        )
    }

    /**
     * Run [check] over the three keys in the nine languages and collect what it complains about.
     */
    private fun eachValue(
        keys: List<String> = PENDING_KEYS,
        check: (locale: String, key: String, value: String?) -> String?,
    ): List<String> {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        assertEquals(
            "the app ships nine languages, and a directory that disappears takes its three labels " +
                "with it without failing anything else here",
            LOCALES,
            files.map { it.parentFile.name },
        )
        return files.flatMap { file ->
            val values = valuesOf(file)
            assertTrue(
                "no strings read from ${file.path} — wrong working directory?",
                values.size > 100,
            )
            keys.mapNotNull { key -> check(file.parentFile.name, key, values[key]) }
        }
    }

    /** Each `<string>`'s text exactly as the file carries it, escapes included. */
    private fun valuesOf(file: File): Map<String, String> = STRING
        .findAll(file.readText())
        .associate { it.groupValues[1] to it.groupValues[2] }

    private fun translations(): List<File> = (res.listFiles() ?: emptyArray<File>())
        .filter { it.isDirectory && it.name.startsWith("values-") }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private companion object {
        /** A `<string>` with its text. Same shape as `TranslationParityTest`'s, copied on purpose. */
        val STRING = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        /**
         * A Java format specifier, wider than the parity test's `%s`/`%d`: anything that would make
         */
        val FORMAT = Regex("%(?:\\d+\\$)?[-#+0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]")

        /** U+2026, the single character. Three full stops are NOT this and must not be. */
        const val ELLIPSIS = "…"

        /** A permanent destroy, held behind the Undo window (`heldBackDestroy`). */
        const val DESTROY = "status_message_deleted_forever"

        /** Empty trash, held the same way. */
        const val EMPTY = "status_trash_emptied"

        /** A folder delete, hidden locally now and deleted on the server by `FolderDeleteWorker`. */
        const val FOLDER = "inbox_folder_deleted"

        val PENDING_KEYS = listOf(DESTROY, EMPTY, FOLDER)

        val LOCALES = listOf(
            "values", "values-de", "values-es", "values-fr", "values-it",
            "values-nl", "values-pl", "values-pt", "values-ru",
        )

        /**
         * The 27 sentences the app used to post while the write was still pending, read out of the
         * resources at the commit before the fix. Nothing else is forbidden — a reword is free.
         */
        val ANNOUNCED_AS_DONE = mapOf(
            "values" to mapOf(
                DESTROY to "Message deleted permanently",
                EMPTY to "Trash emptied",
                FOLDER to "Folder \\\"%1\$s\\\" deleted",
            ),
            "values-de" to mapOf(
                DESTROY to "Nachricht endgültig gelöscht",
                EMPTY to "Papierkorb geleert",
                FOLDER to "Ordner „%1\$s“ gelöscht",
            ),
            "values-es" to mapOf(
                DESTROY to "Mensaje eliminado definitivamente",
                EMPTY to "Papelera vaciada",
                FOLDER to "Carpeta «%1\$s» eliminada",
            ),
            "values-fr" to mapOf(
                DESTROY to "Message supprimé définitivement",
                EMPTY to "Corbeille vidée",
                FOLDER to "Dossier « %1\$s » supprimé",
            ),
            "values-it" to mapOf(
                DESTROY to "Messaggio eliminato definitivamente",
                EMPTY to "Cestino svuotato",
                FOLDER to "Cartella \"%1\$s\" eliminata",
            ),
            "values-nl" to mapOf(
                DESTROY to "Bericht definitief verwijderd",
                EMPTY to "Prullenbak geleegd",
                FOLDER to "Map \"%1\$s\" verwijderd",
            ),
            "values-pl" to mapOf(
                DESTROY to "Wiadomość usunięta trwale",
                EMPTY to "Kosz opróżniony",
                FOLDER to "Usunięto folder „%1\$s”",
            ),
            "values-pt" to mapOf(
                DESTROY to "Mensagem excluída permanentemente",
                EMPTY to "Lixeira esvaziada",
                FOLDER to "Pasta \"%1\$s\" eliminada",
            ),
            "values-ru" to mapOf(
                DESTROY to "Письмо удалено навсегда",
                EMPTY to "Корзина очищена",
                FOLDER to "Папка «%1\$s» удалена",
            ),
        )

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
