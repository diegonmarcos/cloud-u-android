package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeViewModel.kt` and `ComposeScreen.kt` as text
 */
class DraftReopenWiringTest {

    // -- the ViewModel: when the flag goes up, and when it comes back down ----------------------

    /**
     * THE FLAG IS RAISED BEFORE THE COROUTINE, not on its first line. The composer's first frame
     */
    @Test fun `the wait is declared before the coroutine that does the fetching`() {
        val branch = draftBranch()
        val flag = branch.indexOf("_draftLoading.value = true")
        val launch = branch.indexOf("viewModelScope.launch")
        assertTrue("the draftId branch no longer raises _draftLoading. Branch was:\n$branch", flag >= 0)
        assertTrue("the draftId branch no longer launches. Branch was:\n$branch", launch >= 0)
        assertTrue(
            "_draftLoading.value = true must come BEFORE viewModelScope.launch: the first frame is " +
                "composed from the flow, not from the coroutine, and an editor drawn for those " +
                "frames can be typed into and saved — which adds a second draft. Branch was:\n$branch",
            flag < launch,
        )
    }

    /**
     * EVERY WAY OUT LOWERS IT — hence a `finally` and not a line at the end of the `try`. The
     */
    @Test fun `the wait is lowered from a finally, so no exit can leave it up`() {
        val fin = draftFinallyBlock()
        assertEquals(
            "the draftId branch's finally must lower _draftLoading and do nothing else. It was:\n$fin",
            listOf("_draftLoading.value = false"),
            fin.lines().map { it.trim() }.filter { it.startsWith("_draftLoading.value") },
        )
    }

    /**
     * BOTH WRITES TO THE FLAG, WHOLE LINES, AND ONLY THOSE TWO. Pinned as a list so a third
     */
    @Test fun `the wait flag is written exactly twice, up then down`() {
        val branch = draftBranch()
        assertEquals(
            "the draftId branch must raise the flag once and lower it once. Branch was:\n$branch",
            listOf("_draftLoading.value = true", "_draftLoading.value = false"),
            branch.lines().map { it.trim() }.filter { it.startsWith("_draftLoading.value") },
        )
    }

    /**
     * EVERY LINE OF THE FILE THAT NAMES THE FLAG, DECLARATION INCLUDED. The rule above reads the
     */
    @Test fun `every line that names the wait flag, declaration included`() {
        assertEquals(
            "ComposeViewModel.kt must name _draftLoading on exactly these six lines: it is declared " +
                "false, exposed read-only, and raised-then-lowered once per reopen route — the " +
                "phone's own store first (#95), the server's second. A seventh line, or a " +
                "declaration that starts true, raises it " +
                "on paths that have no finally to lower it: the composer then hangs on a spinner with " +
                "an empty bar and only the X — for a new message whose signature block is empty, for " +
                "good. And a raise DELETED from either route re-opens the window this rule's two " +
                "branches exist for: an editor drawn, editable, with Save live, before the draft " +
                "behind it is known to be readable at all.",
            listOf(
                "private val _draftLoading = MutableStateFlow(false)",
                "val draftLoading: StateFlow<Boolean> = _draftLoading.asStateFlow()",
                "_draftLoading.value = true",
                "try { prepareLocalDraft(draftId!!) } finally { _draftLoading.value = false }",
                "_draftLoading.value = true",
                "_draftLoading.value = false",
            ),
            codeLines(COMPOSE_VIEW_MODEL).map { it.trim() }.filter { it.contains("_draftLoading") },
        )
    }

    // -- the ViewModel: the one place a reopened draft is prefilled from --------------------------

    /**
     * EVERY LINE OF THE BRANCH THAT NAMES THE PREFILL — ONE. [ComposeViewModelWiringTest] pins
     */
    @Test fun `every line of the draft branch that names the prefill`() {
        val branch = draftBranch()
        assertEquals(
            "the reopened-draft branch must name _prefill on exactly one line, the one that fills " +
                "the editor from the FETCHED draft. Any other line — above all one that prefills " +
                "from the cache before the fetch — draws an editor over data the server has not " +
                "confirmed and lets a save commit fields nobody read back. Branch was:\n$branch",
            listOf("_prefill.value = draftFieldsOf(draft, _editingDraft.value)"),
            branch.lines().map { it.trim() }.filter { it.contains("_prefill") },
        )
    }

    // -- the ViewModel: what a failure records ---------------------------------------------------

    /**
     * THE VERDICT IS THE APP'S ONE OFFLINE VERDICT, WITH ITS ARGUMENTS. The whole line, because
     */
    @Test fun `a draft that could not be read records which of the two sentences to show`() {
        val branch = draftBranch()
        assertEquals(
            "the draftId branch must record its failure through draftLoadNoticeFor, with the app's " +
                "one offline verdict and its arguments. Branch was:\n$branch",
            listOf(
                "_draftLoadFailed.value = draftLoadNoticeFor(offline = false)",
                "_draftLoadFailed.value = draftLoadNoticeFor(isOfflineFailure(t, online = hasUsableNetwork(app)))",
            ),
            branch.lines().map { it.trim() }.filter { it.startsWith("_draftLoadFailed.value") },
        )
    }

    /**
     * AND EVERY LINE OF THE FILE THAT NAMES IT, DECLARATION INCLUDED — the symmetric rule to the
     */
    @Test fun `every line that names the failure notice, declaration included`() {
        assertEquals(
            "ComposeViewModel.kt must name _draftLoadFailed on exactly these seven lines: declared " +
                "null, exposed read-only, written once per failure inside the reopened-draft " +
                "branch, and once per failure of a reopen off the PHONE's own store (#95) — no " +
                "account to resolve, the row already consumed by the uploader, and the row this " +
                "composer may not open right now because its files are being staged or it is being " +
                "uploaded (noticeLocalDraftBusy). Born non-null, or written anywhere " +
                "else, the composer opens on the dead end's sentence with no editor at all — for a " +
                "brand new message too, and with nothing on that path to clear it. A local verdict " +
                "MISSING from this list is one that went back to _attachmentStatus: a status line " +
                "under an editor that is still drawn and still offers Save over a draft this " +
                "screen does not hold.",
            listOf(
                "private val _draftLoadFailed = MutableStateFlow<Int?>(null)",
                "val draftLoadFailed: StateFlow<Int?> = _draftLoadFailed.asStateFlow()",
                "_draftLoadFailed.value = draftLoadNoticeFor(offline = false)",
                "_draftLoadFailed.value = draftLoadNoticeFor(isOfflineFailure(t, online = hasUsableNetwork(app)))",
                "_draftLoadFailed.value = R.string.compose_draft_load_failed",
                "_draftLoadFailed.value = R.string.compose_local_draft_gone",
                "_draftLoadFailed.value = R.string.compose_draft_load_failed",
            ),
            codeLines(COMPOSE_VIEW_MODEL).map { it.trim() }.filter { it.contains("_draftLoadFailed") },
        )
    }

    /**
     * ONE STRING, ONE MEANING. `compose_prefill_failed` says "the quoted original of a reply is
     */
    @Test fun `the reply prefill's sentence stays on the reply path and nowhere else`() {
        val branch = draftBranch()
        val rest = prepareBody().replace(branch, "")
        assertTrue(
            "compose_prefill_failed must not be shown for a reopened draft: nothing is prefilled " +
                "there any more, and the editor it annotated is not drawn at all. Branch was:\n$branch",
            "compose_prefill_failed" !in branch,
        )
        assertTrue(
            "compose_prefill_failed must still be shown when a REPLY's quoted original cannot be " +
                "fetched — that path is unchanged. prepare() outside the draft branch was:\n$rest",
            "compose_prefill_failed" in rest,
        )
    }

    // -- the screen: where the decision comes from ------------------------------------------------

    /**
     * THE CALL SITE, WHOLE, WITH ITS THREE ARGUMENTS NAMED. Everything else in this class checks
     */
    @Test fun `the decision is taken from the two flows and the prefill flag`() {
        val lines = codeLines(COMPOSE_SCREEN).map { it.trim() }
        assertEquals(
            "ComposeScreen must take the reopen decision exactly once, from the fetch flag, the " +
                "failure notice and `applied` — hard-coding any of the three re-opens the defect " +
                "the whole branch closes.",
            listOf("val reopen = draftReopenView(draftLoading, draftLoadFailed, applied)"),
            lines.filter { it.contains("draftReopenView(") },
        )
    }

    /**
     * AND THE TWO FLOWS IT READS, WHOLE LINES. `by … collectAsStateWithLifecycle()` is what makes
     */
    @Test fun `both flows are collected with the lifecycle, not sampled once`() {
        val lines = codeLines(COMPOSE_SCREEN).map { it.trim() }
        assertEquals(
            "the fetch flag must be observed, not read once: `by … collectAsStateWithLifecycle()`.",
            listOf("val draftLoading by viewModel.draftLoading.collectAsStateWithLifecycle()"),
            lines.filter { it.startsWith("val draftLoading ") },
        )
        assertEquals(
            "the failure notice must be observed, not read once: a sampled value leaves the dead " +
                "end's sentence permanently absent from a screen that has nothing else to say.",
            listOf("val draftLoadFailed by viewModel.draftLoadFailed.collectAsStateWithLifecycle()"),
            lines.filter { it.startsWith("val draftLoadFailed ") },
        )
    }

    /**
     * THE FLAG IT READS IS THE SAVED ONE. `applied` is the whole point of the third argument: it
     */
    @Test fun `the flag the decision reads is the one that survives process death`() {
        val lines = codeLines(COMPOSE_SCREEN).map { it.trim() }
        assertEquals(
            "`applied` must stay a rememberSaveable: the reopen decision reads it to know the " +
                "draft has already been shown, and that has to hold across a process kill.",
            listOf("var applied by rememberSaveable { mutableStateOf(false) }"),
            lines.filter { it.startsWith("var applied ") },
        )
    }

    // -- the screen: it obeys the decision -------------------------------------------------------

    /**
     * THE WHOLE ACTION BAR IS GATED, IN ONE PLACE. Not each button disabled on its own: Save,
     */
    @Test fun `no action is offered until the draft has actually been read`() {
        val lines = codeLines(COMPOSE_SCREEN).map { it.trim() }
        val at = lines.indexOfFirst { it == "actions = {" }
        assertTrue("ComposeScreen.kt has no `actions = {` — did the top bar move?", at >= 0)
        assertEquals(
            "the first thing inside the TopAppBar's actions must be the gate, whole and unweakened: " +
                "while a reopened draft is being read, or after it failed, the bar carries nothing " +
                "at all. The X in navigationIcon is the only way out and stays outside this gate.",
            "if (reopen is DraftReopenView.Editor) {",
            lines[at + 1],
        )
    }

    /**
     * THREE SCREENS, AND THE EDITOR IS ONE OF THEM — it is not drawn behind a spinner, it is not
     */
    @Test fun `the body draws one of the three screens the decision names`() {
        val block = blockAfter(codeLines(COMPOSE_SCREEN).joinToString("\n"), "when (reopen) {")
        assertEquals(
            "the composer's body must branch on draftReopenView's answer, one screen per case. " +
                "Block was:\n$block",
            listOf(
                "DraftReopenView.Waiting -> LoadingRing(Modifier.align(Alignment.Center))",
                "is DraftReopenView.DeadEnd -> Text(",
                "DraftReopenView.Editor ->",
            ),
            block.lines().map { it.trim() }.filter {
                it.startsWith("DraftReopenView.") || it.startsWith("is DraftReopenView.")
            },
        )
    }

    /**
     * The dead end shows the sentence the DECISION handed it, and does not pick one of its own.
     */
    @Test fun `the dead end shows the sentence it was handed`() {
        val block = blockAfter(codeLines(COMPOSE_SCREEN).joinToString("\n"), "when (reopen) {")
        assertTrue(
            "the dead end must render stringResource(reopen.notice) — the id the decision carried. " +
                "Block was:\n$block",
            block.lines().map { it.trim() }.any { it == "text = stringResource(reopen.notice)," },
        )
    }

    // -- reading the source -----------------------------------------------------------------------

    /** The body of `fun prepare(` in `ComposeViewModel.kt`, braces balanced, comments cut. */
    private fun prepareBody(): String {
        val code = codeLines(COMPOSE_VIEW_MODEL).joinToString("\n")
        val at = code.indexOf("fun prepare(")
        check(at >= 0) { "ComposeViewModel.kt declares no 'prepare(' — did it get renamed?" }
        return blockAfter(code.substring(at), "{")
    }

    /**
     * The arm of `prepare` that reopens a draft the SERVER holds, braces balanced.
     */
    private fun draftBranch(): String = blockAfter(prepareBody(), "DraftOpenRoute.SERVER -> {")

    /** The `finally { … }` of that branch, braces balanced. */
    private fun draftFinallyBlock(): String = blockAfter(draftBranch(), "} finally {")

    /** What follows the first [opener] in [code], up to the brace that closes it. */
    private fun blockAfter(code: String, opener: String): String {
        val at = code.indexOf(opener)
        check(at >= 0) { "the source no longer contains `$opener` — did it move or get renamed?" }
        val start = at + opener.length
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

    /** The lines of [file] that are code, with comments taken off: the comments here name the very
     *  calls and the very ordering the rules above pin. */
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

    companion object {
        private const val VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        private const val SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, VIEW_MODEL_PATH) }
        private val COMPOSE_SCREEN: File by lazy { File(root, SCREEN_PATH) }
    }
}
