package app.sterna.ui.connect

import app.sterna.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EXECUTES the sentence an add ends on, it does not read the routes that show it.
 */
class AccountAddedToastTest {

    /**
     * The witness this whole file rests on. If the two resource ids were the same number — the
     */
    @Test fun `the two sentences are different resources, or nothing here can fail`() {
        assertNotEquals(
            "R.string.connect_account_added and R.string.connect_account_refreshed resolve to the " +
                "same id in this test's classpath, so this file cannot tell the two apart and " +
                "proves nothing at all. Fix the classpath rather than trusting the green.",
            R.string.connect_account_added,
            R.string.connect_account_refreshed,
        )
    }

    @Test fun `an add that really created the account keeps its own sentence`() {
        val chosen: Int? = accountAddedToast(created = true, createdRes = R.string.connect_account_added)
        assertEquals(
            "a first sign-in on this server did add an account, and the reader must still be told " +
                "\"Account added\" — the route's own sentence, not the re-add one.",
            R.string.connect_account_added,
            chosen,
        )
    }

    /**
     * The same, with the OTHER resource: the argument is what is pinned, not "some string". A body
     */
    @Test fun `the sentence a create gets is the one the route passed in`() {
        val chosen: Int? = accountAddedToast(created = true, createdRes = R.string.connect_outlook_added)
        assertEquals(
            "the Outlook sign-in hands in its own sentence and must get it back: the decision is " +
                "which of created/refreshed happened, never which words a route uses to say it.",
            R.string.connect_outlook_added,
            chosen,
        )
    }

    @Test fun `a route that says nothing on a create still says nothing`() {
        assertNull(
            "four of the six add routes have never shown anything after a successful add, and this " +
                "change does not add a toast to them: a `null` resource means silence, and turning " +
                "it into a sentence is noise the reader never asked for.",
            accountAddedToast(created = true, createdRes = null),
        )
    }

    @Test fun `an add that landed on an account already there says so`() {
        val chosen: Int? = accountAddedToast(created = false, createdRes = R.string.connect_account_added)
        assertEquals(
            "⛔ THE DEFECT: nothing was added — the account was already installed and its " +
                "connection was refreshed in place. Saying \"Account added\" there tells the reader " +
                "she now has two accounts on this server, and the accounts list will not show one.",
            R.string.connect_account_refreshed,
            chosen,
        )
    }

    /**
     * And on the silent routes too. This is the case that was invisible: the sign-in came back,
     */
    @Test fun `a re-add speaks even on a route that says nothing when it creates`() {
        val chosen: Int? = accountAddedToast(created = false, createdRes = null)
        assertEquals(
            "a re-add must be said out loud even where a create is silent: silence after a re-add " +
                "is exactly what made the same mailbox be added again and again.",
            R.string.connect_account_refreshed,
            chosen,
        )
    }
}

/**
 * RESOURCE LINT — the sentence exists in the nine languages the app ships, and carries something.
 */
class AccountRefreshedStringTest {

    @Test fun `the re-add sentence is there, and not empty, in the nine languages`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        assertEquals(
            "the app ships nine languages, and a directory that disappears takes this sentence " +
                "with it without failing anything else here",
            LOCALES,
            files.map { it.parentFile.name },
        )
        val faults = files.mapNotNull { file ->
            val values = valuesOf(file)
            assertTrue("no strings read from ${file.path} — wrong working directory?", values.size > 100)
            val value = values[KEY]
            when {
                value == null -> "${file.parentFile.name}: no such string"
                value.isBlank() -> "${file.parentFile.name}: <$value>"
                else -> null
            }
        }
        assertEquals(
            "this is the only thing shown to someone who re-added an account she already had: " +
                "without it, in that language, the sign-in ends in silence and she has no way to " +
                "know the address was already installed. Missing or empty in:",
            emptyList<String>(),
            faults,
        )
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
        const val KEY = "connect_account_refreshed"

        /** A `<string>` with its text. Same shape as `PendingDeletionLabelTest`'s, copied on purpose. */
        val STRING = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        val LOCALES = listOf(
            "values", "values-de", "values-es", "values-fr", "values-it",
            "values-nl", "values-pl", "values-pt", "values-ru",
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
