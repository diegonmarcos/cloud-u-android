package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT — the wiring of [resumeOutboxRow] and [outboxRowToResume] into the three places
 */
class OutboxResumeWiringTest {

    // -- the composer takes the row back, after the startup recovery, without touching the fields --

    @Test fun `the resume joins the startup recovery, takes the row, and binds it — nothing else`() {
        assertEquals(
            "⛔ resumeOutboxEdit is the whole of what a composer rebuilt after a process death does " +
                "with the id its route carries. It goes through resumeOutboxRow WITH " +
                "app.container.outboxRecovery — taken before that Job is done, the row it just " +
                "marked EDITING is parked FAILED under the open composer by the startup sweep. It " +
                "takes the row through repo.takeOutboxForEdit, the same door as Outbox → Edit, " +
                "which refuses SENDING/EDITING/encrypted rows and stages the attachments. And it " +
                "binds through bindQueuedRow with the CURRENT identities, so the send identity, " +
                "the attachments, the PGP mode and the draft being replaced come back — a bind " +
                "that skips any of them sends the message under the default identity, or " +
                "duplicates the draft. Body was:\n" + bodyOf("resumeOutboxEdit"),
            listOf(
                "val app = getApplication<Application>()",
                "val draft = resumeOutboxRow(id, app.container.outboxRecovery) { rowId ->",
                "runCatching { repo.takeOutboxForEdit(rowId, File(app.cacheDir, \"outgoing\")) }",
                ".onFailure { android.util.Log.w(\"SternaCompose\", \"couldn't resume the outbox item \$rowId\", it) }",
                ".getOrNull()",
                "} ?: return",
                "bindQueuedRow(composeDraftOf(draft), _fromOptions.value)",
            ),
            codeOf(bodyOf("resumeOutboxEdit")),
        )
    }

    @Test fun `the resume never writes the fields — they come back from the screen's own saveables`() {
        // A8: To/Cc/Bcc/subject are `rememberSaveable`, the body comes through the resume slot or
        // is lost — and then "Close this screen and reopen the message" is true. A `_prefill` here
        // would paint the ROW's text over what the user had typed since queueing it.
        assertEquals(
            "⛔ resumeOutboxEdit writes _prefill: the row's fields would overwrite the restored ones",
            emptyList<String>(),
            codeOf(bodyOf("resumeOutboxEdit")).filter { "_prefill" in it },
        )
        assertEquals(
            "⛔ bindQueuedRow writes _prefill: it is called from the resume too, where the fields " +
                "must not be touched. The prefill belongs to the restored lambda of prepare() alone",
            emptyList<String>(),
            codeOf(bodyOf("bindQueuedRow")).filter { "_prefill" in it },
        )
    }

    // -- where the resume is asked, in prepare() ------------------------------------------------

    @Test fun `the restore branch reads the hand-over once, consumes it, then asks the resume`() {
        val body = bodyOf("prepare")
        assertEquals(
            "⛔ prepare() must read outbox.restored.value exactly here and nowhere else: the two " +
                "settlements at its head, and ONE read into `handed` that both the restored lambda " +
                "and outboxRowToResume are fed from. A second read after consumeRestored() answers " +
                "null and the resume would run on a live reopen, taking the row a second time",
            listOf(
                "_editingOutbox.value = holdsQueuedOutboxRow(restore, outbox.restored.value)",
                "savedDraftBehindScreen(draftId, if (restore) outbox.restored.value?.draftEmailId else null)",
                "val handed = outbox.restored.value",
            ),
            codeOf(body).filter { "outbox.restored.value" in it },
        )
        val (_, after) = restoredLambda(body)
        assertEquals(
            "⛔ the resume must stand right after outbox.consumeRestored() and before the return of " +
                "the restore branch: before the consume, it would be wedged between the restored " +
                "lambda and the consume that clears what the lambda reads (OnlyCopyWiringTest); " +
                "after the return, it would never run. It is asked through outboxRowToResume, " +
                "which is what keeps a LIVE reopen (hand-over present) from taking the row twice, " +
                "and it runs in viewModelScope: a composer popped before the recovery is over " +
                "must not take a row nobody will release",
            listOf(
                "outbox.consumeRestored()",
                "outboxRowToResume(handed, outboxId)?.let { id -> viewModelScope.launch { resumeOutboxEdit(id) } }",
                "return",
            ),
            codeOf(after).take(3),
        )
    }

    @Test fun `the id reaches prepare() from the screen, which reads it from the route`() {
        val viewModel = codeLines(COMPOSE_VIEW_MODEL).map { it.trim() }
        assertTrue(
            "prepare() must take `outboxId: Long? = null` — the row id the route carries",
            "outboxId: Long? = null," in viewModel,
        )
        val screen = codeLines(COMPOSE_SCREEN).map { it.trim() }
        assertEquals(
            "ComposeScreen must hand its outboxId to prepare(): dropped here, the composer rebuilt " +
                "after a process death is detached and Send enqueues a second copy beside the " +
                "parked original",
            listOf(
                "viewModel.prepare(",
                "replyTo, mode, accountId, restore, to, cc, bcc, subject, body, draftId,",
                "quoteAlreadyOnScreen = quoteLanded,",
                "outboxId = outboxId,",
                ")",
            ),
            blockAt(screen, "quoteAlreadyOnScreen = quoteLanded,", before = 2, size = 5),
        )
        assertTrue(
            "ComposeScreen must declare `outboxId: Long? = null` beside draftId",
            "outboxId: Long? = null," in screen,
        )
    }

