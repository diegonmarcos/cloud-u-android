package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads five source files as text and proves nothing about
 * what is drawn. Same instrument and disclaimer as [UnreadTintWiringTest] and [PureBlackWiringTest].
 *
 * `SettingsBackupListMonogramTest` runs the decision (the default, the key, the backup field).
 * Nothing here can run the PLUMBING: `MainActivity`, the CompositionLocal, the view model, the
 * Appearance screen and the row itself are all Compose or Android code, and this module has no
 * Robolectric, no `compose-ui-test`, no `androidTest` — adding one is a dependency decision, not a
 * test decision. Five silent edits this file exists to catch:
 *
 * 1. **the guard at the row itself inverted, dropped, or leaving the `Spacer` outside it.**
 *     Nothing in this repo executes `EmailListItem`, so `if (!LocalListMonogram.current)` hides the
 *     initials for everyone who asked to keep them, and a `Spacer` left outside the braces adds its
 *     12 dp to the row's own `padding(start = 16.dp)` and leaves a dead band at the start of every
 *     row when the setting is off — the one visual trap of this change;
 *  2. **the default written out as a literal.** It exists in THREE copies — the repository, the
 *     activity's `initial =` and the CompositionLocal's fallback. A `false` in either of the last
 *     two strips the initials off the list for the first frames after launch (or for good, under
 *     any provider-less preview) while every value test still reads `LIST_MONOGRAM_DEFAULT`;
 *  3. the setting collected in the activity and never provided: every row falls back to the
 *     CompositionLocal and the switch does nothing;
 *  4. the switch missing from the Appearance screen, greyed out, hidden behind an `if`, or wired to
 *     the wrong string keys — a row that says "Preview" and toggles the initials;
 *  5. the setting missing from the backup snapshot or the restore: it exports as if it did not
 *     exist, and an import silently puts the initials back for someone who had removed them.
 *
 * Every rule compares a WHOLE line (leading and trailing blanks removed, nothing else), never a
 * `contains` for something it expects to find: a substring check is blind to any mutation that
 * LENGTHENS the line — `initial = LIST_MONOGRAM_DEFAULT && false` contains the constant. `contains`
 * appears only where this file screens for something that must NOT be there.
 */
class ListMonogramWiringTest {

    // -- 1. the row itself ---------------------------------------------------------------------

    /**
     * The only observer of the guard and of the `Spacer`'s position. `EmailListItem` is a
     * `@Composable`: no test in this repo calls it, so an inverted condition or a `Spacer` moved one
     * line down compiles, ships, and leaves the whole suite green.
     */
    @Test fun `the row draws the initials only when the setting says so, spacer included`() {
        assertBlock(
            EMAIL_LIST_ITEM,
            EXPECTED_ROW_BLOCK,
            "the message row's monogram block",
            "the initials would be shown to a reader who turned them off (or hidden from one who " +
                "kept them), or the 12 dp Spacer would be left outside the guard and add itself to " +
                "the row's start padding, leaving a dead band at the start of every row",
        )
    }

    /**
     * The block above is anchored on the `if` and compared FORWARDS only, so it says nothing about
     * what sits just BEFORE the guard. A second `Spacer(Modifier.width(12.dp))` inserted there is
     * invisible to it and puts 28 dp of nothing at the start of every row once the initials are off
     * — the very dead band this file claims to catch. The row has exactly one 12 dp spacer, and it
     * is inside the guard.
     */
    @Test fun `the row has exactly one twelve-dp spacer, the one inside the guard`() {
        val spacers = codeLines(EMAIL_LIST_ITEM).filter { it == "Spacer(Modifier.width(12.dp))" }
        assertEquals(
            "EmailListItem.kt must contain 'Spacer(Modifier.width(12.dp))' exactly once — the one " +
                "the guard above pins INSIDE the condition. A second one, in particular just " +
                "before the 'if', survives the block rule (which only compares forwards) and leaves " +
                "12 dp of nothing at the start of every row when the initials are off, on top of " +
                "the Row's own padding(start = 16.dp): a 28 dp empty band down the whole list. " +
                "Found ${spacers.size}.",
            1,
            spacers.size,
        )
    }

