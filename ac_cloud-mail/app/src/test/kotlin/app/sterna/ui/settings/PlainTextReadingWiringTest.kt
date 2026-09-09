package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads three source files as text and proves nothing about
 */
class PlainTextReadingWiringTest {

    // -- the stored setting -----------------------------------------------------------------------

    @Test fun `the repository states the default once, and messages open as HTML`() {
        assertEquals(
            "SettingsRepository.kt must declare 'const val PLAIN_TEXT_DEFAULT = false' — the one " +
                "definition the other copies read. Flipped here, the update that ships this setting " +
                "flattens every message on every device without anyone asking.",
            listOf("const val PLAIN_TEXT_DEFAULT = false"),
            codeLines(SETTINGS_REPOSITORY).filter { "PLAIN_TEXT_DEFAULT =" in it },
        )
    }

    @Test fun `the flow is fed by this setting's own decision function`() {
        assertEquals(
            "SettingsRepository.kt must expose the setting as " +
                "'val plainText: Flow<Boolean> = dataStore.data.map(::plainTextFrom)' — the whole " +
                "line, the function reference included. It is the ONLY link between the stored key " +
                "and the app, and no test builds a SettingsRepository. A neighbouring decision " +
                "function here (::replyBarFrom is one identifier away) leaves the switch flipping " +
                "back by itself, and makes the reply-bar switch decide how messages are rendered.",
            listOf("val plainText: Flow<Boolean> = dataStore.data.map(::plainTextFrom)"),
            codeLines(SETTINGS_REPOSITORY).filter { it.startsWith("val plainText") },
        )
    }

    @Test fun `flipping the switch stores what the user chose, not a constant`() {
        assertEquals(
            "SettingsRepository.kt must write the argument: " +
                "'dataStore.edit { it[KEY_PLAIN_TEXT] = enabled }'. A constant here — or the " +
                "negation of the argument — makes the setting unsettable: it flips on screen and " +
                "springs back on the next emission.",
            listOf("dataStore.edit { it[KEY_PLAIN_TEXT] = enabled }"),
            codeLines(SETTINGS_REPOSITORY).filter { "it[KEY_PLAIN_TEXT]" in it },
        )
        assertEquals(
            "SettingsViewModel.kt must pass the argument through: " +
                "'viewModelScope.launch { settings.setPlainText(enabled) }'. Same defect one step " +
                "earlier, and just as invisible from here.",
            listOf("viewModelScope.launch { settings.setPlainText(enabled) }"),
            codeLines(SETTINGS_VIEW_MODEL).filter { "settings.setPlainText(" in it },
        )
    }

