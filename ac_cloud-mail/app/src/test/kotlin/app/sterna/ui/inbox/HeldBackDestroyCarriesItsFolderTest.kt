package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, and a narrow one: neither `InboxViewModel` nor `MessageDestroyWorker` can be
 */
class HeldBackDestroyCarriesItsFolderTest {

    /** The code lines of [body], comments and blanks dropped, whitespace normalised. */
    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * The order of two WHOLE code lines of [lines], with BOTH required to exist.
     */
    private fun assertLineOrder(what: String, lines: List<String>, first: String, then: String) {
        val i = lines.indexOf(first)
        val j = lines.indexOf(then)
        assertTrue("$what — no code line reads exactly <$first>; the rule guards nothing", i >= 0)
        assertTrue("$what — no code line reads exactly <$then>; the rule guards nothing", j >= 0)
        assertTrue("$what — <$first> (line $i) must come BEFORE <$then> (line $j)", i < j)
    }

    /** The block body of `fun [name]` in [file] — braces included. */
    private fun bodyOf(file: File, name: String): String {
        val source = file.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${file.name} has no function named '$name' — did it get renamed?")
        val open = source.indexOf('{', fn.range.last)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in ${file.name}.$name")
    }

    @Test fun theWorkerHandsTheDestroyTheFolderItWasEnqueuedWith() {
        val body = bodyOf(WORKER, "doWork")
        assertEquals(
            "the worker must read the expected folder out of its own Data",
            listOf("val mailboxId = inputData.getString(KEY_MAILBOX_ID)"),
            codeLinesNaming(body, "KEY_MAILBOX_ID"),
        )
        assertEquals(
            "the worker must hand that folder AND that numbering to destroyAll — without them " +
                "the destroy can tell neither a rescued message from one still in the Trash " +
                "(#122) nor a renumbered folder from the one confirmed (#99)",
            listOf(
                "if (ids.isNotEmpty() && repo.destroyAll(credentials, ids, mailboxId, uidValidity)" +
                    ".failed.isNotEmpty()) {",
            ),
            codeLinesNaming(body, "repo.destroyAll("),
        )
    }

    /**
     * M2's only executioner. The numbering must come out of THIS REQUEST'S `Data` — frozen when
     */
    @Test fun theWorkerOpposesTheNumberingItWasENQUEUED_withNotTheCurrentOne() {
        val body = bodyOf(WORKER, "doWork")
        assertEquals(
            "the worker must read the frozen numbering out of its own Data, never ask for it",
            listOf("val uidValidity = inputData.getLong(KEY_UID_VALIDITY, 0L).takeIf { it > 0L }"),
            codeLinesNaming(body, "KEY_UID_VALIDITY"),
        )
        assertEquals(
            "doWork must not look the numbering up at execution time — that is the defect",
            emptyList<String>(),
            codeLinesNaming(body, "recordedUidValidity"),
        )
    }

    @Test fun everyEnqueuedRequestCarriesTheFolderOfTheIdsItHolds() {
        val body = bodyOf(WORKER, "destroyRequests")
        assertEquals(
            "each request's Data must carry the ids of THAT chunk",
            listOf("KEY_EMAIL_IDS to chunk.toTypedArray(),"),
            codeLinesNaming(body, "KEY_EMAIL_IDS"),
        )
        assertEquals(
            "each request's Data must carry the folder of the ids in THAT request",
            listOf("KEY_MAILBOX_ID to folder.mailboxId,"),
            codeLinesNaming(body, "KEY_MAILBOX_ID"),
        )
        assertEquals(
            "and the numbering THAT folder was under when the user confirmed — 0 when there is " +
                "none, which destroys nothing rather than destroying unopposed (#99)",
            listOf("KEY_UID_VALIDITY to (folder.uidValidity ?: 0L),"),
            codeLinesNaming(body, "KEY_UID_VALIDITY"),
        )
    }

