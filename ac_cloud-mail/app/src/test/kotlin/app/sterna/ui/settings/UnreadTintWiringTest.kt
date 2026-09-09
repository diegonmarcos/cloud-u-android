package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads four source files as text and proves nothing about
 */
class UnreadTintWiringTest {

    // -- 1. the default, in its three copies -----------------------------------------------------

    @Test fun `the repository states the default once, and it is on`() {
        assertEquals(
            "SettingsRepository.kt must declare 'const val UNREAD_TINT_DEFAULT = true' — the one " +
                "definition the other two copies read. The setting is announced as on by default; " +
                "flipped here, a fresh install shows no unread background at all.",
            listOf("const val UNREAD_TINT_DEFAULT = true"),
            codeLines(SETTINGS_REPOSITORY).filter { "UNREAD_TINT_DEFAULT =" in it },
        )
    }

    @Test fun `the activity's first frame reads the shared default`() {
        assertEquals(
            "MainActivity.kt must collect the setting as " +
                "'$EXPECTED_ACTIVITY_COLLECT' — the whole line. A literal here is a second copy of " +
                "the default: disagree with the repository and the list flashes the wrong " +
                "background on every launch, for as long as DataStore takes to answer.",
            listOf(EXPECTED_ACTIVITY_COLLECT),
            codeLines(MAIN_ACTIVITY).filter { it.startsWith("val unreadTint by") },
        )
        assertEquals(
            "MainActivity.kt must hand it to the list through " +
                "'$EXPECTED_ACTIVITY_PROVIDES' — collected and never provided, every row falls " +
                "back to the CompositionLocal's default and the switch does nothing.",
            listOf(EXPECTED_ACTIVITY_PROVIDES),
            // The import of the CompositionLocal names it too and is not a wiring line.
            codeLines(MAIN_ACTIVITY).filter { "LocalUnreadTint" in it && !it.startsWith("import ") },
        )
    }

    @Test fun `the CompositionLocal falls back to the shared default too`() {
        assertEquals(
            "LocalListDensity.kt must declare '$EXPECTED_LOCAL' — the whole line. This is the " +
                "third copy of the default and the one nothing else reaches: it is what every " +
                "composable outside MainActivity's provider gets.",
            listOf(EXPECTED_LOCAL),
            codeLines(LOCAL_LIST_DENSITY).filter { "LocalUnreadTint" in it },
        )
    }

