package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision is EXECUTED here — [failureDetail] is a file-level function in
 */
class IdentityFailureDetailTest {

    @Test fun `the sentence the server refused with loses its final stop, and nothing else`() {
        assertEquals(
            "the detail the screen shows still carries the server's own full stop, so the account " +
                "screen writes '…for this account.. The address is saved…' — the defect measured " +
                "on the S7 on 2026-08-31.",
            "invalidProperties: E-mail address not configured for this account",
            failureDetail("invalidProperties: E-mail address not configured for this account."),
        )
    }

    @Test fun `the offline sentence keeps its quotes, its colon and its inner stop`() {
        assertEquals(
            "trimming the trailing stop must not touch anything else: this is the sentence a dead " +
                "DNS produces, and it is quoted to the user as the server (here, the network) " +
                "wrote it, character for character bar the last.",
            "Unable to resolve host \"mail.example.test\": No address associated with hostname",
            failureDetail("Unable to resolve host \"mail.example.test\": No address associated with hostname."),
        )
    }

    @Test fun `a sentence with no final punctuation is passed through untouched`() {
        assertEquals(
            "a detail that ends in a word must reach the screen exactly as the server wrote it.",
            "Identity not found",
            failureDetail("Identity not found"),
        )
    }

    @Test fun `repeated stops and the blanks between them all go`() {
        assertEquals(
            "one trailing stop is removed but the ones behind it stay, so the screen still shows " +
                "two in a row.",
            "refusé",
            failureDetail("refusé. . "),
        )
        assertEquals(
            "an ellipsis typed as three stops must go whole: the interface string writes the one " +
                "full stop that ends the sentence.",
            "refusé",
            failureDetail("refusé..."),
        )
        assertEquals(
            "the blanks around the sentence are not trimmed, so the screen shows 'server: refusé .'",
            "refusé",
            failureDetail("  refusé .  "),
        )
    }

    @Test fun `question and exclamation go, colon and ellipsis stay`() {
        assertEquals("a trailing ? is sentence punctuation too", "Really", failureDetail("Really?"))
        assertEquals("a trailing ! is sentence punctuation too", "Refused", failureDetail("Refused!"))
        assertEquals(
            "a trailing colon must STAY: it says the server's sentence was cut off before its " +
                "cause, and silently dropping it turns a truncated message into a whole-looking one.",
            "Caused by:",
            failureDetail("Caused by:"),
        )
        assertEquals(
            "a trailing ellipsis must STAY: it is the mark of a message the server itself " +
                "truncated, not the end of a sentence we are about to write.",
            "Reading the identities…",
            failureDetail("Reading the identities…"),
        )
    }

    @Test fun `nothing left to show is an empty string, for the caller to replace`() {
        assertEquals("a null message has nothing to show", "", failureDetail(null))
        assertEquals("an empty message has nothing to show", "", failureDetail(""))
        assertEquals("blanks only have nothing to show", "", failureDetail("   "))
        assertEquals(
            "a message made of punctuation alone must come back EMPTY, so the call site falls " +
                "back to the exception class name — otherwise the screen writes 'on the server: " +
                " .' with a hole in it.",
            "",
            failureDetail("..."),
        )
        assertEquals("mixed punctuation and blanks are still nothing", "", failureDetail(" ?! . "))
    }

    @Test fun `a stop INSIDE the message is never touched`() {
        assertEquals(
            "only the FINAL punctuation goes; a stop inside the sentence is part of what the " +
                "server said.",
            "a.b failed",
            failureDetail("a.b failed"),
        )
        assertEquals(
            "the inner stop of a host name survives; only the sentence's own end is removed.",
            "smtp.masto.top refused the identity",
            failureDetail("smtp.masto.top refused the identity."),
        )
    }
}