    // -- 2 & 3. the default, in its three copies, and the provider --------------------------------

    @Test fun `the repository states the default once, and it is on`() {
        assertEquals(
            "SettingsRepository.kt must declare 'const val LIST_MONOGRAM_DEFAULT = true' — the one " +
                "definition the other two copies read. Flipped here, the update that ships this " +
                "setting takes the initials away from every user without anyone asking.",
            listOf("const val LIST_MONOGRAM_DEFAULT = true"),
            codeLines(SETTINGS_REPOSITORY).filter { "LIST_MONOGRAM_DEFAULT =" in it },
        )
    }

    @Test fun `the activity's first frame reads the shared default`() {
        assertEquals(
            "MainActivity.kt must collect the setting as '$EXPECTED_ACTIVITY_COLLECT' — the whole " +
                "line. A literal here is a second copy of the default: disagree with the " +
                "repository and every launch flashes a list with the wrong start column, for as " +
                "long as DataStore takes to answer.",
            listOf(EXPECTED_ACTIVITY_COLLECT),
            codeLines(MAIN_ACTIVITY).filter { it.startsWith("val listMonogram by") },
        )
        assertEquals(
            "MainActivity.kt must hand it to the list through '$EXPECTED_ACTIVITY_PROVIDES' — " +
                "collected and never provided, every row falls back to the CompositionLocal's " +
                "default and the switch does nothing at all.",
            listOf(EXPECTED_ACTIVITY_PROVIDES),
            // The import of the CompositionLocal names it too and is not a wiring line.
            codeLines(MAIN_ACTIVITY).filter { "LocalListMonogram" in it && !it.startsWith("import ") },
        )
    }

    @Test fun `the CompositionLocal falls back to the shared default too`() {
        assertEquals(
            "LocalListDensity.kt must declare '$EXPECTED_LOCAL' — the whole line. This is the " +
                "third copy of the default and the one nothing else reaches: it is what every " +
                "composable outside MainActivity's provider gets.",
            listOf(EXPECTED_LOCAL),
            codeLines(LOCAL_LIST_DENSITY).filter { "LocalListMonogram" in it },
        )
    }

