package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as [OAuthWiringTest]: it
 */
class LoginHintWiringTest {

    /**
     * The mutation this exists to kill: `connectOAuth` handing `null` (or the untrimmed `email`, or
     */
    @Test fun `the generic device flow hands over the address the user typed`() {
        assertEquals(
            "connectOAuth must put emailTrim in AwaitingApproval as the fourth argument: it is " +
                "the address the user typed, and the whole of #55 is that the approval page asks " +
                "for it a second time. ⚠ The call is pinned WHOLE — a fifth argument, or a hint " +
                "wrapped in anything, is a different call and must be re-read here.",
            "ConnectState.AwaitingApproval(device.userCode, device.verificationUri, " +
                "device.verificationUriComplete, emailTrim)",
            callIn(CONNECT_VIEW_MODEL, "connectOAuth", "ConnectState.AwaitingApproval("),
        )
    }

    /**
     * The mutation this exists to kill: the Outlook mirror passing `p.userCode`'s address — i.e.
     */
    @Test fun `the Outlook mirror passes no hint`() {
        assertEquals(
            "observeOutlookSignIn must pass null as AwaitingApproval's fourth argument. The " +
                "Microsoft device flow is out of scope for #55 and OutlookProgress carries no " +
                "address anyway — anything else here is the hint leaking to a second provider.",
            "ConnectState.AwaitingApproval(p.userCode, p.verificationUri, " +
                "p.verificationUriComplete, null)",
            callIn(CONNECT_VIEW_MODEL, "observeOutlookSignIn", "ConnectState.AwaitingApproval("),
        )
    }

    /**
     * The mutation this exists to kill: `DeviceApprovalPanel` passing `null` instead of
     */
    @Test fun `the approval panel forwards the state's hint`() {
        assertEquals(
            "DeviceApprovalPanel must forward state.loginHint to DeviceApprovalContent. Passing " +
                "null (or the composable's own username field) here is #55 undone: the state is " +
                "where the hint travels precisely because this panel cannot tell the two " +
                "producers apart.",
            "DeviceApprovalContent(state.userCode, state.verificationUri, " +
                "state.verificationUriComplete, state.loginHint, onCancel)",
            callIn(CONNECT_SCREEN, "DeviceApprovalPanel", "DeviceApprovalContent("),
        )
    }

    /**
     * The mutation this exists to kill: the post-import panel passing `approval.loginHint` or a
     */
    @Test fun `the imported-account sign-in passes no hint`() {
        assertEquals(
            "ImportAccountSignIn must pass null to DeviceApprovalContent: the post-import device " +
                "flow is Microsoft's, out of scope for #55, and it reuses this composable only " +
                "for the code + button body.",
            "DeviceApprovalContent(approval.userCode, approval.verificationUri, " +
                "approval.verificationUriComplete, null, onCancel = viewModel::cancelImportOAuth)",
            callIn(CONNECT_SCREEN, "ImportAccountSignIn", "DeviceApprovalContent("),
        )
    }

    /**
     * THE ONE LINE OF PRODUCTION THAT JOINS the pure function to the user, and the four rules above
     */
    @Test fun `the approval button computes its target through the hint`() {
        assertEquals(
            "DeviceApprovalContent must build its target with withLoginHint(approval, loginHint) — " +
                "the URL first, the hint second. Dropping the call restores #55; swapping the two " +
                "arguments compiles, opens nothing at all, and no other test in this repo sees it.",
            "withLoginHint(approval, loginHint)",
            callIn(CONNECT_SCREEN, "DeviceApprovalContent", "withLoginHint("),
        )
    }

    // -- reading the files -------------------------------------------------------------------------

    /**
     * The first [marker] call inside function [function] of [file], as one normalised string:
     */
    private fun callIn(file: File, function: String, marker: String): String {
        val body = functionBody(file, function)
        val start = body.indexOf(marker)
        check(start >= 0) {
            "$function() of ${file.name} no longer calls `$marker`: this lint would read nothing " +
                "and pass. Re-point the rule rather than let it go blind."
        }
        var depth = 0
        var end = -1
        for (i in (start + marker.length - 1) until body.length) {
            if (body[i] == '(') depth++
            if (body[i] == ')') depth--
            if (depth == 0) { end = i; break }
        }
        check(end > 0) { "unbalanced parentheses after `$marker` in $function() of ${file.name}" }
        return body.substring(start, end + 1)
            .replace(Regex("""\s+"""), " ")
            .replace("( ", "(")
            .replace(" )", ")")
            .replace(",)", ")")
    }

    /** The body of a function of [file], as text. Braces counted raw, as the other source lints do
     *  — sound only while the file's string literals carry no braces. */
    private fun functionBody(file: File, name: String): String {
        val lines = file.readLines()
        val start = lines.indexOfFirst { Regex("""\bfun $name\b""").containsMatchIn(it) }
        check(start >= 0) {
            "$name() is gone from ${file.name}: this lint reads nothing, so rename it here too " +
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

    private companion object {
        const val VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt"
        const val SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"

        /** Repo root, walked up from the module's working directory (as the other source lints do). */
        private fun sourceFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, path).isFile }
                ?.let { File(it, path) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )

        val CONNECT_VIEW_MODEL: File by lazy { sourceFile(VIEW_MODEL_PATH) }
        val CONNECT_SCREEN: File by lazy { sourceFile(SCREEN_PATH) }
    }
}
