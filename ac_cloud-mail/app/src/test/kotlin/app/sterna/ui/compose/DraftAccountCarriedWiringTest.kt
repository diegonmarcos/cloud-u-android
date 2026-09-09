package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A draft id never leaves the composer under an account other than the one it was read from
 */
class DraftAccountCarriedWiringTest {

    /** The two arguments the guard must be handed, at every site, whole. */
    private val GUARDED = mapOf(
        "draftAccountId" to "carriedDraftAccount()",
        "writingAccountId" to "credentials.id",
    )

    @Test fun `the send refuses to queue a draft id belonging to another account`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "sendInternal")
        assertEquals(
            "the outbox row is written under `credentials` — the account of the identity on show " +
                "— and `performSend` destroys `item.draftEmailId` under the row's OWN account. So " +
                "the pair may only ride this row when the draft was read under that same account: " +
                "the two names, in this order, and nothing else. Swap them and the guard compares " +
                "an account with itself; hand `editingDraftAccountId` as the writing account and " +
                "it always agrees.",
            GUARDED,
            callsOf(body, "unlessDraftBelongsElsewhere(").single(),
        )
        assertEquals(
            "…and it must be applied to the ANSWER, whole: `sendDraftTarget(…)` first, the account " +
                "guard on its result, one statement. Applied to the id alone it leaves the " +
                "numbering behind on the row — half a pair, which is the shape every defect on " +
                "this path has taken. Lines were:",
            listOf(
                "val sendTarget = sendDraftTarget(",
                ").unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)",
            ),
            linesNaming(body, "sendDraftTarget(", "unlessDraftBelongsElsewhere("),
        )
        assertEndsTheChain(body)
        assertEquals(
            "the row must be written off that guarded answer, and nothing else",
            "sendTarget.emailId",
            callsOf(body, "repo.enqueueSend(").single()["draftEmailId"],
        )
        assertEquals(
            "…and so must the record the UNDO WINDOW reopens from: it is restored into " +
                "`editingDraftId` beside `editingDraftAccountId = d.fromAccountId`, i.e. under the " +
                "account this message went out as. A row's id here that belongs to another account " +
                "makes that restore a LIE, and the freeze it feeds harmless-looking",
            "sendTarget.emailId",
            callsOf(body, "SendOutbox.ComposeDraft(").single()["draftEmailId"],
        )
        assertEquals(
            "…the account that record is restored under, whole: it is what `prepare()` reads back " +
                "into `editingDraftAccountId`, so it must stay the account this send goes out as. " +
                "Anything else here and the guarantee this test exists for stops holding one " +
                "gesture later, silently.",
            "_selectedFrom.value?.accountId",
            callsOf(body, "SendOutbox.ComposeDraft(").single()["fromAccountId"],
        )
    }

    @Test fun `a scheduled send asks both questions, and neither replaces the other`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend")
        assertEquals(
            "the scheduled row waits for HOURS and then fires under `accountId = credentials.id`. " +
                "It must carry the draft id only when the draft belongs to that account, and the " +
                "guard needs both names — the draft's, and the one being written as.",
            GUARDED,
            callsOf(body, "unlessDraftBelongsElsewhere(").single(),
        )
        assertEquals(
            "⛔ the fidelity verdict and the account guard are DIFFERENT questions, and the row " +
                "must be gated by both, in this order, on the one pair: drop the first and a " +
                "scheduled send destroys an original this composer could not reproduce; drop the " +
                "second and it expunges a number in the wrong account's Drafts. Lines were:",
            listOf(
                "val sendTarget = sendDraftTarget(",
                ").unlessBodyIsLossy(editingDraftLossy)",
                ".unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)",
            ),
            linesNaming(body, "sendDraftTarget(", "unlessBodyIsLossy(", "unlessDraftBelongsElsewhere("),
        )
        assertEndsTheChain(body)
        assertEquals(
            "sendTarget.emailId",
            callsOf(body, "ScheduledSendEntity(").single()["draftEmailId"],
        )
    }

    @Test fun `re-saving a draft under another account replaces nothing there`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "saveDraft")
        assertEquals(
            "`repo.saveDraft` is handed `credentials` — the identity on show — and CREATES the new " +
                "copy there while ordering the old one destroyed under the same account. For a " +
                "draft opened elsewhere that order leaves for a server that never issued the id, " +
                "so the pair is withheld and the original stays where the user can still see it.",
            GUARDED,
            callsOf(body, "unlessDraftBelongsElsewhere(").single(),
        )
        assertEquals(
            "…and it is asked on the PAIR, built from the id and the numbering frozen at the open " +
                "(#99) — not on the id with the numbering taken from the field beside it. Lines " +
                "were:",
            listOf(
                "val replaces = SendDraftTarget(emailId = editingDraftId, uidValidity = editingDraftUidValidity)",
                ".unlessDraftBelongsElsewhere(draftAccountId = carriedDraftAccount(), writingAccountId = credentials.id)",
            ),
            linesNaming(body, "SendDraftTarget(", "unlessDraftBelongsElsewhere("),
        )
        val save = callsOf(body, "repo.saveDraft(").single()
        assertEquals(
            "the id the save replaces must come out of the guarded answer. `editingDraftId` here " +
                "is the defect: a draft of A destroyed under B, and on IMAP that is a number in " +
                "B's own Drafts.",
            "replaces.emailId",
            save["replacesEmailId"],
        )
        assertEquals(
            "…and its numbering out of the SAME answer — an id from one place beside a numbering " +
                "from another expunges a UID under a UIDVALIDITY it does not belong to",
            "replaces.uidValidity",
            save["replacesUidValidity"],
        )
    }

    @Test fun `the three fields are mapped onto the decision in exactly one place`() {
        val source = codeOf(COMPOSE_VIEW_MODEL.readText())
        assertEquals(
            "⛔ which field answers for the draft's account is `carriedDraftAccountId`'s decision " +
                "(executed in SendDraftTargetTest), and this class hands it the three fields ONCE. " +
                "The server route freezes `editingDraftAccountId` and the local route arms only " +
                "`editingLocalDraftAccountId`, so passing the wrong one answers null — 'unknown' — " +
                "and a null draft account refuses NOTHING: the guard would be disarmed with every " +
                "other rule in this suite still green. Written out at each site instead, a later " +
                "edit fixes one of the three and leaves the other two carrying ids across accounts.",
            listOf(
                mapOf(
                    "editingDraftId" to "editingDraftId",
                    "serverDraftAccountId" to "editingDraftAccountId",
                    "localRowAccountId" to "editingLocalDraftAccountId",
                ),
            ),
            callsOf(source, "carriedDraftAccountId("),
        )
        assertEquals(
            "…and the wiring is a plain function of this class, declared once: a field would be a " +
                "fourth copy of a frozen value, free to go stale against the id it describes. " +
                "Lines were:",
            listOf("private fun carriedDraftAccount(): String? = carriedDraftAccountId("),
            source.lines().map { it.trim() }.filter { it.startsWith("private fun carriedDraftAccount") },
        )
        assertEquals(
            "\u26d4 \u2026and its body is WHOLE, the closing parenthesis included. Pinning the " +
                "declaration and the argument map leaves the tail of the expression unwatched, and " +
                "the tail is where the answer dies: `)?.takeIf { it == editingLocalDraftAccountId }` " +
                "answers null on every server-draft route \u2014 that field is null there by " +
                "construction \u2014 so the guard refuses nothing at all THREE sites at once, with " +
                "every other rule in this suite still green. Body was:",
            listOf(
                "private fun carriedDraftAccount(): String? = carriedDraftAccountId(",
                "editingDraftId = editingDraftId,",
                "serverDraftAccountId = editingDraftAccountId,",
                "localRowAccountId = editingLocalDraftAccountId,",
                ")",
            ),
            source.lines().map { it.trim() }.filter { it.isNotEmpty() }.let { lines ->
                val at = lines.indexOfFirst { it.startsWith("private fun carriedDraftAccount") }
                if (at < 0) emptyList() else lines.subList(at, minOf(at + 5, lines.size))
            },
        )
    }

    /**
     * \u26d4 The guard must be the LAST link: `linesNaming` selects the lines that name it, so a
     */
    private fun assertEndsTheChain(body: String) {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        val at = lines.indexOfFirst { it.startsWith(".unlessDraftBelongsElsewhere(") || it.contains(").unlessDraftBelongsElsewhere(") }
        assertTrue("no line applying the guard found at all. Body was:\n" + lines.joinToString("\n"), at >= 0)
        val next = lines.getOrNull(at + 1)
        assertTrue(
            "\u26d4 the guard must END the expression: the line after it goes on chaining, so " +
                "something is applied to the pair AFTER it was withheld, and no rule in this file " +
                "would see it. The line after the guard was: " + next,
            next != null && !next.startsWith(".") && !next.startsWith("?."),
        )
    }

    // --- reading the source (the instruments of DraftNumberingCarriedWiringTest) ----------------

    /** The lines of [body] naming any of [names], trimmed, in order — whole lines, never `contains`
     *  as a verdict: the comparison above is an equality against the complete list. */
    private fun linesNaming(body: String, vararg names: String): List<String> =
        body.lines().map { it.trim() }.filter { line -> names.any { it in line } }

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

        val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}
