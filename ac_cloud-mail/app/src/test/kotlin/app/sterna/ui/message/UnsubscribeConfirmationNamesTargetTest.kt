package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RESOURCE + SOURCE LINT. The rule it guards is the whole promise of the confirmation dialog:
 */
class UnsubscribeConfirmationNamesTargetTest {

    /** The three confirmations that speak about a destination. The title and the banner do not. */
    private val keysThatNameATarget = listOf(
        "message_unsubscribe_confirm_post",
        "message_unsubscribe_confirm_mail",
        "message_unsubscribe_confirm_open",
    )

    @Test
    fun `every unsubscribe confirmation names its destination, in every language`() {
        val files = listOf(File(res, "values/strings.xml")) + (res.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.parentFile.name }
        assertTrue("expected nine languages, found ${files.size}", files.size == 9)

        val unnamed = files.flatMap { file ->
            val strings = stringsOf(file)
            keysThatNameATarget
                .filter { key -> strings[key]?.contains("%1\$s") != true }
                .map { key -> "${file.parentFile.name}/$key" }
        }

        assertEquals(
            "an unsubscribe confirmation that does not name its destination",
            emptyList<String>(),
            unnamed,
        )
    }

    @Test
    fun `the reader passes the destination to each of them`() {
        val source = File(repoRoot, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").readText()

        val called = CONFIRM_CALL.findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            "MessageScreen no longer shows the three confirmations by stringResource",
            keysThatNameATarget.map { it.removePrefix("message_unsubscribe_confirm_") }.toSet(),
            called.keys,
        )

        val argumentless = called.filterValues { it != "," }.keys
        assertEquals(
            "a confirmation shown without its target argument renders a raw %1\$s",
            emptySet<String>(),
            argumentless,
        )
    }

    private fun stringsOf(file: File): Map<String, String> =
        STRING.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        /**
         * A `stringResource(R.string.message_unsubscribe_confirm_X` and whatever follows the id.
         */
        val CONFIRM_CALL = Regex(
            """stringResource\(\s*R\.string\.message_unsubscribe_confirm_(post|mail|open)""" +
                """(?![A-Za-z0-9_])\s*(.?)""",
        )

        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }

        val res: File by lazy { File(repoRoot, "app/src/main/res") }
    }
}
