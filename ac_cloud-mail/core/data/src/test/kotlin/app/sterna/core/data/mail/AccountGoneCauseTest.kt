package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EXECUTES the choice of what an [AccountGoneException] is allowed to claim, and pins the sentence
 */
class AccountGoneCauseTest {

    /** A shipped-shape id: 36 characters, so any truncation of it shows. */
    private val id = "9f4d2c1e-7b3a-4e58-9c0d-1a2b3c4d5e6f"

    @Test fun `a sign-out is reported as a sign-out, in the caller's own words`() {
        assertEquals(
            "the ordinary case changed wording, or lost the id it exists to name. This is the " +
                "line #130 tuned down to Log.i on the push path, and the one refreshAllInboxes " +
                "has printed since #121 on the other; each keeps its own word for what was " +
                "running, and BOTH must carry the whole account id.",
            listOf(
                "9f4d2c1e-7b3a-4e58-9c0d-1a2b3c4d5e6f signed out mid-pass",
                "9f4d2c1e-7b3a-4e58-9c0d-1a2b3c4d5e6f signed out mid-walk",
            ),
            listOf(
                accountGoneCause(id, accountsUnreadable = false, passKind = "pass"),
                accountGoneCause(id, accountsUnreadable = false, passKind = "walk"),
            ),
        )
    }

    /**
     * The whole point of the volet: `accounts()` answers an unreadable blob with an empty list, so
     */
    @Test fun `an unreadable account list is not reported as a sign-out`() {
        assertEquals(
            "a storage failure is being logged as a sign-out (or the sentence no longer names the " +
                "read failure, or no longer names the account). The logcat a reporter sends us " +
                "then accuses a gesture the user never made, and the defect is hunted in the " +
                "wrong place.",
            "the stored account list could not be read, so 9f4d2c1e-7b3a-4e58-9c0d-1a2b3c4d5e6f " +
                "was not found in it (not a sign-out)",
            accountGoneCause(id, accountsUnreadable = true, passKind = "pass"),
        )
    }

    /**
     * A read failure is not about what was running: whatever the caller was doing, the blob was
     * unreadable. Pinned so that the two claims cannot silently swap roles.
     */
    @Test fun `the read failure reads the same whatever the pass was`() {
        assertEquals(
            "the unreadable-blob sentence now depends on the caller's word, so the two claims no " +
                "longer say what they were written to say.",
            accountGoneCause(id, accountsUnreadable = true, passKind = "pass"),
            accountGoneCause(id, accountsUnreadable = true, passKind = "walk"),
        )
    }
}
