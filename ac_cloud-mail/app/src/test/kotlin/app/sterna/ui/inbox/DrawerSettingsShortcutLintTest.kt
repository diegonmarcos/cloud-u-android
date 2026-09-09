package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 *
 * The drawer offers settings TWICE: a row at the very bottom, under 28 folders, and a shortcut in
 * the header's top-right corner. Two entry points to one screen is the whole point of a shortcut,
 * and also the whole risk — the failure this guards is the second one quietly drifting onto a
 * different destination, which no compiler catches and which looks fine until someone taps it.
 *
 * The rule is therefore not "both open settings" but "both invoke the SAME `onOpenSettings`, and
 * that one lambda is bound to exactly one route". Sharing the lambda is what makes them the same
 * destination by construction rather than by two call sites happening to agree.
 */
class DrawerSettingsShortcutLintTest {

    @Test
    fun `the drawer offers settings from exactly two places and neither invents its own route`() {
        val calls = Regex("""\bonOpenSettings\s*\(\s*\)""").findAll(codeText(INBOX_SCREEN)).count()
        assertEquals(
            "InboxScreen.kt must invoke onOpenSettings() exactly twice — the header shortcut and " +
                "the row at the bottom of the folder list. Neither may navigate on its own: the " +
                "screen is handed ONE settings lambda, and calling it is what keeps the two " +
                "entries pointed at one destination. Found $calls invocation(s).",
            2,
            calls,
        )
    }

    @Test
    fun `the header shortcut is an icon button that calls the shared lambda`() {
        val shortcut = callsHolding("IconButton", SETTINGS_ICON)
        assertTrue(
            "The top-right shortcut must be the SAME `IconButton` tap target the account chevron " +
                "beside it uses, drawing $SETTINGS_ICON, and its onClick must call " +
                "onOpenSettings() — not a navigation of its own and not a second screen. The " +
                "IconButton found holding that icon was:\n$shortcut",
            Regex("""\bonOpenSettings\s*\(\s*\)""").containsMatchIn(shortcut),
        )
        assertTrue(
            "The shortcut must close the drawer after opening settings, as every other drawer " +
                "entry does — a sheet left standing over the screen it just navigated to is the " +
                "bug. The IconButton found was:\n$shortcut",
            Regex("""drawerState\.close\s*\(\s*\)""").containsMatchIn(shortcut),
        )
    }

    @Test
    fun `the row at the bottom of the folder list still calls the same lambda`() {
        val row = callsHolding("NavigationDrawerItem", SETTINGS_ICON)
        assertTrue(
            "The drawer's own settings ROW must keep calling onOpenSettings(). If the header " +
                "shortcut ever becomes the only caller, this rule is what says so instead of the " +
                "row silently going dead. The NavigationDrawerItem found holding $SETTINGS_ICON " +
                "was:\n$row",
            Regex("""\bonOpenSettings\s*\(\s*\)""").containsMatchIn(row),
        )
    }

    @Test
    fun `that one lambda resolves to the app's single settings destination`() {
        val text = codeText(STERNA_APP)
        val bindings = Regex("""onOpenSettings\s*=""").findAll(text).map {
            balancedFrom(text, it.range.last)
        }.toList()
        assertEquals(
            "SternaApp.kt must bind onOpenSettings exactly once — one binding is what makes " +
                "'the shortcut goes where the row goes' a fact about the wiring rather than an " +
                "assumption about two call sites. Bindings found: $bindings",
            1,
            bindings.size,
        )
        assertTrue(
            "The single onOpenSettings binding must navigate to \"$SETTINGS_ROUTE\". This is the " +
                "destination BOTH drawer entries reach, because both call this one lambda; if the " +
                "route is renamed here and this rule is not, the assertion that they agree stops " +
                "meaning anything. The binding found was:\n${bindings.single()}",
            bindings.single().contains("""nav.navigate("$SETTINGS_ROUTE")"""),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The single `[callee](…)` call in InboxScreen whose body holds [marker], whole. */
    private fun callsHolding(callee: String, marker: String): String {
        val text = codeText(INBOX_SCREEN)
        val found = Regex("""\b${Regex.escape(callee)}\s*\(""").findAll(text)
            .map { callWithTrailingLambda(text, it.range.first) }
            .filter { it.contains(marker) }
            .toList()
        check(found.size == 1) {
            "InboxScreen.kt is expected to hold exactly ONE $callee drawing $marker — the anchor " +
                "this rule reads. Found ${found.size}: was it removed, or is there now a second " +
                "one that would have to be told apart here?"
        }
        return found.single()
    }

    /**
     * The whole call starting at [from], INCLUDING a trailing lambda if it has one.
     *
     * `IconButton(onClick = …) { Icon(…) }` keeps its content outside the argument list, so the
     * balanced parentheses alone stop short of the very icon this rule identifies the button by.
     */
    private fun callWithTrailingLambda(text: String, from: Int): String {
        val call = callAt(text, from)
        val after = from + call.length
        val lambda = text.indexOf('{', after)
        if (lambda < 0 || text.substring(after, lambda).isNotBlank()) return call
        return text.substring(from, after) + " " + balancedFrom(text, after)
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

    /** [file] as ONE line of code: comments taken out, runs of whitespace collapsed. */
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
        /** The icon both entries draw — this app's own set, the one already on the settings row. */
        private const val SETTINGS_ICON = "Icons.Filled.Settings"

        /** The one route in the navigation graph that shows [app.sterna.ui.settings.SettingsScreen]. */
        private const val SETTINGS_ROUTE = "settings"

        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
        private const val STERNA_APP_PATH = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val STERNA_APP: File by lazy { File(root, STERNA_APP_PATH) }
    }
}
