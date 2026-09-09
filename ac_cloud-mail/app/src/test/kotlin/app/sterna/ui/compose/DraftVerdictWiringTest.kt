package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fidelity verdict of an edited draft ([ComposeViewModel.editingDraftLossy]) on its way from
 */
class DraftVerdictWiringTest {

    @Test fun `the send hands the verdict to the queue beside the id`() {
        val call = callsOf(bodyOf(COMPOSE_VIEW_MODEL, "sendInternal"), "repo.enqueueSend(").single()
        assertEquals(
            "the id of the draft being replaced must still ride the queue — dropping it leaves a " +
                "stale duplicate in Drafts after every faithful send — through the decision that " +
                "keeps one of THIS PHONE's own draft ids off the row and names the server draft a " +
                "local row stands in for instead (#95, sendDraftTarget): the worker destroys " +
                "whatever this names, and a local id names nothing a server ever issued",
            "sendTarget.emailId",
            call["draftEmailId"],
        )
        assertEquals(
            "enqueueSend must be told the composer's own verdict, whole. `false` (or nothing at " +
                "all — the parameter defaults to the safe true) destroys a draft this composer " +
                "could not reproduce; the decision that reads it is executed in core:data, but " +
                "only this line makes that decision see THIS screen's verdict.",
            "editingDraftLossy",
            call["bodyIsLossy"],
        )
    }

    @Test fun `the draft kept for undo carries the verdict, so an undone send cannot launder it`() {
        val call = callsOf(bodyOf(COMPOSE_VIEW_MODEL, "sendInternal"), "SendOutbox.ComposeDraft(").single()
        assertEquals("sendTarget.emailId", call["draftEmailId"])
        assertEquals(
            "the record an undone send reopens from must carry the verdict WITH the id: restoring " +
                "the id alone is the laundering — the re-send then destroys an original the first " +
                "send had deliberately spared",
            "editingDraftLossy",
            call["draftBodyIsLossy"],
        )
    }

    @Test fun `reopening an undone send restores the verdict beside the id`() {
        assertEquals(
            "bindQueuedRow — the binding the restore branch of prepare() and the resume of a " +
                "composer rebuilt after a process death both run — must read the verdict back " +
                "from the restored draft — the whole line; before it existed, editingDraftLossy " +
                "silently reset to false here while editingDraftId came back, and that pair is " +
                "exactly the destroy. ⛔ The name is `editingDraftLossyOnOpen`: what the reopened " +
                "draft HELD. `editingDraftLossy` is now the read-only OR of that and what the " +
                "composer lost since, and a write landing on it would not compile — which is the " +
                "point.",
            listOf("editingDraftLossyOnOpen = d.draftBodyIsLossy"),
            codeLinesNaming(bodyOf(COMPOSE_VIEW_MODEL, "bindQueuedRow"), "d.draftBodyIsLossy"),
        )
    }

