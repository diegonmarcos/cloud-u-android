package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reopening a draft the phone holds and the server has not got (#95) — SOURCE LINT, THE LAST
 */
class LocalDraftReopenWiringTest {

    @Test fun `the composer routes a draft id by the pure decision, not by a null check`() {
        val prepare = codeLines(bodyOf(COMPOSE_VIEW_MODEL, "prepare"))
        assertEquals(
            "prepare() must dispatch on draftOpenRoute(draftId) — the decision core:data executes " +
                "— and on nothing else. An `if (draftId != null)` reinstated above it sends a " +
                "local id down the SERVER path, where repo.fetchEmail is the first statement: " +
                "offline that throws, the catch posts compose_prefill_failed, and the composer " +
                "opens EMPTY over the only copy of the text. Lines naming the route were:",
            listOf("when (draftOpenRoute(draftId)) {"),
            prepare.filter { "draftOpenRoute" in it },
        )
        assertEquals(
            "…and the three arms, whole and in order. A `when` over the route has no ordering to " +
                "get wrong, which is the point: the LOCAL arm cannot be moved below the SERVER one.",
            listOf("DraftOpenRoute.LOCAL -> {", "DraftOpenRoute.SERVER -> {", "DraftOpenRoute.NONE -> Unit"),
            prepare.filter { it.startsWith("DraftOpenRoute.") },
        )
        val at = prepare.indexOf("DraftOpenRoute.LOCAL -> {")
        assertTrue("prepare() has no LOCAL arm at all. Body was:\n${prepare.joinToString("\n")}", at >= 0)
        assertEquals(
            "⛔ the LOCAL arm, whole and in order. Three things, and each one is a defect prevented:\n" +
                "  · the wait is RAISED BEFORE the launch, never on its first line — the composer's " +
                "first frame is composed from that flow and a launch's body has not run by then, so " +
                "raised inside, the editor is on screen (editable, Save and Send live) for the " +
                "frames in between, and a save landing in that window writes a SECOND draft while " +
                "the first stays intact and invisible (#63, on the local route this time);\n" +
                "  · the wait is lowered from a `finally` at THIS level, not inside " +
                "prepareLocalDraft — that function leaves by three bare `return`s (no credentials, " +
                "Gone, Busy) as well as by any throw from Room, and a flag only one path lowers is " +
                "a spinner for good, with the sentence never drawn under it;\n" +
                "  · and it RETURNS: falling through runs the fresh-compose branches below over a " +
                "draft that has just been filled in.\n" +
                "Arm was:",
            listOf(
                "DraftOpenRoute.LOCAL -> {",
                "_draftLoading.value = true",
                "viewModelScope.launch {",
                "try { prepareLocalDraft(draftId!!) } finally { _draftLoading.value = false }",
                "}",
                "return",
            ),
            prepare.subList(at, at + 6),
        )
    }

    @Test fun `the local reopen touches the network nowhere`() {
        assertEquals(
            "⛔ THE rule of this branch. prepareLocalDraft must reach the repository EXACTLY once, " +
                "and only to take the row out for editing. A repo.fetchEmail / repo.cachedEmail " +
                "added here (as a \"fallback\", as \"enrichment\") is a call that CANNOT succeed: " +
                "the id names a row no server has ever heard of, so IMAP answers \"Message is not " +
                "in the cache.\" and JMAP an unknown id — and offline it does not even fail fast, " +
                "it blocks on the connect timeout while the user looks at an empty composer. " +
                "Repository calls found were:",
            listOf(
                "take = { repo.takeLocalDraftForEdit(credentials.id, it) },",
                "giveBack = { runCatching { repo.releaseLocalDraftEdit(credentials.id, it) } },",
            ),
            functionBody(COMPOSE_VIEW_MODEL, "private suspend fun prepareLocalDraft(").filter { "repo." in it },
        )
    }

    /**
     * The lease this coroutine took, given back BY THIS COROUTINE when the screen goes while the
     */
    @Test fun `the lease is given back by the coroutine that took it`() {
        val body = functionBody(COMPOSE_VIEW_MODEL, "private suspend fun prepareLocalDraft(")
        val at = body.indexOf("val lease = takeLocalDraftEditOrGiveItBack(")
        assertTrue(
            "prepareLocalDraft no longer takes its lease through takeLocalDraftEditOrGiveItBack — " +
                "armed after a bare repository call, a cancellation inside it leaves the row " +
                "EDITING for ever. Body was:\n${body.joinToString("\n")}",
            at >= 0,
        )
        assertEquals(
            "⛔ the call, whole and in order. `arm` must set BOTH fields — the account as well as " +
                "the id, since every write to that table is scoped by (accountId, id) and ids " +
                "collide between two accounts of one server (#31) — and `giveBack` must name the " +
                "release, not the consume: this row's text has not gone anywhere. Lines were:",
            listOf(
                "val lease = takeLocalDraftEditOrGiveItBack(",
                "id = id,",
                "take = { repo.takeLocalDraftForEdit(credentials.id, it) },",
                "arm = { taken ->",
                "editingLocalDraftAccountId = credentials.id",
                "_editingLocalDraftId.value = taken.id",
                "},",
                "giveBack = { runCatching { repo.releaseLocalDraftEdit(credentials.id, it) } },",
                ")",
            ),
            body.subList(at, at + 9),
        )
    }

    /**
     * Two failures, two sentences, and the difference is the point — not the wording.
     */
    @Test fun `the refused row and the missing row say different things`() {
        val body = functionBody(COMPOSE_VIEW_MODEL, "private suspend fun prepareLocalDraft(")
        val at = body.indexOf("val row = when (lease) {")
        assertTrue(
            "prepareLocalDraft no longer branches on the three answers of the lease. Body was:\n" +
                body.joinToString("\n"),
            at >= 0,
        )
        assertEquals(
            "⛔ the three arms, whole and in order: gone says compose_local_draft_gone and RETURNS, " +
                "busy says compose_draft_load_failed and RETURNS, and only a taken lease hands the " +
                "row on. Arms were:",
            listOf(
                "val row = when (lease) {",
                "LocalDraftEdit.Gone -> {",
                "noticeLocalDraftGone()",
                "return",
                "}",
                "LocalDraftEdit.Busy -> {",
                "noticeLocalDraftBusy()",
                "return",
                "}",
                "is LocalDraftEdit.Taken -> lease.row",
                "}",
            ),
            body.subList(at, at + 11),
        )
        assertEquals(
            "…and the busy sentence must be the string that already exists in the nine locales, on " +
                "the flow that also STOPS THE EDITOR BEING DRAWN (draftReopenView). A new string " +
                "here is a translation debt for a sentence already written; a notice on " +
                "_attachmentStatus instead would leave a blank composer with a live Save over a " +
                "draft this screen does not hold. Body was:",
            listOf("_draftLoadFailed.value = R.string.compose_draft_load_failed"),
            functionBody(COMPOSE_VIEW_MODEL, "private fun noticeLocalDraftBusy("),
        )
        assertEquals(
            "⛔ …and the GONE sentence is its own, and on the same flow. `compose_draft_load_failed` " +
                "(\"close this screen and try opening it again\") asks for something that cannot " +
                "work on a row the uploader has already consumed, and `compose_draft_offline` would " +
                "accuse a network that has nothing to do with it. Body was:",
            listOf("_draftLoadFailed.value = R.string.compose_local_draft_gone"),
            functionBody(COMPOSE_VIEW_MODEL, "private fun noticeLocalDraftGone("),
        )
    }

    /**
     * THE WHOLE BODY, line by line and in order — because the cheapest mutation here is a
     */
    @Test fun `the whole of prepareLocalDraft, line by line`() {
        val body = functionBody(COMPOSE_VIEW_MODEL, "private suspend fun prepareLocalDraft(")
        assertEquals(
            "prepareLocalDraft is pinned whole. Every field of the projection must be handed over " +
                "— a column that is not assigned here is a column the user loses on the next save " +
                "— and the two ways out must SAY so, on the flow that also takes the editor off " +
                "the screen: the upload worker can consume the row between the list being drawn " +
                "and the tap landing, and a bare `?: return` opened a blank composer with not a " +
                "word on it. Body was:\n" + body.joinToString("\n"),
            listOf(
                "val credentials = credentials()",
                "if (credentials == null) {",
                "noticeLocalDraftNoAccount()",
                "return",
                "}",
                "val lease = takeLocalDraftEditOrGiveItBack(",
                "id = id,",
                "take = { repo.takeLocalDraftForEdit(credentials.id, it) },",
                "arm = { taken ->",
                "editingLocalDraftAccountId = credentials.id",
                "_editingLocalDraftId.value = taken.id",
                "},",
                "giveBack = { runCatching { repo.releaseLocalDraftEdit(credentials.id, it) } },",
                ")",
                "val row = when (lease) {",
                "LocalDraftEdit.Gone -> {",
                "noticeLocalDraftGone()",
                "return",
                "}",
                "LocalDraftEdit.Busy -> {",
                "noticeLocalDraftBusy()",
                "return",
                "}",
                "is LocalDraftEdit.Taken -> lease.row",
                "}",
                "val fields = localDraftPrefill(row)",
                "_prefill.value = DraftFields(",
                "to = fields.to,",
                "cc = fields.cc,",
                "bcc = fields.bcc,",
                "subject = fields.subject,",
                "body = fields.body,",
                // …and the styling the row stored (#131), out of the same projection as the text.
                // Left out, a draft this app styled reopens with its bold gone from the screen,
                // and the next save stores that removal.
                "bodyRanges = fields.bodyRanges,",
                // …and its lists (#131), out of that same projection. Left out, a draft holding a
                // list reopens flat and the next save writes the flattening.
                "bodyBlocks = fields.bodyBlocks,",
                // …and its LINKS (#131) — ROUTE 3 OF 4. Left out, a draft that carries an address
                // reopens without it, and since the anchor PARSED the row is judged reproducible:
                // the next save writes the removal over the only copy that had it.
                "bodyLinks = fields.bodyLinks,",
                "expand = fields.expand,",
                // Added with the reply-all composer (#271): the projection carries it, so the
                // reopen must hand it over like every other column.
                "showAllRecipients = fields.showAllRecipients,",
                ")",
                "_attachments.value = fields.attachments",
                "inReplyTo = fields.inReplyTo",
                "references = fields.references",
                "fields.fromEmail?.let { written ->",
                "_fromOptions.value.firstOrNull {",
                "it.accountId == credentials.id && it.identity.email.equals(written, ignoreCase = true)",
                "}",
                "}?.let {",
                "_selectedFrom.value = it",
                "refreshPgp()",
                "}",
                "editingDraftLossyOnOpen = fields.bodyIsLossy",
                "editingDraftId = fields.editingDraftId",
                "replacedServerDraftId = fields.replacedServerDraftId",
                "replacedServerDraftUidValidity = fields.replacedServerDraftUidValidity",
                // The delete confirmation's warning is armed HERE, from the row's own two terms
                // and by the same decision the empty save's notice is posted from (#95). Left out,
                "_deleteKeepsServerCopy.value = emptiedLocalDraftKeepsServerCopy(",
                "fields.replacedServerDraftId,",
                "fields.bodyIsLossy,",
                "addressingIsProvenEmpty = true,",
                ")",
            ),
            body,
        )
        assertEquals(
            "…and the no-account notice is the sentence the SERVER reopen already gives for the " +
                "same cause (`draftLoadNoticeFor(offline = false)` resolves to this very id): an " +
                "account that will not resolve is transitory, so \"close this screen and try " +
                "opening it again\" is true here. On `_draftLoadFailed`, like the other two — a " +
                "line on `_attachmentStatus` annotates an editor that is still drawn, still " +
                "editable, and still offering Save over a draft this screen never read. Body was:",
            listOf("_draftLoadFailed.value = R.string.compose_draft_load_failed"),
            functionBody(COMPOSE_VIEW_MODEL, "private fun noticeLocalDraftNoAccount("),
        )
    }

    @Test fun `no send ever carries an id this phone minted`() {
        assertEquals(
            "⛔ every `draftEmailId =` in this class, whole lines and in order: the outbox row, " +
                "the record an undone send reopens from, and the scheduled row. Each one is what a " +
                "delivery worker DESTROYS once the message is on its way — on JMAP an Email/set " +
                "destroy, on IMAP a name ending in a UID, and a UID always means SOMETHING in a " +
                "folder. A reopened local draft made the composer hold exactly such an id, and the " +
                "only thing between it and the destroy was the server refusing a name it never " +
                "issued. `sendDraftTarget` is that guard — and, for a draft reopened off this " +
                "phone's own store, the only thing that still NAMES the server draft the row " +
                "stands in for; it is executed in SendDraftTargetTest, and what nothing but this " +
                "line can see is whether the call site still asks it. The scheduled row keeps its " +
                "own fidelity guard — taken on the PAIR, where the answer is built, not on the id " +
                "alone here — and the two answer different questions. Lines were:",
            listOf(
                "draftEmailId = sendTarget.emailId,",
                "draftEmailId = sendTarget.emailId,",
                "draftEmailId = sendTarget.emailId,",
            ),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText())).filter { "draftEmailId =" in it },
        )
        assertEquals(
            "⛔ …and its numbering comes out of the SAME answer, at all three: the pair is one " +
                "decision. Half of it read off the composer beside the other half read off the row " +
                "is the worst of both — on IMAP a UID expunged under a UIDVALIDITY it does not " +
                "belong to hits ANOTHER message of Drafts, and on the other order the destroy is " +
                "silently a no-op while the app believes the duplicate is gone. Lines were:",
            listOf(
                "draftUidValidity = sendTarget.uidValidity,",
                "draftUidValidity = sendTarget.uidValidity,",
                "draftUidValidity = sendTarget.uidValidity,",
            ),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText())).filter { "draftUidValidity =" in it },
        )
    }

    @Test fun `every send raises the sending flag BEFORE its coroutine, not inside it`() {
        // What abandon() reads to decide it must give NOTHING back is this flag, and between the
        // tap and the first dispatch of the coroutine there is a real window: a Compose recomposition
        val lines = codeLines(codeOf(COMPOSE_VIEW_MODEL.readText()))
        val raised = lines.withIndex().filter { it.value == "_state.value = ComposeState.Sending" }
        assertTrue(
            "no `_state.value = ComposeState.Sending` left in this class at all — did the state " +
                "get renamed? This rule pins its POSITION and must fail loudly rather than pin " +
                "nothing.",
            raised.size == 4,
        )
        assertEquals(
            "each of the four entry points — the deletion of a draft the phone is keeping, the " +
                "send, the scheduled send, and submit() — must read exactly these lines in this " +
                "order: the flag is raised under the re-entry guard and ABOVE " +
                "`viewModelScope.launch {`, never after it. The deletion is guarded by the read of " +
                "the lease itself (`heldEditingLocalDraft`, which refuses during a send) and " +
                "raises the flag for the same reason as the three below: the X stays live, and " +
                "abandon() reading a false flag would hand the row back to the upload worker while " +
                "this gesture is destroying it — the deleted draft goes up to the server instead. " +
                "⚠ The scheduled send leaves on `return false` and the other three on a bare " +
                "`return`: only that one is asked for an answer, because the screen confirms with " +
                "\"Scheduled — <date>\" on what it returns. Lines around each were:",
            listOf(
                listOf(
                    "val id = heldEditingLocalDraft() ?: return",
                    "_state.value = ComposeState.Sending",
                    "viewModelScope.launch {",
                    "var destroyed = false",
                ),
                listOf(
                    "if (_state.value is ComposeState.Sending) return",
                    "_state.value = ComposeState.Sending",
                    "val (to, cc, bcc, subject, body) = args",
                    "viewModelScope.launch {",
                ),
                listOf(
                    "if (_state.value is ComposeState.Sending) return false",
                    "_state.value = ComposeState.Sending",
                    "viewModelScope.launch {",
                    "try {",
                ),
                listOf(
                    "if (_state.value is ComposeState.Sending) return",
                    "_state.value = ComposeState.Sending",
                    "viewModelScope.launch {",
                    // The same shape as the deletion above, and for the same reason: whether the
                    // gesture went through is a VALUE the `finally` reads, not a position in the
                    // try — see `a save that went through gives the phone's own row back`.
                    "var saved = false",
                ),
            ),
            raised.map { lines.subList(it.index - 1, it.index + 3) },
        )
    }

    @Test fun `the phone's own draft is consumed only once the message exists somewhere else`() {
        val send = codeLines(bodyOf(COMPOSE_VIEW_MODEL, "sendInternal"))
        assertTrue(
            "sendInternal does not go through commitThenConsumeLocalDraft at all — the local " +
                "draft a send was reopened from is then simply left in the queue. Body was:\n" +
                send.joinToString("\n"),
            send.indexOf("val id = commitThenConsumeLocalDraft(") >= 0,
        )
        assertEquals(
            "⛔ the send must go through commitThenConsumeLocalDraft — the ORDER is executed in " +
                "core:data (LocalDraftReopenTest) and cannot be got wrong from here — and the " +
                "outbox row must be written INSIDE its commit. Consuming the row first, or in a " +
                "finally, loses the text on both sides on exactly the failure this branch exists " +
                "for. Lines were:",
            listOf(
                "val id = commitThenConsumeLocalDraft(",
                "localDraftId = _editingLocalDraftId.value,",
                "commit = {",
                "repo.enqueueSend(",
            ),
            send.subList(send.indexOf("val id = commitThenConsumeLocalDraft("), send.indexOf("val id = commitThenConsumeLocalDraft(") + 4),
        )
        assertEquals(
            "…and the consume is the composer's own, once: the row AND its staged files. ⚠ The " +
                "row is named a fourth time, ABOVE all of them, and it is not a consume: the " +
                "refusal reads it to know whether the text is still stored somewhere, and " +
                "therefore whether the sentence it shows may say \"reopen the draft\" instead of " +
                "\"type it again\" — which on this route is the instruction that destroys the " +
                "text. Lines naming the local draft in sendInternal were:",
            listOf(
                "holdsStoredDraft = _editingLocalDraftId.value != null || editingDraftId != null || " +
                    "editingOutboxId != null,",
                "val id = commitThenConsumeLocalDraft(",
                "localDraftId = _editingLocalDraftId.value,",
                "consume = { local -> consumeEditingLocalDraft(local) },",
            ),
            send.filter { "LocalDraft" in it },
        )
        val scheduled = codeLines(bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend"))
        assertTrue(
            "scheduleSend does not go through commitThenConsumeLocalDraft at all. Body was:\n" +
                scheduled.joinToString("\n"),
            scheduled.indexOf("commitThenConsumeLocalDraft(") >= 0,
        )
        assertEquals(
            "a SCHEDULED send commits the message just as truly, and the same order applies: the " +
                "row is inserted and its worker booked, then the local draft goes. Left behind, " +
                "the draft is uploaded to the server's Drafts and the message is sent from " +
                "somewhere else hours later — a copy of it stays in Drafts for good. Lines were:",
            listOf(
                "commitThenConsumeLocalDraft(",
                "localDraftId = _editingLocalDraftId.value,",
                "commit = {",
                "val id = repo.insertScheduledSend(",
            ),
            scheduled.subList(scheduled.indexOf("commitThenConsumeLocalDraft("), scheduled.indexOf("commitThenConsumeLocalDraft(") + 4),
        )
        assertEquals(
            "the same three on the scheduled route, under the same fourth line: the refusal reads " +
                "the row to choose its sentence, and it reads it BEFORE anything is committed.",
            listOf(
                "holdsStoredDraft = _editingLocalDraftId.value != null || editingDraftId != null || " +
                    "editingOutboxId != null,",
                "commitThenConsumeLocalDraft(",
                "localDraftId = _editingLocalDraftId.value,",
                "consume = { local -> consumeEditingLocalDraft(local) },",
            ),
            scheduled.filter { "LocalDraft" in it },
        )
        assertEquals(
            "the consume itself, whole: the account it was leased under (#31 — ids collide " +
                "between two accounts of one server), the lease dropped synchronously, and the row " +
                "with its files. Body was:",
            listOf(
                "val account = editingLocalDraftAccountId ?: return",
                "_editingLocalDraftId.value = null",
                "repo.consumeLocalDraft(account, id)",
            ),
            functionBody(COMPOSE_VIEW_MODEL, "private suspend fun consumeEditingLocalDraft("),
        )
    }

    /**
     * **A body this composer LOST is refused in front of BOTH sends** (#95 × the parcel).
     */
    @Test fun `a lost body is refused in front of both sends, on the RAW body`() {
        val send = codeLines(bodyOf(COMPOSE_VIEW_MODEL, "sendInternal"))
        val sendGuard = lostBodyGuard(SEND_LOST_BODY_BODY, "return")
        val inSend = send.indexOf(LOST_BODY_HEAD)
        assertTrue(
            "sendInternal opens with no lost-body guard at all, or no longer opens it with:\n" +
                "    $LOST_BODY_HEAD\n⛔ Body was:\n" + send.joinToString("\n"),
            inSend >= 0,
        )
        assertEquals(
            "the guard, whole and adjacent: it decides, it SAYS THE RIGHT SENTENCE, and it " +
                "returns. `args.body` is the composer's RAW body and the only argument that proves " +
                "anything — `textBody` carries the signature, is never blank, and turns this into " +
                "dead code. `holdsStoredDraft` must OR all THREE stores: short of any of them it " +
                "answers \"Type it again.\" over a copy sitting intact — a server draft in Drafts, " +
                "a queued row parked EDITING behind Outbox → Edit — and retyping is what sends " +
                "the text after it, the queued row included. Without the notice the tap does nothing " +
                "visible and the user taps again; with a line between the refusal and the return " +
                "the send goes on anyway. Lines were:",
            sendGuard,
            send.subList(inSend, (inSend + sendGuard.size).coerceAtMost(send.size)),
        )
        assertTrue(
            "…and it must sit ABOVE `_state.value = ComposeState.Sending` (index " +
                "${send.indexOf(SENDING)}) and ABOVE the commit that consumes the row (index " +
                "${send.indexOf(COMMIT)}); the guard is at $inSend. Below the flag the composer is " +
                "already sending a message it is about to refuse; below the commit the only copy " +
                "of the text is already destroyed.",
            inSend < send.indexOf(SENDING) && inSend < send.indexOf(COMMIT),
        )

        val scheduled = codeLines(bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend"))
        val scheduleGuard = lostBodyGuard(SCHEDULE_LOST_BODY_BODY, "return false")
        val inScheduled = scheduled.indexOf(LOST_BODY_HEAD)
        assertTrue(
            "scheduleSend opens with no lost-body guard at all, or no longer opens it with:\n" +
                "    $LOST_BODY_HEAD\n⛔ Body was:\n" + scheduled.joinToString("\n"),
            inScheduled >= 0,
        )
        assertEquals(
            "the same lines, for the same reasons, on the other route — `body` being this " +
                "function's own parameter, the raw one, while the `textBody` computed below it " +
                "carries the signature and can never be blank. A scheduled send commits just as " +
                "truly (the row is written and its worker booked) and consumes the local draft the " +
                "same way. ⛔ It leaves on `return false`, not on `return`: the screen puts up " +
                "\"Scheduled — <date>\" on what this function answers, so a refusal that reports " +
                "nothing is announced as a schedule and contradicted by the notice one line later. " +
                "Lines were:",
            scheduleGuard,
            scheduled.subList(inScheduled, (inScheduled + scheduleGuard.size).coerceAtMost(scheduled.size)),
        )
        assertTrue(
            "…and above `_state.value = ComposeState.Sending` (index ${scheduled.indexOf(SENDING)}) " +
                "and above `commitThenConsumeLocalDraft(` (index " +
                "${scheduled.indexOf("commitThenConsumeLocalDraft(")}); the guard is at " +
                "$inScheduled.",
            inScheduled < scheduled.indexOf(SENDING) &&
                inScheduled < scheduled.indexOf("commitThenConsumeLocalDraft("),
        )

        assertEquals(
            "…and the ACCEPTED path answers `true`, on the last line of scheduleSend. The screen " +
                "shows \"Scheduled — <date>\" on that answer and on nothing else, so `false` here " +
                "is a message scheduled in silence — the mirror of the defect above, and just as " +
                "invisible from the pins on the refusals. Body ended with:",
            "return true",
            scheduled.last(),
        )

        val code = codeLines(codeOf(COMPOSE_VIEW_MODEL.readText()))
        assertEquals(
            "⛔ …and the decision is asked in exactly TWO places, both of them a send. A third " +
                "call — a save, a helper, a `canSend` recomputed — is a second answer to the same " +
                "question, and the rules above all stay green. Lines calling it were:",
            listOf(LOST_BODY_HEAD, LOST_BODY_HEAD),
            code.filter { "lostBodySendWording(" in it },
        )
        assertEquals(
            "⛔ …and the refusal itself is asked ONLY through that wording, never beside it: a " +
                "second `composerMaySendBody(` in this file is a route refusing without saying " +
                "which sentence it owes, which is exactly the state this branch shipped in. Lines " +
                "calling it were:",
            emptyList<String>(),
            code.filter { "composerMaySendBody(" in it },
        )
    }

    /**
     * **The SAVE has a decision of its own, and it is asked ONCE.**
     */
    @Test fun `the save asks what it would destroy exactly once, on the raw body and the queued row`() {
        val code = codeLines(codeOf(COMPOSE_VIEW_MODEL.readText()))
        assertEquals(
            "⛔ `lostBodySaveDestroysQueuedRow(` belongs in exactly ONE place, the head of " +
                "saveDraft, and with these arguments. A second call is a second answer to the " +
                "question of what a save destroys; a changed argument is the guard gone dead while " +
                "reading like a working one. Lines calling it were:",
            listOf(SAVE_LOST_BODY_GUARD),
            code.filter { "lostBodySaveDestroysQueuedRow(" in it },
        )
    }

    @Test fun `emptying a reopened local draft really deletes it, and does not requeue it`() {
        val save = codeLines(bodyOf(COMPOSE_VIEW_MODEL, "saveDraft"))
        val from = save.indexOf("val original = editingDraftId")
        val to = save.indexOf("submit(to) { credentials, recipients ->")
        assertTrue(
            "saveDraft no longer opens its emptied-draft branch with `val original = " +
                "editingDraftId`, or no longer saves below it — did the branch move? Body was:\n" +
                save.joinToString("\n"),
            from in 0 until to,
        )
        val emptied = save.subList(from, to)
        assertEquals(
            "⛔ the emptied-draft branch, whole and in order (#69 × #95). Three things it must get " +
                "right, and all three were wrong: `repo.discardDraft` on a `local-draft:` id " +
                "destroys NOTHING (destroyableUnderNumbering answers an empty list for an id no " +
                "server issued) while the screen closes as a success; the phone's own row must be " +
                "given back to NOBODY on this close, since abandon() hands it back PENDING and " +
                "re-arms its upload at the row's own instant — for a draft saved offline, now — so " +
                "the text the user just emptied would race the delete to the server; and that " +
                "\"nobody\" is said to abandon() as `emptying = true`, ⛔ never by blanking " +
                "`editingLocalDraftId` first. One field cannot carry both \"which row I hold\" and " +
                "\"give nothing back this once\": blanked, a submit() that FAILED left the row " +
                "EDITING with nobody left to release it — invisible to the upload worker until the " +
                "next cold start, on the very path where the user was told the deletion failed. " +
                "Branch was:",
            listOf(
                "val original = editingDraftId",
                "val originalUidValidity = editingDraftUidValidity",
                "val originalAccountId = editingDraftAccountId",
                "abandon(emptying = true, body = body.text)",
                "if (original == null) {",
                "_state.value = ComposeState.Done",
                "} else if (draftOpenRoute(original) == DraftOpenRoute.LOCAL) {",
                "submit(to) { _, _ -> destroyReplacedServerDraft(original) }",
                "} else {",
                // …and the SERVER route reads the same fidelity verdict this one does: a draft
                // the composer never read whole keeps its server copy and the screen says so
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
            ),
            emptied,
        )
    }

    @Test fun `emptying a local draft destroys the server draft it was standing in front of`() {
        assertEquals(
            "⛔ the emptied-local-draft gesture, whole and in order (#95 × #69). SOURCE LINT, and " +
                "read EmptiedLocalDraftTest first: both decisions are executed there — what the " +
                "order carries (the server id, its folder, the numbering frozen on the row) and " +
                "the order of the three acts. What no JVM test can see from here is what this " +
                "class HANDS them, and every argument below is load-bearing:\n" +
                "  · `mailboxId` — without it the JMAP destroy spares every id (#122) and the " +
                "screen still closes as a success;\n" +
                "  · `replacedServerDraftUidValidity` — without it the IMAP destroy has nothing to " +
                "oppose and destroys nothing (#99). It is the value FROZEN on the row; a " +
                "`repo.recordedUidValidity…` call here re-reads it and is the defect itself;\n" +
                "  · `destroyDurably`, never `MessageDestroyWorker.schedule` — that one takes the " +
                "account's unique work slot with REPLACE and would silently drop a permanent " +
                "delete the user confirmed seconds ago, still inside her undo window;\n" +
                "  · `evict` — without it the draft stays on screen until the worker has run;\n" +
                "  · `consume` — without it the row is left behind and re-uploaded at the next " +
                "launch;\n" +
                "  · `bodyIsLossy = editingDraftLossy` — the verdict that KEEPS the server copy " +
                "when this phone never read it whole. A `false` written here destroys, for ever, " +
                "attachments and markup the user was never shown;\n" +
                "  · `tell` — the word that keeping it owes her (WYSIWYG). Dropped, the app " +
                "silently does the opposite of what she asked;\n" +
                "  · ⛔ `credentialsDestroyingReplacedServerDraft(leasedUnderAccountId = " +
                "editingLocalDraftAccountId, …)` — the account the ROW was leased under, and the " +
                "one thing here that must NOT come from the caller. `credentials()` answers the " +
                "\"From\" picker, which offers every account: with From switched to another " +
                "account the folder, the destroy and the eviction all went to a server that never " +
                "issued this id, failed in silence, and the row was consumed under its own account " +
                "anyway — mask lifted, pre-edit text back at the top of Drafts for good. " +
                "⛔ And NO fallback: `?: credentials()` appended to that call is the defect " +
                "restored, which is why these lines are compared WHOLE.\n" +
                "Body was:",
            listOf(
                "val credentials = credentialsDestroyingReplacedServerDraft(",
                "leasedUnderAccountId = editingLocalDraftAccountId,",
                "composingAsAccountId = _selectedFrom.value?.accountId ?: accountId,",
                "lookup = { store.credentials(it) },",
                ") ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))",
                "val serverDraft = replacedServerDraftId",
                "val plan = planForEmptiedLocalDraft(",
                "replacedServerDraftId = serverDraft,",
                "replacedServerDraftUidValidity = replacedServerDraftUidValidity,",
                "mailboxId = serverDraft?.let { repo.draftMailboxOf(credentials, it) },",
                "bodyIsLossy = editingDraftLossy,",
                // The second keeping term, read here for the same reason `mailboxId` is: it
                // takes a server read, and the decision stays pure. Under the account the ROW was
                "addressingIsProvenEmpty = serverDraft != null && !editingDraftLossy &&",
                "repo.emptiedDraftAddressingIsProvenEmpty(credentials, serverDraft),",
                ")",
                "destroyThenConsumeEmptiedLocalDraft(",
                "localDraftId = localDraftId,",
                "plan = plan,",
                "destroy = { MessageDestroyWorker.destroyDurably(getApplication(), credentials.id, listOf(it)) },",
                "evict = { ids -> repo.evictAll(credentials.id, ids) },",
                "consume = { id -> consumeEditingLocalDraft(id) },",
                "tell = { _notices.tryEmit(R.string.compose_emptied_draft_server_copy_kept) },",
                ")",
            ),
            codeLines(bodyOf(COMPOSE_VIEW_MODEL, "destroyReplacedServerDraft")),
        )
        assertEquals(
            "⛔ …and it takes NO credentials. Handed them, the ACCOUNT is decided by the caller, " +
                "and both callers are the composer — whose `credentials()` is the account of the " +
                "identity on show. The parameter is the defect; deleting it is the fix. " +
                "Declarations found were:",
            listOf("private suspend fun destroyReplacedServerDraft(localDraftId: String) {"),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText()))
                .filter { it.startsWith("private suspend fun destroyReplacedServerDraft") },
        )
        assertEquals(
            "⛔ …and the account is resolved in exactly ONE place. A second call — a helper, an " +
                "\"already resolved above\" shortcut — is a second answer to the same question, " +
                "and the tests above all stay green. Lines were:",
            listOf("val credentials = credentialsDestroyingReplacedServerDraft("),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText()))
                .filter { "credentialsDestroyingReplacedServerDraft(" in it },
        )
    }

    /**
     * **Deleting a draft the phone is keeping is the EMPTYING, reached by a button** (#95 × #69).
     */
    @Test fun `deleting a kept draft is the emptying gesture, reached by a button`() {
        assertEquals(
            "⛔ the deletion, whole and in order. ⛔ It resolves NO account of its own: " +
                "`credentials()` here is the account of the identity the \"From\" picker is " +
                "showing, and the picker covers every account — handed over, the destroy of the " +
                "server draft this row replaces left for a server that never issued its id while " +
                "the row was consumed under its own account, which is the whole defect. The " +
                "account is the one the ROW was leased under and it is resolved inside " +
                "destroyReplacedServerDraft, which throws when that account has gone — caught " +
                "here, banner drawn, lease given back, nothing destroyed. Body was:",
            listOf(
                "val id = heldEditingLocalDraft() ?: return",
                "_state.value = ComposeState.Sending",
                "viewModelScope.launch {",
                "var destroyed = false",
                "try {",
                "destroyReplacedServerDraft(id)",
                "destroyed = true",
                "_localDraftDeleted.tryEmit(Unit)",
                "} catch (t: Throwable) {",
                "_state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName, whileSaving = true)",
                "} finally {",
                "localDraftLeaseAfterDelete(_editingLocalDraftId.value, destroyed)?.let { releaseLocalDraftEdit(it) }",
                "}",
                "}",
            ),
            codeLines(bodyOf(COMPOSE_VIEW_MODEL, "deleteEditingLocalDraft")),
        )
        assertEquals(
            "…and the read, whole: it refuses while a send is in flight (INV-6 — that send consumes " +
                "the row itself, and a delete racing it destroys the message twice) and on a lease " +
                "already given back, and it CLEARS NOTHING. Body was:",
            listOf(
                "if (_state.value is ComposeState.Sending) return null",
                "return _editingLocalDraftId.value",
            ),
            codeLines(bodyOf(COMPOSE_VIEW_MODEL, "heldEditingLocalDraft")),
        )
        assertEquals(
            "⛔ …and the lease is PUBLISHED from one holder, not a var beside a flow: two of them " +
                "drift, and the screen would offer a Delete over a row this class no longer holds. " +
                "Lines declaring it were:",
            listOf(
                "private val _editingLocalDraftId = MutableStateFlow<String?>(null)",
                "val editingLocalDraftId: StateFlow<String?> = _editingLocalDraftId.asStateFlow()",
            ),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText()))
                .filter { it.startsWith("private val _editingLocalDraftId") || it.startsWith("val editingLocalDraftId") },
        )
        val lines = codeLines(codeOf(COMPOSE_VIEW_MODEL.readText()))
        assertEquals(
            "\u26d4 \u2026and the ACCOUNT that lease belongs to is a PLAIN field, declared once. " +
                "Everything this gesture does is scoped by it \u2014 the folder the destroy is " +
                "opposed to, the destroy itself, the eviction, the consume and the release \u2014 " +
                "so it is the one value that decides which server hears about this draft, and no " +
                "JVM test can instantiate this class to watch it. Renaming or re-typing it here " +
                "silently moves every one of those. Lines declaring it were:",
            listOf("private var editingLocalDraftAccountId: String? = null"),
            lines.filter { it.startsWith("private var editingLocalDraftAccountId") },
        )
        val at = lines.indexOf("private var editingLocalDraftAccountId: String? = null")
        assertTrue(
            "\u26d4 \u2026and it is a field, NOT a computed one: `get() = _selectedFrom.value" +
                "?.accountId ?: field` compiles, leaves every other rule in this file green, and " +
                "restores the whole defect one layer beneath them \u2014 worse than the original, " +
                "because the row would then also be consumed and released under the account of " +
                "whatever identity the \"From\" picker happens to be showing. The line after the " +
                "declaration was: " + lines.getOrNull(at + 1),
            lines.getOrNull(at + 1)?.let { !it.startsWith("get()") && !it.startsWith("set(") } == true,
        )
    }

    /**
     * **A save gives the row back too, and until this line nothing did** (#95 × #31).
     */
    @Test fun `a save that went through gives the phone's own row back`() {
        assertEquals(
            "⛔ submit(), whole and in order, and two of these lines are defects on their own:\n" +
                "  · the lease is read INSIDE the finally (`_editingLocalDraftId.value`), never " +
                "captured into a val before the try. Captured, a stale id is handed to the upload " +
                "worker — on the empty-save routes the row is consumed inside the gesture — and " +
                "the draft that was just deleted goes back to PENDING and is filed in the " +
                "server's Drafts, for good;\n" +
                "  · `saved = true` sits AFTER op(...), never before it. Before, a save that threw " +
                "releases the row while the composer is still open on it: the upload worker takes " +
                "the half-typed text up to the server and discardLocalDraft deletes the row with " +
                "its attachment folder, under a banner that says the save failed.\n" +
                "Body was:",
            listOf(
                "if (_state.value is ComposeState.Sending) return",
                "_state.value = ComposeState.Sending",
                "viewModelScope.launch {",
                "var saved = false",
                "try {",
                "val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))",
                "op(credentials, parseAddrs(to))",
                "saved = true",
                "_state.value = ComposeState.Done",
                "} catch (t: Throwable) {",
                "_state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName, whileSaving = true)",
                "} finally {",
                "localDraftLeaseAfterSave(_editingLocalDraftId.value, saved)?.let { releaseLocalDraftEdit(it) }",
                "}",
                "}",
            ),
            codeLines(bodyOf(COMPOSE_VIEW_MODEL, "submit")),
        )
        assertEquals(
            "⛔ …and these four lines, in this order — which is what this can see, and it is " +
                "less than \"submit() is called from saveDraft and from nowhere else\": it reads " +
                "TEXT, so a fourth caller adds an entry and goes red, while MOVING one of the " +
                "three into a private helper declared in between and called from sendInternal " +
                "would not. What a fourth caller brings is the case this wiring has no guard " +
                "for: a send in flight consumes the local row itself (INV-6), and a release on " +
                "its success hands the upload worker a copy of the message that has just gone " +
                "out. So the day this list changes, go and read WHERE from. Declaration and " +
                "calls were:",
            listOf(
                "submit(to) { _, _ -> destroyReplacedServerDraft(original) }",
                "submit(to) { _, _ ->",
                "submit(to) { credentials, recipients ->",
                "private inline fun submit(",
            ),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText())).filter { "submit(" in it },
        )
    }

    /**
     * M56's only executioner. `destroyDurably` cannot be executed anywhere: WorkManager is not
     */
    @Test fun `the emptied draft's destroy never takes the account's one work slot`() {
        assertEquals(
            "MessageDestroyWorker.destroyDurably, whole. Body was:",
            listOf(
                "destroyRequests(accountId, folders, delayMs = 0L)",
                ".forEach { WorkManager.getInstance(context).enqueue(it).await() }",
            ),
            codeLines(bodyOf(DESTROY_WORKER, "destroyDurably")),
        )
    }

    /**
     * M55's second half. `draftDestroyFolder` is EXECUTED by `DraftDestroyFolderTest` (core:data)
     */
    @Test fun `the draft's destroy folder is decided by the pure function, fed both answers`() {
        assertEquals(
            "MailRepository.draftMailboxOf, whole: the two account-scoped Room reads (ids collide " +
                "between two accounts of one server, #31) handed to draftDestroyFolder. Body was:",
            listOf(
                "suspend fun draftMailboxOf(credentials: AccountCredentials, emailId: String): String? =",
                "draftDestroyFolder(",
                "cachedMailboxId = emailDao.mailboxOf(credentials.id, emailId),",
                "draftsRoleMailboxId = mailboxDao.idForRole(credentials.id, \"drafts\"),",
                ")",
            ),
            expressionBodyOf(MAIL_REPOSITORY, "suspend fun draftMailboxOf("),
        )
    }

    @Test fun `the re-save is armed with the local row's own id`() {
        val body = functionBody(COMPOSE_VIEW_MODEL, "private suspend fun prepareLocalDraft(")
        assertEquals(
            "editingDraftId is what a re-save lands on (localDraftTarget looks a local id up by " +
                "id) — it must be the projection's value, whole. Anything else mints a SECOND row " +
                "for the same draft at the next save: two rows, two Message-IDs, two uploads. " +
                "Assignments found were:",
            listOf("editingDraftId = fields.editingDraftId"),
            body.filter { it.startsWith("editingDraftId") },
        )
        assertEquals(
            "…and the fidelity verdict travels WITH it, off the row that carries it: `false` here " +
                "authorises destroying an original this composer cannot reproduce. ⛔ It is written " +
                "to `editingDraftLossyOnOpen` — what the ROW held — while the destroy routes read " +
                "`editingDraftLossy`, the OR of that and the body this composer lost on the way " +
                "into a parcel. Assignments found were:",
            listOf("editingDraftLossyOnOpen = fields.bodyIsLossy"),
            body.filter { it.startsWith("editingDraftLossy") },
        )
        assertEquals(
            "⛔ …and this route arms NEITHER the numbering NOR the account that pair carries on the " +
                "server route. The id above is a `local-draft:` one: no server issued it, so a " +
                "numbering beside it would license an expunge against an id that names nothing, " +
                "and an account beside it would say WHICH server to send that expunge to. Both " +
                "stay null, which is what makes the empty-save take the LOCAL branch and destroy " +
                "the replaced server draft under the account the ROW was leased under " +
                "(editingLocalDraftAccountId) instead. Assignments found were:",
            emptyList<String>(),
            body.filter { it.startsWith("editingDraftUidValidity") || it.startsWith("editingDraftAccountId") },
        )
        assertTrue(
            "the fields must come from the pure projection localDraftPrefill(row) — a prefill " +
                "built by hand here is a list of columns nobody checks, and the one it forgets " +
                "(Bcc, In-Reply-To, the attachments) is silently dropped from the draft. Body " +
                "was:\n${body.joinToString("\n")}",
            body.contains("val fields = localDraftPrefill(row)"),
        )
    }

    @Test fun `the lease is taken at the open and given back when the screen goes`() {
        val open = functionBody(COMPOSE_VIEW_MODEL, "private suspend fun prepareLocalDraft(")
        assertEquals(
            "the row must be remembered SYNCHRONOUSLY as the lease is taken, account and id, or " +
                "abandon() below has nothing to give back — and it is `arm`, run by " +
                "takeLocalDraftEditOrGiveItBack the instant the take answers, that does it. Lines were:",
            listOf("editingLocalDraftAccountId = credentials.id", "_editingLocalDraftId.value = taken.id"),
            open.filter { "editingLocalDraft" in it },
        )
        assertEquals(
            "⛔ abandon(), whole and in order. The local release belongs in THE MIDDLE — under the " +
                "`Sending` guard and above the outbox `?: return` — and both neighbours are a " +
                "defect, which is why the decision is taken in values by `abandonPlan` " +
                "(SendDraftTargetTest executes it) and not by the order of statements. ABOVE the " +
                "guard: closing the screen during a send re-arms the upload of the row that send " +
                "is already consuming, and a copy of the message that has just gone out is filed " +
                "in the server's Drafts, put back by every launch. BELOW the outbox `?: return`: " +
                "every reopened local draft has a null outbox id, so the release never runs at all " +
                "and the row stays EDITING for ever, held back from the upload worker by the very " +
                "state that was meant to be temporary. Body was:",
            listOf(
                "val plan = abandonPlan(",
                "sending = _state.value is ComposeState.Sending,",
                "emptying = emptying,",
                // The queued row's door is decided on the RAW body handed in, through the one
                // reading of a lost body the send and the save already use — never on a flag
                // saying a refusal was shown, and never inside the ViewModel.
                "parkOutbox = closingParksQueuedRow(composerBodyWasLost, body),",
                "localDraftId = _editingLocalDraftId.value,",
                "outboxId = editingOutboxId,",
                ")",
                "plan.localDraftToRelease?.let { releaseLocalDraftEdit(it) }",
                "plan.outboxToRelease?.let { id ->",
                "editingOutboxId = null",
                "_editingOutbox.value = false",
                "appScope.launch {",
                "runCatching { repo.releaseOutboxEdit(id) }",
                ".onFailure { android.util.Log.w(\"SternaCompose\", \"couldn't release the edited outbox item\", it) }",
                "}",
                "}",
                "plan.outboxToPark?.let { id ->",
                "editingOutboxId = null",
                "_editingOutbox.value = false",
                "appScope.launch {",
                "runCatching { repo.parkInterruptedOutboxEdit(id) }",
                ".onFailure { android.util.Log.w(\"SternaCompose\", \"couldn't park the interrupted outbox item\", it) }",
                "}",
                "}",
            ),
            codeLines(bodyOf(COMPOSE_VIEW_MODEL, "abandon")),
        )
        assertEquals(
            "…and the release itself, whole: the id is HANDED IN by the plan — read back off the " +
                "field here, a call slipping past the plan would release during a send again — the " +
                "field is cleared synchronously, and the write goes through appScope, since " +
                "viewModelScope is cancelled by the back gesture that triggers this and the write " +
                "would simply never happen. Body was:",
            listOf(
                "val account = editingLocalDraftAccountId ?: return",
                "_editingLocalDraftId.value = null",
                "appScope.launch {",
                "runCatching { repo.releaseLocalDraftEdit(account, id) }",
                ".onFailure { android.util.Log.w(\"SternaCompose\", \"couldn't release the edited local draft\", it) }",
                "}",
            ),
            codeLines(bodyOf(COMPOSE_VIEW_MODEL, "releaseLocalDraftEdit")),
        )
    }

    /**
     * **The DECLARATION of `abandon`, whole — not its body.** The rule above reads the body, and
     */
    @Test fun `the emptying flag is off by default, and only the emptied save turns it on`() {
        assertEquals(
            "⛔ the declaration, WHOLE — `= true` here is a one-character mutation that leaves the " +
                "phone's own draft row EDITING on every ordinary close, invisible to the upload " +
                "worker until the next cold start — and every line of this file that names " +
                "abandon(, in order. Lines were:",
            listOf(
                "fun abandon(body: String, emptying: Boolean = false) {",
                "abandon(emptying = true, body = body.text)",
            ),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText())).filter { "abandon(" in it },
        )
        assertEquals(
            "…and the ordinary close, on the screen, must go on calling it WITHOUT `emptying`, and " +
                "WITH the editor's raw body: it is the one caller that must hand the local row back " +
                "to the upload worker, and the body is what decides whether the queued row is " +
                "parked or given back. A `viewModel.abandon(body.text, emptying = true)` slipped " +
                "in here is the same defect from the other end, and the declaration rule above " +
                "cannot see it. Calls were:",
            listOf("viewModel.abandon(body.text)"),
            codeLines(codeOf(COMPOSE_SCREEN.readText())).filter { "abandon(" in it },
        )
    }

    // -- reading the source (the same instruments as DraftVerdictWiringTest) ----------------------

    /** The body of `fun [name](` in [file], braces balanced, comments cut. */
    private fun bodyOf(file: File, name: String): String {
        val code = codeOf(file.readText())
        val at = Regex("""\bfun\s+$name\s*\(""").find(code)
            ?: error("${file.name} declares no 'fun $name(' — did it get renamed, or not written yet?")
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

    /**
     * The body of the declaration whose line contains [opener] in [file] — the lines between the
     * brace it opens and the one that closes it, trimmed, in order, braces excluded.
     */
    private fun functionBody(file: File, opener: String): List<String> {
        val lines = codeOf(file.readText()).lines()
        val start = lines.indexOfFirst { opener in it }
        check(start >= 0) { "${file.name} has no line containing '$opener' — renamed, or not written yet?" }
        var depth = 0
        var seen = false
        val out = mutableListOf<String>()
        for (i in start until lines.size) {
            val line = lines[i]
            val next = depth + line.count { it == '{' } - line.count { it == '}' }
            if (seen) {
                if (next <= 0) return out
                line.trim().takeIf { it.isNotEmpty() }?.let { out += it }
            }
            depth = next
            if (depth > 0) seen = true
        }
        return out
    }

    /**
     * The declaration line of [opener] in [file] and its EXPRESSION body — from the `=` that ends
     */
    private fun expressionBodyOf(file: File, opener: String): List<String> {
        val lines = codeOf(file.readText()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val start = lines.indexOfFirst { opener in it }
        check(start >= 0) { "${file.name} has no line containing '$opener' — renamed, or not written yet?" }
        check(lines[start].endsWith("=")) {
            "'$opener' is no longer a one-expression body — it reads:\n${lines[start]}"
        }
        val out = mutableListOf(lines[start])
        var depth = 0
        for (i in start + 1 until lines.size) {
            out += lines[i]
            depth += lines[i].count { it == '(' } - lines[i].count { it == ')' }
            if (depth <= 0) return out
        }
        error("unbalanced parentheses under '$opener' in ${file.name}")
    }

    /** [body]'s code lines, trimmed, blanks dropped. */
    private fun codeLines(body: String): List<String> =
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** [text] with every comment taken out — block comments tracked, `//` honoured outside strings. */
    private fun codeOf(text: String): String {
        val out = StringBuilder()
        var inBlockComment = false
        for (raw in text.lines()) {
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
            out.append(code.toString().trimEnd()).append('\n')
        }
        return out.toString()
    }

    private companion object {
        /** The re-entry flag both sends raise, and the line every guard here must sit above. */
        const val SENDING = "_state.value = ComposeState.Sending"

        /** The line that destroys the row: the guard is above it or it guards nothing. */
        const val COMMIT = "val id = commitThenConsumeLocalDraft("

        /**
         * The head of the two calls to `lostBodySendWording`, and the arguments each hands it —
         */
        const val LOST_BODY_HEAD = "val lostBody = lostBodySendWording("
        const val LOST_BODY_LOST = "bodyWasLost = composerBodyWasLost,"
        const val SEND_LOST_BODY_BODY = "body = args.body.text,"
        const val SCHEDULE_LOST_BODY_BODY = "body = body.text,"
        const val LOST_BODY_HELD =
            "holdsStoredDraft = _editingLocalDraftId.value != null || editingDraftId != null || " +
                "editingOutboxId != null,"

        /**
         * …and the sentence the refusal owes the reader, which is TWO sentences and not one: a tap
         */
        val LOST_BODY_NOTICE = listOf(
            "_notices.tryEmit(",
            "when (lostBody) {",
            "LostBodyWording.REOPEN_DRAFT -> R.string.compose_body_lost_reopen_draft",
            "LostBodyWording.RETYPE -> R.string.compose_body_lost_cannot_send",
            "},",
            ")",
        )

        /**
         * …and the SAVE route's guard, whole, for the same reason: the arguments ARE the proof.
         */
        const val SAVE_LOST_BODY_GUARD =
            "if (lostBodySaveDestroysQueuedRow(bodyWasLost = composerBodyWasLost, body = body.text, " +
                "holdsQueuedRow = editingOutboxId != null)) {"

        /** The refusal of one route, whole: decide, say which sentence, leave. */
        fun lostBodyGuard(bodyArgument: String, exit: String): List<String> =
            listOf(LOST_BODY_HEAD, LOST_BODY_LOST, bodyArgument, LOST_BODY_HELD, ")", "if (lostBody != null) {") +
                LOST_BODY_NOTICE + listOf(exit, "}")

        val COMPOSE_VIEW_MODEL: File by lazy {
            repoFile("app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt")
        }

        /** The screen the ordinary close lives on — the caller the default value exists for. */
        val COMPOSE_SCREEN: File by lazy {
            repoFile("app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt")
        }

        val DESTROY_WORKER: File by lazy {
            repoFile("app/src/main/kotlin/app/sterna/mail/MessageDestroyWorker.kt")
        }

        /** Another module's source, read as text on purpose: the gesture crosses the seam. */
        val MAIL_REPOSITORY: File by lazy {
            repoFile("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        }

        private fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("cannot find $path from ${File("").absolutePath}")
    }
}