    // -- the route carries the id, as it carries draftId ----------------------------------------

    @Test fun `the compose route declares outboxId and hands it to the screen, the Outbox route sets it`() {
        val app = codeLines(STERNA_APP).map { it.trim() }
        for (line in listOf(
            // The route pattern: the twin of draftId.
            "\"&to={to}&cc={cc}&bcc={bcc}&subject={subject}&body={body}&draftId={draftId}&outboxId={outboxId}\",",
            "navArgument(\"outboxId\") { type = NavType.StringType; nullable = true; defaultValue = null },",
            "outboxId = entry.arguments?.getString(\"outboxId\")?.toLongOrNull(),",
            // Outbox → Edit: the id travels in the route, so it survives the process.
            "onEditDraft = { id -> entry.navigateOnce { nav.navigate(\"compose?restore=true&outboxId=\$id\") } },",
            // The Undo route does NOT: an undone send has no row to come back to.
            "onReopenDraft = { nav.navigate(\"compose?restore=true\") },",
        )) {
            assertTrue("SternaApp.kt has no line reading exactly:\n  $line", line in app)
        }
    }

    // -- the Outbox screen hands the id it took, not a boolean -------------------------------------

    @Test fun `the Outbox screen opens the composer with the id of the row it took`() {
        val viewModel = codeLines(OUTBOX_VIEW_MODEL).map { it.trim() }
        assertTrue(
            "OutboxViewModel.readyToEdit must carry the id of the row taken (Long?), not a flag",
            "private val _readyToEdit = MutableStateFlow<Long?>(null)" in viewModel,
        )
        assertEquals(
            "the id is published right after the draft is handed to the composer in memory, and " +
                "the Outbox screen puts it in the route: the two ways the composer can find its row",
            listOf(
                "sendOutbox.reopen(composeDraftOf(draft))",
                "_readyToEdit.value = id",
            ),
            blockAt(viewModel, "sendOutbox.reopen(composeDraftOf(draft))", before = 0, size = 2),
        )
        val screen = codeLines(OUTBOX_SCREEN).map { it.trim() }
        assertEquals(
            "the screen must navigate with the id it was handed",
            listOf(
                "LaunchedEffect(readyToEdit) {",
                "readyToEdit?.let { id ->",
                "viewModel.consumeEdit()",
                "onEditDraft(id)",
                "}",
                "}",
            ),
            blockAt(screen, "readyToEdit?.let { id ->", before = 1, size = 6),
        )
    }

    // -- reading the sources ----------------------------------------------------------------------
    // Recopied from OnlyCopyWiringTest on purpose: the source lints of this repo do not share
    // helpers, so no rule can be disarmed from a distance.

    private fun codeOf(body: String): List<String> =
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun blockAt(lines: List<String>, anchor: String, before: Int, size: Int): List<String> {
        val at = lines.indexOf(anchor)
        assertTrue("no line reads exactly:\n  $anchor", at >= 0)
        return lines.subList(at - before, at - before + size)
    }

    /** The body of `fun [name](` in `ComposeViewModel.kt`, braces balanced, comments cut. */
    private fun bodyOf(name: String): String {
        val code = codeLines(COMPOSE_VIEW_MODEL).joinToString("\n")
        val at = code.indexOf("fun $name(")
        check(at >= 0) { "ComposeViewModel.kt declares no '$name(' — did it get renamed?" }
        val start = code.indexOf('{', code.indexOf(')', at)) + 1
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

    /** [body] split at the `handed?.let { d -> … }` block: what is inside it, and what follows. */
    private fun restoredLambda(body: String): Pair<String, String> {
        val at = body.indexOf(RESTORED_LAMBDA)
        check(at >= 0) { "the restore branch of prepare() no longer opens '$RESTORED_LAMBDA'. Body was:\n$body" }
        val start = body.indexOf('{', at) + 1
        var depth = 1
        var i = start
        while (i < body.length && depth > 0) {
            when (body[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        check(depth == 0) { "unbalanced braces in the restore branch of prepare() — was:\n$body" }
        return body.substring(start, i - 1) to body.substring(i)
    }

    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

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
        private const val RESTORED_LAMBDA = "handed?.let { d ->"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt").isFile }
                ?: error("cannot find the checkout root from ${File("").absolutePath}")
        }
        private val COMPOSE_VIEW_MODEL by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt") }
        private val COMPOSE_SCREEN by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt") }
        private val STERNA_APP by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt") }
        private val OUTBOX_VIEW_MODEL by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/outbox/OutboxViewModel.kt") }
        private val OUTBOX_SCREEN by lazy { File(root, "app/src/main/kotlin/app/sterna/ui/outbox/OutboxScreen.kt") }
    }
}
