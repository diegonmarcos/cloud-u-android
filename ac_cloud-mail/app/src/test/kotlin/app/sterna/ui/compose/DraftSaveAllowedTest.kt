package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the composer may offer to keep a draft (#35). A draft is uploaded
 */
class DraftSaveAllowedTest {

    // --- Encrypted: never ------------------------------------------------------------------------

    @Test fun anEncryptedMessageMayNotBeKeptAsADraft() {
        assertFalse(draftSaveAllowed(PgpMode.ENCRYPT))
    }

    @Test fun aLockClosedByHandForbidsTheDraftLikeTheAccountDefault() {
        // The lock cycles off → sign → encrypt; two taps from plain is a hand-set ENCRYPT, and it
        // must forbid the draft exactly as an encrypt-by-default account does. Same field, same
        // answer: the dialog cannot end up more permissive than the toolbar depending on how the
        // message came to be encrypted.
        val handSet = nextPgpMode(nextPgpMode(PgpMode.OFF))
        assertFalse(draftSaveAllowed(handSet))
    }

    @Test fun anEncryptedUnsignedMessageMayNotBeKeptAsADraftEither() {
        // The mode that encrypts without signing hands the server exactly the same plaintext when
        // it is saved as a draft. The rule is asked as "does this mode encrypt?" and not
        // "is this THE encrypting mode", which is what makes this answer come out right.
        assertFalse(
            "a draft of an unsigned encrypted message uploads its plaintext to the mail server — " +
                "the very thing the mode exists to withhold.",
            draftSaveAllowed(PgpMode.ENCRYPT_UNSIGNED),
        )
    }

    // --- Everything else: allowed ----------------------------------------------------------------

    @Test fun aPlainMessageMayBeKeptAsADraft() {
        assertTrue(draftSaveAllowed(PgpMode.OFF))
    }

    @Test fun aSignedMessageMayBeKeptAsADraft() {
        // SIGN is deliberately not caught: a signed message is readable in transit and at rest by
        // design, so its draft leaks nothing the send would not. Catching it here would be a
        // regression, not extra safety.
        assertTrue(draftSaveAllowed(PgpMode.SIGN))
    }

    @Test fun unlockingAnEncryptedMessageAllowsTheDraftAgain() {
        // Third tap on the lock: back to plain, and the draft is offered again — including for a
        // draft reopened for editing, whose mode is whatever the composer currently shows.
        assertTrue(draftSaveAllowed(nextPgpMode(PgpMode.ENCRYPT)))
    }

    // --- The cycle the hand-set path relies on ---------------------------------------------------

    @Test fun theLockCyclesOffSignEncryptOff() {
        assertTrue(nextPgpMode(PgpMode.OFF) == PgpMode.SIGN)
        assertTrue(nextPgpMode(PgpMode.SIGN) == PgpMode.ENCRYPT)
        assertTrue(nextPgpMode(PgpMode.ENCRYPT) == PgpMode.OFF)
    }

    @Test fun theCycleNeverWalksIntoTheUnsignedMode() {
        // Three stops, still. ENCRYPT_UNSIGNED is reached ONLY by choosing it in the long-press
        // menu: a short tap must not put anyone into sending unsigned mail without asking. The
        // three assertions above are the same rule read forwards.
        assertTrue(
            "the short tap now walks into the unsigned mode. Two taps from plain would start " +
                "sending mail that proves nothing about who sent it, chosen by nobody.",
            PgpMode.entries.none { nextPgpMode(it) == PgpMode.ENCRYPT_UNSIGNED },
        )
    }

    @Test fun aTapAlwaysGetsOutOfTheUnsignedMode() {
        // And out means OFF, exactly as it does from ENCRYPT: the padlock is the way back to plain.
        assertTrue(nextPgpMode(PgpMode.ENCRYPT_UNSIGNED) == PgpMode.OFF)
        assertTrue(draftSaveAllowed(nextPgpMode(PgpMode.ENCRYPT_UNSIGNED)))
    }

    // --- The two rules are independent -----------------------------------------------------------

    @Test fun contentAloneDoesNotMakeAnEncryptedDraftSavable() {
        // #69 says the compose holds something worth saving; #35 says it may not be saved anyway.
        // Both must hold for a save, so a full message under a closed lock is still refused.
        assertTrue(draftHasContent("alex@example.org", "", "", "Lunch", "see you at noon", false))
        assertFalse(draftSaveAllowed(PgpMode.ENCRYPT))
    }

    // --- Scheduling is stricter than a draft (audit A2/A3) ---------------------------------------
    //
    // A scheduled send is fired by a headless worker (no PGP provider) and its table carries no
    // attachments, so scheduling must refuse what a draft happily keeps: a signed message and a
    // message with attachments. Both would otherwise go out unsigned or amputated at the due time.

    @Test fun aPlainMessageWithoutAttachmentsMayBeScheduled() {
        assertTrue(scheduleSendAllowed(PgpMode.OFF, hasAttachment = false))
    }

    @Test fun aSignedMessageMayBeSavedAsADraftButNotScheduled() {
        // The exact A3 split: SIGN is fine for a draft (readable at rest anyway) but a scheduled
        // SIGN send would leave unsigned — the worker can't reach the key.
        assertTrue(draftSaveAllowed(PgpMode.SIGN))
        assertFalse(scheduleSendAllowed(PgpMode.SIGN, hasAttachment = false))
    }

    @Test fun anEncryptedMessageMayNotBeScheduledEither() {
        assertFalse(scheduleSendAllowed(PgpMode.ENCRYPT, hasAttachment = false))
    }

    @Test fun attachmentsAloneBlockSchedulingEvenInPlainMode() {
        // A2: the scheduled table drops attachments, so a plain message carrying one must not be
        // schedulable — it would fire stripped of its files.
        assertFalse(scheduleSendAllowed(PgpMode.OFF, hasAttachment = true))
    }

    @Test fun pgpAndAttachmentsCombineWithoutReopeningTheGate() {
        // Both reasons at once still refuses (no toggle cancels the other out).
        assertFalse(scheduleSendAllowed(PgpMode.SIGN, hasAttachment = true))
        assertFalse(scheduleSendAllowed(PgpMode.ENCRYPT, hasAttachment = true))
    }
}