    // -- the backup, both ways ------------------------------------------------------------------

    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = codeLines(SETTINGS_REPOSITORY)
        assertEquals(
            "snapshotBackup must carry 'plainText = plainText.first(),' — without it the switch is " +
                "absent from every export, and the setting is lost the moment someone moves device.",
            listOf("plainText = plainText.first(),"),
            repository.filter { it.startsWith("plainText = plainText") },
        )
        assertEquals(
            "restoreBackup must apply it: 'backup.plainText?.let { setPlainText(it) }'. Exported " +
                "and never read back is the same defect one step later: re-importing one's own " +
                "settings file silently turns the reading mode off. The '?.let' is the part that " +
                "matters — an absent field means 'leave as is', not 'off'.",
            listOf("backup.plainText?.let { setPlainText(it) }"),
            repository.filter { it.startsWith("backup.plainText") },
        )
    }

    // -- the settings screen ----------------------------------------------------------------------

    @Test fun `the settings screen's first frame reads the shared default`() {
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "val plainText = settings.plainText.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = PLAIN_TEXT_DEFAULT,",
                ")",
            ),
            "the view model's plainText state",
            "the switch would show the wrong position for the first frames after the Reading screen " +
                "opens, and a literal here is a second copy of the default",
        )
    }

    @Test fun `the message section carries the switch, with its own two strings`() {
        val section = messageSection()
        val at = switchAt(section)
        assertTrue(
            "the 'Message' section of the Reading screen must contain a SettingSwitch reading " +
                "settings_plain_text_* — the setting is otherwise stored, read and unreachable. " +
                "Section was:\n" + section.joinToString("\n"),
            at >= 0,
        )
        val found = section.subList(at, section.size)
        val mismatches = EXPECTED_SWITCH.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == EXPECTED_SWITCH[i]) null
            else "line ${i + 1} of the switch: expected '${EXPECTED_SWITCH[i]}' but found '$actual'"
        }
        assertEquals(
            "the switch is pinned WHOLE, line by line and in order: its two string keys (a row " +
                "labelled with the neighbouring setting's text is a lie the parity test cannot " +
                "see), the state it shows, and the setter it calls. Mismatches:\n" +
                mismatches.joinToString("\n") + "\nat index $at",
            emptyList<String>(),
            mismatches,
        )
    }

    /** The negative screen for the switch itself: nothing may dim it or make it conditional. */
    @Test fun `the switch is neither greyed out nor hidden behind a condition`() {
        val section = messageSection()
        val start = switchAt(section)
        check(start >= 0) { "no reading-mode switch in the Message section of SettingsScreen.kt" }
        val block = section.subList(start, minOf(start + EXPECTED_SWITCH.size, section.size))
        assertTrue(
            "the reading-mode switch must carry no 'enabled =' argument and no 'if (': a switch " +
                "dimmed or hidden while the value it stands for is still in force is the WYSIWYG " +
                "lie SettingsScreenHonestyTest was written for. Block was:\n" +
                block.joinToString("\n"),
            block.none { "enabled =" in it } && block.none { "if (" in it },
        )
        // A condition WRAPPING the switch sits outside those six lines and would hide the row
        // entirely with every rule above still green — the defect PureBlackWiringTest was written
        // for after the Android-12 guard swallowed a switch.
        val enclosing = enclosingLineOfSwitch()
        assertTrue(
            "the line that WRAPS the reading-mode switch must not be a condition: nested in one, " +
                "the row disappears from Settings for whoever fails it and the setting becomes " +
                "unreachable while the value it stands for is still in force. Enclosing line was:\n" +
                enclosing,
            "if (" !in enclosing && "when (" !in enclosing,
        )
    }

    @Test fun `the screen collects the setting from the view model`() {
        assertEquals(
            "the Reading screen must read the state through " +
                "'val plainText by viewModel.plainText.collectAsStateWithLifecycle()' — the whole " +
                "line, like every other setting on the screen.",
            listOf("val plainText by viewModel.plainText.collectAsStateWithLifecycle()"),
            codeLines(SETTINGS_SCREEN).filter { it.startsWith("val plainText") },
        )
    }

    // -- reading the sources ----------------------------------------------------------------------

    /** The nearest enclosing line of the switch — the first line above it indented LESS than it is.
     *  Reads the file unTRIMMED, since indentation is the only thing that says what wraps what. */
    private fun enclosingLineOfSwitch(): String {
        val lines = SETTINGS_SCREEN_RAW
        val title = lines.indexOfFirst { it.trim() == EXPECTED_SWITCH[1] }
        check(title >= 0) {
            "no '${EXPECTED_SWITCH[1]}' in SettingsScreen.kt — the reading-mode switch is gone, and " +
                "this rule must be taught the new shape rather than left green over nothing"
        }
        val start = (title - 1 downTo 0).first { lines[it].trim() == "SettingSwitch(" }
        val indent = lines[start].indentWidth()
        return (start - 1 downTo 0)
            .map { lines[it] }
            .firstOrNull { it.isNotBlank() && it.indentWidth() < indent }
            ?: error("the switch is at the top level of SettingsScreen.kt — has the file moved?")
    }

    private fun String.indentWidth() = length - trimStart().length

    /** Where the pinned switch starts inside the section body, found by its own title string. */
    private fun switchAt(section: List<String>): Int =
        section.indexOfFirst { it == EXPECTED_SWITCH[1] }.let { if (it <= 0) -1 else it - 1 }

    /** The body of the Reading screen's `Message` section, closed by counting braces. */
    private fun messageSection(): List<String> {
        val lines = codeLines(SETTINGS_SCREEN)
        val opener = "SettingsSection(stringResource(R.string.settings_message_section)) {"
        val at = lines.indexOfFirst { it == opener }
        check(at >= 0) {
            "no '$opener' in SettingsScreen.kt — the section moved or was reshaped, and this lint " +
                "must be taught the new shape rather than left green over a section it never read"
        }
        var depth = 1
        val body = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return body
            body += line
        }
        error("the 'Message' section is never closed in SettingsScreen.kt")
    }

    /** Locates [expected]'s first line in [file] and compares the block that follows it, whole. */
    private fun assertBlock(file: File, expected: List<String>, what: String, cost: String) {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { it == expected.first() }
        check(at >= 0) {
            "no '${expected.first()}' in ${file.name} — $what is gone, and with it the guard. " +
                "What the user would see: $cost"
        }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned WHOLE, line by line and in order — nothing in this repo executes " +
                "these lines. What the user would see if this drifted: $cost. Mismatches:\n" +
                mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        val EXPECTED_SWITCH = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_plain_text_title),",
            "subtitle = stringResource(R.string.settings_plain_text_subtitle),",
            "checked = plainText,",
            "onCheckedChange = viewModel::setPlainText,",
            ")",
        )

        private const val SETTINGS_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_REPOSITORY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this lint reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_REPOSITORY: File by lazy { File(root, SETTINGS_REPOSITORY_PATH) }
        val SETTINGS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        }
        val SETTINGS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt")
        }

        /** SettingsScreen.kt with its indentation intact — see `enclosingLineOfSwitch`. */
        val SETTINGS_SCREEN_RAW: List<String> by lazy {
            SETTINGS_SCREEN.readLines().filterNot {
                val code = it.trimStart()
                code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
            }
        }
    }
}
