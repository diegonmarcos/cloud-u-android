package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class UnreadWidgetPushWiringLintTest {

    /**
     * The case the app-scoped collection cannot cover: a process the DELIVERY woke. Mail lands, the
     */
    @Test fun `the delivery pass redraws BOTH widgets, last, and outside its sign-out arm`() {
        assertEquals(
            "FetchAndNotify.run must END with a best-effort redraw of EACH home-screen widget, " +
                "outside its try. Without it, mail that arrives on a dead process notifies and " +
                "leaves the cell as it was until something else opens the app (#112) — the " +
                "counter showing the old number, and the latest-messages list not showing the " +
                "message the banner just announced, which is the one thing that widget is for. " +
                "⛔ Both, and both here: the two widgets have separate presence and separate " +
                "draws, so a pass that refreshes one of them leaves the other frozen.",
            listOf(
                "runCatching { UnreadWidgetDraw.refresh(context) }",
                ".rethrowIfCancelled()",
                ".onFailure { android.util.Log.w(\"FetchAndNotify\", \"unread widget not redrawn after the pass\", it) }",
                "runCatching { RecentMailWidgetDraw.refresh(context) }",
                ".rethrowIfCancelled()",
                ".onFailure { android.util.Log.w(\"FetchAndNotify\", \"latest-messages widget not redrawn after the pass\", it) }",
            ),
            functionBody(FETCH_AND_NOTIFY, "suspend fun run(").takeLast(6),
        )
    }

    /**
     * The other half: everything that moves while the process LIVES — a message read, a swipe, a
     */
    @Test fun `AppContainer collects the push, gated on a widget being placed`() {
        assertEquals(
            "AppContainer.init must launch UnreadWidgetPush.redraws over " +
                "UnreadWidgetPresence.placed(appContext) and accountStore.accountsFlow, collecting " +
                "the BY-ACCOUNT breakdown into UnreadWidgetDraw.redraw — the total alone cannot " +
                "feed the enlarged cell's lines. Dropped, the widget stops following the mail the " +
                "user is reading; ungated, every install pays for a widget it does not have; " +
                "without accountsFlow, the cell keeps its label and its tap target when an account " +
                "that never synced arrives or leaves, because such an account is absent from the " +
                "breakdown entirely.",
            listOf(
                "appScope.launch {",
                "UnreadWidgetPush.redraws(UnreadWidgetPresence.placed(appContext), accountStore.accountsFlow) {",
                "mailRepository.observeUnifiedInboxUnreadByAccount()",
                "}.collect { unreadByAccount ->",
                "runCatching { UnreadWidgetDraw.redraw(appContext, unreadByAccount) }",
                ".rethrowIfCancelled()",
                ".onFailure { Log.w(\"Sterna\", \"unread widget not redrawn; the cell keeps its number\", it) }",
                "}",
                "}",
            ),
            blockContaining(STERNA_APPLICATION, "UnreadWidgetPush.redraws(", "appScope.launch {"),
        )
    }

    /**
     * THE GATE'S THREE WRITERS, whole lines, because two of them are `override fun`s the system
     */
    @Test fun `the provider opens and closes the gate, and the seed reads it the right way round`() {
        assertEquals(
            "UnreadWidgetProvider.onEnabled must open the gate. Without it, a widget placed while " +
                "the app is running never starts the collection and its number stands still until " +
                "the process is replaced.",
            listOf(
                "override fun onEnabled(context: Context) {",
                "UnreadWidgetPresence.set(true)",
            ),
            functionBody(PROVIDER, "override fun onEnabled(context: Context)"),
        )
        assertEquals(
            "UnreadWidgetProvider.onDisabled must CLOSE it, with false. Dropped — or handed true — " +
                "the app keeps two global Room aggregates subscribed for a widget the user removed, " +
                "for as long as the process lives, and nothing on screen ever says so.",
            listOf(
                "override fun onDisabled(context: Context) {",
                "UnreadWidgetPresence.set(false)",
            ),
            functionBody(PROVIDER, "override fun onDisabled(context: Context)"),
        )
        assertEquals(
            "the seed must ask the widget manager and pass its answer through anyWidgetPlaced " +
                "unchanged. A negation here shuts the gate for everyone who has a widget and opens " +
                "it for everyone who has not, and it is one character.",
            listOf(
                "fun placed(context: Context): StateFlow<Boolean> {",
                "if (!seeded) {",
                "seeded = true",
                "set(anyWidgetPlaced(UnreadWidgetDraw.placedIds(context)))",
                "}",
                "return state",
            ),
            functionBody(WIDGET_DRAW, "fun placed(context: Context)"),
        )
    }

    // The third rule that lived here — "nothing in the widget package sets a PendingIntent yet" —
    // was a temporary guard, explicitly held only until the pane that adds a tap arrived. It has:
    // its replacement is [UnreadWidgetTapWiringLintTest], which pins what the shipped PendingIntent
    // must BE (its own action, the guards it enters, the call it ends in) rather than its absence.

    /**
     * The lines of [file], comments dropped whole and trailing `//` cut, blanks removed. Without
     * this every rule below would also match the prose written to explain it.
     */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
    }

    /** [line] up to its `//` comment, ignoring a `//` inside a string literal. */
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

    /**
     * The code lines of the function opening with [signature], its own closing brace excluded, so a
     */
    private fun functionBody(file: File, signature: String): List<String> {
        val lines = codeLines(file)
        val start = lines.indexOfFirst { it.startsWith(signature) }
        check(start >= 0) { "${file.name} no longer declares `$signature` — was it renamed?" }
        val body = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            val next = depth + line.count { it == '{' } - line.count { it == '}' }
            if (opened && next == 0) return body
            body += line
            depth = next
            if (depth > 0) opened = true
        }
        error("`$signature` in ${file.name} never closes — the slice would be the rest of the file")
    }

    /**
     * The whole brace-balanced block that opens with [opener] and contains [marker] — the launch
     */
    private fun blockContaining(file: File, marker: String, opener: String): List<String> {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { marker in it }
        if (at < 0) return emptyList()
        val start = (at downTo 0).firstOrNull { lines[it] == opener }
            ?: error("no `$opener` above `$marker` in ${file.name}")
        val block = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            block += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth == 0) return block
        }
        error("`$opener` in ${file.name} never closes")
    }

    private companion object {
        const val ANCHOR = "app/src/main/kotlin/app/sterna/SternaApplication.kt"

        /** Repo root, walked up from the module's working directory. */
        val ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, ANCHOR).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val STERNA_APPLICATION: File by lazy { File(ROOT, ANCHOR) }
        val FETCH_AND_NOTIFY: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/push/FetchAndNotify.kt")
        }
        val PROVIDER: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/widget/UnreadWidgetProvider.kt")
        }

        /** Holds `UnreadWidgetPresence` and `anyWidgetPlaced` as well as the draw itself. */
        val WIDGET_DRAW: File by lazy {
            File(ROOT, "app/src/main/kotlin/app/sterna/widget/UnreadWidgetDraw.kt")
        }
    }
}
