package app.sterna.core.data.mail

import app.sterna.core.data.db.OutboxEntity
import app.sterna.core.data.db.OutboxState
import app.sterna.core.jmap.model.EmailBodyPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read receipt (RFC 8098) inside [MailRepository]: the queued row → the four writes.
 */
class ReadReceiptWiringTest {

    // --- 1. executed: the mapping a re-edited queued message goes through -----------------------

    private fun queued(requestReceipt: Boolean) = OutboxEntity(
        id = 7,
        accountId = "accA",
        recipients = "bob@example.org,carol@example.org",
        cc = "dan@example.org",
        subject = "Six o'clock",
        textBody = "see you there",
        createdAtMillis = 1,
        notBeforeMillis = 2,
        state = OutboxState.QUEUED,
        pgpMode = null,
        draftEmailId = "d1",
        requestReceipt = requestReceipt,
    )

    @Test fun `a queued message that asked for a receipt reopens still asking`() {
        val draft = outboxDraftOf(queued(requestReceipt = true), emptyList())

        assertTrue(
            "re-editing a queued message enqueues a NEW row from the composer: a request lost " +
                "here is a request cancelled, and nothing on screen says so",
            draft.requestReceipt,
        )
    }

    @Test fun `a queued message that asked for nothing reopens asking for nothing`() {
        assertEquals(false, outboxDraftOf(queued(requestReceipt = false), emptyList()).requestReceipt)
    }

    @Test fun `the rest of the message survives the same mapping`() {
        // A witness on the fields that were already there: this test would still pass on a mapping
        // that answered `true` unconditionally, so the two above are read together with this one.
        val draft = outboxDraftOf(
            queued(requestReceipt = true).copy(pgpMode = "SIGN"),
            listOf(EmailBodyPart(blobId = "b1", type = "application/pdf", name = "report.pdf")),
        )

        assertEquals("bob@example.org, carol@example.org", draft.to)
        assertEquals("dan@example.org", draft.cc)
        assertEquals("Six o'clock", draft.subject)
        assertEquals("see you there", draft.body)
        assertEquals("SIGN", draft.pgpMode)
        assertEquals("d1", draft.draftEmailId)
        assertEquals(7L, draft.outboxId)
        assertEquals(listOf("report.pdf"), draft.attachments.map { it.name })
    }

    // --- 2. read as source text: the four writes still ask the row ------------------------------

    @Test fun `the SMTP send asks the queued row for its receipt`() {
        val calls = callsOf(bodyOf("performDelivery"), "outgoing(")
        assertTrue("performDelivery no longer builds an OutgoingMessage — did the path move?", calls.size >= 2)
        assertEquals(
            "every OutgoingMessage performDelivery builds must carry the ROW's answer: this one " +
                "feeds both the SMTP submission and the Sent/Drafts APPEND that reuses its bytes",
            List(calls.size) { "item.requestReceipt" },
            calls.map { it["requestReceipt"] },
        )
    }

    @Test fun `the one builder of every raw write puts its parameter on the message, whole`() {
        // The consumer, not the callers. `outgoing(` is asked for the receipt at four sites and
        // every one of them is pinned above — but all four hand it to THIS body, and this body is
        val calls = callsOf(bodyOf("outgoing"), "OutgoingMessage(")
        assertEquals("outgoing() must still build exactly one OutgoingMessage", 1, calls.size)
        assertEquals(
            "the OutgoingMessage must carry the `requestReceipt` PARAMETER and nothing else: a " +
                "literal, or a condition of its own ANDed onto it, silently drops the request from " +
                "every raw write there is while the callers all still pass it in",
            "requestReceipt",
            calls.single()["requestReceipt"],
        )
    }

    @Test fun `the structured JMAP send asks the queued row for its receipt`() {
        val calls = callsOf(bodyOf("performDelivery"), "client.sendEmail(")
        assertEquals("performDelivery must still submit through client.sendEmail", 1, calls.size)
        assertEquals(
            "Email/set must be told what the row asked for; `false` here is a send that asks nothing",
            "item.requestReceipt",
            calls.single()["requestReceipt"],
        )
    }

    @Test fun `the PGP route inherits the MIME write and is wired at the same source`() {
        // The mute path. The raw bytes are built by the SAME `outgoing(` above and imported
        // verbatim, so this states that the PGP branch's own build is one of the calls the first
        // test covers — and that it is the LAST of them, i.e. inside the `pgpEntity != null` branch.
        val body = bodyOf("performDelivery")
        val calls = callsOf(body, "outgoing(")
        assertTrue("the PGP branch must still build the raw message itself", calls.size >= 2)
        assertTrue(
            "the raw PGP message must be built from `outgoing(...)` and imported, so it inherits " +
                "the MIME header write instead of needing a second implementation",
            body.indexOf("OutgoingMime.build(") < body.lastIndexOf("outgoing("),
        )
        assertTrue(
            "importAndSendEmail must be handed those very bytes",
            "client.importAndSendEmail(" in body,
        )
    }