    /** The negative screen — a `contains`, deliberately: it looks for what must be absent. */
    @Test fun `neither copy of the default is spelled out as a boolean`() {
        val offenders = listOf(MAIN_ACTIVITY, LOCAL_LIST_DENSITY).flatMap { file ->
            codeLines(file)
                .filter { "listMonogram" in it || "LocalListMonogram" in it || "LIST_MONOGRAM" in it }
                .filter { Regex("""\b(true|false)\b""").containsMatchIn(it) }
                .map { "${file.name}: $it" }
        }
        assertEquals(
            "no line about this setting in MainActivity.kt or LocalListDensity.kt may write 'true' " +
                "or 'false': both must read LIST_MONOGRAM_DEFAULT. A literal that agrees with the " +
                "repository today is the one that silently disagrees with it tomorrow, and no " +
                "value test can see either copy. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `the settings screen's first frame reads the shared default too`() {
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "val listMonogram = settings.listMonogram.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = LIST_MONOGRAM_DEFAULT,",
                ")",
            ),
            "the view model's listMonogram state",
            "the switch would show the wrong position for the first frames after the Appearance " +
                "screen opens, and a literal here is a fourth copy of the default",
        )
    }

    // -- 4. the switch on the Appearance screen ---------------------------------------------------

    @Test fun `the message list section carries the switch, with its own two strings`() {
        val section = messageListSection()
        val at = switchAt(section)
        assertTrue(
            "the 'Message list' section of AppearanceScreen must contain a SettingSwitch reading " +
                "settings_list_monogram_* — the setting is otherwise stored, read and unreachable. " +
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
        val section = messageListSection()
        val start = switchAt(section)
        check(start >= 0) { "no sender-initials switch in the Message list section of SettingsScreen.kt" }
        val block = section.subList(start, minOf(start + EXPECTED_SWITCH.size, section.size))
        assertTrue(
            "the sender-initials switch must carry no 'enabled =' argument and no 'if (': a switch " +
                "dimmed or hidden while the value it stands for is still in force is the WYSIWYG " +
                "lie SettingsScreenHonestyTest was written for. Block was:\n" +
                block.joinToString("\n"),
            block.none { "enabled =" in it } && block.none { "if (" in it },
        )
        // The block above is the switch's own six lines; a condition WRAPPING it sits outside them
        // and would hide the row entirely on some devices with every rule here still green — the
        // defect PureBlackWiringTest was written for after the Android-12 guard swallowed a switch.
        // Same instrument as SettingsScreenHonestyTest.enclosingLine().
        val enclosing = enclosingLineOfSwitch()
        assertTrue(
            "the line that WRAPS the sender-initials switch must not be a condition: nested in " +
                "one, the row disappears from Appearance for whoever fails it and the setting " +
                "becomes unreachable while the value it stands for is still in force. Enclosing " +
                "line was:\n$enclosing",
            "if (" !in enclosing && "when (" !in enclosing,
        )
    }

    @Test fun `the screen collects the setting from the view model`() {
        assertEquals(
            "AppearanceScreen must read the state through " +
                "'val listMonogram by viewModel.listMonogram.collectAsStateWithLifecycle()' — the " +
                "whole line, like every other setting on the screen.",
            listOf("val listMonogram by viewModel.listMonogram.collectAsStateWithLifecycle()"),
            codeLines(SETTINGS_SCREEN).filter { it.startsWith("val listMonogram by") },
        )
    }

    // -- the write path ---------------------------------------------------------------------------

    /**
     * The half of the setting no other test here touches: **storing** what the user chose. Nothing
     * executes `setListMonogram`, so writing a constant instead of the argument leaves the feature
     * dead — the switch flips, then springs back on the next emission — with the suite green.
     */
    /**
     * The single line that connects the stored key to the rest of the app, and the one every
     * other rule in this file walks past: it starts with `val listMonogram:`, carries no
     * `LIST_MONOGRAM_DEFAULT =`, no `it[KEY_LIST_MONOGRAM]` and none of the backup prefixes. Nothing
     * in this repo builds a `SettingsRepository`, so nothing executes it either.
     *
     * The mistake the shape of the code invites is a copy-paste: three near-identical blocks that
     * differ by one identifier, and `map(::unreadTintFrom)` compiles perfectly here. The setting is
     * then inert AND parasitic — the reader turns the initials off, the switch springs back on the
     * next emission, and turning the unread background off takes the initials away with it.
     */
    @Test fun `the flow is fed by this setting's own decision function`() {
        assertEquals(
            "SettingsRepository.kt must expose the setting as " +
                "'val listMonogram: Flow<Boolean> = dataStore.data.map(::listMonogramFrom)' — the " +
                "whole line, the function reference included. It is the ONLY link between the " +
                "stored key and the app, and no test builds a SettingsRepository. A neighbouring " +
                "decision function here (::unreadTintFrom is one identifier away) leaves the switch " +
                "flipping back by itself, and makes the unread-background switch hide the initials.",
            listOf("val listMonogram: Flow<Boolean> = dataStore.data.map(::listMonogramFrom)"),
            codeLines(SETTINGS_REPOSITORY).filter { it.startsWith("val listMonogram") },
        )
    }

    @Test fun `flipping the switch stores what the user chose, not a constant`() {
        assertEquals(
            "SettingsRepository.kt must write the argument: 'dataStore.edit { it[KEY_LIST_MONOGRAM] " +
                "= enabled }'. A constant here — or the negation of the argument — makes the " +
                "setting unsettable: it flips on screen and springs back.",
            listOf("dataStore.edit { it[KEY_LIST_MONOGRAM] = enabled }"),
            codeLines(SETTINGS_REPOSITORY).filter { "it[KEY_LIST_MONOGRAM]" in it },
        )
        assertEquals(
            "SettingsViewModel.kt must pass the argument through: " +
                "'settings.setListMonogram(enabled)'. Same defect one step earlier, and just as " +
                "invisible from here. ⚠ The call no longer sits alone on its `viewModelScope.launch " +
                "{ … }` line: the write is now awaited and followed by the home-screen redraw " +
                "(#112), whose whole body — the order included — is pinned by " +
                "RecentMailWidgetWiringLintTest.",
            listOf("settings.setListMonogram(enabled)"),
            codeLines(SETTINGS_VIEW_MODEL).filter { "settings.setListMonogram(" in it },
        )
    }

    // -- 5. the backup ----------------------------------------------------------------------------

    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = codeLines(SETTINGS_REPOSITORY)
        assertEquals(
            "snapshotBackup must carry 'listMonogram = listMonogram.first(),' — without it the " +
                "switch is absent from every export, and a restore on a new device silently puts " +
                "the initials back for a reader who had removed them.",
            listOf("listMonogram = listMonogram.first(),"),
            repository.filter { it.startsWith("listMonogram = listMonogram") },
        )
        assertEquals(
            "restoreBackup must apply it: 'backup.listMonogram?.let { setListMonogram(it) }'. " +
                "Exported and never read back is the same defect one step later. The '?.let' is " +
                "the part that matters: an absent field means 'leave as is', not 'off'.",
            listOf("backup.listMonogram?.let { setListMonogram(it) }"),
            repository.filter { it.startsWith("backup.listMonogram") },
        )
    }

    // -- reading the sources ----------------------------------------------------------------------

    /**
     * The nearest enclosing line of the sender-initials switch — the first line above it indented
     * LESS than it is. Copied from [SettingsScreenHonestyTest]'s `enclosingLine()`, indentation and
     * all, which is why it reads the file again unTRIMMED: the rest of this class compares trimmed
     * lines, and indentation is the only thing that says what wraps what.
     */
    private fun enclosingLineOfSwitch(): String {
        val lines = EMAIL_SETTINGS_SCREEN_RAW
        val title = lines.indexOfFirst { it.trim() == EXPECTED_SWITCH[1] }
        check(title >= 0) {
            "no '${EXPECTED_SWITCH[1]}' in SettingsScreen.kt — the sender-initials switch is gone, " +
                "and this rule must be taught the new shape rather than left green over nothing"
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

    /**
     * The body of `SettingsSection(stringResource(R.string.settings_message_list_section)) {`, and
     * only it: the block is closed by counting braces, not by waiting for the next section (see
     * [UnreadTintWiringTest] for the section that swallowed three hundred unrelated lines).
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
        const val EXPECTED_ACTIVITY_COLLECT =
            "val listMonogram by settings.listMonogram.collectAsState(initial = LIST_MONOGRAM_DEFAULT)"
        const val EXPECTED_ACTIVITY_PROVIDES = "LocalListMonogram provides listMonogram,"
        const val EXPECTED_LOCAL =
            "val LocalListMonogram = compositionLocalOf { LIST_MONOGRAM_DEFAULT }"

        /** The guard, the monogram, the spacer, the closing brace — in this order, or not at all. */
        val EXPECTED_ROW_BLOCK = listOf(
            "if (LocalListMonogram.current) {",
            "Monogram(",
            "seed = (recipient ?: email.from.firstOrNull())?.email ?: senderName,",
            "label = recipient?.display() ?: senderName,",
            ")",
            "Spacer(Modifier.width(12.dp))",
            "}",
        )

        val EXPECTED_SWITCH = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_list_monogram_title),",
            "subtitle = stringResource(R.string.settings_list_monogram_subtitle),",
            "checked = listMonogram,",
            "onCheckedChange = viewModel::setListMonogram,",
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
        val MAIN_ACTIVITY: File by lazy { File(root, "app/src/main/kotlin/app/sterna/MainActivity.kt") }
        val LOCAL_LIST_DENSITY: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/components/LocalListDensity.kt")
        }
        val EMAIL_LIST_ITEM: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt")
        }
        val SETTINGS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        }
        val SETTINGS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt")
        }

        /** SettingsScreen.kt with its indentation intact — see `enclosingLineOfSwitch`. Comment-only
         *  lines are dropped whole, as in the sibling lints, so prose can never wrap anything. */
        val EMAIL_SETTINGS_SCREEN_RAW: List<String> by lazy {
            SETTINGS_SCREEN.readLines().filterNot {
                val code = it.trimStart()
                code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
            }
        }
    }
}
