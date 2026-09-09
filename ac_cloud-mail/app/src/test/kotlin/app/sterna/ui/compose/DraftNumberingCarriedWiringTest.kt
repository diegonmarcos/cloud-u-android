package app.sterna.ui.compose

import app.sterna.core.data.mail.MailRepository
import app.sterna.send.composeDraftOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The frozen numbering (#99, V2) on the app side of the queue: composer → outbox row → scheduled
 */
class DraftNumberingCarriedWiringTest {

    // --- 1. executed: reopening a queued message for editing ------------------------------------

    private fun outboxDraft(draftUidValidity: Long?) = MailRepository.OutboxDraft(
        to = "bob@example.org",
        cc = "",
        bcc = "",
        subject = "Six o'clock",
        body = "see you there",
        // Not this test's subject (the styling carried is SendOutboxTest's), but the field has no
        // default on purpose: a row's html is the only place its styling lives while it waits.
        htmlBody = null,
        fromAccountId = "accA",
        fromEmail = "alex@masto.top",
        attachments = emptyList(),
        inReplyTo = emptyList(),
        references = emptyList(),
        pgpMode = null,
        draftEmailId = "d1",
        draftUidValidity = draftUidValidity,
        outboxId = 42,
        requestReceipt = false,
    )

    @Test fun `a queued message reopens carrying the numbering its row held`() {
        assertEquals(
            "reopening a queued item enqueues a NEW row when it is sent again: a numbering dropped " +
                "in this hand-over leaves the server draft in place for ever, and nothing on " +
                "screen says the message is now in Drafts twice",
            42L,
            composeDraftOf(outboxDraft(draftUidValidity = 42L)).draftUidValidity,
        )
    }

    @Test fun `a row queued before the column existed reopens with nothing frozen`() {
        assertEquals(null, composeDraftOf(outboxDraft(draftUidValidity = null)).draftUidValidity)
    }

    @Test fun `the id and its numbering survive the same hand-over together`() {
        // The witness: a mapping answering 42 unconditionally would satisfy the first test alone,
        // and a numbering without the id it belongs to means nothing.
        val draft = composeDraftOf(outboxDraft(draftUidValidity = 42L))

        assertEquals("d1", draft.draftEmailId)
        assertEquals(42L, draft.draftUidValidity)
        assertEquals(42L, draft.editingOutboxId)
        assertEquals("Six o'clock", draft.subject)
    }

    // --- 2. read as source text: the composer hands it on ---------------------------------------

    @Test fun `the send queues the numbering the composer froze`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "sendInternal")
        assertEquals(
            "enqueueSend must be handed the numbering out of `sendDraftTarget`. `null` here is a " +
                "deferred send that destroys nothing — the draft it replaces stays in Drafts for " +
                "ever — and anything READ here instead is the number recorded after a renumbering, " +
                "which is the defect this branch closes.",
            "sendTarget.uidValidity",
            callsOf(body, "repo.enqueueSend(").single()["draftUidValidity"],
        )
        assertEquals(
            "…beside the id it belongs to, OUT OF THE SAME ANSWER: the pair is one decision, and " +
                "an id from one place beside a numbering from another expunges a UID under a " +
                "UIDVALIDITY it does not belong to, i.e. another message of Drafts (#95 × #99)",
            "sendTarget.emailId",
            callsOf(body, "repo.enqueueSend(").single()["draftEmailId"],
        )
        assertEquals(SEND_TARGET, callsOf(body, "sendDraftTarget(").single())
    }

    @Test fun `the draft kept for Undo keeps it too, so an undone send can still destroy`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "sendInternal")
        assertEquals(
            "undoing a send reopens the composer from THIS record: without the frozen numbering " +
                "the reopened message can no longer destroy the draft it replaces, and saving or " +
                "sending it leaves a duplicate behind with nothing on screen saying so",
            "sendTarget.uidValidity",
            callsOf(body, "SendOutbox.ComposeDraft(").single()["draftUidValidity"],
        )
    }

    @Test fun `a scheduled send stores it in its own row`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend")
        assertEquals(
            "the scheduled row is where the message waits for hours, and it becomes an outbox row " +
                "only when the worker fires. What is not in this row is gone by then, and the " +
                "destroy goes out blind on a folder the server may have renumbered meanwhile",
            "sendTarget.uidValidity",
            callsOf(body, "ScheduledSendEntity(").single()["draftUidValidity"],
        )
        assertEquals(SEND_TARGET, callsOf(body, "sendDraftTarget(").single())
    }

    /**
     * The four arguments the composer must hand `sendDraftTarget`, by name and whole — both send
     */
    private val SEND_TARGET = mapOf(
        "editingDraftId" to "editingDraftId",
        "editingDraftUidValidity" to "editingDraftUidValidity",
        "replacedServerDraftId" to "replacedServerDraftId",
        "replacedServerDraftUidValidity" to "replacedServerDraftUidValidity",
    )

    @Test fun `the scheduled worker hands the row's numbering to the outbox`() {
        val body = bodyOf(SCHEDULED_SEND_WORKER, "doWork")
        assertEquals(
            "ScheduledSendWorker is the hand-over from the scheduled table to the outbox — the " +
                "exact place a column held on one table only loses its value, silently, hours " +
                "after the user wrote the mail. `null` here re-opens the defect for every " +
                "scheduled send while every other rule in this suite stays green",
            "row.draftUidValidity",
            callsOf(body, "repo.enqueueSend(").single()["draftUidValidity"],
        )
    }

    @Test fun `a message coming back from the queue restores the freeze instead of re-reading it`() {
        // bindQueuedRow: the binding the restore branch of prepare() runs on the hand-over, and
        // the resume of a composer rebuilt after a process death runs on the row it took back.
        val prepare = codeLines(bodyOf(COMPOSE_VIEW_MODEL, "bindQueuedRow"))
        val id = prepare.indexOf(REOPENED_ID)
        assertTrue(
            "bindQueuedRow() must still take the reopened message's draft id as '$REOPENED_ID' — " +
                "this rule pins the restore NEXT TO it and must fail loudly rather than pin it " +
                "beside something it never located. Body was:\n${prepare.joinToString("\n")}",
            id >= 0,
        )
        assertEquals(
            "the numbering must be restored on the line right after the id it belongs to, from the " +
                "record the message came back in. Reading it afresh here (the composer has " +
                "credentials and an id: it compiles) answers the number a sync wrote AFTER the " +
                "renumbering — the one value that lets the expunge through on another draft. " +
                "Body was:\n${prepare.joinToString("\n")}",
            id + 1,
            prepare.indexOf(RESTORE),
        )
    }

    @Test fun `the outbox screen reopens through the pure mapping, never by building a draft itself`() {
        // The second consumer of `composeDraftOf`, and the one nothing in this repository read:
        // the Outbox screen's Edit. An inline `SendOutbox.ComposeDraft(...)` written here can copy
        assertEquals(
            "OutboxViewModel must hand the row to `composeDraftOf` and reopen with its result, " +
                "whole line — the mapping the executed rules above run",
            listOf("sendOutbox.reopen(composeDraftOf(draft))"),
            codeLines(OUTBOX_VIEW_MODEL.readText()).filter { "sendOutbox.reopen(" in it },
        )
    }

    /**
     * Its own test, deliberately: a failed assertion ends its method, so the rule above would hide
     * this one exactly when both are broken — which is the same edit.
     */
    @Test fun `the outbox screen builds no ComposeDraft of its own`() {
        assertEquals(
            "an inline construction is a list of fields nobody checks, and the one it forgets is " +
                "silently the default. This screen is the second consumer of the mapping and the " +
                "one no test read: a draft built here without `draftUidValidity` sends every " +
                "reopened queued message back with nothing frozen",
            emptyList<String>(),
            codeLines(OUTBOX_VIEW_MODEL.readText()).filter { "ComposeDraft(" in it },
        )
    }

    @Test fun `nothing in the composer assigns that field anything else`() {
        // Whole lines, in order, across the WHOLE class: `= null` at either site is a draft left
        // behind for ever, and a third assignment smuggled in anywhere is a number nobody froze.
        val assignments = codeLines(COMPOSE_VIEW_MODEL.readText())
            .filter { it.startsWith("editingDraftUidValidity =") }
        assertEquals(
            "exactly two writes — the restore of a message coming back from the queue, and the " +
                "freeze taken when a saved draft is opened — and the two hand-overs to " +
                "sendDraftTarget. Lines were:\n" + assignments.joinToString("\n"),
            listOf(RESTORE, FREEZE, HANDED_OVER, HANDED_OVER),
            assignments,
        )
    }

    // --- reading the source ---------------------------------------------------------------------

    /** The body of `fun [name](` in [file], braces balanced, comments cut. */
    private fun bodyOf(file: File, name: String): String {
        val code = codeOf(file.readText())
        val at = Regex("""\bfun\s+$name\s*\(""").find(code)
            ?: error("${file.name} declares no 'fun $name(' — did it get renamed?")
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

    /** The non-blank lines of [text], trimmed. [text] is expected to have been stripped of comments
     *  already ([codeOf]) — prose naming these very assignments must not answer for the code. */
    private fun codeLines(text: String): List<String> =
        codeOf(text).lines().map { it.trim() }.filter { it.isNotBlank() }

    /** The arguments of every `[call]…)` in [body], as name → value; positional keyed `#0`, `#1`, … */
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
        check(calls.isNotEmpty()) { "no call to '$call' left in this body — did it move?" }
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

    /** [source] with every comment removed, string literals untouched. */
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
        /** The line the reopen branch takes the draft's id on, and the restore that must follow it. */
        private const val REOPENED_ID = "editingDraftId = d.draftEmailId"
        private const val RESTORE = "editingDraftUidValidity = d.draftUidValidity"

        /** The read-time freeze `DraftNumberingFreezeWiringTest` owns; pinned here as the only other write. */
        private const val FREEZE =
            "editingDraftUidValidity = repo.recordedUidValidityForDraft(credentials, draftId)"

        /** Not a write: the named argument each send site hands `sendDraftTarget`. */
        private const val HANDED_OVER = "editingDraftUidValidity = editingDraftUidValidity,"

        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        private const val SCHEDULED_SEND_WORKER_PATH =
            "app/src/main/kotlin/app/sterna/send/ScheduledSendWorker.kt"
        private const val OUTBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/outbox/OutboxViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
        val SCHEDULED_SEND_WORKER: File by lazy { File(root, SCHEDULED_SEND_WORKER_PATH) }
        val OUTBOX_VIEW_MODEL: File by lazy { File(root, OUTBOX_VIEW_MODEL_PATH) }
    }
}
