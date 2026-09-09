package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 */
class SwipeRewindWiringTest {

    // -- the view model: who says a swipe did not take --------------------------------------------

    @Test fun `the swipe's failure arm reports the row, by its account-qualified key`() {
        val writes = failureArmLines().filter { "_swipeRewind" in it }
        assertEquals(
            "swipeRemove's onFailure must report exactly one thing to the rewind set: the key of " +
                "the row that flew off. Compared whole, so `+ emptySet()`, `+ EmailKey(null, " +
                "email.id)` or a bare id are all a different line. Arm was:\n" +
                failureArmLines().joinToString("\n"),
            listOf("_swipeRewind.value = _swipeRewind.value + email.emailKey()"),
            writes,
        )
    }

    @Test fun `the set is exposed read-only and emptied one key at a time`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
        assertEquals(
            "InboxViewModel must hold the reported rows as a private MutableStateFlow of " +
                "EmailKey and expose it read-only. Set<String> here is the #92 defect by " +
                "construction: two accounts of one server share message ids.",
            listOf(
                "private val _swipeRewind = MutableStateFlow<Set<EmailKey>>(emptySet())",
                "val swipeRewind: StateFlow<Set<EmailKey>> = _swipeRewind.asStateFlow()",
            ),
            lines.filter { it.startsWith("private val _swipeRewind") || it.startsWith("val swipeRewind") },
        )
        assertEquals(
            "swipeRewound(key) must remove THAT key and nothing else. Clearing the whole set " +
                "would drop a second row's rewind that had not played yet; leaving the key in " +
                "makes the row rewind on every recomposition.",
            listOf("_swipeRewind.value = _swipeRewind.value - key"),
            functionBody(INBOX_VIEW_MODEL, "fun swipeRewound(key: EmailKey)"),
        )
    }

    @Test fun `nothing else in the view model writes the set`() {
        val writes = codeLines(INBOX_VIEW_MODEL).map { it.trim() }.filter { "_swipeRewind.value =" in it }
        assertEquals(
            "exactly two writers: the swipe's failure arm (report) and swipeRewound (forget). A " +
                "third is another path quietly rewinding rows, and the folded-thread twin " +
                "(threadSwipeRemove) is deliberately NOT one of them — that path is out of this " +
                "branch's scope. Writers found:\n${writes.joinToString("\n")}",
            2, writes.size,
        )
    }

    /** Witness, deliberately out of scope: the failure arm must still do everything it already did. */
    @Test fun `the failure arm still restores and reconciles, and still drops nothing locally`() {
        val arm = failureArmLines()
        assertTrue(
            "the row was never dropped locally (network-first), so the arm must keep " +
                "restoreSearchResults + refresh() and must NOT have gained a local eviction — " +
                "that would hide a message still on the server. Arm was:\n${arm.joinToString("\n")}",
            "restoreSearchResults(listOf(email.emailKey()))" in arm && "refresh()" in arm,
        )
    }

    // -- the screen: who hears it, and which rows are told ----------------------------------------

    @Test fun `the screen collects the reported rows once`() {
        val collects = codeLines(INBOX_SCREEN).map { it.trim() }.filter { "viewModel.swipeRewind" in it }
        assertEquals(
            "InboxScreen must collect the set once, with the lifecycle, like every other flow it " +
                "reads. Lines mentioning viewModel.swipeRewind were:\n${collects.joinToString("\n")}",
            listOf("val swipeRewind by viewModel.swipeRewind.collectAsStateWithLifecycle()"),
            collects.filter { it.startsWith("val ") },
        )
    }

    @Test fun `the row rewinds all three values it flew off with, then says so`() {
        val body = row()
        val effects = Regex("""LaunchedEffect\(rewindSwipe\)""").findAll(body).toList()
        assertEquals(
            "SwipeableEmailRow must host exactly one LaunchedEffect(rewindSwipe). Found " +
                "${effects.size}.",
            1, effects.size,
        )
        assertEquals(
            "the effect must put offsetX, lift AND flyDir back and only then tell the view model " +
                "the row is home. All three, pinned whole: offsetX alone leaves the row at " +
                "alpha 0 (lift drives the fade), so it is still invisible at rest — the reported " +
                "defect, unfixed, with the suite green. flyDir alone leaves the tilt. And without " +
                "the last call the key stays in the set and the row rewinds on every " +
                "recomposition. Effect was:",
            "if (rewindSwipe) { offsetX.snapTo(0f) lift.snapTo(0f) flyDir = 0 onSwipeRewound() }",
            flat(balanced(body, effects.single().range.last, '{', '}')),
        )
    }

    @Test fun `the rewind never becomes a second way to refuse a swipe`() {
        // LocalDraftListWiringLintTest owns the refusal, and it lives on gesturesEnabled precisely
        // because commitSwipe destroys the row on screen before onSwipe runs. A rewind flag read
        // by the gesture would be a second, quieter refusal: the row would stop answering the
        // finger for as long as a key sat in the set.
        val body = row()
        assertEquals(
            "rewindSwipe may appear exactly three times in SwipeableEmailRow: the parameter, the " +
                "effect's key, and the effect's own `if`. A fourth mention is the flag being read " +
                "somewhere it must not be — the pointerInput block, gesturesEnabled, or the " +
                "row's clickable.",
            3, Regex("""\brewindSwipe\b""").findAll(body).count(),
        )
        assertEquals(
            "onSwipeRewound may appear exactly twice: the parameter and the call at the end of " +
                "the rewind.",
            2, Regex("""\bonSwipeRewound\b""").findAll(body).count(),
        )
    }

    @Test fun `both rows are told, and always by an account-qualified key`() {
        val calls = callSites(code(INBOX_SCREEN), "SwipeableEmailRow")
        assertEquals(
            "InboxScreen must call SwipeableEmailRow exactly twice — the list renderer and an " +
                "unfolded conversation's children. Both go through performSwipe → swipeRemove, so " +
                "both can fly a row off over a write that fails. Found ${calls.size}.",
            2, calls.size,
        )
        assertEquals(
            "each call site must decide with needsSwipeRewind on the row's OWN (account, id) " +
                "pair, and hand back the same pair when the row is home. Passing the id alone " +
                "brings back the sibling account's homonym (#92). The children's site is pinned " +
                "for its OWN reason, and a narrower one — see the class comment: its rewind is a " +
                "no-op today, and what it really owes is clearing the key. Arguments found " +
                "were:\n" +
                calls.joinToString("\n") { argument(it, "rewindSwipe") + " " + argument(it, "onSwipeRewound") },
            listOf(
                "rewindSwipe = needsSwipeRewind(swipeRewind, email.accountId, email.id),",
                "rewindSwipe = needsSwipeRewind(swipeRewindKeys, child.accountId, child.id),",
            ),
            calls.map { argument(it, "rewindSwipe") },
        )
        assertEquals(
            "and each must clear its own key — the children's through the callback ThreadChildren " +
                "is given, which is the only place the child's account is in hand.",
            listOf(
                "onSwipeRewound = { viewModel.swipeRewound(email.emailKey()) },",
                "onSwipeRewound = { onSwipeRewound(child) },",
            ),
            calls.map { argument(it, "onSwipeRewound") },
        )
    }

    @Test fun `the children are handed the whole set and a way to clear a key`() {
        val call = callSites(code(INBOX_SCREEN), "ThreadChildren").also {
            assertEquals("InboxScreen must call ThreadChildren exactly once. Found ${it.size}.", 1, it.size)
        }.single()
        assertEquals(
            "ThreadChildren must receive the reported keys and the per-child clear — the child's " +
                "account is in hand nowhere else, and without the clear its key stays in the set " +
                "for the rest of the process.",
            listOf(
                "swipeRewindKeys = swipeRewind,",
                "onSwipeRewound = { child -> viewModel.swipeRewound(child.emailKey()) },",
            ),
            listOf(argument(call, "swipeRewindKeys"), argument(call, "onSwipeRewound")),
        )
        assertEquals(
            "and it must declare them as keys, not ids: Set<String> here is #92 by construction.",
            listOf("swipeRewindKeys: Set<EmailKey>,", "onSwipeRewound: (Email) -> Unit,"),
            parameters(INBOX_SCREEN, "private fun ThreadChildren(")
                .filter { it.startsWith("swipeRewindKeys") || it.startsWith("onSwipeRewound") },
        )
    }

    @Test fun `the decision is asked twice and asked nowhere else`() {
        assertEquals(
            "needsSwipeRewind must be CALLED exactly twice in InboxScreen — once per row site. A " +
                "third caller is another surface taking this decision on its own.",
            2, Regex("""(?<!fun )\bneedsSwipeRewind\(""").findAll(code(INBOX_SCREEN)).count(),
        )
    }

    // -- guards this fix must not have moved -------------------------------------------------------

    @Test fun `the list key is still the account-qualified pair`() {
        val keys = codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.startsWith("key = listRows.itemKey") }
        assertEquals(
            "the paged list's key must stay the (account, id) pair. It is what makes the rewind " +
                "necessary AND possible: stable, so the failure's refresh() re-emits the row into " +
                "the same composition rather than a fresh one — changing it to force a remount " +
                "would 'fix' the symptom by throwing away every row's state on every reconcile.",
            listOf("key = listRows.itemKey { \"\${it.email.accountId}|\${it.email.id}\" },"),
            keys,
        )
    }

    @Test fun `the drag block still takes no new key`() {
        // Duplicated from SwipeCommitWiringTest on purpose: this is the guard a rewind signal is
        // most likely to be wired into by accident, and a rule that lives only in the other file
        // is a rule this branch never reads.
        val keys = callSites(row(), "pointerInput")
        assertEquals("SwipeableEmailRow must host exactly one pointerInput. Found ${keys.size}.", 1, keys.size)
        assertEquals(
            "the key list must stay these five: any extra key restarts the gesture in progress " +
                "every time the row is remeasured. The fifth, drawerCanOpen, is a layout class " +
                "and not a measurement — it moves only across 1 200 dp (#103 volet 6).",
            "gesturesEnabled, rightAction, leftAction, drawerBandPx, drawerCanOpen",
            flat(keys.single()),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The body of `SwipeableEmailRow`, comments stripped. */
    private fun row(): String = body(INBOX_SCREEN, "SwipeableEmailRow")

    /**
     * The code lines of the `.onFailure { … }` arm of `swipeRemove`, trimmed, comments already off.
     */
    private fun failureArmLines(): List<String> {
        val text = code(INBOX_VIEW_MODEL)
        val start = text.indexOf("private fun swipeRemove(")
        check(start >= 0) { "InboxViewModel.kt declares no swipeRemove — did it get renamed?" }
        val declaration = balanced(text, start, '{', '}')
        val onFailure = declaration.indexOf(".onFailure {")
        check(onFailure >= 0) { "swipeRemove has no .onFailure arm any more — where did the failure go?" }
        return balanced(declaration, onFailure, '{', '}')
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * The whole `name = …` argument of a call, from the line that opens it to the line where its
     */
    private fun argument(callText: String, name: String): String {
        val lines = callText.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("$name = ") }
        check(start >= 0) { "no `$name = ` argument in this call:\n$callText" }
        val out = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            if (depth <= 0) break
        }
        return flat(out.joinToString("\n"))
    }

    /** The argument text of every CALL to [name] in [text] (its declaration excluded), balanced. */
    private fun callSites(text: String, name: String): List<String> =
        Regex("""(?<!fun )\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')') }
            .toList()

    /** The body lines of the declaration starting at [header], trimmed, braces balanced. */
    private fun functionBody(file: File, header: String): List<String> {
        val text = code(file)
        val start = text.indexOf(header)
        check(start >= 0) { "${file.name} declares no '$header' — did it get renamed?" }
        return balanced(text, start + header.length - 1, '{', '}')
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The PARAMETER lines of the declaration starting at [header], trimmed, parens balanced. */
    private fun parameters(file: File, header: String): List<String> {
        val text = code(file)
        val start = text.indexOf(header)
        check(start >= 0) { "${file.name} declares no '$header' — did it get renamed?" }
        return balanced(text, start, '(', ')').lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** [text] with every run of whitespace squeezed to one space, so a wrapped call reads as one. */
    private fun flat(text: String): String = text.trim().replace(Regex("\\s+"), " ")

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

    /**
     * The lines of [file] that are code, with their comments taken off — a line whose first
     */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val start = line.trimStart()
        if (start.startsWith("//") || start.startsWith("*") || start.startsWith("/*")) null
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

    /** [file]'s code as one string, so a call the formatter wraps over four lines reads as one. */
    private fun code(file: File): String = codeLines(file).joinToString("\n")

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

    companion object {

        private const val INBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"
        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

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
        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