    @Test fun `the verdict is computed from the draft itself, every leg of it`() {
        // The mutation that used to pass: `editingDraftLossy = false` here left the whole suite
        // green and brought the entire symptom back — the verdict is the ONLY thing standing
        // between a reopened HTML/inline/calendar draft and its destruction on send.
        val body = bodyOf(COMPOSE_VIEW_MODEL, "prepare")
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val at = lines.indexOfFirst { it.startsWith("val lossy = reopenedDraftIsLossy(") }
        assertTrue("prepare() no longer computes the verdict from the fetched draft — did the reopen path move?", at >= 0)
        assertEquals(
            "the verdict must be the pure function's — `reopenedDraftIsLossy` in core:data, the " +
                "one a JVM test can EXECUTE — fed the four legs of what this editor cannot give " +
                "back, whole lines: an HTML body this editor cannot read back (`draftHtmlIsLossy`, " +
                "since #131: an html of OUR OWN reopens with its styling and costs nothing, any " +
                "other is still flattened to text on open — ⛔ `draft.htmlContent() != null` here " +
                "keeps a duplicate in Drafts on every re-save of a styled draft, and a bare " +
                "`false` destroys the original of one written elsewhere), inline images and " +
                "calendar parts (never carried), and the read-receipt request the draft's header " +
                "holds (the checkbox comes back clear — D1). Any leg dropped is that content, or " +
                "that request, silently destroyed on the next send.",
            listOf(
                "val lossy = reopenedDraftIsLossy(",
                "hasHtmlBody = draftHtmlIsLossy(draft.htmlContent()),",
                "inlineImageCount = draft.inlineImageParts().size,",
                "calendarPartCount = draft.calendarParts().size,",
                "receiptHeader = draft.dispositionNotificationTo,",
                ")",
            ),
            lines.subList(at, at + 6),
        )
        assertEquals(
            "each leg must be read off the DRAFT that was just fetched, never off this screen's " +
                "own state: the composer's receipt checkbox (`args.requestReceipt`) comes back " +
                "clear on a reopen, so feeding it here would make the receipt leg answer " +
                "\"nothing asked\" every single time and stop nothing at all",
            mapOf(
                "hasHtmlBody" to "draftHtmlIsLossy(draft.htmlContent())",
                "inlineImageCount" to "draft.inlineImageParts().size",
                "calendarPartCount" to "draft.calendarParts().size",
                "receiptHeader" to "draft.dispositionNotificationTo",
            ),
            callsOf(body, "reopenedDraftIsLossy(").single(),
        )
        assertEquals(
            "every assignment of the verdict in prepare(), whole lines and in order (the restore " +
                "side reads it back from the undone send in bindQueuedRow, pinned by the rule " +
                "above): the reopen branch ARMS IT PESSIMISTIC " +
                "on entry, before the coroutine, beside the id it makes harmless; then — ONCE, and " +
                "as the last thing it does — it lowers it from what the draft said AND what the " +
                "attachment carry answered; the catch answers `true` — unknown — for a draft that " +
                "could not be read at all. ⛔ THREE assignments, not four: the body verdict and the " +
                "carry verdict must reach the field on ONE line. Two assignments — computing the " +
                "body verdict into the field and hardening it back afterwards — leave a window, " +
                "between them, in which the id is armed and the verdict is a clean `false` while " +
                "the carry is still suspended per part and `_attachments` is still empty. A send " +
                "tapped there goes out with NO attachments and destroys the original that held " +
                "the only copy of the files: #63, word for word. Drop the entry arming instead " +
                "and a fetch that dies (offline, process killed, no credentials) leaves the " +
                "verdict at its field default `false` with an id already armed, which is the same " +
                "destroy. `editingDraftLossyOnOpen = false` anywhere here is the defect, whole.\n" +
                "⛔ AND EVERY ONE OF THEM NAMES `editingDraftLossyOnOpen`, the field that says what " +
                "the draft HELD when it was opened — never `editingDraftLossy`, which is now a " +
                "read-only OR of that and the body this COMPOSER lost on the way into a parcel. " +
                "The four routes that destroy still read `editingDraftLossy` and not one of them " +
                "changed; if prepare() could write to it, its last statement would wipe the lost " +
                "body on every reopen. ⚠ The filter below is a substring, so a write that came " +
                "back to `editingDraftLossy` itself lands right here.",
            listOf(
                "editingDraftLossyOnOpen = true",
                "editingDraftLossyOnOpen = lossy || !carried",
                "editingDraftLossyOnOpen = true",
            ),
            lines.filter { "editingDraftLossy" in it },
        )
        assertEquals(
            "⛔ and the restore side — bindQueuedRow — names the verdict exactly once, from the " +
                "restored record: a `= false` or a `= true` added there is the same defect on the " +
                "other door, whole.",
            listOf("editingDraftLossyOnOpen = d.draftBodyIsLossy"),
            bodyOf(COMPOSE_VIEW_MODEL, "bindQueuedRow").lines().map { it.trim() }.filter { "editingDraftLossy" in it },
        )
    }

