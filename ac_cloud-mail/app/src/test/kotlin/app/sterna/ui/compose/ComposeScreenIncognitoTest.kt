package app.sterna.ui.compose

import app.sterna.ui.NavHostSourceRulesTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class ComposeScreenIncognitoTest {

    private val composeScreen: List<String> by lazy { source(COMPOSER).readLines() }
    private val wrapperFile: List<String> by lazy { source(WRAPPER_FILE).readLines() }

    // --- placement: not less ---------------------------------------------------------------------

    @Test fun `every call site of the composer is wrapped`() {
        val calls = callSites()
        // Not vacuous: the composer really is mounted, once, from the navigation graph. If this
        // number moves, the new call site has to be looked at rather than assumed covered.
        assertEquals("expected exactly one call site of ComposeScreen(, found $calls", 1, calls.size)
        val unwrapped = calls.filterNot { (file, line) ->
            wrapperBlocks(source(file).readLines()).any { line in it }
        }
        assertTrue(
            "ComposeScreen is mounted outside $WRAPPER at ${unwrapped.map { (f, l) -> "$f:${l + 1}" }} " +
                "— a composer whose keyboard is never asked to forget (#120)",
            unwrapped.isEmpty(),
        )
    }

    // --- placement: nor more ---------------------------------------------------------------------

    @Test fun `the wrapper is used exactly once in the whole application`() {
        // Counted over every main source, so wrapping SternaApp in MainActivity — which would make
        // search, settings and account setup incognito without touching a line of this package —
        // is a failure here and not a silent widening.
        val uses = wrapperUses().map { (file, line) -> "$file:${line + 1}" }
        assertEquals(
            "$WRAPPER must appear exactly ONCE in app/src/main. Found: $uses. More than one means " +
                "the request is being made somewhere it was not promised (#120)",
            1,
            uses.size,
        )
    }

    @Test fun `the one wrapper wraps the composer and nothing else`() {
        val (file, line) = wrapperUses().single()
        val lines = source(file).readLines()
        val block = blockFrom(lines, line)
        assertTrue(
            "$file:${line + 1} does not wrap the composer",
            block.any { "$COMPOSER_FUN(" in lines[it] },
        )
        // The witnesses, named one by one: these are the screens the composer's rule must NOT
        // reach. Search is the one the bench check leans on.
        val caught = OFF_LIMITS.filter { screen -> block.any { screen in lines[it] } }
        assertTrue(
            "$file:${line + 1} also wraps $caught — the keyboard would stop learning outside the " +
                "composer, which is more than #120 asked for and breaks the search witness",
            caught.isEmpty(),
        )
        assertEquals(
            "the wrapper block spans ${block.count { "Screen(" in lines[it] }} screens; it must span " +
                "the composer alone",
            1,
            block.count { "Screen(" in lines[it] },
        )
    }

    // --- the composer's file composes nothing outside the composer -------------------------------

    @Test fun `every input of the composer's file belongs to the composer`() {
        // Not vacuous: the composer really does have five fields today (To/Cc/Bcc share one
        // composable), at three call sites.
        assertEquals(
            "ComposeScreen.kt no longer has the 3 BasicTextField call sites this rule was written " +
                "for — re-read it before trusting this test",
            3,
            composeScreen.count { FIELD in it },
        )
        val stray = fieldsOutside(composeScreen, COMPOSER_FUN)
        assertTrue(
            "input(s) in ComposeScreen.kt composed by something other than ComposeScreen — they are " +
                "outside the wrapped subtree and a keyboard will learn from them (#120). Line(s): $stray",
            stray.isEmpty(),
        )
    }

    @Test fun `the field-bearing helpers are the composer's own`() {
        // Pins WHICH composables the guarantee reaches, not merely that some set is reached: the
        // subject and the recipient rows live in private composables, covered only because
        // ComposeScreen is what calls them.
        val reached = reachableFrom(composeScreen, COMPOSER_FUN)
        assertTrue("ComposeField is not called by ComposeScreen: $reached", "ComposeField" in reached)
        assertTrue("RecipientChipsField is not called by ComposeScreen: $reached", "RecipientChipsField" in reached)
    }

    // --- the one line of the wrapper a test cannot execute ---------------------------------------

    @Test fun `the delegate fills the EditorInfo before the flag is added`() {
        // ORDER IS THE WHOLE CORRECTNESS of createInputConnection: the wrapped request WRITES
        // outAttributes.imeOptions. Add the bit first and the delegate overwrites it — a fix that
        // runs and does nothing. It cannot be executed: calling createInputConnection needs a real
        // EditorInfo and this module mocks no framework class.
        //
        // Comments are stripped and each anchor is required to be UNIQUE before the two are
        // compared: otherwise a comment quoting either line — this file's own explanations do —
        // would move the anchor and let the inversion back in.
        val code = wrapperFile.filterNot { it.isComment() }
        val delegate = code.indicesOf(DELEGATE_CALL)
        val flag = code.indicesOf(FLAG_ASSIGNMENT)
        assertEquals("expected one un-commented '$DELEGATE_CALL', found ${delegate.size}", 1, delegate.size)
        assertEquals("expected one un-commented '$FLAG_ASSIGNMENT', found ${flag.size}", 1, flag.size)
        assertTrue(
            "the flag is added before the delegate fills the EditorInfo — the delegate will " +
                "overwrite it (code lines ${flag.single()} vs ${delegate.single()})",
            delegate.single() < flag.single(),
        )
    }

    @Test fun `the flag is applied to the EditorInfo the field just filled in`() {
        // The arguments are the point: `outAttributes.imeOptions` on both sides of the assignment.
        assertTrue(
            "IncognitoKeyboard.kt must feed the field's own imeOptions back through the decision",
            wrapperFile.any { !it.isComment() && FLAG_ASSIGNMENT in it },
        )
        assertTrue(
            "IncognitoKeyboard.kt must install the interceptor over its content",
            wrapperFile.any { !it.isComment() && "InterceptPlatformTextInput(INCOGNITO_INTERCEPTOR, content)" in it },
        )
    }

    // --- the lints' own decisions, run against inputs where the answer is known -------------------
    //
    // Without these, this file could only ever report "green" and there would be no evidence it
    // can say anything else.

    @Test fun `a helper called from elsewhere is reported`() {
        assertEquals(listOf(9), fieldsOutside(FIXTURE_HELPER_OF_ANOTHER_SCREEN, COMPOSER_FUN))
    }

    @Test fun `a second screen with a field in the same file is reported`() {
        assertEquals(listOf(8), fieldsOutside(FIXTURE_SECOND_SCREEN, COMPOSER_FUN))
    }

    @Test fun `a field in a dialog of the composer is covered`() {
        // The gain of wrapping the call site rather than the Scaffold: a dialog composed by
        // ComposeScreen is inside the subtree, so a field in one would be covered.
        assertEquals(emptyList<Int>(), fieldsOutside(FIXTURE_FIELD_IN_A_DIALOG, COMPOSER_FUN))
    }

    @Test fun `the shape the composer actually has is accepted`() {
        assertEquals(emptyList<Int>(), fieldsOutside(FIXTURE_COVERED, COMPOSER_FUN))
    }

    @Test fun `an unwrapped call site is reported`() {
        assertTrue(wrapperBlocks(FIXTURE_ROUTE_UNWRAPPED).none { 1 in it })
    }

    @Test fun `a wrapped call site is accepted`() {
        assertTrue(wrapperBlocks(FIXTURE_ROUTE_WRAPPED).any { 2 in it })
    }

    @Test fun `a second wrapper in the same file is seen`() {
        // What indexOfFirst used to miss: a wrapper added AFTER the composer's one, in the very
        // file the rule reads.
        assertEquals(2, wrapperBlocks(FIXTURE_TWO_WRAPPERS).size)
    }

    @Test fun `a wrapper that spans another screen is reported`() {
        val block = wrapperBlocks(FIXTURE_WRAPS_SEARCH_TOO).single()
        val caught = OFF_LIMITS.filter { screen -> block.any { screen in FIXTURE_WRAPS_SEARCH_TOO[it] } }
        assertEquals(listOf("SearchScreen("), caught)
    }

    // --- the decisions ---------------------------------------------------------------------------

    /** Every `ComposeScreen(` CALL in the app's main sources, as (path relative to kotlin/, line). */
    private fun callSites(): List<Pair<String, Int>> = mainSourceLines { file, lines ->
        lines.indices
            .filter { "$COMPOSER_FUN(" in lines[it] }
            .filterNot { DECLARATION.containsMatchIn(lines[it]) }              // the declaration
            .filterNot { lines[it].isComment() || lines[it].trimStart().startsWith("import ") }
            .map { file to it }
    }

    /** Every `IncognitoKeyboard {` USE in the app's main sources. */
    private fun wrapperUses(): List<Pair<String, Int>> = mainSourceLines { file, lines ->
        lines.indices.filter { WRAPPER in lines[it] && !lines[it].isComment() }.map { file to it }
    }

    private fun <T> mainSourceLines(of: (String, List<String>) -> List<T>): List<T> =
        NavHostSourceRulesTest.mainSources().flatMap { of(relative(it), it.readLines()) }

    /**
     * 1-based line numbers of the [FIELD] call sites of a file that [root] does NOT compose:
     * neither written in [root] itself, nor in a function [root] reaches by call.
     */
    private fun fieldsOutside(lines: List<String>, root: String): List<Int> {
        val ranges = functionRanges(lines)
        val covered = reachableFrom(lines, root) + root
        return lines.indices
            .filter { FIELD in lines[it] && !lines[it].isComment() }
            .filter { i -> ranges.none { (name, range) -> name in covered && i in range } }
            .map { it + 1 }
    }

    /** Names of the file's own functions reachable, by call, from [root]. */
    private fun reachableFrom(lines: List<String>, root: String): Set<String> {
        val ranges = functionRanges(lines)
        val reached = mutableListOf(ranges[root] ?: return emptySet())
        val covered = mutableSetOf<String>()
        while (true) {
            val next = ranges.filterKeys { it != root && it !in covered }
                .filter { (name, own) ->
                    reached.any { region -> region.any { i -> i !in own && "$name(" in lines[i] } }
                }
            if (next.isEmpty()) return covered
            covered += next.keys
            reached += next.values
        }
    }

    /** Every top-level `fun` of the file, mapped to the line range it spans (0-based, inclusive). */
    private fun functionRanges(lines: List<String>): Map<String, IntRange> {
        val boundaries = lines.indices.filter { DECLARATION.containsMatchIn(lines[it]) }
        return boundaries.mapIndexedNotNull { n, start ->
            val name = FUN_NAME.find(lines[start])?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val end = boundaries.getOrNull(n + 1)?.minus(1) ?: lines.lastIndex
            name to (start..end)
        }.toMap()
    }

    /** EVERY `IncognitoKeyboard {` block of a file, not merely the first one. */
    private fun wrapperBlocks(lines: List<String>): List<IntRange> =
        lines.indices
            .filter { WRAPPER in lines[it] && !lines[it].isComment() }
            .map { blockFrom(lines, it) }

    /** The lines spanned by the block opened at [start], braces counted outside strings/comments. */
    private fun blockFrom(lines: List<String>, start: Int): IntRange {
        var depth = 0
        var opened = false
        for (i in start..lines.lastIndex) {
            if ('{' in lines[i]) opened = true
            depth += braceBalance(lines[i])
            if (opened && depth <= 0) return start..i
        }
        error("unbalanced braces from line ${start + 1} — this lint cannot read the file")
    }

    private fun braceBalance(line: String): Int {
        var depth = 0
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '\\' -> i++
                c == '"' -> quoted = !quoted
                quoted -> Unit
                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> return depth
                c == '{' -> depth++
                c == '}' -> depth--
            }
            i++
        }
        return depth
    }

    private fun String.isComment(): Boolean =
        trimStart().startsWith("//") || trimStart().startsWith("*") || trimStart().startsWith("/*")

    private fun List<String>.indicesOf(needle: String): List<Int> = indices.filter { needle in this[it] }

    private fun source(relative: String): File = NavHostSourceRulesTest.source(relative)

    private fun relative(file: File): String = file.path.substringAfter("app/src/main/kotlin/")

    private companion object {
        const val WRAPPER = "IncognitoKeyboard {"
        const val FIELD = "BasicTextField("
        const val COMPOSER_FUN = "ComposeScreen"
        const val COMPOSER = "app/sterna/ui/compose/ComposeScreen.kt"
        const val WRAPPER_FILE = "app/sterna/ui/compose/IncognitoKeyboard.kt"
        const val DELEGATE_CALL = "request.createInputConnection(outAttributes)"
        const val FLAG_ASSIGNMENT = "outAttributes.imeOptions = incognitoImeOptions(outAttributes.imeOptions)"

        /** Screens whose keyboard must keep its memory. Search is the bench witness. */
        val OFF_LIMITS = listOf(
            "SearchScreen(", "SettingsScreen(", "ConnectScreen(", "InboxScreen(", "MessageScreen(",
            "OutboxScreen(", "SternaApp(", "NavHost(",
        )

        /** A top-level declaration: what ends the previous one. */
        val DECLARATION = Regex(
            """^(?:private |internal |public )?(?:suspend )?(?:fun|class|object|interface|enum class|data class|val|var|const val)\b""",
        )

        /** A top-level function, its name in group 1 (receiver and type parameters dropped). */
        val FUN_NAME = Regex("""^(?:private |internal |public )?(?:suspend )?fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")

        /** The composer's file as it is: fields in the screen, and in a helper the screen calls. */
        val FIXTURE_COVERED = """
            |fun ComposeScreen() {
            |    Scaffold {
            |        BasicTextField(body)
            |        Subject()
            |    }
            |}
            |
            |private fun Subject() {
            |    BasicTextField(subject)
            |}
        """.trimMargin().lines()

        /** A dialog composed by the screen: inside the subtree, so covered. */
        val FIXTURE_FIELD_IN_A_DIALOG = """
            |fun ComposeScreen() {
            |    AlertDialog {
            |        BasicTextField(name)
            |    }
            |    Scaffold {
            |        BasicTextField(body)
            |    }
            |}
        """.trimMargin().lines()

        /** A helper of the file that the composer does not call: outside the wrapped subtree. */
        val FIXTURE_HELPER_OF_ANOTHER_SCREEN = """
            |fun ComposeScreen() {
            |    Scaffold {
            |        BasicTextField(body)
            |    }
            |}
            |
            |// Called by MessageScreen, not by the composer.
            |internal fun QuickReplyField() {
            |    BasicTextField(reply)
            |}
        """.trimMargin().lines()

        /** A second screen written into the same file. */
        val FIXTURE_SECOND_SCREEN = """
            |fun ComposeScreen() {
            |    Scaffold {
            |        Subject()
            |    }
            |}
            |
            |fun ComposeSheet() {
            |    BasicTextField(note)
            |}
            |
            |private fun Subject() {
            |    BasicTextField(subject)
            |}
        """.trimMargin().lines()

        /** A route that mounts the composer bare. */
        val FIXTURE_ROUTE_UNWRAPPED = """
            |composable("compose") {
            |    ComposeScreen(
            |        onDone = {},
            |    )
            |}
        """.trimMargin().lines()

        /** The same route, wrapped. */
        val FIXTURE_ROUTE_WRAPPED = """
            |composable("compose") {
            |    IncognitoKeyboard {
            |    ComposeScreen(
            |        onDone = {},
            |    )
            |    }
            |}
        """.trimMargin().lines()

        /** A second wrapper added below the first, in the same file. */
        val FIXTURE_TWO_WRAPPERS = """
            |composable("compose") {
            |    IncognitoKeyboard {
            |        ComposeScreen()
            |    }
            |}
            |composable("search") {
            |    IncognitoKeyboard {
            |        SearchScreen()
            |    }
            |}
        """.trimMargin().lines()

        /** One wrapper, stretched over a screen it must not cover. */
        val FIXTURE_WRAPS_SEARCH_TOO = """
            |IncognitoKeyboard {
            |    ComposeScreen(
            |    )
            |    SearchScreen(
            |    )
            |}
        """.trimMargin().lines()
    }
}
