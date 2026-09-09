package app.sterna.ui.compose

import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.send.composeDraftOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The read receipt (RFC 8098) on its way from the composer to the queue.
 */
class ReadReceiptWiringTest {

    // --- 1. executed: reopening a queued message for editing ------------------------------------

    private fun outboxDraft(requestReceipt: Boolean) = MailRepository.OutboxDraft(
        to = "bob@example.org",
        cc = "",
        bcc = "",
        subject = "Six o'clock",
        body = "see you there",
        // Not this test's subject either; no default, for the same reason as draftUidValidity.
        htmlBody = null,
        fromAccountId = "accA",
        fromEmail = "alex@masto.top",
        attachments = listOf(EmailBodyPart(blobId = "b1", type = "image/png", name = "map.png")),
        inReplyTo = listOf("<m1@example.org>"),
        references = listOf("<m0@example.org>"),
        pgpMode = "SIGN",
        draftEmailId = "d1",
        // Not this test's subject (the numbering carried with the draft id is
        // DraftNumberingCarriedWiringTest's), but the field has no default on purpose.
        draftUidValidity = 77L,
        outboxId = 42,
        requestReceipt = requestReceipt,
    )

    @Test fun `a queued message that asked for a receipt reopens still asking`() {
        assertTrue(
            "re-editing a queued message enqueues a NEW row: a request dropped in this hand-over " +
                "is a request cancelled, and the message goes out asking nothing",
            composeDraftOf(outboxDraft(requestReceipt = true)).requestReceipt,
        )
    }

    @Test fun `a queued message that asked for nothing reopens asking for nothing`() {
        assertEquals(false, composeDraftOf(outboxDraft(requestReceipt = false)).requestReceipt)
    }

    @Test fun `the rest of the message survives the same hand-over`() {
        // The witness: without it, a mapping that answered `true` unconditionally would pass above.
        val draft = composeDraftOf(outboxDraft(requestReceipt = true))

        assertEquals("bob@example.org", draft.to)
        assertEquals("Six o'clock", draft.subject)
        assertEquals("see you there", draft.body)
        assertEquals("accA", draft.fromAccountId)
        assertEquals("alex@masto.top", draft.fromIdentityEmail)
        assertEquals(listOf("map.png"), draft.attachments.map { it.name })
        assertEquals(listOf("<m1@example.org>"), draft.inReplyTo)
        assertEquals(listOf("<m0@example.org>"), draft.references)
        assertEquals("SIGN", draft.pgpMode)
        assertEquals("d1", draft.draftEmailId)
        assertEquals(42L, draft.editingOutboxId)
    }

    // --- 2. read as source text: the composer hands its answer on --------------------------------

