package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 */
class DrawerDividerMarginLintTest {

    @Test
    fun `the divider closing the account list carries the air under it`() {
        val divider = accountsDividerCall()
        assertEquals(
            "The divider that closes the drawer's account list must pay its own bottom margin. " +
                "The row immediately under it is selectable, and when selected Material fills it " +
                "with a rounded pill: without this margin the pill's fill starts ON the hairline " +
                "(issue #179). 'bottom' and not 'vertical' — the block above already ends on 8–10 " +
                "dp of bottom padding. Compared as the WHOLE call: 'bottom = 12.dp' is contained " +
                "in 'bottom = 12.dp * 0'. The call found, anchored on the 'accounts.size > 1' " +
                "guard that holds selectUnified(), was:\n$divider",
            EXPECTED_DIVIDER_CALL,
            divider,
        )
    }

    /**
     * The counterpart, and the reason the rule above is about the DIVIDER: the margin belongs to
     */
    @Test
    fun `the entries under the divider did not take the margin instead`() {
        val found = ENTRY_SELECTORS.map { it to modifierArguments(entryCalling(it)) }
        assertEquals(
            "The two drawer entries that can sit right under the divider must keep their modifier " +
                "exactly as it was — the horizontal inset, nothing else. Padding the item instead " +
                "of the divider either misses the single-account layout entirely ('Unread' is the " +
                "row against the line there) or pushes the selected pill out of line with the " +
                "folder rows below it. Compared as whole arguments; an empty list means the " +
                "modifier was dropped. Modifier arguments found were:" +
                "\n${found.joinToString("\n")}",
            ENTRY_SELECTORS.map { it to listOf(EXPECTED_ENTRY_MODIFIER) },
            found,
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /**
     * The `HorizontalDivider(...)` call that closes the account list, whole.
     */
    private fun accountsDividerCall(): String {
        val text = codeText(INBOX_SCREEN)
        val guards = Regex("""\bif\s*\(\s*accounts\.size\s*>\s*1\s*\)""").findAll(text)
            .filter { Regex("""\bselectUnified\s*\(""").containsMatchIn(balancedFrom(text, it.range.last)) }
            .toList()
        check(guards.size == 1) {
            "InboxScreen.kt is expected to hold exactly ONE 'accounts.size > 1' block carrying " +
                "selectUnified() — the unified entry, and this rule's anchor. Found ${guards.size}. " +
                "The divider below it can no longer be told from the three others in the file, so " +
                "this rule is guarding nothing until the anchor is repaired."
        }
        val anchor = guards.single().range.first
        val before = Regex("""\bHorizontalDivider\s*\(""").findAll(text)
            .filter { it.range.first < anchor }
            .toList()
        check(before.isNotEmpty()) {
            "InboxScreen.kt is expected to draw a HorizontalDivider before the unified entry's " +
                "guard — the line that closes the account list. Found none before offset $anchor."
        }
        val call = callAt(text, before.last().range.first)
        val gap = text.substring(before.last().range.first + call.length, anchor)
        check(gap.isBlank()) {
            "The divider this rule pins must be the one IMMEDIATELY above the unified entry's " +
                "guard; something now sits between them, so the margin below the divider no longer " +
                "lands under the selectable row. Text found between them was:\n$gap"
        }
        return call
    }

    /** The single `NavigationDrawerItem(...)` whose onClick calls [selector], whole. */
    private fun entryCalling(selector: String): String {
        val text = codeText(INBOX_SCREEN)
        val entries = Regex("""\bNavigationDrawerItem\s*\(""").findAll(text)
            .map { callAt(text, it.range.first) }
            .filter { Regex("""\b${Regex.escape(selector)}\s*\(""").containsMatchIn(it) }
            .toList()
        check(entries.size == 1) {
            "InboxScreen.kt is expected to hold exactly ONE NavigationDrawerItem calling " +
                "$selector() — a drawer row that can sit right under the account divider. Found " +
                "${entries.size}: was it removed, renamed, or is there now a second one that would " +
                "have to be told apart here?"
        }
        return entries.single()
    }

    /** The `modifier = …` arguments of the call [text], whole, in source order. */
    private fun modifierArguments(text: String): List<String> =
        argumentsOf(text).filter { Regex("""^modifier\s*=""").containsMatchIn(it) }

    /** The arguments of the call [text], split at the commas of its own depth, blanks dropped. */
    private fun argumentsOf(text: String): List<String> {
        val open = text.indexOf('(')
        check(open >= 0) { "not a call: $text" }
        val args = mutableListOf<String>()
        var depth = 0
        var start = open + 1
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(', '{', '[' -> depth++
                ')', ']', '}' -> depth--
            }
            if (depth == 0) {
                args += text.substring(start, i)
                break
            }
            if (depth == 1 && text[i] == ',') {
                args += text.substring(start, i)
                start = i + 1
            }
            i++
        }
        return args.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The whole call starting at [from], up to the `)` that balances its first `(`. */
    private fun callAt(text: String, from: Int): String {
        val open = text.indexOf('(', from)
        check(open >= 0) { "no argument list opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /** [text] from [from] up to the `}` that balances the first `{` at or after it. */
    private fun balancedFrom(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) { "no block opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /**
     * [file] as ONE line of code: comments taken out, runs of whitespace collapsed — so a call the
     */
    private fun codeText(file: File): String = strippedLines(file).joinToString(" ")
        .replace(Regex("""\s+"""), " ").trim()

    private fun strippedLines(file: File): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in file.readLines()) {
            val code = StringBuilder()
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            out += code.toString()
        }
        return out
    }

    companion object {
        /** The divider that closes the account list, whole — margin on the line, below only. */
        private const val EXPECTED_DIVIDER_CALL =
            "HorizontalDivider(Modifier.padding(bottom = 12.dp))"

        /** The two rows that can sit right under it, by the view each one selects. */
        private val ENTRY_SELECTORS = listOf("selectUnified", "selectUnread")

        /** Their modifier, unchanged: the horizontal inset, and no vertical air of their own. */
        private const val EXPECTED_ENTRY_MODIFIER =
            "modifier = Modifier.padding(horizontal = 12.dp)"

        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
    }
}
