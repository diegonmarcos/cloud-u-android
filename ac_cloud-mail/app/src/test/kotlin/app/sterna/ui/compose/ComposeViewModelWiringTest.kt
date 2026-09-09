package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeViewModel.kt` as text and proves nothing
 */
class ComposeViewModelWiringTest {

    @Test fun `what can survive this screen is settled from both facts, not from the route alone`() {
        val body = prepareBody().replace(Regex("""\s+"""), " ")
        assertTrue(
            "prepare() must settle the saved-draft fact through savedDraftBehindScreen(draftId, " +
                "if (restore) outbox.restored.value?.draftEmailId else null) — the whole " +
                "expression. `draftId` alone is the defect (#35): an undone send reopens the " +
                "composer with restore=true and NO draftId in the route, while the draft it was " +
                "sent from is still sitting in Drafts, so the dialog announced the destruction of " +
                "a message that exists. And the restored id must be read BEFORE the restore branch " +
                "below consumes it. Body was:\n$body",
            "_savedDraftBehind.value = savedDraftBehindScreen(draftId, " +
                "if (restore) outbox.restored.value?.draftEmailId else null)" in body,
        )
    }

    @Test fun `the deletable draft is taken only after the draft has actually been read`() {
        // The auditors' mutation, and it is the one a well-meaning reviewer writes: move
        // `_editingDraft.value = …` two lines up, "the cached row is free, take it first". Offline
        val body = prepareBody()
        val fetch = body.indexOf("repo.fetchEmail(")
        val take = body.indexOf("_editingDraft.value =")
        assertTrue("prepare() no longer fetches the draft — did the reopen path move?", fetch >= 0)
        assertTrue("prepare() no longer records the draft to delete — did it move?", take >= 0)
        assertTrue(
            "the row the Delete button acts on must be taken AFTER repo.fetchEmail(...) has " +
                "returned: the fetch is what fails offline, and everything below it is skipped. " +
                "Above it, the button appears on a composer that shows nothing. Body was:\n$body",
            fetch < take,
        )
        assertTrue(
            "it must come from the cache the Drafts list itself was drawn from (repo.cachedEmail), " +
                "not from the fetched value: the delete needs a row that is really there. " +
                "Body was:\n$body",
            Regex("""_editingDraft\.value = runCatching \{ repo\.cachedEmail\(""").containsMatchIn(body),
        )
    }

    @Test fun `the reopened draft's prefill is handed the cached row, account-scoped`() {
        // The behaviour lives in draftFieldsOf, which is pure and executed by ComposeTextTest; what
        // no test here can see is whether this class still FEEDS it the cached row. Without the
        val body = prepareBody().replace(Regex("""\s+"""), " ")
        assertTrue(
            "prepare() must build the draft prefill as draftFieldsOf(draft, _editingDraft.value): " +
                "the cached row is the only place a recipient the server dropped still exists " +
                "(#96). Body was:\n$body",
            "_prefill.value = draftFieldsOf(draft, _editingDraft.value)" in body,
        )
        val cache = body.indexOf(
            "_editingDraft.value = runCatching { repo.cachedEmail(credentials.id, draftId) }.getOrNull()",
        )
        val prefill = body.indexOf("_prefill.value = draftFieldsOf(")
        assertTrue(
            "the cached row must be read from repo.cachedEmail(credentials.id, draftId) — " +
                "account-scoped (#31). Body was:\n$body",
            cache >= 0,
        )
        assertTrue(
            "the cached row must be taken BEFORE the prefill is built, or the prefill is handed a " +
                "flow that is still null and the fallback is dead code — the composer opens with " +
                "the field the server emptied, which is the whole defect. Body was:\n$body",
            prefill > cache,
        )
    }

    /**
     * Who a reply is addressed to is decided in ONE place, [replyRecipient] /
     */
    @Test fun `the composer asks the one function who a reply answers`() {
        val body = bodyOf("buildPrefill")
        assertEquals(
            "buildPrefill must address replies through ComposeText's replyRecipient/" +
                "replyAllRecipients and nothing else. Reading `original.from` here is the defect: " +
                "Reply-To names where the sender wants the answer, and answering the From posts " +
                "it to an address they deliberately set aside. Body was:\n$body",
            listOf(
                "to = replyAllRecipients(original, selves),",
                "to = replyRecipient(original),",
            ),
            body.lines().map { it.trim() }.filter { it.startsWith("to = ") && it != "to = \"\"," },
        )
    }

    /**
     * The queued row a composer was editing is DESTROYED by `consumeEditingOutbox` —
     */
    @Test fun `the queued row is dropped only for a draft that really reached the server`() {
        val body = bodyOf("saveDraft")
        assertEquals(
            "saveDraft must consume the edited outbox row behind " +
                "draftSaveConsumesTheQueuedRow(outcome), and nowhere else in this body. " +
                "Body was:\n$body",
            listOf("if (draftSaveConsumesTheQueuedRow(outcome)) consumeEditingOutbox()"),
            body.lines().map { it.trim() }.filter { "consumeEditingOutbox()" in it },
        )
    }

    /**
     * The other half of the same outcome, and the one that decides whether the message is SENT.
     */
    @Test fun `the queued row that survives the save is parked, and parked before anything drops it`() {
        val body = bodyOf("saveDraft")
        assertEquals(
            "saveDraft must park the edited outbox row behind draftSaveParksTheQueuedRowAs(" +
                "outcome), and nowhere else in this body. Body was:\n$body",
            listOf("draftSaveParksTheQueuedRowAs(outcome)?.let { parkEditingOutbox(it) }"),
            body.lines().map { it.trim() }.filter { "parkEditingOutbox(" in it },
        )
        val park = body.indexOf("parkEditingOutbox(")
        val consume = body.indexOf("consumeEditingOutbox()")
        assertTrue("saveDraft no longer parks the edited row at all", park >= 0)
        assertTrue("saveDraft no longer consumes the edited row at all", consume >= 0)
        assertTrue(
            "the park must be written BEFORE the consume: consumeEditingOutbox() sets " +
                "editingOutboxId to null, so a park below it returns without touching the row and " +
                "the message is left EDITING — the defect, with a line of code that looks right. " +
                "Body was:\n$body",
            park < consume,
        )
    }

    /**
     * THE WHOLE BODY, line by line and in order — a filter on one name is blind to the mutation
     */
    @Test fun `the whole of saveDraft, statement by statement`() {
        val body = bodyOf("saveDraft")
        assertEquals(
            "ComposeViewModel.saveDraft is pinned whole. If you meant to change it, read the ⛔ " +
                "comments in it first: the lost-body guard is the FIRST statement of the function " +
                "and above the consume at the foot of it; the park must be a statement of the " +
                "submit block itself (not nested in a condition) and must come before the consume. " +
                "Body was:\n$body",
            listOf(
                // FIRST, and above everything that writes: a queued row reopened by Outbox →
                // Edit and redrawn empty over a resume slot that lost the body is saved as an empty
                "if (lostBodySaveDestroysQueuedRow(bodyWasLost = composerBodyWasLost, body = body.text, holdsQueuedRow = editingOutboxId != null)) {",
                "_notices.tryEmit(R.string.compose_body_lost_reopen_draft)",
                "return",
                "}",
                "if (!draftSaveAllowed(_pgpMode.value)) {",
                "_notices.tryEmit(R.string.compose_pgp_no_draft)",
                "return",
                "}",
                "if (!hasDraftContent(to, cc, bcc, subject, body.text)) {",
                "val original = editingDraftId",
                "val originalUidValidity = editingDraftUidValidity",
                // …and the ACCOUNT both were read from, captured with them: the expunge below is
                // addressed to it, never to `credentials()` — the account of the identity the
                // "From" picker is showing, and that picker covers every account (#31 × #63).
                "val originalAccountId = editingDraftAccountId",
                // The close that accompanies a deletion hands the phone's own row to NOBODY —
                // abandon() would otherwise put it back PENDING and re-arm its upload, sending the
                "abandon(emptying = true, body = body.text)",
                "if (original == null) {",
                "_state.value = ComposeState.Done",
                // A `local-draft:` id names nothing repo.discardDraft can destroy: under IMAP
                // numbering it answers an empty list, so the emptied draft survived and went up on
                "} else if (draftOpenRoute(original) == DraftOpenRoute.LOCAL) {",
                "submit(to) { _, _ -> destroyReplacedServerDraft(original) }",
                "} else {",
                // And the server route reads the SAME verdict the local one does: a draft this
                // composer never read whole keeps its server copy and the screen says so
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
                "}",
                "return",
                "}",
                "submit(to) { credentials, recipients ->",
                "val identity = selectedIdentity()",
                // What the save REPLACES is one answer — the id frozen at the open with the
                // numbering it belongs to — and it is withheld WHOLE when that pair was read under
                "val replaces = SendDraftTarget(emailId = editingDraftId, uidValidity = editingDraftUidValidity)",
                ".unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)",
                "val outcome = repo.saveDraft(",
                // The text part is `toPlainText(body)` and NOT `body.text` (#131): a list is
                // written `- ` / `1. ` there and a link is written `label <url>`, so a recipient
                "credentials, recipients, subject, toPlainText(body),",
                // The styling as a SECOND part, from the one pure function every route uses:
                // null for a body with no styling, so a plain draft is written EXACTLY as it was
                "html = draftHtmlToSave(body),",
                "cc = parseAddrs(cc), bcc = parseAddrs(bcc),",
                "inReplyTo = inReplyTo, references = references,",
                "replacesEmailId = replaces.emailId,",
                "replacesUidValidity = replaces.uidValidity,",
                "attachments = _attachments.value,",
                "bodyIsLossy = editingDraftLossy,",
                // Both, and each with its own name. The disjunction goes on the row and licenses
                // the destroy (#63); the composer's own loss is the only term the write guard may
                // weigh. Passing `editingDraftLossy` here too refuses an erasure the user made on
                // purpose on every reopened HTML draft, in silence.
                "composerBodyWasLost = composerBodyWasLost,",
                // …and the EMPTINESS of what was typed, which travels apart from the text since
                // the text is derived (#131). `toPlainText(body)` of an empty body carrying one
                "typedBodyIsBlank = body.text.isBlank(),",
                "fromName = identity?.name, fromEmail = identity?.email,",
                "requestReceipt = requestReceipt,",
                ")",
                "if (draftSaveNeedsNotice(outcome)) {",
                "_notices.tryEmit(R.string.compose_draft_original_kept)",
                "}",
                "draftSaveParksTheQueuedRowAs(outcome)?.let { parkEditingOutbox(it) }",
                "if (draftSaveConsumesTheQueuedRow(outcome)) consumeEditingOutbox()",
                "}",
            ),
            codeOf(body),
        )
    }

    /**
     * And the park has to SURVIVE the screen going away — the whole body again, for the same
     */
    @Test fun `the park outlives the screen, like abandon does`() {
        val body = bodyOf("parkEditingOutbox")
        assertEquals(
            "parkEditingOutbox must take the row's id synchronously and then write through " +
                "appScope — never viewModelScope, which the back gesture cancels. Body was:\n$body",
            listOf(
                "val id = editingOutboxId ?: return",
                "editingOutboxId = null",
                "_editingOutbox.value = false",
                "appScope.launch {",
                "runCatching { repo.parkOutboxEdit(id, state) }",
                ".onFailure { android.util.Log.w(\"SternaCompose\", \"couldn't park the edited outbox item\", it) }",
                "}",
            ),
            codeOf(body),
        )
    }

    /**
     * And the park of an INTERRUPTED EDIT has to survive the screen going away too — `abandon`
     */
    @Test fun `the interrupted edit is parked through appScope, like the release`() {
        val body = codeOf(bodyOf("abandon"))
        val at = body.indexOf("plan.outboxToPark?.let { id ->")
        check(at >= 0) { "abandon() no longer parks 'plan.outboxToPark' — did the plan lose its field? Body was:\n$body" }
        assertEquals(
            "abandon() must take the parked row's id off the plan, clear the lease synchronously, " +
                "and write through appScope with repo.parkInterruptedOutboxEdit — never " +
                "viewModelScope, and never releaseOutboxEdit. Body was:\n$body",
            listOf(
                "plan.outboxToPark?.let { id ->",
                "editingOutboxId = null",
                "_editingOutbox.value = false",
                "appScope.launch {",
                "runCatching { repo.parkInterruptedOutboxEdit(id) }",
                ".onFailure { android.util.Log.w(\"SternaCompose\", \"couldn't park the interrupted outbox item\", it) }",
                "}",
                "}",
            ),
            body.drop(at),
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    /** The body of `fun prepare(`, braces balanced, comments cut. */
    private fun prepareBody(): String = bodyOf("prepare")

    /** [body]'s code lines, trimmed and blanks dropped — the shape the whole-body rules compare. */
    private fun codeOf(body: String): List<String> =
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }

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

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}
