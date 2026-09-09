package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads TWO sources as TEXT — `ComposeViewModel.kt`,
 */
class OnlyCopyWiringTest {

    /**
     * WHOLE LINES, never `contains`. The mutation this rule exists for is
     */
    @Test fun `the undone send is marked as the only copy, from the id and nothing else`() {
        val body = bodyOf("bindQueuedRow")
        assertEquals(
            "After \"Undo\" on the send banner the queued row and its staged directory are gone: " +
                "the message lives on this screen alone, in memory. These lines are what tells " +
                "the composer so. Invert or weaken the last one and the back gesture closes the " +
                "composer in silence and the message is lost, with no dialog and no draft — and " +
                "ComposeDirtyTest stays green throughout, because the rule it runs is still right, " +
                "it is simply never handed the truth. The observable face of the id " +
                "(_editingOutbox — the title, the leave dialog) is settled beside it, from the " +
                "same field: a composer rebuilt after a process death gets it from here too. " +
                "Body of bindQueuedRow() was:\n$body",
            listOf(
                "_editingOutbox.value = d.editingOutboxId != null",
                "editingOutboxId = d.editingOutboxId",
                "_onlyCopy.value = d.editingOutboxId == null",
            ),
            codeLinesNaming(body, "editingOutboxId"),
        )
        assertEquals(
            "⛔ prepare() names editingOutboxId again. The id and the flag are written ONCE, in " +
                "bindQueuedRow, which the restored lambda of prepare() and the resume after a " +
                "process death both call — a second write here wins by running later",
            emptyList<String>(),
            codeLinesNaming(prepareBody(), "editingOutboxId"),
        )
    }

    /**
     * The census the rule above cannot see through. It reads the body of `prepare` only; a
     */
    @Test fun `every line naming the flag in the file is one of these three`() {
        val lines = codeLines(COMPOSE_VIEW_MODEL)
        assertEquals(
            "⛔ a line naming _onlyCopy was added, moved or removed. This flag is the composer's " +
                "single reason to guard a message that exists nowhere else, and a second write " +
                "elsewhere in the file wins by running later: clearing it on the way out of the " +
                "restore path closes the screen silently on an undone send and loses it. Cutting " +
                "the field from the flow the screen collects loses it just as quietly. One " +
                "declaration, one exposure, and one write in bindQueuedRow(), taken from the " +
                "restored row's id — called by the restored lambda of prepare() and by the " +
                "resume of a composer rebuilt after a process death",
            listOf(
                "private val _onlyCopy = MutableStateFlow(false)",
                "val onlyCopy: StateFlow<Boolean> = _onlyCopy.asStateFlow()",
                "_onlyCopy.value = d.editingOutboxId == null",
            ),
            lines.map { it.trim() }.filter { "_onlyCopy" in it },
        )
    }

    /**
     * The link BETWEEN the two files, pinned as a whole line. The rule above watches the flow
     */
    @Test fun `the screen collects the flag from the view model, in this one line`() {
        assertEquals(
            "⛔ the composer screen no longer reads the flag from the view model the way it did. " +
                "This line is the whole join between the field that knows the message exists " +
                "nowhere else and the verdict that guards it: cut it, hard-code it or weaken it " +
                "and the back gesture closes an undone send in silence — no \"Discard message?\", " +
                "no draft, no queued row left to reopen. Every other rule in this file, and " +
                "ComposeDirtyTest, stay green while that happens. A SECOND declaration of the " +
                "name anywhere on this screen is the same loss written differently: the call site " +
                "hands over a name, not a value, and the text everything else reads is unchanged.",
            listOf("val onlyCopy by viewModel.onlyCopy.collectAsStateWithLifecycle()"),
            codeLines(COMPOSE_SCREEN).map { it.trim() }
                .filter { Regex("""\b(val|var)\s+onlyCopy\b""").containsMatchIn(it) },
        )
    }

    /**
     * The value the screen HANDS the verdict, parsed out of the call and compared whole.
     */
    @Test fun `the screen still hands the flag to the leave guard`() {
        val call = callsOf(withoutComments(COMPOSE_SCREEN.readText()), "ComposeDirty.isDirty(").single()
        assertEquals(
            "the flag saying this message exists nowhere else must be handed to the " +
                "unsaved-changes verdict. Pass `false`, weaken it, or drop the argument, and a " +
                "composer reopened by \"Undo\" on the send banner closes on the back gesture in " +
                "silence: no \"Discard message?\", no draft, no queued row left to reopen — the " +
                "message is gone. ComposeDirtyTest stays green either way, because the rule it " +
                "runs is still right; it is simply never asked about this message.",
            "onlyCopy",
            call["onlyCopy"],
        )
    }

