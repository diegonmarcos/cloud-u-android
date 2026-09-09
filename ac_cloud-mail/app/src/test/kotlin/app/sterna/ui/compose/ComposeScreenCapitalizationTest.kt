package app.sterna.ui.compose

import app.sterna.ui.NavHostSourceRulesTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class ComposeScreenCapitalizationTest {

    private val composeScreen: List<String> by lazy {
        NavHostSourceRulesTest.source(COMPOSER).readLines()
    }

    // --- the promise, field by field -------------------------------------------------------------

    @Test fun `the composer still has the three inputs this rule was written for`() {
        // Not vacuous: three call sites, one per top-level composable. If this moves, the rule has
        // to be re-read rather than believed.
        assertEquals(
            "ComposeScreen.kt no longer has 3 BasicTextField call sites",
            3,
            composeScreen.count { FIELD in it && !it.isComment() },
        )
        assertEquals(
            "the three inputs are no longer one per composable",
            listOf(BODY, SUBJECT, RECIPIENTS),
            fields(composeScreen).map { it.first },
        )
    }

    @Test fun `the body asks for sentence capitalisation and nothing else`() {
        assertEquals(
            "the composer's BODY must carry exactly this keyboardOptions (#184)",
            BODY_OPTIONS,
            optionsOf(composeScreen, BODY),
        )
    }

    @Test fun `the subject asks for sentence capitalisation next to its keyboard type`() {
        assertEquals(
            "the composer's SUBJECT (ComposeField) must carry exactly this keyboardOptions (#184)",
            SUBJECT_OPTIONS,
            optionsOf(composeScreen, SUBJECT),
        )
    }

    @Test fun `the recipients are left alone`() {
        // The other half of what was posted on #184: addresses get no capitalisation. Whole-string
        // equality, so both adding capitalisation and dropping ImeAction.Done (#83) fail here.
        assertEquals(
            "the RECIPIENTS' keyboardOptions must stay exactly as they were (#184, #83)",
            RECIPIENTS_OPTIONS,
            optionsOf(composeScreen, RECIPIENTS),
        )
        assertFalse(
            "the recipients must NOT ask for capitalisation — they are addresses (#184)",
            CAPITALIZATION in optionsOf(composeScreen, RECIPIENTS),
        )
    }

    @Test fun `the body still names no ime action`() {
        // #26: the body is the only multi-line field of the screen, so an ImeAction there replaces
        // the line break. Checked over the WHOLE call, not just the keyboardOptions argument.
        val (_, block) = fields(composeScreen).single { it.first == BODY }
        val named = block.filter { !composeScreen[it].isComment() && "ImeAction" in composeScreen[it] }
        assertTrue(
            "the body names an ImeAction at line(s) ${named.map { it + 1 }} — it would take the " +
                "place of the newline in the only multi-line field of the composer (#26)",
            named.isEmpty(),
        )
    }

    // --- the lint's own decisions, on inputs whose answer is known -------------------------------
    //
    // Without these, this file could only ever report "green".

    @Test fun `an argument spread over several lines is read as one`() {
        assertEquals(
            "keyboardOptions = KeyboardOptions( keyboardType = keyboardType, " +
                "capitalization = KeyboardCapitalization.Sentences, )",
            optionsOf(FIXTURE_WRAPPED_ARGUMENT, "ComposeField"),
        )
    }

    @Test fun `a lengthened argument is not accepted`() {
        // THE reason this lint compares whole strings: `contains` would still find the
        // capitalisation here and call it a pass.
        val read = optionsOf(FIXTURE_BODY_WITH_IME_ACTION, "ComposeScreen")
        assertTrue("the substring a naive lint would look for is present", CAPITALIZATION in read)
        assertFalse("...yet the argument is not the one that was promised", BODY_OPTIONS == read)
    }

    @Test fun `a field with no keyboardOptions is reported as missing`() {
        assertEquals(MISSING, optionsOf(FIXTURE_NO_OPTIONS, "ComposeScreen"))
    }

    @Test fun `each field is attributed to the composable that writes it`() {
        assertEquals(
            listOf("ComposeScreen", "ComposeField"),
            fields(FIXTURE_TWO_FIELDS).map { it.first },
        )
        assertEquals(
            "keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)",
            optionsOf(FIXTURE_TWO_FIELDS, "ComposeField"),
        )
    }

    // --- the decisions ---------------------------------------------------------------------------

    /**
     * The whole `keyboardOptions = KeyboardOptions(…)` argument of the [FIELD] call written in
     */
    private fun optionsOf(lines: List<String>, owner: String): String {
        val (_, block) = fields(lines).singleOrNull { it.first == owner }
            ?: error("no single $FIELD written in $owner")
        val at = block.singleOrNull {
            !lines[it].isComment() && lines[it].trimStart().startsWith("$OPTIONS =")
        } ?: return MISSING
        val argument = callFrom(lines, at)
        return lines.slice(argument)
            .joinToString(" ") { it.trim() }
            .replace(Regex("""\s+"""), " ")
            .trim()
            .removeSuffix(",")
    }

    /** Every [FIELD] call, as (name of the top-level function writing it, lines it spans). */
    private fun fields(lines: List<String>): List<Pair<String, IntRange>> {
        val ranges = functionRanges(lines)
        return lines.indices
            .filter { FIELD in lines[it] && !lines[it].isComment() }
            .map { i ->
                val owner = ranges.entries.singleOrNull { (_, range) -> i in range }?.key
                    ?: error("the $FIELD at line ${i + 1} belongs to no top-level function")
                owner to callFrom(lines, i)
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

    /** The lines spanned by the call opened at [start], parentheses counted outside strings/comments. */
    private fun callFrom(lines: List<String>, start: Int): IntRange {
        var depth = 0
        var opened = false
        for (i in start..lines.lastIndex) {
            if (lines[i].isComment()) continue
            if ('(' in lines[i]) opened = true
            depth += parenBalance(lines[i])
            if (opened && depth <= 0) return start..i
        }
        error("unbalanced parentheses from line ${start + 1} — this lint cannot read the file")
    }

    private fun parenBalance(line: String): Int {
        var depth = 0
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '\\' -> i++
                c == '"' -> quoted = !quoted
                quoted -> Unit
                // A character literal: ',' and '(' are data, not punctuation.
                c == '\'' -> i += if (line.getOrNull(i + 1) == '\\') 3 else 2
                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> return depth
                c == '(' -> depth++
                c == ')' -> depth--
            }
            i++
        }
        return depth
    }

    private fun String.isComment(): Boolean =
        trimStart().startsWith("//") || trimStart().startsWith("*") || trimStart().startsWith("/*")

    private companion object {
        const val COMPOSER = "app/sterna/ui/compose/ComposeScreen.kt"
        const val FIELD = "BasicTextField("
        const val OPTIONS = "keyboardOptions"
        const val MISSING = "<no keyboardOptions argument>"
        const val CAPITALIZATION = "capitalization = KeyboardCapitalization.Sentences"

        /** The three composables that write an input, in source order. */
        const val BODY = "ComposeScreen"
        const val SUBJECT = "ComposeField"
        const val RECIPIENTS = "RecipientChipsField"

        // The three strings the rule is: pinned here, never recomputed from the file.
        const val BODY_OPTIONS =
            "keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)"
        const val SUBJECT_OPTIONS =
            "keyboardOptions = KeyboardOptions(keyboardType = keyboardType, " +
                "capitalization = KeyboardCapitalization.Sentences)"
        const val RECIPIENTS_OPTIONS =
            "keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done)"

        /** A top-level declaration: what ends the previous one. */
        val DECLARATION = Regex(
            """^(?:private |internal |public )?(?:suspend )?(?:fun|class|object|interface|enum class|data class|val|var|const val)\b""",
        )

        /** A top-level function, its name in group 1 (receiver and type parameters dropped). */
        val FUN_NAME = Regex("""^(?:private |internal |public )?(?:suspend )?fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")

        val FIXTURE_WRAPPED_ARGUMENT = """
            |private fun ComposeField() {
            |    BasicTextField(
            |        value = value,
            |        keyboardOptions = KeyboardOptions(
            |            keyboardType = keyboardType,
            |            capitalization = KeyboardCapitalization.Sentences,
            |        ),
            |    )
            |}
        """.trimMargin().lines()

        val FIXTURE_BODY_WITH_IME_ACTION = """
            |fun ComposeScreen() {
            |    BasicTextField(
            |        value = body,
            |        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            |    )
            |}
        """.trimMargin().lines()

        val FIXTURE_NO_OPTIONS = """
            |fun ComposeScreen() {
            |    BasicTextField(
            |        value = body,
            |        onValueChange = { body = it },
            |    )
            |}
        """.trimMargin().lines()

        val FIXTURE_TWO_FIELDS = """
            |fun ComposeScreen() {
            |    BasicTextField(
            |        value = body,
            |        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            |    )
            |}
            |
            |private fun ComposeField() {
            |    BasicTextField(
            |        value = value,
            |        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            |    )
            |}
        """.trimMargin().lines()
    }
}
