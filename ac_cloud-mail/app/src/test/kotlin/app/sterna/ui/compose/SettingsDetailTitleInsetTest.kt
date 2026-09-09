package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `SettingsScreen.kt` as text and proves NOTHING about
 */
class SettingsDetailTitleInsetTest {

    @Test fun `the settings title keeps an end inset, so the address is not flush with the edge`() {
        titleText() // locates loudly first: no Text, no title argument, no rule.
        val body = titleSlot().lines().map(::normalise).filter { it.isNotEmpty() }
        assertEquals(
            "DetailScaffold's title slot must draw exactly this, line for line. It is pinned WHOLE, " +
                "not by looking for the inset line inside it, because both ways found of putting " +
                "the account address back against the right edge (#146) leave that one line " +
                "untouched: chaining '.offset(x = 16.dp)' on the NEXT line pushes the glyphs back " +
                "out by exactly what the padding let in, and drawing 'title.take(24) + \"…\"' cuts " +
                "the address without any of the keywords the other rule blacklists. 'end = 0.dp' " +
                "dies here too. The 16dp mirrors the inset the platform already lays at the start, " +
                "and this one slot roofs the twelve settings screens.\n" +
                "⚠ FALSE RED, read before you 'fix' it: the same fix written on one idiomatic line " +
                "— Text(title, modifier = Modifier.padding(end = 16.dp)) — renders identically, " +
                "pixel for pixel, and still fails here. This test is the only thing holding that " +
                "formatting (no ktlint, no spotless in this repo); if the reflow is deliberate, " +
                "reflow the expected list with it.\n" +
                "Slot was:\n${titleSlot()}",
            TITLE_SLOT,
            body,
        )
    }

    @Test fun `the settings title is bounded nowhere else, so an address is never ellipsised`() {
        val text = titleText()
        val lines = text.lines().map(::normalise)
        assertEquals(
            "DetailScaffold's title must be bounded NOWHERE ELSE. This title holds the account " +
                "address whenever the account-name field is empty, and a maxLines/overflow pair " +
                "cuts it into something unreadable — 'alexandra.billerey@exam…' names no account " +
                "anyone can recognise, which is worse than the edge-to-edge title reported in " +
                "#146. softWrap = false is worse still: the address stops wrapping altogether and " +
                "is clipped at the bar's edge with no ellipsis at all. A long title wraps. Title " +
                "Text was:\n$text",
            emptyList<String>(),
            lines.filter { line -> BOUNDS.any { it in line } },
        )
    }

    // -- reading the source -------------------------------------------------------------------

    /**
     * The body of `DetailScaffold`'s `title = { … }` slot, braces balanced, comments cut.
     */
    private fun titleSlot(): String {
        val code = code(SETTINGS_SCREEN)
        val declared = code.indexOf(DECLARATION)
        check(declared >= 0) {
            "SettingsScreen.kt no longer declares '$DECLARATION' — this rule pins the title inset " +
                "of the shared settings scaffold (#146) and has nothing to pin if it was renamed " +
                "or moved to another file"
        }
        val body = balanced(code, declared, '{', '}')
        val bar = body.indexOf(TOP_BAR)
        check(bar >= 0) {
            "DetailScaffold no longer calls '$TOP_BAR'. Its body was:\n$body"
        }
        val arguments = balanced(body, bar, '(', ')')
        val slots = SLOT.findAll(arguments).toList()
        check(slots.size == 1) {
            "DetailScaffold's $TOP_BAR must declare exactly one 'title = {' slot, found " +
                "${slots.size}. Arguments were:\n$arguments"
        }
        val slot = balanced(arguments, slots.single().range.last, '{', '}')
        check("navigationIcon" !in slot && "scrollBehavior" !in slot) {
            "the 'title = {' slot does not close before the next argument — the braces did not " +
                "balance and this rule is reading the rest of the top bar. Read:\n$slot"
        }
        return slot
    }

    /** The arguments of the one `Text(…)` of the title slot — the loud localisation of both rules. */
    private fun titleText(): String {
        val slot = titleSlot()
        val texts = callArguments(slot, "Text")
        check(texts.size == 1) {
            "DetailScaffold's title slot must draw exactly ONE Text, found ${texts.size} — with a " +
                "second one the inset can sit on a sibling while the title itself is drawn bare " +
                "and still runs to the edge, and both rules below would keep counting the " +
                "modifier they can see. Slot was:\n$slot"
        }
        val text = texts.single()
        check("title" in text) {
            "the one Text of the title slot must be the one drawing the 'title' parameter — these " +
                "rules pin a modifier onto THAT Text and must fail rather than pin it onto " +
                "something they never located. Text was:\n$text"
        }
        return text
    }

    /** A source line reduced to what it lays: leading blanks and the trailing comma are noise. */
    private fun normalise(line: String): String = line.trim().removeSuffix(",").trim()

    /** The argument text of every `name(…)` call in [text], parentheses balanced. */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')') }
            .toList()

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    /** [file]'s code as one string, comments cut: the KDoc above `DetailScaffold` names the bar. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    companion object {
        private const val DECLARATION = "internal fun DetailScaffold("
        private const val TOP_BAR = "LargeTopAppBar("

        /** Anchored: a bare `indexOf("title = {")` would also match inside `subtitle = {`. */
        private val SLOT = Regex("""(?<![\w.])title = \{""")

        /**
         * The whole body of the title slot, normalised (trimmed, trailing comma dropped, blank and
         */
        private val TITLE_SLOT = listOf(
            "Text(",
            "title",
            "modifier = Modifier.padding(end = 16.dp)",
            ")",
        )

        /** Everything that can bound this title into a cut address. */
        private val BOUNDS = listOf("maxLines", "overflow", "softWrap")

        private const val SETTINGS_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val SETTINGS_SCREEN: File by lazy { File(root, SETTINGS_SCREEN_PATH) }
    }
}