    @Test fun theViewModelGroupsTheHoldBackByFolderNotOnlyByAccount() {
        val held = bodyOf(INBOX_VIEW_MODEL, "heldBackDestroy")
        assertEquals(
            "the hold-back must record the folder each message sat in when the user confirmed",
            listOf("val targets = emails.mapNotNull { e -> credentialsFor(e)?.let { Triple(it, e.id, e.mailboxId.orEmpty()) } }"),
            codeLinesNaming(held, "credentialsFor(e)"),
        )
        assertEquals(
            "the scheduled destroy must be grouped by account AND folder, and carry the frozen numbering",
            listOf(
                "MessageDestroyWorker.schedule(getApplication(), accountId, foldersToDestroy(accountId, rows, numbering), " +
                    "PURGE_HOLD_BACK_MS)",
            ),
            codeLinesNaming(held, "MessageDestroyWorker.schedule("),
        )
        assertEquals(
            "flushing a superseded hold-back destroys the same set — it must carry the same folders " +
                "and the numbering ALREADY captured, not a fresh one",
            listOf("MessageDestroyWorker.flushNow(getApplication(), accountId, foldersToDestroy(accountId, rows, numbering))"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "flushPendingDestroy"), "MessageDestroyWorker.flushNow("),
        )
    }

    /**
     * The freeze itself: read ONCE, in the hold-back, before the destroy is enqueued. `flushNow`
     */
    @Test fun theViewModelFreezesTheNumberingWithTheConfirmationAndReplaysIt() {
        assertEquals(
            "the hold-back must capture the numbering of the messages it is about, once",
            listOf("val numbering = recordedNumbering(targets)"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "heldBackDestroy"), "recordedNumbering("),
        )
        assertEquals(
            "and it must ask the repository for the numbering EACH ROW WAS READ UNDER, per account",
            listOf(
                "repo.numberingRowsWereReadUnder(credentials, emailIds).map { (emailId, uidValidity) -> " +
                    "(credentials.id to emailId) to uidValidity }",
            ),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "recordedNumbering"), "repo.numberingRowsWereReadUnder("),
        )
        val flush = bodyOf(INBOX_VIEW_MODEL, "flushPendingDestroy")
        assertEquals(
            "the flush must replay the numbering captured at the confirmation, then clear it " +
                "with the targets it belongs to",
            listOf("val numbering = pendingDeleteNumbering", "pendingDeleteNumbering = emptyMap()"),
            codeLinesNaming(flush, "pendingDeleteNumbering"),
        )
        assertEquals(
            "and must never re-read it: what it would read is the numbering of the renumbering",
            emptyList<String>(),
            codeLinesNaming(flush, "recordedUidValidity"),
        )
        assertEquals(
            "each request must be one route of the pure split — one folder, one numbering, its ids " +
                "in order — translated and nothing more",
            listOf("return routes.map { MessageDestroyWorker.FolderDestroy(it.mailboxId, it.emailIds, it.uidValidity) }"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "foldersToDestroy"), "FolderDestroy("),
        )
        assertEquals(
            "and the split itself must be the pure function, keyed by folder AND numbering: a " +
                "grouping written here is a decision no test can run",
            listOf(
                "val routes = UidValidity.imapDestroyRoutes(rows.map { it.second to it.third }) { " +
                    "numbering[accountId to it] }",
            ),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "foldersToDestroy"), "imapDestroyRoutes("),
        )
    }

    /**
     * THE REGRESSION THIS BRANCH CLOSES, stated as an absence.
     */
    @Test fun theDestroyPathNeverAsksTheFOLDERForItsNumbering() {
        listOf("heldBackDestroy", "recordedNumbering").forEach { function ->
            assertEquals(
                "`$function` must not read the folder's current numbering — that is the value a " +
                    "renumbering has already realigned, and opposing it to itself licenses the " +
                    "expunge this whole chain exists to refuse",
                emptyList<String>(),
                codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, function), "recordedUidValidity"),
            )
        }
    }

    /**
     * THE UPSTREAM HALF OF #99: a destruction may only touch a line that is STILL THE LINE THAT
     * WAS TICKED.
     *
     * A selection holds KEYS, and an IMAP key (`imap:<account>:<folder>:<uid>`) carries the bare
     * UID with no numbering in it. A server that renumbers the shifting way makes the next
     * background walk `@Upsert` the row bearing that exact text with ANOTHER message's envelope:
     * the tick follows the key, so the new envelope is on screen already ticked and nobody touched
     * anything. Confirm the delete on a row that reads "in the Trash" and `UID EXPUNGE` leaves on a
     * live message no one selected — with no error and no way back.
     *
     * Nothing downstream can see it, and `mayDestroyUnderStatedNumbering` is not wrong: it destroys
     * what the line represents NOW, under a number the server confirms. Only the selection spans
     * the two moments, so only here can the earlier fact be opposed.
     *
     * Source lint, and only because `InboxViewModel` needs an `Application` and Room. The
     * decision itself is EXECUTED — `DestroyOnlyWhatWasTickedTest` runs the rule, and
     * `DestroyUnderTheNumberingTheRowWasReadUnderTest` runs it against a scripted socket and reads
     * the verdict off the commands that left the client. What can only be read as text is the
     * WIRING: which list the destruction is handed, and with which arguments the split is called.
     */
    @Test fun theDestructionTakesOnlyWhatIsStillTheLineThatWasTicked() {
        val body = bodyOf(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "the held-back destroy must be handed the KEPT half of the split and never plan.destroy " +
                "again: the whole leg includes the rows whose line was replaced under the tick, and " +
                "destroying those is the data loss this closes. Body was:\n$body",
            listOf(
                "heldBackDestroy(split.kept, getApplication<Application>().getString(R.string.status_message_deleted_forever))",
            ),
            codeLinesNaming(body, "heldBackDestroy("),
        )
        val lines = body.lines().map { it.trim() }
        val call = lines.indexOf("val split = UidValidity.destroyableUnderTheNumberingItWasTickedUnder(")
        assertTrue(
            "the cut must go through the pure function — a comparison written inline here is a " +
                "decision no test can run. Body was:\n$body",
            call >= 0,
        )
        assertEquals(
            "and its ARGUMENTS are the rule: the destroy leg, the stamp of the TICK, and the stamp " +
                "the row carries NOW. Hand it the same map twice and it can never refuse anything, " +
                "while every name in the call stays exactly as it reads here. Body was:\n$body",
            listOf(
                "rows = plan.destroy,",
                "tickedUnder = { ticked[it.emailKey()] },",
                "readUnderNow = { email -> credentialsFor(email)?.let { now[it.id to email.id] } },",
                ")",
            ),
            lines.subList(call + 1, call + 5),
        )
        assertEquals(
            "and the 'now' side must be the per-ROW read, grouped per account — the folder's own " +
                "record is the value a background pass has already realigned. Body was:\n$body",
            listOf("val now = recordedNumbering(destroyTargets)"),
            codeLinesNaming(body, "recordedNumbering("),
        )
        // AND THE ROWS IT READS ARE THE DESTROY LEG. This line is one identifier away from
        // `plan.move`, and that single word turns the whole guard off in silence: `now` then holds
        assertEquals(
            "the 'now' read must be built from the DESTROY leg — built from any other list it " +
                "answers nothing about the rows being destroyed, and a guard that can never " +
                "refuse is a guard that is not there. Body was:\n$body",
            listOf(
                "val destroyTargets = plan.destroy.mapNotNull { e -> credentialsFor(e)?.let { " +
                    "Triple(it, e.id, e.mailboxId.orEmpty()) } }",
            ),
            codeLinesNaming(body, "destroyTargets ="),
        )
    }

    /**
     * The stamp of the tick is captured WITH THE KEYS, before anything suspends, and it is never
     */
    @Test fun theStampOfTheTickIsCapturedAtTheGestureAndNeverReReadForAKeyAlreadyTicked() {
        val body = bodyOf(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "deleteSelected must capture the ticks' stamps beside the keys, oppose them in the " +
                "split, and give the refused ones back — those three lines and no fourth. " +
                "Body was:\n$body",
            listOf(
                "val ticked = awaitTickedNumbering(keys)",
                "tickedUnder = { ticked[it.emailKey()] },",
                "tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }",
            ),
            codeLinesNaming(body, "ticked"),
        )
        // THE FIRST LINE OF THE LAUNCH, and that is what replaces the old "capture outside the
        // launch" rule. The stamps are no longer TAKEN at the gesture, they are AWAITED: the read
        val gestureLines = body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }
        assertEquals(
            "…and it must be the FIRST statement inside viewModelScope.launch: after " +
                "selectionNumbering, the cache read or the Trash probes it would be racing its " +
                "own clearSelection(). Body was:\n$body",
            "val ticked = awaitTickedNumbering(keys)",
            gestureLines[gestureLines.indexOfFirst { it == "viewModelScope.launch {" } + 1],
        )
        assertLineOrder(
            "and the KEYS stay captured before the launch — they say WHICH selection the stamps " +
                "are awaited for. Body was:\n$body",
            gestureLines, "val keys = _selectedKeys.value", "viewModelScope.launch {",
        )
        assertEquals(
            "the field has exactly THREE writers and no fourth: the collector on the selection, so " +
                "a new way of ticking a row cannot arrive without its stamp; and the two " +
                "give-backs — the held-back destroy's and the cross-account move's — each of " +
                "which puts the stamps OF THE TICK back before the keys they belong to. The two " +
                "remaining lines are not writers at all: they are the NAMED ARGUMENT of the pure " +
                "split, `tickedUnder = { ticked[...] }`, which reads the local copy taken at the " +
                "gesture. A writer beyond these three is a stamp written at a moment nobody chose. " +
                "Listed in file order: collector, destroy (argument, then give-back), " +
                "cross-account move (argument, then give-back). ⛔ The two give-backs do NOT read " +
                "alike and must not be aligned on each other: the destroy's is keyed on what the " +
                "numbering split refused, the cross-account move's on every outcome that did not " +
                "move (crossAccountGiveBack), because that path has a second refusal the split " +
                "never sees.",
            listOf(
                "tickedUnder = (known + read).filterKeys { it in still }",
                "tickedUnder = { ticked[it.emailKey()] },",
                "tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }",
                "tickedUnder = { ticked[it.emailKey()] },",
                "tickedUnder = givenBack.associateWith { ticked[it] }",
            ),
            codeLinesNaming(INBOX_VIEW_MODEL.readText(), "tickedUnder ="),
        )
        assertEquals(
            "and that collector must sit on _selectedKeys itself, not on any of the five sites " +
                "that add to it",
            listOf("_selectedKeys.collect { rememberTickedNumbering(it) }"),
            codeLinesNaming(INBOX_VIEW_MODEL.readText(), "rememberTickedNumbering(it)"),
        )
        assertEquals(
            "and it must read the numbering OF THE ROWS, grouped per account: one read per message " +
                "is thousands of database reads on a select-all",
            listOf("val numbering = repo.numberingRowsWereReadUnder(credentials, group.map { it.emailId })"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "rememberTickedNumbering"), "repo."),
        )
        // LOADING THE STAMPS TAKES FOUR LINES, NOT ONE, and each of the four can be mutated
        // while the other three keep their exact appearance: `repo.numberingRowsWereReadUnder(…)`
        //
        // What a broken load costs is IMAP mail, and IMAP mail only.
        // `MailRepository.numberingRowsWereReadUnder` returns `emptyMap()` for anything that is
        val remembered = bodyOf(INBOX_VIEW_MODEL, "rememberTickedNumbering")
        // What the user loses if this line drops what it read (`read[key] = null`): every IMAP
        // stamp is null, the bulk delete destroys a line swapped under the tick again, and the
        // move between accounts sends it to the other account again — no Undo there to take it
        // back — while the repository call above still reads word for word as it does today.
        assertEquals(
            "the numbering read for the new keys must be WRITTEN under each key: a stamp that is " +
                "read and then discarded is no stamp at all, and both #99 guards then pass every " +
                "IMAP row. Body was:\n$remembered",
            listOf("group.forEach { key -> read[key] = numbering[key.emailId] }"),
            codeLinesNaming(remembered, "read[key]"),
        )
        // ONLY THE KEYS WITHOUT A STAMP ARE READ. `(fresh + keys)` compiles without a warning
        // and inverts the rule this very test is named after: every already-stamped key is read
        assertEquals(
            "only the keys that carry no stamp yet may be read: a re-read answers what the row " +
                "carries NOW, which is the one value this field exists not to hold. " +
                "Body was:\n$remembered",
            listOf("fresh.groupBy { credentialsFor(it) }.forEach { (credentials, group) ->"),
            codeLinesNaming(remembered, "groupBy"),
        )
        // AND WHAT SURVIVES THE READ IS THE SELECTION AS IT IS NOW. The read above suspends, so
        // the stamps are filtered on the keys still ticked when it comes back. Written as a
        // constant — `emptySet()` — the field is emptied on every pass instead: no stamp survives,
        // both guards pass everything on IMAP, and no call site anywhere looks different.
        assertEquals(
            "the stamps kept must be those of the keys STILL ticked when the read came back, off " +
                "the flow and never a constant. Body was:\n$remembered",
            listOf("val still = _selectedKeys.value"),
            codeLinesNaming(remembered, "val still ="),
        )
        // And it must read ONLY the keys it does not already hold. Re-reading a known key
        // answers what its row carries NOW — which is the value the whole field exists not to be —
        assertEquals(
            "the collector must keep the stamps it already has and read the NEW keys only",
            listOf(
                "val known = tickedUnder.filterKeys { it in keys }",
                "val fresh = keys.filterNot { it in known }",
                "tickedUnder = (known + read).filterKeys { it in still }",
            ),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "rememberTickedNumbering"), "known"),
        )
    }

    /**
     * What the split refused is COUNTED and GIVEN BACK. A destruction that quietly dropped part of
     */
    @Test fun whatTheSplitRefusedIsCountedAsAFailureAndPutBackInTheSelection() {
        val body = bodyOf(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "the refused rows must be counted as failures and NOT a second time as attempts. " +
                "Body was:\n$body",
            listOf(
                "val lostBefore = resolved.unresolved.size + plan.untreated.size",
                "val lost = lostBefore + split.drifted.size",
                "attemptedBefore = plan.destroy.size + lostBefore,",
                "failedBefore = lost,",
                "when (bulkOutcome(attempted = keys.size, failed = lost)) {",
            ),
            codeLinesNaming(body, "lost"),
        )
        assertEquals(
            "and they must go back into the selection, still ticked, WITH THE STAMPS THEY WERE " +
                "TICKED UNDER — and the stamps first. Given back without them, the collector sees " +
                "keys it has never met, reads their numbering as it is NOW, and the second tap on " +
                "Delete destroys exactly what the first one refused: the fix would buy one tap. " +
                "Body was:\n$body",
            listOf(
                "val lost = lostBefore + split.drifted.size",
                "if (split.drifted.isNotEmpty()) {",
                "tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }",
                "_selectedKeys.value = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }",
            ),
            codeLinesNaming(body, "split.drifted"),
        )
        assertLineOrder(
            "…and the stamps BEFORE the keys: nothing suspends between the two lines, which is " +
                "the only reason the collector cannot slip in and re-read them. Body was:\n$body",
            codeLinesOf(body),
            "tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }",
            "_selectedKeys.value = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }",
        )
        assertEquals(
            "with the selection bar back up — the keys alone leave the screen in a state nothing " +
                "can act on. Body was:\n$body",
            listOf("_selectionActive.value = true"),
            codeLinesNaming(body, "_selectionActive"),
        )
        assertLineOrder(
            "⛔ and AFTER the delegation, never before: bulkBatched calls clearSelection() " +
                "synchronously at the call, so a re-selection written earlier is wiped by the very " +
                "line that follows it. Body was:\n$body",
            codeLinesOf(body), "bulkBatched(", "if (split.drifted.isNotEmpty()) {",
        )
    }

    companion object {
        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val WORKER_PATH = "$APP_SOURCES/app/sterna/mail/MessageDestroyWorker.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val WORKER: File by lazy { File(root, WORKER_PATH) }
    }
}