    /** The negative screen — a `contains`, deliberately: it looks for what must be absent. */
    @Test fun `neither copy of the default is spelled out as a boolean`() {
        val offenders = listOf(MAIN_ACTIVITY, LOCAL_LIST_DENSITY).flatMap { file ->
            codeLines(file)
                .filter { "unreadTint" in it || "LocalUnreadTint" in it }
                .filter { Regex("""\b(true|false)\b""").containsMatchIn(it) }
                .map { "${file.name}: $it" }
        }
        assertEquals(
            "no line about this setting in MainActivity.kt or LocalListDensity.kt may write 'true' " +
                "or 'false': both must read UNREAD_TINT_DEFAULT. A literal that agrees with the " +
                "repository today is the one that silently disagrees with it tomorrow, and no " +
                "value test can see either copy. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `the settings screen's first frame reads it as well`() {
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "val unreadTint = settings.unreadTint.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = UNREAD_TINT_DEFAULT,",
                ")",
            ),
            "the view model's unreadTint state",
        )
    }

    // -- 2 & 3. the switch on the Appearance screen ----------------------------------------------

    @Test fun `the message list section carries the switch, with its own two strings`() {
        val section = messageListSection()
        val expected = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_unread_tint_title),",
            "subtitle = stringResource(R.string.settings_unread_tint_subtitle),",
            "checked = unreadTint,",
            "onCheckedChange = viewModel::setUnreadTint,",
            ")",
        )
        val at = section.indexOfFirst { it == expected.first() }
        assertTrue(
            "the 'Message list' section of AppearanceScreen must contain a SettingSwitch — the " +
                "setting is otherwise stored, read and unreachable. Section was:\n" +
                section.joinToString("\n"),
            at >= 0,
        )
        val found = section.subList(at, minOf(at + expected.size, section.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of the switch: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "the switch is pinned WHOLE, line by line and in order: its two string keys (a row " +
                "labelled with the neighbouring setting's text is a lie the parity test cannot " +
                "see), the state it shows, and the setter it calls. Mismatches:\n" +
                mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    @Test fun `the switch is neither greyed out nor hidden behind a condition`() {
        val section = messageListSection()
        assertTrue(
            "the 'Message list' section must carry no 'enabled =' argument and no 'if (': a " +
                "switch dimmed or hidden while the value it stands for is still in force is the " +
                "WYSIWYG lie SettingsScreenHonestyTest was written for. Section was:\n" +
                section.joinToString("\n"),
            section.none { "enabled =" in it } && section.none { "if (" in it },
        )
    }

    @Test fun `the screen collects the setting from the view model`() {
        assertEquals(
            "AppearanceScreen must read the state through " +
                "'val unreadTint by viewModel.unreadTint.collectAsStateWithLifecycle()' — the " +
                "whole line, like every other setting on the screen.",
            listOf("val unreadTint by viewModel.unreadTint.collectAsStateWithLifecycle()"),
            codeLines(SETTINGS_SCREEN).filter { it.startsWith("val unreadTint by") },
        )
    }

    // -- 4. the backup ---------------------------------------------------------------------------

    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = codeLines(SETTINGS_REPOSITORY)
        assertEquals(
            "snapshotBackup must carry 'unreadTint = unreadTint.first(),' — without it the switch " +
                "is absent from every export, and a restore on a new device silently turns the " +
                "tint back on for a reader who had removed it.",
            listOf("unreadTint = unreadTint.first(),"),
            repository.filter { it.startsWith("unreadTint = unreadTint") },
        )
        assertEquals(
            "restoreBackup must apply it: 'backup.unreadTint?.let { setUnreadTint(it) }'. " +
                "Exported and never read back is the same defect one step later. The '?.let' is " +
                "the part that matters: an absent field means 'leave as is', not 'off'.",
            listOf("backup.unreadTint?.let { setUnreadTint(it) }"),
            repository.filter { it.startsWith("backup.unreadTint") },
        )
    }

    // -- reading the sources ---------------------------------------------------------------------

    /**
     * The body of `SettingsSection(stringResource(R.string.settings_message_list_section)) {`, and
     */
    private fun messageListSection(): List<String> {
        val lines = codeLines(SETTINGS_SCREEN)
        val opener = "SettingsSection(stringResource(R.string.settings_message_list_section)) {"
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
        error("the 'Message list' section is never closed in SettingsScreen.kt")
    }

    /** Locates [expected]'s first line in [file] and compares the block that follows it, whole. */
    private fun assertBlock(file: File, expected: List<String>, what: String) {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { it == expected.first() }
        check(at >= 0) { "no '${expected.first()}' in ${file.name} — $what is gone" }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned WHOLE, line by line and in order. Nothing in this repo executes " +
                "these lines. Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        const val EXPECTED_ACTIVITY_COLLECT =
            "val unreadTint by settings.unreadTint.collectAsState(initial = UNREAD_TINT_DEFAULT)"
        const val EXPECTED_ACTIVITY_PROVIDES = "LocalUnreadTint provides unreadTint,"
        const val EXPECTED_LOCAL = "val LocalUnreadTint = compositionLocalOf { UNREAD_TINT_DEFAULT }"

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
        val MAIN_ACTIVITY: File by lazy { File(root, "app/src/main/kotlin/app/sterna/MainActivity.kt") }
        val LOCAL_LIST_DENSITY: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/components/LocalListDensity.kt")
        }
        val SETTINGS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        }
        val SETTINGS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt")
        }
    }
}
