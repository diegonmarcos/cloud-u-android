package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The empty-save of a draft opened straight off the SERVER, on its way from the composer to the
 */
class EmptiedServerDraftWiringTest {

    @Test fun `the empty save asks the decision about this screen's own verdict`() {
        assertEquals(
            "⛔ the decision must be fed BOTH terms, whole: `editingDraftLossy`, the verdict " +
                "`prepare()` computed from the draft it fetched, and the addressing term read " +
                "just above it. `false` for the first (or any other term) is the defect with a " +
                "decision bolted on top: the answer is then always \"destroy\", and a draft made " +
                "of one inline image — drawn EMPTY, because draftHasContent counts neither " +
                "inline images nor calendar parts — is expunged on the server without a word. " +
                "`true` for the second is the SAME defect one term along: a Cc this composer " +
                "never managed to read is then declared absent, and the only copy that carried " +
                "it is expunged (#63, on the route that leaves not even a duplicate).",
            mapOf("#0" to "editingDraftLossy", "addressingIsProvenEmpty" to "addressingIsProvenEmpty"),
            callsOf(bodyOf("saveDraft"), "emptiedServerDraftIsKept(").single(),
        )
    }

    @Test fun `the kept copy is SAID, and the expunge is what the other branch does`() {
        val lines = codeLines(bodyOf("saveDraft"))
        // Exact equality, never a prefix: the LOCAL route one branch above opens with almost the
        // same words and closes on its own line (`submit(to) { _, _ -> destroyReplacedServerDraft(original) }`),
        // so a prefix match can land on that one and this test then pins the wrong branch.
        val at = lines.indexOfFirst { it == "submit(to) { _, _ ->" }
        assertTrue(
            "saveDraft's empty-save no longer opens the server route on a multi-line lambda — the " +
                "guard and the notice live inside it, so a one-liner here is the defect. Did the " +
                "branch move? " +
                "Body was:\n" + lines.joinToString("\n"),
            at >= 0,
        )
        assertEquals(
            "⛔ the whole block, in order. Three things are pinned together because each alone " +
                "leaves the defect standing: the decision is asked BEFORE anything is destroyed; " +
                "the kept branch says so with the sentence the other route already posts " +
                "(compose_emptied_draft_server_copy_kept — no new string, it exists in the nine " +
                "locales), and it says it INSIDE the lambda, since submit() moves the screen to " +
                "Done only after this returns and a notice emitted afterwards is read by nobody; " +
                "and the destroying branch stays word for word what it was, frozen numbering " +
                "included (#99) — `null` there destroys nothing and the draft comes back on the " +
                "next sync. Body was:\n" + lines.joinToString("\n"),
            listOf(
                "submit(to) { _, _ ->",
                // The account is resolved by a LOCAL FUN and not into a val up front: a
                // lossy draft keeps its copy whatever the account says, and resolving an account
                "fun destroyingCredentials(): AccountCredentials =",
                "credentialsDestroyingEmptiedServerDraft(",
                "openedUnderAccountId = originalAccountId,",
                "composingAsAccountId = _selectedFrom.value?.accountId ?: accountId,",
                "lookup = { store.credentials(it) },",
                ") ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))",
                // The second term of the guard, and the whole correction: the addressing
                // of the copy about to be expunged, PROVEN empty or not proven at all. Read under
                "val addressingIsProvenEmpty = !editingDraftLossy &&",
                "repo.emptiedDraftAddressingIsProvenEmpty(destroyingCredentials(), original)",
                "if (emptiedServerDraftIsKept(editingDraftLossy, addressingIsProvenEmpty = addressingIsProvenEmpty)) {",
                "_notices.tryEmit(R.string.compose_emptied_draft_server_copy_kept)",
                "} else {",
                "repo.discardDraft(destroyingCredentials(), original, originalUidValidity)",
                "}",
                "}",
            ),
            lines.subList(at, (at + 15).coerceAtMost(lines.size)),
        )
    }

    /**
     * …and WHOSE server hears the expunge. `submit`'s own credentials are the account of the
     */
    @Test fun `the account it is addressed to is resolved once, from the freeze, with no fallback`() {
        val save = codeOf(COMPOSE_VIEW_MODEL.readText())
        assertEquals(
            "⛔ the frozen account, whole: `openedUnderAccountId = originalAccountId` — the value " +
                "captured beside the id and the numbering, before abandon(). " +
                "`_selectedFrom.value?.accountId` or `credentials.id` there IS the defect, and " +
                "`originalAccountId ?: credentials.id` is the defect with a guard bolted on top — " +
                "which is why whole arguments are compared and never substrings.",
            mapOf(
                "openedUnderAccountId" to "originalAccountId",
                "composingAsAccountId" to "_selectedFrom.value?.accountId ?: accountId",
                "lookup" to "{ store.credentials(it) }",
            ),
            callsOf(bodyOf("saveDraft"), "credentialsDestroyingEmptiedServerDraft(").single(),
        )
        assertEquals(
            "⛔ …and it is resolved in exactly ONE place in this class. A second call — a helper, " +
                "an \"already resolved above\" shortcut, a retry — is a second answer to the same " +
                "question, and every rule above stays green. Lines were:",
            listOf("credentialsDestroyingEmptiedServerDraft("),
            codeLines(save).filter { "credentialsDestroyingEmptiedServerDraft(" in it },
        )
    }

