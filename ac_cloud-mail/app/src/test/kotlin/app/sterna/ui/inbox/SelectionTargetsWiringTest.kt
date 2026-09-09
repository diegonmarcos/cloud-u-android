package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class SelectionTargetsWiringTest {

    /**
     * The three arguments both paths must hand the shared resolution — the live selection, the
     */
    private val expectedArguments = listOf(
        "keys = keys",
        "cached = repo.cachedEmailsByIds(keys)",
        "displayed = searchState.value.results",
    )

    @Test fun `toggling the selection's read state resolves every selected key`() {
        val body = body(INBOX_VIEW_MODEL, "toggleSelectedRead")
        val call = callArguments(body, "resolveSelectionTargets").singleOrNull()
        checkNotNull(call) {
            "toggleSelectedRead() must contain exactly one resolveSelectionTargets(...) call — the " +
                "decision SelectionTargetsTest runs. Body was:\n$body"
        }
        assertEquals(
            "resolveSelectionTargets(...) must be handed exactly these arguments, whole. " +
                "Arguments were:\n$call",
            expectedArguments,
            arguments(call),
        )
    }

    @Test fun `the read-state icon is decided over the same resolution`() {
        val body = body(INBOX_VIEW_MODEL, "refreshSelectionReadState")
        val call = callArguments(body, "resolveSelectionTargets").singleOrNull()
        checkNotNull(call) {
            "refreshSelectionReadState() must resolve the selection the same way the action does, " +
                "or the icon speaks for a subset of what the action will touch. Body was:\n$body"
        }
        assertEquals(
            "resolveSelectionTargets(...) must be handed exactly these arguments, whole. " +
                "Arguments were:\n$call",
            expectedArguments,
            arguments(call),
        )
    }

    /**
     * The cache may only be read AS the `cached` argument. A second, direct read left in either
     */
    @Test fun `neither path reads the cache anywhere but into the resolution`() {
        for (name in listOf("toggleSelectedRead", "refreshSelectionReadState")) {
            assertEquals(
                "$name() must read the cache once, as the 'cached' argument and nowhere else — a " +
                    "direct repo.cachedEmailsByIds(...) short-circuits the resolution and drops " +
                    "every selected message with no local row.",
                listOf("cached = repo.cachedEmailsByIds(keys),"),
                codeLinesNaming(body(INBOX_VIEW_MODEL, name), "repo.cachedEmailsByIds("),
            )
        }
    }

    /**
     * The vote and the loop must be over ALL the targets. Pinned whole-line because the mutation
     */
    @Test fun `the whole selection votes, and the whole selection is written`() {
        val body = body(INBOX_VIEW_MODEL, "toggleSelectedRead")
        assertEquals(
            "toggleSelectedRead() must act on every resolved target, unfiltered. Body was:\n$body",
            listOf("val emails = resolved.targets.map { it.email }"),
            codeLinesNaming(body, "resolved.targets"),
        )
        assertEquals(
            "the direction of the toggle is decided by the WHOLE selection: at least one unread " +
                "anywhere in it means mark read. Body was:\n$body",
            listOf("val targetSeen = !emails.all { it.isSeen }"),
            codeLinesNaming(body, "targetSeen ="),
        )
        assertEquals(
            "toggleSelectedRead() must not read folderTrusted: nothing here reads a row's folder, " +
                "so the flag can only serve to drop messages. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "folderTrusted"),
        )
    }

    /**
     * The second half of the body, which the rules above never looked at. Each of these three lines
     */

    @Test fun `the server write carries the direction just decided`() {
        val body = body(INBOX_VIEW_MODEL, "toggleSelectedRead")
        assertEquals(
            "the server write must carry the direction the whole selection voted for, not a " +
                "constant. Body was:\n$body",
            listOf("runCatching { repo.setRead(credentials, email.id, targetSeen) }"),
            codeLinesNaming(body, "repo.setRead("),
        )
    }

    @Test fun `the icon is set to what was just written`() {
        val body = body(INBOX_VIEW_MODEL, "toggleSelectedRead")
        assertEquals(
            "the icon must be set to what was just written — the selection is kept, so nothing " +
                "re-reads it afterwards. Body was:\n$body",
            listOf("_selectionAllRead.value = targetSeen"),
            codeLinesNaming(body, "_selectionAllRead.value"),
        )
    }

    @Test fun `marking read takes the notifications of those messages with it`() {
        val body = body(INBOX_VIEW_MODEL, "toggleSelectedRead")
        assertEquals(
            "marking read must take the notifications of the very messages acted on with it. " +
                "Body was:\n$body",
            listOf("if (targetSeen) dismissReadNotifications(emails.map { it.emailKey() })"),
            codeLinesNaming(body, "dismissReadNotifications("),
        )
    }

    /**
     * What the user is told, and over what. `status_action_failed` ("Couldn't complete the action")
     */
    @Test fun `a partial failure is told apart from a total one, over the whole selection`() {
        val body = body(INBOX_VIEW_MODEL, "toggleSelectedRead")
        assertEquals(
            "the unresolved keys must be counted as failures, not merely reported. Body was:\n$body",
            listOf("var failed = resolved.unresolved.size"),
            codeLinesNaming(body, "resolved.unresolved"),
        )
        assertEquals(
            "the outcome must be decided by bulkOutcome over the WHOLE selection. Body was:\n$body",
            listOf("when (bulkOutcome(attempted = keys.size, failed = failed)) {"),
            codeLinesNaming(body, "bulkOutcome("),
        )
        assertEquals(
            "and each outcome must get its own wording: silence, the partial string, the total " +
                "one. Body was:\n$body",
            listOf(
                "BulkOutcome.NONE -> Unit",
                "BulkOutcome.TOTAL -> _message.value = getApplication<Application>()" +
                    ".getString(R.string.status_action_failed)",
                "BulkOutcome.PARTIAL -> _message.value = getApplication<Application>()" +
                    ".getString(R.string.status_action_partly_failed)",
            ),
            codeLinesNaming(body, "BulkOutcome."),
        )
    }

    /**
     * The icon needs at least one target: `emptyList().all { }` is `true`, so a selection whose
     * rows are none of them resolvable used to draw "mark unread" over messages nobody had read.
     */
    @Test fun `an empty resolution is not 'all read'`() {
        val refresh = body(INBOX_VIEW_MODEL, "refreshSelectionReadState")
        assertEquals(
            "the icon must require at least one target AND all of them seen — 'no target' is not " +
                "'all read'. Body was:\n$refresh",
            listOf("_selectionAllRead.value = targets.isNotEmpty() && targets.all { it.email.isSeen }"),
            codeLinesNaming(refresh, "_selectionAllRead.value"),
        )
    }

    // -- reading the sources ------------------------------------------------------------------
    // Same readers as SelectAllWiringTest, deliberately duplicated rather than shared: a helper
    // these lints agree on is a helper a single edit can loosen for all of them at once.

    /** The code lines of [body] naming [needle], comments dropped, whitespace normalised. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The lines of [file] that are code, with comments taken off. */
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
     * The declaration of `fun`/`val` [name] in [file] and its body, as text. Fails loudly when the
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
     * A call's arguments, one entry each, whitespace normalised: split on the commas at the call's
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
    }
}