    @Test fun `the send queues the answer the screen handed it`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "sendInternal")
        assertEquals(
            "enqueueSend must be told what the composer asked for. `false` (or nothing at all, the " +
                "parameter has a default) is a message that asks for no receipt while the box was " +
                "ticked — the send disagreeing with the screen.",
            "args.requestReceipt",
            callsOf(body, "repo.enqueueSend(").single()["requestReceipt"],
        )
    }

    @Test fun `the undone send is kept with the answer, so it reopens asking`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "sendInternal")
        assertEquals(
            "the draft kept for Undo must carry it too: undoing a send reopens the composer from " +
                "THIS record, and a message that comes back with the box cleared has had its " +
                "request cancelled by the undo",
            "args.requestReceipt",
            callsOf(body, "SendOutbox.ComposeDraft(").single()["requestReceipt"],
        )
    }

    @Test fun `a scheduled send stores the answer in its own row`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend")
        assertEquals(
            "the scheduled row is where the message waits for hours; the answer has to be in it, " +
                "or it is lost when the worker hands the message to the outbox",
            "requestReceipt",
            callsOf(body, "ScheduledSendEntity(").single()["requestReceipt"],
        )
    }

    @Test fun `a saved draft is saved with the answer`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "saveDraft")
        assertEquals(
            "the server copy of the draft must say what would be sent (the header is written into " +
                "it); a draft saved without it describes a different message",
            "requestReceipt",
            callsOf(body, "repo.saveDraft(").single()["requestReceipt"],
        )
    }

    @Test fun `every mention in the ViewModel is the value it was handed`() {
        // Whole lines, across the three commit paths: `= true`, `= false` or a condition of its own
        // smuggled in at any one of them is a different message, and each reads as harmless.
        val code = codeOf(COMPOSE_VIEW_MODEL.readText())
        assertEquals(
            listOf(
                // prepare(): a restored/reopened message re-arms the box through the prefill.
                "requestReceipt = d.requestReceipt,",
                // sendInternal(): the queued row, then the draft kept for Undo. Both read the same
                // SendArgs, which is also what the OpenPGP round-trip re-enters with.
                "requestReceipt = args.requestReceipt,",
                "requestReceipt = args.requestReceipt,",
                // scheduleSend() then saveDraft(): the screen's answer, straight through.
                "requestReceipt = requestReceipt,",
                "requestReceipt = requestReceipt,",
            ),
            code.lines().map { it.trim() }.filter { it.startsWith("requestReceipt =") },
        )
    }

    @Test fun `the reopened message re-arms the box through the prefill`() {
        // `prepare` builds a DraftFields for three different openings; the one at stake is the
        // restore/reopen branch, recognised by the draft it reads its fields from.
        val prefills = callsOf(bodyOf(COMPOSE_VIEW_MODEL, "prepare"), "DraftFields(")
        val restored = prefills.single { it["to"] == "d.to" }

        assertEquals(
            "the restored draft's answer must reach the screen through DraftFields — the composer " +
                "owns the box in a rememberSaveable, so this is the one way in",
            "d.requestReceipt",
            restored["requestReceipt"],
        )
        // …and the other two openings must NOT set it. A fresh compose, or one prefilled from a
        // mailto: link, has nobody's request to restore: ticking the box there would ask for a
        // receipt the user never asked for.
        assertEquals(
            "only the reopened message re-arms the box",
            emptyList<String>(),
            (prefills - restored).mapNotNull { it["requestReceipt"] },
        )
    }

    @Test fun `the scheduled worker hands the row's answer to the outbox`() {
        val body = bodyOf(SCHEDULED_SEND_WORKER, "doWork")
        assertEquals(
            "ScheduledSendWorker is the hand-over from the scheduled table to the outbox: `false` " +
                "here drops the request hours after the user asked, with nothing to show for it",
            "row.requestReceipt",
            callsOf(body, "repo.enqueueSend(").single()["requestReceipt"],
        )
    }

    // --- 3. read as source text: the SCREEN hands its answer on ---------------------------------

    @Test fun `the send button hands the box's state to the ViewModel`() {
        assertEquals(
            "ComposeScreen must hand `requestReceipt` to viewModel.send, whole. A literal here " +
                "(or an extra condition tacked onto it) kills the feature on EVERY protocol and " +
                "every path at once while the menu entry still shows a ticked box — the screen " +
                "saying one thing and the wire doing another, with nothing else in this suite " +
                "able to see it.",
            listOf(SEND_ARGUMENTS),
            positionalArguments(composeScreen(), "viewModel.send("),
        )
    }

    @Test fun `both save-draft buttons hand it over too`() {
        // Two call sites, and they are reached differently: the toolbar's Save icon, and the "Save
        // draft" choice of the leave dialogue. One of them wired to `false` writes a server copy
        // that describes a different message from the one the screen is showing.
        val calls = positionalArguments(composeScreen(), "viewModel.saveDraft(")
        assertEquals(
            "the composer must still save the draft from exactly two places — the toolbar icon and " +
                "the leave dialogue's \"Save draft\". A new one this rule cannot see is a third " +
                "way to drop the request.",
            2,
            calls.size,
        )
        assertEquals(
            "every saveDraft call must hand `requestReceipt` over whole, so the copy on the server " +
                "asks for what the screen says it asks for",
            listOf(SAVE_ARGUMENTS, SAVE_ARGUMENTS),
            calls,
        )
    }

    @Test fun `every scheduled send is scheduled with it`() {
        // TWO call sites since #161: the preset entries of the menu, and the instant picked by hand
        // in the calendar + clock pair. Both write the same row, where the message then waits for
        // hours — one of them wired to `false` drops the request on that path alone, which is the
        // kind of half-loss nothing on screen shows.
        val calls = positionalArguments(composeScreen(), "viewModel.scheduleSend(")
        assertEquals(
            "the composer must still schedule from exactly two places — the menu's presets and the " +
                "hand-picked date+time (#161). A third one this rule cannot see is another way to " +
                "drop the request.",
            2,
            calls.size,
        )
        assertEquals(
            "each schedule call must hand `requestReceipt` to viewModel.scheduleSend after the " +
                "delay: the row it writes is where the message waits for hours, and what is not " +
                "in it is gone by the time the worker sends. The instant slot is named per call " +
                "site — `millis` for the hand-picked pair, `sendAt` for the preset decided at the " +
                "tap — and both are decided again INSIDE the tap; ScheduleMenuWiringTest pins that.",
            listOf(
                SEND_ARGUMENTS.dropLast(1) + "sendAt" + "requestReceipt",
                SEND_ARGUMENTS.dropLast(1) + "millis" + "requestReceipt",
            ).sortedBy { it.joinToString() },
            calls.sortedBy { it.joinToString() },
        )
    }

    @Test fun `the leave guard treats the box as a field of the message`() {
        // The defect this rule was written for: the box was not a field of the verdict at all, so on
        // a reopened outbox message BOTH senses were lost in silence, with no dialogue — unticking
        val call = callsOf(codeOf(COMPOSE_SCREEN.readText()), "ComposeDirty.isDirty(").single()
        assertEquals(
            "the live state of the box must be handed to the unsaved-changes verdict",
            "requestReceipt",
            call["requestReceipt"],
        )
        assertEquals(
            "and the value the message OPENED with, as its own baseline. Handing `requestReceipt` " +
                "twice makes the verdict blind again; handing `false` makes a reopened outbox " +
                "message that already asked look edited the moment it opens, which fires the " +
                "discard dialogue on a reopen-and-close that touched nothing (#70 BLOCKER 3).",
            "initialRequestReceipt",
            call["initialRequestReceipt"],
        )
    }

    @Test fun `the prefill arms the box and its baseline from the reopened message`() {
        // Both assignments, whole lines, inside the prefill effect. `initialRequestReceipt = false`
        // is the mutation that reads as harmless and brings #70 BLOCKER 3 back; anything other than
        // `it.requestReceipt` on the first line cancels a request on reopening.
        val block = balancedBraces(codeOf(COMPOSE_SCREEN.readText()), "LaunchedEffect(prefill) {")
        assertEquals(
            "the prefill must set BOTH the box and the baseline it is compared against, each from " +
                "the reopened message's own answer and nothing else",
            listOf(
                "requestReceipt = it.requestReceipt",
                "initialRequestReceipt = it.requestReceipt",
            ),
            block.lines().map { it.trim() }
                .filter { it.startsWith("requestReceipt =") || it.startsWith("initialRequestReceipt =") },
        )
    }

    // --- reading the source ---------------------------------------------------------------------

    /** `ComposeScreen.kt` as code, comments cut. */
    private fun composeScreen(): String = codeOf(COMPOSE_SCREEN.readText())

    /**
     * The positional arguments of every `[call]…)` in [source], in order.
     */
    private fun positionalArguments(source: String, call: String): List<List<String>> =
        callsOf(source, call).map { arguments ->
            arguments.keys.filter { it.startsWith("#") }
                .sortedBy { it.drop(1).toInt() }
                .map { arguments.getValue(it) }
        }

    /** [source] from the `{` of [opening] to the brace that closes it. */
    private fun balancedBraces(source: String, opening: String): String {
        val at = source.indexOf(opening)
        check(at >= 0) { "ComposeScreen.kt no longer contains '$opening' — did the prefill move?" }
        val start = source.indexOf('{', at) + 1
        var depth = 1
        var i = start
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return source.substring(start, (i - 1).coerceAtLeast(start))
    }

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

    /**
     * The arguments of every `[call]…)` in [body], as name → value expression; positional arguments
     */
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

    /** [source] with every comment removed, string literals untouched: the comments here name the
     *  very arguments these rules pin, so prose must not be able to answer for the code. */
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
        private const val COMPOSE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        /**
         * What the composer hands to send/scheduleSend, positionally — the receipt is the last one.
         */
        private val SEND_ARGUMENTS =
            listOf("to", "cc", "bcc", "subject.text", "rich", "requestReceipt")

        /**
         * …and to saveDraft, which takes the SAME `rich` since #131: the draft is stored with its
         */
        private val SAVE_ARGUMENTS =
            listOf("to", "cc", "bcc", "subject.text", "rich", "requestReceipt")

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
        val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
    }
}