    @Test fun `the saved draft row is what carries the receipt to both protocols`() {
        // Moved by #95. The draft is written to `local_drafts` before either protocol is tried,
        // and BOTH uploads then read the row — so this one argument is now the single hop, and
        // `requestReceipt = false` here mutes the header on IMAP and on JMAP at once.
        // What the IMAP APPEND makes of it is EXECUTED in LocalDraftSaveTest.
        val calls = callsOf(bodyOf("saveDraft"), "localDraftRow(")
        assertEquals("saveDraft must still build its row with localDraftRow", 1, calls.size)
        assertEquals(
            "the row the phone keeps must say what the message would ask for; both the APPEND and " +
                "Email/set are built from it",
            "requestReceipt",
            calls.single()["requestReceipt"],
        )
    }

    @Test fun `the JMAP draft save asks the row for its receipt`() {
        val calls = callsOf(bodyOf("uploadDraft"), "client.saveDraft(")
        assertEquals("uploadDraft must still create the draft through client.saveDraft", 1, calls.size)
        assertEquals(
            "the JMAP draft must carry the header too — the copy on the server is what the user " +
                "will reopen and send",
            "row.requestReceipt",
            calls.single()["requestReceipt"],
        )
    }

    @Test fun `taking a queued item out for editing goes through the pure mapping`() {
        assertEquals(
            "takeOutboxForEdit must build its draft with outboxDraftOf(item, attachments) — the " +
                "function the tests above EXECUTE. Inlining the mapping back here puts it out of " +
                "reach of every JVM test, which is how a field gets dropped unnoticed.",
            listOf("val draft = outboxDraftOf(item, attachments)"),
            codeOf(bodyOf("takeOutboxForEdit")).lines().map { it.trim() }
                .filter { "outboxDraftOf(" in it },
        )
    }

    @Test fun `the outbox row is what the delivery reads, not a default`() {
        // Every mention of the field in performDelivery, whole lines: `= false`, `= true` or a
        // condition of its own smuggled in here would each be a different message on the wire.
        assertEquals(
            listOf(
                "requestReceipt = item.requestReceipt,",
                "requestReceipt = item.requestReceipt,",
                "requestReceipt = item.requestReceipt,",
            ),
            codeOf(bodyOf("performDelivery")).lines().map { it.trim() }
                .filter { it.startsWith("requestReceipt") },
        )
    }

    // --- reading the source ---------------------------------------------------------------------

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /**
     * The arguments of every `[call]…)` in [body], as name → value expression. Positional arguments
     */
    private fun callsOf(body: String, call: String): List<Map<String, String>> {
        val code = codeOf(body)
        val calls = mutableListOf<Map<String, String>>()
        var from = 0
        while (true) {
            val at = code.indexOf(call, from)
            if (at < 0) break
            val open = at + call.length - 1
            val close = matchingParen(code, open)
            calls += parseArguments(code.substring(open + 1, close))
            from = close
        }
        return calls
    }

    /** The index of the ')' closing the '(' at [open], quotes and nesting accounted for. */
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
            // `=` only counts as a named argument when it is not part of ==, >=, <=, != or a lambda.
            if (eq > 0 && arg.take(eq).trim().matches(Regex("""\w+""")) && arg.getOrNull(eq + 1) != '=') {
                arg.take(eq).trim() to arg.substring(eq + 1).trim()
            } else {
                "#$index" to arg
            }
        }.toMap()
    }

    /**
     * [body] with every comment removed — block comments included, and string literals left alone.
     */
    private fun codeOf(body: String): String {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < body.length) {
            val c = body[i]
            when {
                inString && c == '\\' -> { out.append(c).append(body.getOrElse(i + 1) { ' ' }); i += 2; continue }
                c == '"' -> { inString = !inString; out.append(c) }
                inString -> out.append(c)
                c == '/' && body.getOrNull(i + 1) == '/' -> {
                    while (i < body.length && body[i] != '\n') i++
                    continue
                }
                c == '/' && body.getOrNull(i + 1) == '*' -> {
                    val end = body.indexOf("*/", i + 2)
                    i = if (end < 0) body.length else end + 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
