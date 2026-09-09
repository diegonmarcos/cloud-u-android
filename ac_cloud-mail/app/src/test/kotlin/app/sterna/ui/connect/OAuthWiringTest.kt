package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class OAuthWiringTest {

    /**
     * The mutation this exists to kill: `connect_oauth_denied` back in `pollForToken`, which is
     */
    @Test fun `the screen never picks the declined sentence itself`() {
        val offenders = source().lines().withIndex()
            .filter { (_, line) -> DENIED.containsMatchIn(line) }
            .map { (i, line) -> "${i + 1}: ${line.trim()}" }
        assertEquals(
            "ConnectViewModel must not name R.string.connect_oauth_denied at all. It says " +
                "\"Sign-in was declined or cancelled.\" — true only when the user actually " +
                "declined, and that case is decided in oauthFailureSpec, from the server's own " +
                "`error`. Written here it becomes the answer to every failure again, including " +
                "the ones the server explained, which is the whole defect. Found:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(), offenders,
        )
    }

    /**
     * A refusal carries the server's `error` (and, for Microsoft, an AADSTS code); the branch must
     */
    @Test fun `the poll failure says what the server said`() {
        val branch = failedBranch()
        assertTrue(
            "the `is DevicePollVerdict.Refused ->` branch of pollForToken must set the error state " +
                "with oauthFailureMessage(<context>, verdict.failure) — one whole line, matching " +
                "${POLL_ROUTES.pattern}. Anything else is this screen choosing the words again, " +
                "and `verdict.failure` is the only thing carrying the server's reason. Branch was:\n" +
                branch.joinToString("\n"),
            branch.any { POLL_ROUTES.matches(it) },
        )
        val hardCoded = branch.filter { ERROR_FROM_RESOURCE.containsMatchIn(it) }
        assertEquals(
            "and it must not ALSO set the state from a hard-coded string: a second " +
                "ConnectState.Error(string(R.string.…)) in this branch either overwrites the " +
                "routed message or is what actually runs. Found:\n" + hardCoded.joinToString("\n"),
            emptyList<String>(), hardCoded,
        )
    }

    /**
     * A wait that ran out has two endings — the code expired under polls that were being answered,
     */
    @Test fun `the deadline's sentence is not this screen's to choose`() {
        val body = functionBody("pollForToken")
        assertTrue(
            "the `is DevicePollVerdict.RanOut ->` arm of pollForToken must take its sentence from " +
                "oauthRanOutMessage(<verdict>.everReachedAServer), as a whole line matching " +
                "${RAN_OUT_ROUTES.pattern}. Body was:\n$body",
            body.lineSequence().map { it.trim() }.any { RAN_OUT_ROUTES.matches(it) },
        )
        val hardCoded = body.lines().withIndex()
            .filter { (_, line) -> RAN_OUT_SENTENCES.containsMatchIn(line) }
            .map { (i, line) -> "${i + 1}: ${line.trim()}" }
        assertEquals(
            "and pollForToken must not name R.string.connect_oauth_expired or " +
                "R.string.connect_oauth_network itself: they are the two answers oauthRanOutMessage " +
                "picks between, and naming one here is this screen deciding again which end went " +
                "quiet — the defect (#55), restored. Found:\n" + hardCoded.joinToString("\n"),
            emptyList<String>(), hardCoded,
        )
    }

    /**
     * Discovery's two failures are different facts — no OAuth at all, versus OAuth without a
     */
    @Test fun `discovery chooses its own sentence`() {
        val body = functionBody("connectOAuth")
        assertTrue(
            "connectOAuth must take its discovery verdict from oauthDiscoveryFailure(<metadata>), " +
                "as a whole line matching ${DISCOVERY_VERDICT.pattern}. ⚠ The anchor is the point: " +
                "`val x = oauthDiscoveryFailure(m) ?: R.string.connect_oauth_unsupported` still " +
                "*contains* the call and is exactly the mutation that puts the false sentence " +
                "back. Body was:\n$body",
            body.lineSequence().map { it.trim() }.any { DISCOVERY_VERDICT.matches(it) },
        )
        val hardCoded = body.lines().map { it.trim() }.filter { UNSUPPORTED.containsMatchIn(it) }
        assertEquals(
            "and connectOAuth must not name R.string.connect_oauth_unsupported itself: it is one " +
                "of the two answers oauthDiscoveryFailure picks between, and a server that " +
                "advertises OAuth without a device flow is told it has none when this screen " +
                "reaches for it directly. Found:\n" + hardCoded.joinToString("\n"),
            emptyList<String>(), hardCoded,
        )
    }

    // -- reading the file --------------------------------------------------------------------------

    /**
     * The lines of the `is DevicePollVerdict.Refused ->` arm of `pollForToken`, trimmed: from the
     */
    private fun failedBranch(): List<String> {
        val lines = functionBody("pollForToken").lines()
        val start = lines.indexOfFirst { FAILED_ARM.containsMatchIn(it) }
        check(start >= 0) {
            "pollForToken no longer has an `is DevicePollVerdict.Refused ->` arm: this lint would " +
                "read an empty branch and pass. Rename it here too rather than let the rule go blind."
        }
        val out = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            out += line.trim()
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out
    }

    /** The body of a function of [CONNECT_VIEW_MODEL], as text. Braces counted raw, as above. */
    private fun functionBody(name: String): String {
        val lines = source().lines()
        val start = lines.indexOfFirst { Regex("""\bfun $name\b""").containsMatchIn(it) }
        check(start >= 0) {
            "$name() is gone from ConnectViewModel: this lint reads nothing, so rename it here too " +
                "rather than let the rules pass over an empty string."
        }
        val out = StringBuilder()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            out.appendLine(line)
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    private fun source(): String = CONNECT_VIEW_MODEL.readText()

    private companion object {
        const val PATH = "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt"

        /** Repo root, walked up from the module's working directory (as the other source lints do). */
        val CONNECT_VIEW_MODEL: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, PATH).isFile }
                ?.let { File(it, PATH) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )
        }

        /** Forbidden tokens: a substring search cannot be evaded by making the line longer. */
        val DENIED = Regex("""\bR\.string\.connect_oauth_denied\b""")
        val UNSUPPORTED = Regex("""\bR\.string\.connect_oauth_unsupported\b""")
        val ERROR_FROM_RESOURCE = Regex("""ConnectState\.Error\(\s*string\(R\.string\.""")
        val RAN_OUT_SENTENCES = Regex("""\bR\.string\.connect_oauth_(expired|network)\b""")

        /** Required shapes: whole trimmed lines, anchored, so a longer line is a different line. */
        val POLL_ROUTES = Regex("""^_state\.value = ConnectState\.Error\(oauthFailureMessage\(.+, verdict\.failure\)\)$""")
        val DISCOVERY_VERDICT = Regex("""^val [A-Za-z]\w* = oauthDiscoveryFailure\([A-Za-z]\w*\)$""")
        val RAN_OUT_ROUTES = Regex(
            """^_state\.value = ConnectState\.Error\(string\(oauthRanOutMessage\([A-Za-z]\w*\.everReachedAServer\)\)\)$""",
        )

        val FAILED_ARM = Regex("""\bis DevicePollVerdict\.Refused\s*->""")
    }
}