    /**
     * WHERE the write stands, which the rule above cannot see. That rule compares the TEXT of
     */
    @Test fun `the flag is written inside the restored draft, just before the row is consumed`() {
        val body = prepareBody()
        val (inside, after) = restoredLambda(body)
        assertEquals(
            "⛔ bindQueuedRow(d, options) is no longer the last statement of the " +
                "`handed?.let { d -> … }` block of prepare(). WHERE it stands is the whole of its " +
                "meaning: outside that lambda the bind runs when nothing at all was handed over — " +
                "a composer reopened after the app was killed, empty, with nothing to lose — and " +
                "marks THAT screen as holding the only copy, so an empty composer starts asking " +
                "\"Discard message?\" while the invariant of the restore branch (#96) is gone. " +
                "Buried in a branch INSIDE the lambda it keeps its text and stops running on the " +
                "very path it guards, so an undone send closes in silence. It is the last thing " +
                "the restore branch does before the restored row is consumed, with `options` — " +
                "the identities just listed — so the From is the one the message was queued " +
                "under. Restore branch of prepare() was:\n$body",
            listOf("bindQueuedRow(d, options)"),
            codeOf(inside).takeLast(1),
        )
        assertEquals(
            "⛔ the write of _onlyCopy is no longer the last statement of bindQueuedRow(), beside " +
                "the id it is read from. Buried in a branch inside it, it keeps its text and its " +
                "order — the two text rules here stay green — and stops running on the path it " +
                "guards. Body of bindQueuedRow() was:\n" + bodyOf("bindQueuedRow"),
            listOf(
                "editingOutboxId = d.editingOutboxId",
                "_onlyCopy.value = d.editingOutboxId == null",
            ),
            codeOf(bodyOf("bindQueuedRow")).takeLast(2),
        )
        assertEquals(
            "⛔ something now stands between the restored lambda and outbox.consumeRestored(). " +
                "The flag is written from the restored record on the last line of that lambda and " +
                "the consume follows immediately: consumeRestored() clears what the write reads, " +
                "so a write that drifts past it reads null and calls an undone send an empty " +
                "screen, and a statement wedged in between gets to run on the restore path having " +
                "been argued for nowhere. (The resume of a composer rebuilt after a process death " +
                "stands AFTER the consume, on purpose — OutboxResumeWiringTest pins it there.)",
            "outbox.consumeRestored()",
            codeOf(after).firstOrNull(),
        )
    }

    /**
     * The PREMISE of every rule above, and the only one of them that is held by an OMISSION.
     */
    @Test fun `the draft kept for undo names no queued row`() {
        val body = bodyOf("sendInternal")
        assertEquals(
            "⛔ sendInternal names editingOutboxId on a line that is not the refusal's. The rule " +
                "below reads the construction's argument names; this one reads the WHOLE body, " +
                "because the record is built on one line and handed to the hold window on " +
                "another: `outbox.hold(draft = draft.copy(editingOutboxId = id))` puts the row id " +
                "back without the construction changing by one character, and the guard goes out " +
                "with everything green. EXACTLY ONE line may name the field, and it is the one " +
                "below: the wording term of the lost-body refusal, which only READS the id to " +
                "choose between \"reopen the message\" and \"type it again\", sits above " +
                "consumeEditingOutbox(), and writes nothing. Any second line — and any change to " +
                "that one — is the defect this rule exists for. Lines naming it were:",
            listOf(
                "holdsStoredDraft = _editingLocalDraftId.value != null || editingDraftId != null || " +
                    "editingOutboxId != null,",
            ),
            codeLinesNaming(body, "editingOutboxId"),
        )
        val call = callsOf(body, "SendOutbox.ComposeDraft(").single()
        assertFalse(
            "⛔ the draft kept for the undo now names editingOutboxId. It must not: the queued row " +
                "was consumed by consumeEditingOutbox() a few lines above (#70), so once this send " +
                "is undone the message exists on the composer screen alone, in memory, and this " +
                "very omission is what makes prepare() say so (`_onlyCopy = d.editingOutboxId == " +
                "null`). Naming the field here — even with a plausible id, even for symmetry with " +
                "another construction site — puts a row id back into a record whose row is gone, " +
                "turns the only guard that message has off, and closes the composer on the back " +
                "gesture in silence. No other rule in this file, and no executed test, reddens " +
                "when that happens. Arguments were: ${call.keys}",
            "editingOutboxId" in call.keys,
        )
    }

    // -- reading the source ---------------------------------------------------------------------
    // Recopied from ComposeViewModelWiringTest and ReadReceiptWiringTest on purpose: these helpers
    // are duplicated across the source lints of this repo rather than shared, so no rule can be
    // disarmed from a distance.

    /** The body of `fun prepare(`, braces balanced, comments cut. */
    private fun prepareBody(): String = bodyOf("prepare")

    /** [body]'s code lines, trimmed and blanks dropped — the shape a whole-body rule would compare. */
    private fun codeOf(body: String): List<String> =
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** [body]'s code lines that name [needle], trimmed — whole lines, in the order they are written. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeOf(body).filter { needle in it }

    /**
     * [body] split at the `handed?.let { d -> … }` block: what is INSIDE it, and
     */
    private fun restoredLambda(body: String): Pair<String, String> {
        val at = body.indexOf(RESTORED_LAMBDA)
        check(at >= 0) {
            "the restore branch of prepare() no longer opens '$RESTORED_LAMBDA' — this rule pins " +
                "WHERE the only-copy flag is written, and cannot say so if that block was renamed " +
                "or taken apart. Body of prepare() was:\n$body"
        }
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
     *  flag and the very ordering the rules above pin. */
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

    /**
     * [source] with every comment removed, string literals untouched. Recopied from
     */
    private fun withoutComments(source: String): String {
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

    companion object {
        /** The block of `prepare` that only runs when a draft was really handed back. */
        private const val RESTORED_LAMBDA = "handed?.let { d ->"

        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        private const val COMPOSE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
    }
}