    @Test fun `the reopened draft's id and a pessimistic verdict are armed together, before the coroutine`() {
        // THIS INVARIANT IS THE INVERSE OF THE ONE THIS TEST USED TO PIN, and the old race is why
        // it existed: editingDraftId was once set BEFORE carryDraftAttachments, so sending while
        //
        // That answer paid for the race with a hole. `applied` (a `rememberSaveable` in
        // ComposeScreen) survives process death, so a draft reopened before the process was killed
        //
        // So the id no longer buys the safety; the VERDICT does, and the invariant moved WITH it:
        // it is not "the id is laid last", it is "the LICENCE TO DESTROY is laid last". The pair is
        val lines = bodyOf(COMPOSE_VIEW_MODEL, "prepare").lines().map { it.trim() }
        val assignments = lines.withIndex()
            .filter { Regex("""\beditingDraftId\s*=""").containsMatchIn(it.value) }
        assertEquals(
            "every assignment of editingDraftId in prepare(), whole lines: exactly the reopen " +
                "branch's, nothing else (the restore side's lives in bindQueuedRow, below). An " +
                "extra one — an alias like `this.editingDraftId = draftId` slipped in further " +
                "down — arms a SECOND id whose verdict nothing on that line makes pessimistic, " +
                "and the equality-only filter this test used to run was blind to it.",
            listOf("editingDraftId = draftId"),
            assignments.map { it.value },
        )
        assertEquals(
            "every assignment of editingDraftId in bindQueuedRow — the restore side, run by the " +
                "restored lambda of prepare() and by the resume after a process death: exactly " +
                "the one read back from the record, beside its verdict.",
            listOf("editingDraftId = d.draftEmailId"),
            bodyOf(COMPOSE_VIEW_MODEL, "bindQueuedRow").lines().map { it.trim() }
                .filter { Regex("""\beditingDraftId\s*=""").containsMatchIn(it) },
        )
        val code = lines.filter { it.isNotEmpty() }
        val armed = code.indexOf("editingDraftId = draftId")
        assertTrue("prepare() no longer arms the reopened draft's id — did the reopen path move?", armed > 0)
        assertEquals(
            "⭐ THE ARMING IS ONE GESTURE, AND IT IS PLAYED BEFORE THE COROUTINE. These five code " +
                "lines, whole and adjacent, in this order: the wait flag, the id, THE ACCOUNT THE " +
                "ID WAS READ UNDER, the pessimistic verdict, then `viewModelScope.launch`. " +
                "Nothing else may sit between them — an extra line between the id and the verdict " +
                "is a window in which an id is armed and the verdict is still its `false` " +
                "default. And the four must stay OUTSIDE the launch, for the reason the wait flag " +
                "is already raised outside it: `applied` draws the editor and the whole bar from " +
                "the first frame, while the fetch blocks until the connect timeout, so a Save " +
                "tapped in that window would read a clean verdict and DESTROY the original, " +
                "attachments included — `draftReplacementIsFaithful` compares the composer's " +
                "attachments with themselves, and on JMAP `addressingIsCarried` is a hard-coded " +
                "true. Moving any of it onto the coroutine's first line re-opens exactly that " +
                "window.\n" +
                "⛔ THE ACCOUNT LINE IS PART OF THE GESTURE, not a neighbour of it. Frozen after " +
                "the fetch instead — where it lived until this rule was rewritten — a failed " +
                "reopen leaves an armed id with a NULL account, and null is the one value " +
                "`unlessDraftBelongsElsewhere` waves through: switch \"From\" to another account " +
                "over the text still on screen, tap Save, and A's draft id replaces a row under " +
                "B, overwriting B's own pending draft where the two accounts share a server (#31). " +
                "The order pinned there is DraftNumberingFreezeWiringTest's. Body was:\n" +
                code.joinToString("\n"),
            listOf(
                "_draftLoading.value = true",
                "editingDraftId = draftId",
                "editingDraftAccountId = credentials()?.id",
                "editingDraftLossyOnOpen = true",
                "viewModelScope.launch {",
            ),
            code.subList(armed - 1, (armed + 4).coerceAtMost(code.size)),
        )
        val read = code.indexOfFirst { it == "val draft = repo.fetchEmail(credentials, draftId)" }
        assertTrue("prepare() no longer fetches the draft it reopens — did the reopen path move?", read >= 0)
        val computed = code.indexOfFirst { it == "val lossy = reopenedDraftIsLossy(" }
        assertTrue(
            "the body verdict must be COMPUTED after the draft has actually been read — computing " +
                "it above repo.fetchEmail would judge a draft nobody has seen. ⚠ Computed, not " +
                "assigned: it lands in a local, and the field is written further down. Body was:\n" +
                code.joinToString("\n"),
            computed > read,
        )
        val carry = code.indexOfFirst { "carryDraftAttachments(" in it }
        assertTrue(
            "…and the attachment carry must come after that computation and still run on EVERY " +
                "reopen. Body was:\n" + code.joinToString("\n"),
            carry > computed,
        )
        assertEquals(
            "⛔ THE LICENCE TO DESTROY IS LAID LAST, AND IT IS ONE LINE. These three code lines, " +
                "whole and adjacent, close the try: the carry into its own `val`, the single " +
                "assignment that lowers the verdict, then the catch. Nothing may follow them in " +
                "the try — whatever is added there runs with the destroy already licensed.\n" +
                "⛔ The two `val`s MUST NOT collapse into `lossy || !carryDraftAttachments(…)`: " +
                "`||` short-circuits, so a draft already judged lossy would never have its " +
                "attachments carried at all and they would never come back on screen. The carry " +
                "always runs; only the assignment waits.\n" +
                "⛔ And the assignment must read BOTH answers. `editingDraftLossyOnOpen = lossy` alone " +
                "hands a clean verdict to a draft whose files could not be downloaded, and the " +
                "next save destroys the original that held the only copy of them. Body was:\n" +
                code.joinToString("\n"),
            listOf(
                "val carried = carryDraftAttachments(credentials, draft)",
                "editingDraftLossyOnOpen = lossy || !carried",
                "} catch (t: Throwable) {",
            ),
            code.subList(carry, (carry + 3).coerceAtMost(code.size)),
        )
    }

