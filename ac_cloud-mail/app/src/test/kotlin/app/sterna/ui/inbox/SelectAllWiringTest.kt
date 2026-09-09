package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class SelectAllWiringTest {

    @Test fun `select all is decided by the shared function, over the live search state`() {
        val body = body(INBOX_VIEW_MODEL, "selectAll")
        assertTrue(
            "InboxViewModel.selectAll() must decide through selectAllKeys(...) — the pure choice " +
                "SelectAllKeysTest runs. Body was:\n$body",
            "selectAllKeys(" in body,
        )
        assertTrue(
            "selectAll() must read searchState — the very state the screen's result list is drawn " +
                "from. Without it the selection cannot know what is being shown, which is #126. " +
                "Body was:\n$body",
            "searchState.value" in body,
        )
        assertTrue(
            "selectAll() must not assign the folder's cached ids straight into the selection: " +
                "that IS the defect — filter, select all, delete, and the whole folder goes to the " +
                "Trash. Body was:\n$body",
            !Regex("""_selectedKeys\.value\s*=\s*repo\.cachedIds""").containsMatchIn(body),
        )
    }

    @Test fun `every argument of the decision is the live one`() {
        val call = callArguments(body(INBOX_VIEW_MODEL, "selectAll"), "selectAllKeys").singleOrNull()
        checkNotNull(call) { "selectAll() must contain exactly one selectAllKeys(...) call" }
        // WHOLE arguments, compared as a list — not substrings. A substring rule accepts anything
        // written after what it matched, and one of those rewrites is destructive across accounts:
        assertEquals(
            "selectAllKeys(...) must be handed exactly these arguments, whole. Arguments were:\n$call",
            listOf(
                "searching = search.active",
                "query = search.query",
                "results = search.results?.map { it.emailKey() }",
                "loading = search.loading",
                "complete = search.complete",
                // The folder read, minus the server rows a local draft is standing in for (#95):
                // those are taken OUT of the list but are still filed in `emails`, so the read
                "folderKeys = selectableKeysMinusHidden(repo.selectableIds(currentScopes(), filtered), hidden)",
            ),
            arguments(call),
        )
    }

    @Test fun `the folder branch is read through the list's own filter`() {
        // The second half of #126: the navigation list is filtered in SQL by "unread only", so the
        // selection must be too, or the funnel plus Select all plus delete moves read mail that was
        //
        // It used to demand the raw funnel (`val filtered = unreadOnly.value`), and that stopped
        // being the list's filter when a third scope arrived: `Sel.Unread` forces the filter on
        val body = body(INBOX_VIEW_MODEL, "selectAll").replace(Regex("""\s+"""), " ")
        assertTrue(
            "selectAll() must take the list's own unread filter from listUnreadOnly(selection.value, " +
                "unreadOnly.value) — the same decision the pager was built with — and hand it to the " +
                "folder read. Body was:\n$body",
            "val filtered = listUnreadOnly(selection.value, unreadOnly.value)" in body,
        )
        assertTrue(
            "selectAll() must not read the folder unfiltered: repo.cachedIds() is the whole folder, " +
                "filters and all, and that IS #126. Body was:\n$body",
            "repo.cachedIds" !in body,
        )
    }

    @Test fun `the decision branches on the screen's own display rule`() {
        val body = body(INBOX_VIEW_MODEL, "selectAllKeys")
        assertTrue(
            "selectAllKeys must ask searchDisplay(...) for SearchDisplay.RESULTS — the same call " +
                "InboxScreen branches on. A hand-written 'results.isNotEmpty()' here would agree " +
                "with the screen today and drift from it the day the screen's rule changes, and " +
                "the drift is silent: the user selects one list and deletes another. " +
                "Body was:\n$body",
            "searchDisplay(" in body && "SearchDisplay.RESULTS" in body,
        )
    }

    @Test fun `the screen still draws its result list under the predicate the selection copies`() {
        // The drift detector. selectAllKeys' first two terms are InboxScreen's `searchActive`; this
        // rule fails if the screen stops spelling it that way, which is the moment to carry the
        // change across rather than the moment to discover it on a phone.
        //
        // Anchored on the BRANCH, and compared WHOLE-LINE, for two reasons the auditors proved:
        //  - the same `val searchActive = …` line exists elsewhere in the file (a scroll
        val lines = codeLines(INBOX_SCREEN).map { it.trim() }
        val branch = "searchActive -> when (searchDisplay(ui.searchResults.size, ui.searchLoading, ui.searchComplete)) {"
        val at = lines.indexOfFirst { it == branch }
        assertTrue(
            "InboxScreen must still branch its list area on exactly:\n  $branch\nThat call — its " +
                "arguments included — is the shape selectAllKeys is fed; a different one means the " +
                "screen and the selection are answering two different questions.",
            at >= 0,
        )
        val declaration = lines.take(at).indexOfLast { it.startsWith("val searchActive") }
        assertTrue("no 'val searchActive' declared above the list's search branch", declaration >= 0)
        assertEquals(
            "the searchActive the list branches on must be exactly 'ui.searching && " +
                "ui.searchQuery.isNotBlank()' — selectAllKeys runs that predicate and nothing else. " +
                "An extra conjunct here (say '&& !selectionActive') makes the screen draw one list " +
                "while Select all takes another, which is #126 with the roles swapped.",
            "val searchActive = ui.searching && ui.searchQuery.isNotBlank()",
            lines[declaration],
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The lines of [file] that are code, with comments taken off — a line opening a comment is
     *  dropped whole, a trailing `//` outside a string cuts the rest of its line. The cut is what
     *  makes these rules mean anything: the comments here NAME the calls they forbid. */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

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


    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text: everything up to the
     */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        val declaration = Regex("""\b(fun|val|var)\s+$name\b""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var closed = false
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (closed && i > start && line.indentWidth() <= indent) break
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            closed = depth == 0
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    /**
     * A call's arguments, one entry each, whitespace normalised: split on the commas that are at
     */
    private fun arguments(call: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        call.forEach { c ->
            when (c) {
                '(', '{', '[' -> { depth++; current.append(c) }
                ')', '}', ']' -> { depth--; current.append(c) }
                ',' -> if (depth == 0) { out += current.toString(); current.clear() } else current.append(c)
                else -> current.append(c)
            }
        }
        out += current.toString()
        return out.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
    }

    /** The argument text of every call to [name] in [text], parentheses balanced. */
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

    companion object {
        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
    }
}
