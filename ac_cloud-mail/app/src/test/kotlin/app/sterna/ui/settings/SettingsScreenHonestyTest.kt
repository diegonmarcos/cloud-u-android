package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE AND RESOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class SettingsScreenHonestyTest {

    // -- the switches -------------------------------------------------------------------------

    @Test fun `the conversation switches do not depend on which accounts exist`() {
        val section = conversationSection()
        assertTrue(
            "the Conversations section must not carry an `enabled =` argument: a switch greyed by " +
                "the account set is the coupled toggle this fix removed, and it comes back the " +
                "moment one JMAP account is added. Section was:\n$section",
            "enabled" !in section,
        )
        assertTrue(
            "the Conversations section must not branch on anything: the note it used to hide " +
                "behind `if (imapOnly)` was absent in exactly the mixed case that needed it. " +
                "Section was:\n$section",
            "if (" !in section,
        )
    }

    @Test fun `the conversation section is not wrapped in a condition either`() {
        // The rule above reads INSIDE the section, and a condition can grow back just above it:
        // `if (…) { SettingsSection(...) }` shows or hides the whole thing — the same coupling,
        // one line out of reach. The line that opens the block the section sits in is the place
        // that can do it, so that is the line read here.
        val enclosing = enclosingLine()
        assertTrue(
            "the Conversations section must not be wrapped in a condition: hiding the whole " +
                "section on the account set is the coupling this fix removed, whatever is inside " +
                "it. Enclosing line was:\n$enclosing",
            "if (" !in enclosing && "when (" !in enclosing,
        )
    }

    @Test fun `the screen never asks whether every account is IMAP`() {
        val mentions = codeLines(SETTINGS_SCREEN).filter { "ImapOnly" in it || "imapOnly" in it }
        assertEquals(
            "SettingsScreen must not ask whether every account is IMAP: the honest statement is " +
                "unconditional, and any form of that question on this screen is a condition growing " +
                "back. The function it used to call (isImapOnly) is gone — bringing it, or a local " +
                "equivalent, back here is the regression. Found:\n" +
                mentions.joinToString("\n"),
            emptyList<String>(), mentions,
        )
    }

    @Test fun `the conversation switch still shows the subtitle that carries the fact`() {
        val section = conversationSection()
        assertTrue(
            "the Conversations switch must keep showing R.string.settings_conversation_subtitle — " +
                "it is the only place the app states where the thread comes from on each protocol. " +
                "Section was:\n$section",
            "R.string.settings_conversation_subtitle" in section,
        )
    }

    // -- the wording, in all nine languages ---------------------------------------------------

    @Test fun `every language says where the thread comes from on each protocol`() {
        val files = stringFiles()
        // A FLOOR, not a count: the rule is "every locale shipped", and pinning the exact number
        // made a tenth translation fail a test that talks about conversations.
        assertTrue(
            "only ${files.size} locale string files found; the app ships at least nine, so this " +
                "rule is no longer reading all of them and a locale can keep an old subtitle " +
                "that says something else about where the thread comes from on each protocol",
            files.size >= 9,
        )
        val silent = files.mapNotNull { file ->
            val subtitle = SUBTITLE.find(file.readText())?.groupValues?.get(1)
                ?: return@mapNotNull "${file.parentFile.name}: no settings_conversation_subtitle"
            val missing = listOf("JMAP", "IMAP").filterNot { it in subtitle }
            if (missing.isEmpty()) null else "${file.parentFile.name}: does not name ${missing.joinToString(" nor ")}"
        }
        assertEquals(
            "the conversation subtitle must name BOTH protocols in every language: it is the whole " +
                "statement, and translation parity cannot see it because the key already exists " +
                "everywhere. Only the wording changed, and a locale can silently keep the old one.",
            emptyList<String>(), silent,
        )
    }

    // -- reading the files --------------------------------------------------------------------

    /**
     * The `SettingsSection` block that holds the Conversations settings, as text: from the line
     */
    private fun conversationSection(): String {
        val lines = codeLines(SETTINGS_SCREEN)
        val start = sectionStart(lines)
        val indent = lines[start].indentWidth()
        val out = mutableListOf(lines[start])
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            out += line
            if (line.indentWidth() <= indent) break
        }
        check(out.size > 2) {
            "the Conversations section reads as ${out.size} line(s) — the rules below would be " +
                "true of it by vacuity. Either the section really is empty, or its indentation " +
                "changed under this reader:\n" + out.joinToString("\n")
        }
        return out.joinToString("\n")
    }

    /**
     * The line that opens the block the section sits in — the nearest preceding code line indented
     */
    private fun enclosingLine(): String {
        val lines = codeLines(SETTINGS_SCREEN)
        val start = sectionStart(lines)
        val indent = lines[start].indentWidth()
        return (start - 1 downTo 0)
            .map { lines[it] }
            .firstOrNull { it.isNotBlank() && it.indentWidth() < indent }
            ?: error("the Conversations section is at the top level of SettingsScreen.kt — has the file moved?")
    }

    private fun sectionStart(lines: List<String>): Int {
        val start = lines.indexOfFirst { "R.string.settings_conversation_section" in it }
        check(start >= 0) {
            "SettingsScreen.kt no longer opens a section with R.string.settings_conversation_section " +
                "— was it renamed or moved? These rules must be moved with it."
        }
        return start
    }

    /** As in the sibling source lints: a line whose first non-blank character opens a comment is
     *  dropped whole, trailing comments are left in — a false match is a false failure, not a
     *  false pass. */
    private fun codeLines(file: File): List<String> = file.readLines().filterNot {
        val code = it.trimStart()
        code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
    }

    private fun stringFiles(): List<File> = (File(root, RES).listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private fun String.indentWidth() = length - trimStart().length

    private companion object {
        val SUBTITLE = Regex("<string name=\"settings_conversation_subtitle\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        const val RES = "app/src/main/res"
        const val SETTINGS_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources and resources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_SCREEN: File by lazy { File(root, SETTINGS_SCREEN_PATH) }
    }
}
