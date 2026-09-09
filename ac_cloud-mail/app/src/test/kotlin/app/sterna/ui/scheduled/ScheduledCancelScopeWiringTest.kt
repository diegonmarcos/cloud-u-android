package app.sterna.ui.scheduled

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * READ AS SOURCE TEXT — the last resort, and only for what nothing in this module can run.
 */
class ScheduledCancelScopeWiringTest {

    @Test fun `cancel runs on the app scope`() {
        assertEquals(
            "the deposit into Drafts must outlive the screen. The X is at the end of a row and the " +
                "user leaves the Scheduled screen at once — on viewModelScope the coroutine is " +
                "cancelled with the NavBackStackEntry, and the message is neither in Drafts nor " +
                "scheduled any more.",
            listOf(LAUNCH),
            codeLines(bodyOf(VIEW_MODEL, "cancel")).filter { ".launch {" in it },
        )
    }

    /** Its own test: a failed assertion ends its method, so one rule would hide the other. */
    @Test fun `cancel launches nothing on the screen's own scope`() {
        assertEquals(
            "a second launch on viewModelScope puts part of the gesture back under the screen's " +
                "lifetime, which is the defect",
            emptyList<String>(),
            codeLines(bodyOf(VIEW_MODEL, "cancel")).filter { "viewModelScope" in it },
        )
    }

    @Test fun `the decision is handed the seams it needs`() {
        val call = callsOf(bodyOf(VIEW_MODEL, "cancel"), "cancelScheduledSend(").single()

        assertEquals(
            setOf(
                "id", "row", "credentials", "accountIsGone", "saveDraft", "dropWorker", "deleteRow",
            ),
            call.keys,
        )
        assertEquals("{ repo.scheduledSend(it) }", call["row"])
        assertEquals(
            "the account of the ROW: this screen is not filtered by account and shows none",
            "{ app.container.accountStore.credentials(it) }",
            call["credentials"],
        )
        assertEquals("{ ScheduledSends.cancel(app, it) }", call["dropWorker"])
        assertEquals("{ repo.deleteScheduledSend(it) }", call["deleteRow"])
    }

    /**
     * Every argument, by name AND by value, in ONE comparison.
     */
    @Test fun `every field of the row reaches saveDraft, by name and by value`() {
        val save = callsOf(bodyOf(VIEW_MODEL, "cancel"), "repo.saveDraft(").single()

        assertEquals(
            mapOf(
                "credentials" to "creds",
                "to" to "d.to",
                "subject" to "d.subject",
                // The one the review's mutation swapped for `d.subject`: the whole message.
                "body" to "d.body",
                // …and its styling (#131), out of the SAME mapping and never re-derived here:
                // dropped, a cancelled scheduled send is filed with the user's bold removed.
                "html" to "d.html",
                "cc" to "d.cc",
                "bcc" to "d.bcc",
                "inReplyTo" to "d.inReplyTo",
                "references" to "d.references",
                // Naming the server draft this message was edited from would let the save DESTROY
                // it, while the deposit flattens the HTML body: a duplicate in Drafts is
                // recoverable, a destroyed original is not (#63).
                "replacesEmailId" to "null",
                // Passed explicitly because we aim at no original — never left out (#99).
                "replacesUidValidity" to "null",
                // Without the chosen identity the draft goes back under the login address (#31).
                "fromName" to "d.fromName",
                "fromEmail" to "d.fromEmail",
                // No composer in this story: the row is read from the database and deposited
                // whole, so nothing was lost on the way into a parcel. `true` here would refuse to
                // write the body of a message whose text is genuinely empty.
                "composerBodyWasLost" to "false",
                // Only ever read when the line above is true, which this route never is — but
                // `saveDraft` takes it with no default, so it is stated. It is answered from the
                // text being deposited: there is no composer in this story to ask (#131).
                "typedBodyIsBlank" to "d.body.isBlank()",
                "requestReceipt" to "d.requestReceipt",
            ),
            save,
        )
    }

    /**
     * A `null` from `credentials` is not proof the account is gone, and the proof is the caller's
     */
    @Test fun `the account-gone proof is delegated, with both halves, to the executable rule`() {
        val call = callsOf(bodyOf(VIEW_MODEL, "cancel"), "cancelScheduledSend(").single()

        assertEquals(
            "{ accountDepartureIsProven( " +
                "accountsUnreadable = app.container.accountStore.accountsUnreadable(), " +
                "accountStillListed = app.container.accountStore.accounts()" +
                ".any { a -> a.id == it }, ) }",
            call["accountIsGone"],
        )
    }

    /**
     * The row leaves the screen only after `saveDraft` returns, and that call attempts the upload
     */
    @Test fun `a second tap on the same row cannot start a second cancellation`() {
        val body = codeLines(bodyOf(VIEW_MODEL, "cancel"))

        assertEquals(
            "the guard must be the first thing the tap does, and it must RETURN. Body was:\n" +
                body.joinToString("\n"),
            listOf("if (!inFlight.add(id)) return"),
            body.filter { "inFlight.add" in it },
        )
        assertEquals(
            "…and it must be released on every path, or one failed cancellation deadens that X " +
                "for the life of the process. Body was:\n" + body.joinToString("\n"),
            listOf("inFlight.remove(id)"),
            body.filter { "inFlight.remove" in it },
        )
    }

    // --- reading the source (same mechanics as DraftNumberingCarriedWiringTest) ------------------

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

    /** The non-blank lines of [text], trimmed, comments already stripped. */
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
        /** The one launch `cancel` may make, whole. */
        private const val LAUNCH = "app.container.appScope.launch {"

        private const val VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/scheduled/ScheduledSendsViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val VIEW_MODEL: File by lazy { File(root, VIEW_MODEL_PATH) }
    }
}
