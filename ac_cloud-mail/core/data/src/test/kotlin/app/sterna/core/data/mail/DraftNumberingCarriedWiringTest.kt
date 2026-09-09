package app.sterna.core.data.mail

import app.sterna.core.data.db.OutboxEntity
import app.sterna.core.data.db.OutboxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frozen numbering on its way THROUGH THE QUEUE (#99, V2): the composer freezes it, the outbox
 */
class DraftNumberingCarriedWiringTest {

    // --- executed: the mapping a re-edited queued message goes through --------------------------

    private fun queued(draftUidValidity: Long?) = OutboxEntity(
        id = 7,
        accountId = "accA",
        recipients = "bob@example.org",
        subject = "Six o'clock",
        textBody = "see you there",
        createdAtMillis = 1,
        notBeforeMillis = 2,
        state = OutboxState.QUEUED,
        draftEmailId = "d1",
        draftUidValidity = draftUidValidity,
    )

    @Test fun `a queued message reopens carrying the numbering it was queued with`() {
        assertEquals(
            "re-editing a queued message enqueues a BRAND-NEW row from the composer's state: a " +
                "numbering lost in this mapping is a draft that can never be destroyed again, i.e. " +
                "a duplicate left in Drafts at the next send, silently",
            42L,
            outboxDraftOf(queued(draftUidValidity = 42L), emptyList()).draftUidValidity,
        )
    }

    @Test fun `a row queued before the column existed reopens with nothing frozen`() {
        // The witness against a mapping that answers a constant: `null` must come back as `null`,
        // and null destroys nothing — the deliberate cost of not backfilling (MIGRATION_22_23).
        assertEquals(null, outboxDraftOf(queued(draftUidValidity = null), emptyList()).draftUidValidity)
    }

    @Test fun `the id and its numbering come back as a pair`() {
        // The other half of the witness: the number is worthless without the id it belongs to, and
        // a mapping that answered 42 unconditionally would satisfy the first test alone.
        val draft = outboxDraftOf(queued(draftUidValidity = 42L), emptyList())

        assertEquals("d1", draft.draftEmailId)
        assertEquals(42L, draft.draftUidValidity)
        assertEquals(7L, draft.outboxId)
        assertEquals("Six o'clock", draft.subject)
    }

    // --- the delivery destroys under the number the ROW carries ---------------------------------

    @Test fun `the send destroys the draft under the numbering its row carried, not a fresh one`() {
        val body = bodyOf("performSend")
        assertEquals(
            "performSend must hand `destroyDraft` the row's own frozen numbering. This runs in a " +
                "headless worker, minutes to days after the composer read that draft id: any value " +
                "READ here is the numbering a sync recorded after a renumbering, which the guard " +
                "then compares with itself, calls SAME, and expunges UIDs that now name other " +
                "messages in Drafts. Body was:\n$body",
            listOf("runCatching { destroyDraft(credentials, it, item.draftUidValidity) }"),
            codeLinesNaming(body, "destroyDraft("),
        )
        assertEquals(
            "…and it must destroy exactly the draft the row names, guarded by that same id",
            listOf("item.draftEmailId?.let {"),
            codeLinesNaming(body, "item.draftEmailId"),
        )
    }

    @Test fun `the send reads no numbering of its own`() {
        val body = bodyOf("performSend")
        assertEquals(
            "performSend must read NOTHING: `item.draftUidValidity ?: recordedUidValidityForDraft(" +
                "credentials, it)` compiles, reads as a safety net, and is the original defect " +
                "entire — the fallback is taken exactly on the rows queued before the column " +
                "existed, whose numbering nobody knows. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "recordedUidValidity"),
        )
    }

    // --- the queueing writes it into the row ----------------------------------------------------

    @Test fun `enqueueSend stores the frozen numbering beside the draft id`() {
        val calls = callsOf(bodyOf("enqueueSend"), "OutboxEntity(")
        assertEquals("enqueueSend must still build exactly one OutboxEntity", 1, calls.size)
        assertEquals(
            "the row is the only place the frozen numbering can survive a process death, and the " +
                "delivery reads it from there. `null` written here (or the parameter quietly not " +
                "written at all) leaves every deferred send destroying nothing — a duplicate draft " +
                "for ever — while the composer still believes it froze a number",
            "draftUidValidity",
            calls.single()["draftUidValidity"],
        )
        assertEquals(
            "…and beside the id it belongs to: the pair is what makes the expunge refusable. " +
                "That id is `replaceableDraftId` and NOT the raw `draftEmailId` parameter since " +
                "#63's send-side proof: the row may only name a draft the outgoing message was " +
                "shown to reproduce (SendDraftDestructionWiringTest owns that rule). The pair " +
                "still travels together — an unproven send writes neither.",
            "replaceableDraftId",
            calls.single()["draftEmailId"],
        )
    }

    @Test fun `the parameter has no default, so a new call site cannot forget it`() {
        val declaration = declarationOf("enqueueSend")
        assertTrue(
            "enqueueSend must declare `draftUidValidity: Long?` with NO default value. A `= null` " +
                "there is silent: a future send path compiles without freezing anything and leaves " +
                "its draft behind on every delivery, and no test in this repository could see it. " +
                "Declaration was:\n$declaration",
            "draftUidValidity: Long?," in declaration.lines().map { it.trim() },
        )
    }

    // --- and it comes back out when a queued message is reopened --------------------------------

    @Test fun `reopening a queued item for editing goes through the pure mapping`() {
        assertEquals(
            "takeOutboxForEdit must build its draft with outboxDraftOf(item, attachments) — the " +
                "function the executed rules of this suite run. Inlining the mapping here puts it " +
                "out of reach of every JVM test, which is how a field gets dropped unnoticed.",
            listOf("val draft = outboxDraftOf(item, attachments)"),
            codeLinesNaming(bodyOf("takeOutboxForEdit"), "outboxDraftOf("),
        )
    }

    // --- reading the source ---------------------------------------------------------------------

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The text from `fun [function](` to the `)` closing its parameter list. */
    private fun declarationOf(function: String): String {
        val code = codeOf(DaoQuerySource.mailSource("MailRepository"))
        val at = Regex("""\bfun\s+$function\s*\(""").find(code)
            ?: error("MailRepository declares no 'fun $function(' — did it get renamed?")
        val open = code.indexOf('(', at.range.first)
        return code.substring(open, matchingParen(code, open) + 1)
    }

    /** The code lines of [body] naming [needle], comments cut, trimmed — compared WHOLE. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeOf(body).lines().map { it.trim() }.filter { needle in it }

    /** The arguments of every `[call]…)` in [body], as name → value; positional keyed `#0`, `#1`, … */
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

    /**
     * [source] with every comment removed, string literals untouched. The comments in this
     */
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
}