    @Test fun `the expunge exists nowhere else in the composer, so nothing goes round the guard`() {
        assertEquals(
            "⛔ exactly one `repo.discardDraft(` in this class, the one under the guard. A second " +
                "call — a helper, an early return \"to be safe\" — is a second way out that reads " +
                "no verdict at all, and every test above stays green. Lines were:",
            listOf("repo.discardDraft(destroyingCredentials(), original, originalUidValidity)"),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText())).filter { "repo.discardDraft(" in it },
        )
    }

    /**
     * And the GATE that decides whether the destroying branch is entered at all.
     */
    @Test fun `what counts as empty is the shared rule, holding this screen's own attachments`() {
        assertEquals(
            "⛔ `hasDraftContent` must hand `draftHasContent` this composer's five fields AND its " +
                "own attachment state, whole. Drop the attachments term (or pass `false`) and a " +
                "draft made only of attachments is judged EMPTY: the guard above then answers " +
                "\"destroy\" on a faithful read, and the server copy is expunged with the chips " +
                "still on screen. Lines were:",
            listOf(
                "): Boolean = draftHasContent(to, cc, bcc, subject, body, _attachments.value.isNotEmpty())",
            ),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText())).filter { "draftHasContent(" in it },
        )
    }

    // --- reading the source (the same instruments as DraftVerdictWiringTest) ---------------------

    /** The body of `fun [name](` in `ComposeViewModel.kt`, braces balanced, comments cut. */
    private fun bodyOf(name: String): String {
        val code = codeOf(COMPOSE_VIEW_MODEL.readText())
        val at = Regex("""\bfun\s+$name\s*\(""").find(code)
            ?: error("ComposeViewModel.kt declares no 'fun $name(' — did it get renamed?")
        val start = code.indexOf('{', code.indexOf(')', at.range.last)) + 1
        var depth = 1
        var i = start
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return code.substring(start, (i - 1).coerceAtLeast(start))
    }

    private fun codeLines(body: String): List<String> =
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The arguments of every `[call]…)` in [body], as name → value expression (`#n` when positional). */
    private fun callsOf(body: String, call: String): List<Map<String, String>> {
        val calls = mutableListOf<Map<String, String>>()
        var from = 0
        while (true) {
            val at = body.indexOf(call, from)
            if (at < 0) break
            val open = at + call.length - 1
            val close = matchingParen(body, open)
            calls += parseArguments(body.substring(open + 1, close))
            from = close
        }
        check(calls.isNotEmpty()) { "no call to '$call' left in saveDraft — did the guard go?" }
        return calls
    }

    private fun matchingParen(code: String, open: Int): Int {
        var depth = 0
        var i = open
        var inString = false
        while (i < code.length) {
            val c = code[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> {
                    depth--
                    if (depth == 0 && c == ')') return i
                }
            }
            i++
        }
        error("unbalanced call in the source at offset $open")
    }

    private fun parseArguments(text: String): Map<String, String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> { current.append(c).append(text.getOrElse(i + 1) { ' ' }); i += 2; continue }
                c == '"' -> { inString = !inString; current.append(c) }
                inString -> current.append(c)
                c == '(' || c == '[' || c == '{' -> { depth++; current.append(c) }
                c == ')' || c == ']' || c == '}' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { args += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) args += current.toString()
        return args.mapIndexed { index, raw ->
            val arg = raw.trim().replace(Regex("""\s+"""), " ")
            val eq = arg.indexOf('=')
            if (eq > 0 && arg.take(eq).trim().matches(Regex("""\w+""")) && arg.getOrNull(eq + 1) != '=') {
                arg.take(eq).trim() to arg.substring(eq + 1).trim()
            } else {
                "#$index" to arg
            }
        }.toMap()
    }

    /** [source] with every comment removed, string literals untouched: the comments around this
     *  branch name the very call it pins, so prose must not be able to answer for the code. */
    private fun codeOf(source: String): String {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < source.length) {
            val c = source[i]
            when {
                inString && c == '\\' -> { out.append(c).append(source.getOrElse(i + 1) { ' ' }); i += 2; continue }
                c == '"' -> { inString = !inString; out.append(c) }
                inString -> out.append(c)
                c == '/' && source.getOrNull(i + 1) == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                    continue
                }
                c == '/' && source.getOrNull(i + 1) == '*' -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private companion object {
        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}
