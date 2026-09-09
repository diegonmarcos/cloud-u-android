package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The decision behind "Wrong email or password." on the IMAP connect screen — EXECUTED, not
 */
class ImapAuthRejectedTest {

    @Test
    fun `AUTHENTICATIONFAILED blames the credentials, whatever the message`() {
        assertTrue(imapAuthRejected("AUTHENTICATIONFAILED", "anything at all"))
    }

    /** THE symptom: server down during AUTHENTICATE is not a wrong password. */
    @Test
    fun `UNAVAILABLE during AUTHENTICATE is not a credentials verdict`() {
        assertFalse(imapAuthRejected("UNAVAILABLE", "AUTHENTICATE … failed: busy"))
    }

    @Test
    fun `AUTHORIZATIONFAILED blames the credentials`() {
        assertTrue(imapAuthRejected("AUTHORIZATIONFAILED", "AUTHENTICATE … failed: no"))
    }

    @Test
    fun `EXPIRED blames the credentials`() {
        assertTrue(imapAuthRejected("EXPIRED", "LOGIN … failed: password expired"))
    }

    /** A code outside the credentials family does not become "wrong password" just because
     *  the message carries the LOGIN verb — the code PRIMES. */
    @Test
    fun `ALERT over a LOGIN message is not a credentials verdict`() {
        assertFalse(imapAuthRejected("ALERT", "LOGIN … failed: read this alert"))
    }

    // ---- no code (pre-RFC 5530 server): the historical message fallback, unchanged ----

    @Test
    fun `without a code an AUTHENTICATE message still blames the credentials`() {
        assertTrue(imapAuthRejected(null, "AUTHENTICATE … failed"))
    }

    @Test
    fun `without a code a LOGIN message still blames the credentials, case-insensitively`() {
        assertTrue(imapAuthRejected(null, "login … failed: no"))
    }

    @Test
    fun `without a code a non-auth failure is not blamed on the credentials`() {
        assertFalse(imapAuthRejected(null, "SELECT failed"))
    }
}

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 */
class ImapAuthRejectedWiringTest {

    @Test
    fun `the catch reads the RFC 5530 code from the ImapException`() {
        assertPinnedLine(
            "val code = (t as? ImapException)?.responseCode",
            "Pinned because nulling or retyping this read (`val code: String? = null`) reinstates " +
                "the original defect: a server that is merely down ([UNAVAILABLE]) accuses the " +
                "user's password again.",
        )
    }

    @Test
    fun `the catch hands that code to the executed decision`() {
        assertPinnedLine(
            "imapAuthRejected(code, t.message.orEmpty()) ->",
            "Pinned because this is the seam between the catch and the decision the tests above " +
                "execute: hand it anything but `code` and the whole ImapAuthRejectedTest suite " +
                "guards a function the screen no longer calls.",
        )
    }

    @Test
    fun `the coded branch shows the code, never the response body`() {
        assertPinnedLine(
            "code != null -> string(R.string.connect_imap_refused, code)",
            "Pinned WITH its argument because swapping `code` for `t.message` puts the server's " +
                "free text back on screen, and a server's free text has already leaked a bearer " +
                "token once (8e942069).",
        )
    }

    @Test
    fun `connect_imap_refused has exactly one call site in ConnectViewModel`() {
        val occurrences = Regex(Regex.escape("connect_imap_refused")).findAll(source()).count()
        assertEquals(
            "connect_imap_refused must occur exactly once in $PATH — the pinned line above. A " +
                "second occurrence is a call site this lint does not read, free to pass the " +
                "server's response body; zero means the coded branch is gone and the body falls " +
                "through the else. Found $occurrences.",
            1, occurrences,
        )
    }

    // -- reading the file --------------------------------------------------------------------------

    /** Exactly one line of the source whose TRIMMED text equals [pinned] — 0 or 2+ both fail. */
    private fun assertPinnedLine(pinned: String, why: String) {
        val hits = source().lines().count { it.trim() == pinned }
        assertEquals(
            "expected exactly one line of $PATH whose trimmed text is:\n    $pinned\n" +
                "but found $hits. The line was rewritten, removed, split, or duplicated — this " +
                "lint compares the WHOLE line, so any change to it (even one that only lengthens " +
                "it) lands here. $why",
            1, hits,
        )
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
    }
}
