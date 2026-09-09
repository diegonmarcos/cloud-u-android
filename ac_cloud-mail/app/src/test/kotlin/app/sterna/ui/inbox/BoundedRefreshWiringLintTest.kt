package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `InboxViewModel.kt` as text and proves nothing about
 */
class BoundedRefreshWiringLintTest {

    @Test
    fun `refresh wraps its work in refreshWithin and passes NO budget of its own`() {
        val body = declarationBody("fun refresh()")
        val calls = Regex("""\brefreshWithin\b""").findAll(body).toList()
        assertEquals(
            "InboxViewModel.refresh() is expected to call refreshWithin exactly once — the bounded " +
                "envelope around the whole of its work. Found ${calls.size} in:\n$body",
            1, calls.size,
        )
        val at = calls.single().range.first
        // The call token plus what follows it, cut to the length of the expected opener — so the
        // comparison sees the '(' of any budget spelled here rather than stopping just before it.
        val opener = body.substring(at, minOf(at + EXPECTED_OPENER.length, body.length))
        assertEquals(
            "refresh() must call refreshWithin with NO argument, i.e. the trailing lambda alone, so " +
                "the budget the app ships with lives in exactly one place: REFRESH_BUDGET_MS, which " +
                "BoundedRefreshTest pins. A bound spelled here — refreshWithin(600_000L) { … } — is a " +
                "second copy no JVM test can reach, and #130 with extra steps. Compared as the whole " +
                "call opener, because 'refreshWithin(' contains 'refreshWithin'. Call site was:\n$body",
            EXPECTED_OPENER,
            opener,
        )
    }

    @Test
    fun `the delivered arm stops the spinner, clears the error, and reports success`() {
        val arm = whenArm("RefreshOutcome.Delivered")
        assertEquals(
            "The arm taken when the refresh landed must set refreshing = false and clear the error, " +
                "then report the success to connectivity (#65: every refresh doubles as the probe), " +
                "then stamp the scopes this refresh actually reconciled — this is the ONLY arm that " +
                "may, because a refresh that brought nothing back must not make a stale list look " +
                "fresh for 30 s (#178; RefreshFreshnessWiringLintTest holds the rest of that rule). " +
                "'refreshing = true' here is one word, compiles, and leaves the tern turning for ever " +
                "after every SUCCESSFUL refresh — the symptom of #130, shipped by its own fix. " +
                "Compared whole, since 'Status(refreshing = true, …)' contains none of the fragments " +
                "a looser rule would look for. Arm was:\n$arm",
            "RefreshOutcome.Delivered -> { " +
                "status.value = Status(refreshing = false, error = null) " +
                "connectivity.reportSuccess() " +
                "rememberReconciled(reconciled) }",
            arm,
        )
    }

    @Test
    fun `the failed arm stops the spinner, shows the cause, and reports the failure`() {
        val arm = whenArm("is RefreshOutcome.Failed")
        assertEquals(
            "The arm taken when the work threw must be exactly what the old catch(Throwable) did: " +
                "refreshing = false, the cause's own message (or its class name) in 'error', and the " +
                "cause handed to connectivity.reportFailure. 'error = null' there is one word and " +
                "makes every failure mute again, which is #65 undone; dropping the reportFailure call " +
                "blinds the connectivity probe. Arm was:\n$arm",
            "is RefreshOutcome.Failed -> { " +
                "val cause = outcome.cause " +
                "status.value = Status(refreshing = false, error = cause.message ?: cause.javaClass.simpleName) " +
                "connectivity.reportFailure(cause) }",
            arm,
        )
    }

    @Test
    fun `the abandoned arm stops the spinner, says timed out, and reports NO connectivity failure`() {
        val arm = whenArm("RefreshOutcome.AbandonedAtBudget")
        assertFalse(
            "The arm taken when the budget ran out must NOT call connectivity.reportFailure: " +
                "nothing has said the device is offline — the refresh simply never came back — and " +
                "the offline banner on a phone that is plainly online is defect #65 itself. Arm " +
                "was:\n$arm",
            arm.contains("reportFailure"),
        )
        assertEquals(
            "The abandoned arm must clear 'refreshing' and put the timed-out wording in 'error', " +
                "and do nothing else. Compared whole, because 'Status(refreshing = false, error = " +
                "timedOut, …)' contains 'refreshing = false' and a looser rule would wave a longer " +
                "line through. Arm was:\n$arm",
            "RefreshOutcome.AbandonedAtBudget -> { " +
                "val timedOut = getApplication<Application>().getString(R.string.sync_error_timed_out) " +
                "status.value = Status(refreshing = false, error = timedOut) }",
            arm,
        )
    }

    // -- reading the source ----------------------------------------------------------------------

    /**
     * The body of the declaration whose header is [header], comments removed and whitespace
     */
    private fun declarationBody(header: String): String {
        val text = codeText(INBOX_VIEW_MODEL)
        val start = text.indexOf(header)
        check(start >= 0) { "InboxViewModel.kt declares no '$header' — did it get renamed?" }
        return balancedFrom(text, start)
    }

    /**
     * The whole `when` arm that starts with [label], from the label to the brace that balances the
     */
    private fun whenArm(label: String): String {
        val text = codeText(INBOX_VIEW_MODEL)
        val hits = Regex("""\b${Regex.escape(label)}\b\s*->""").findAll(text).toList()
        assertEquals(
            "InboxViewModel.kt is expected to map $label exactly once — the arm the bound refresh " +
                "lands on. Found ${hits.size}.",
            1, hits.size,
        )
        return balancedFrom(text, hits.single().range.first)
    }

    /** [text] from [from] up to the `}` that balances the first `{` at or after it. */
    private fun balancedFrom(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) { "no block opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /**
     * [file] as ONE line of code: every comment taken out, every run of whitespace collapsed to a
     */
    private fun codeText(file: File): String {
        val code = StringBuilder()
        var inBlockComment = false
        for (raw in file.readLines()) {
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
            code.append('\n')
        }
        return code.toString().replace(Regex("""\s+"""), " ").trim()
    }

    companion object {
        /** The only call refresh() may spell: the trailing lambda, and no budget of its own. */
        private const val EXPECTED_OPENER = "refreshWithin {"

        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