    /**
     * AND THE ANSWER THE WHOLE THING NOW RESTS ON. Since the verdict is lowered from
     */
    @Test fun `the attachment carry answers whether EVERY part came across`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "carryDraftAttachments")
        assertEquals(
            "carryDraftAttachments must answer by COUNTING what it staged against what the draft " +
                "held, on exactly this line and no other. `return true` (or `staged.isNotEmpty()`, " +
                "or a count against itself) is the #63 destroy restored at its source: the caller " +
                "believes the files came over, lowers the verdict to clean, and the save expunges " +
                "the original. ⚠ Best-effort per part is deliberate — one failed download must not " +
                "drop the rest — which is exactly why the ANSWER has to be the strict count. " +
                "Body was:\n$body",
            listOf("return staged.size == parts.size"),
            body.lines().map { it.trim() }.filter { it.startsWith("return") },
        )
        assertEquals(
            "…and it must still publish what it did manage to stage, so the chips come back on " +
                "screen even on a partial carry. Body was:\n$body",
            listOf("if (staged.isNotEmpty()) _attachments.value = _attachments.value + staged"),
            body.lines().map { it.trim() }.filter { it.contains("_attachments.value =") },
        )
    }

    @Test fun `a scheduled row's id is written only against a clean verdict, and the worker relies on it`() {
        // The scheduled table cannot carry the verdict (no column — a migration is forbidden
        // while another is in flight), so the id itself is the carrier. Two lines, one invariant,
        // pinned together: relax either alone and a scheduled send of a lossy draft destroys it.
        assertEquals(
            "scheduleSend must withhold the destroy when the composer's verdict is not clean — " +
                "the row has nowhere else to say so — and it withholds the PAIR, at the one place " +
                "that builds it. Dropped here, a scheduled send of a draft this composer could not " +
                "reproduce destroys the original it was written to spare. Line was:",
            listOf(").unlessBodyIsLossy(editingDraftLossy)"),
            codeLinesNaming(bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend"), "unlessBodyIsLossy"),
        )
        assertEquals(
            "…and the row is written off that answer, through the guard that keeps a " +
                "`local-draft:` id off it and names the server draft a local row stands in for " +
                "(#95): the two guards answer different questions and neither replaces the other",
            "sendTarget.emailId",
            callsOf(bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend"), "ScheduledSendEntity(").single()["draftEmailId"],
        )
        val fired = callsOf(bodyOf(SCHEDULED_SEND_WORKER, "doWork"), "repo.enqueueSend(").single()
        assertEquals("row.draftEmailId", fired["draftEmailId"])
        assertEquals(
            "the worker may answer `false` ONLY because scheduleSend gated the id above: an id on " +
                "a scheduled row proves the body half, and enqueueSend still reads the addressing " +
                "half at fire time",
            "false",
            fired["bodyIsLossy"],
        )
    }

    // --- reading the source (the same instruments as ReadReceiptWiringTest) ---------------------

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

    /** The code lines of [body] naming [needle], trimmed — comments are already cut by [codeOf]. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }.filter { needle in it }

    /** The arguments of every `[call]…)` in [body], as name → value expression. */
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

    /** [source] with every comment removed, string literals untouched: the comments around these
     *  lines name the very arguments this pins, so prose must not be able to answer for the code. */
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
        private const val SCHEDULED_SEND_WORKER_PATH =
            "app/src/main/kotlin/app/sterna/send/ScheduledSendWorker.kt"

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
    }
}
