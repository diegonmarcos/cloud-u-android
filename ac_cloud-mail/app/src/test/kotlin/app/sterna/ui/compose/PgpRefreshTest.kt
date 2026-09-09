package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the padlock settles on when the composer re-checks OpenPGP (#35), and — the part that was
 */
class PgpRefreshTest {
    private fun refresh(
        current: PgpMode,
        available: Boolean,
        accountConfigured: Boolean = true,
        encryptByDefault: Boolean = false,
        userSet: Boolean = false,
    ) = pgpRefresh(current, available, accountConfigured, encryptByDefault, userSet)

    @Test fun withoutOpenPgpTheModeGoesOff() {
        assertEquals(PgpMode.OFF, refresh(PgpMode.ENCRYPT, available = false).mode)
        assertEquals(PgpMode.OFF, refresh(PgpMode.SIGN, available = false).mode)
    }

    @Test fun losingAnEncryptedMessagesPadlockIsAnnounced() {
        // The provider went away (uninstalled, or it no longer answers) while a message queued as
        // encrypted waited: reopening it must say so rather than quietly show a plain mail.
        val outcome = refresh(PgpMode.ENCRYPT, available = false, accountConfigured = true)
        assertEquals(PgpMode.OFF, outcome.mode)
        assertEquals(PgpDropReason.NO_PROVIDER, outcome.dropped)
    }

    @Test fun anAccountWithoutOpenPgpSaysThatInsteadOfBlamingTheProvider() {
        // Switching "From" to an account that has no OpenPGP set up: the reason is not the same,
        // and telling the user OpenKeychain is missing when it is installed would be a lie.
        val outcome = refresh(PgpMode.SIGN, available = false, accountConfigured = false)
        assertEquals(PgpMode.OFF, outcome.mode)
        assertEquals(PgpDropReason.NOT_CONFIGURED, outcome.dropped)
    }

    @Test fun nothingIsAnnouncedWhenNothingWasLost() {
        // A plain message on an account with no OpenPGP: there was no padlock to lose, so the
        // composer stays quiet. This is the common case and it must not toast on every open.
        val outcome = refresh(PgpMode.OFF, available = false, accountConfigured = false)
        assertEquals(PgpMode.OFF, outcome.mode)
        assertEquals(PgpDropReason.NONE, outcome.dropped)
    }

    @Test fun theSecondCheckOfTheSameDropSaysNothingMore() {
        // The composer re-checks once a restored mode is back in place, so the drop can be examined
        // twice. The first check took the mode to OFF; the second finds OFF and adds nothing, which
        // is what keeps the explanation to a single message.
        val first = refresh(PgpMode.ENCRYPT, available = false)
        assertEquals(PgpDropReason.NO_PROVIDER, first.dropped)
        assertEquals(PgpDropReason.NONE, refresh(first.mode, available = false).dropped)
    }

    // --- the ordering the second check exists to settle ------------------------------------------
    //
    // Reopening a signed/encrypted message runs two checks around one restore, and which of them
    // lands first is not fixed: with the provider merely unreachable the first check waits for it

    /** Replay a run of checks over [start], returning the mode left and every reason given aloud. */
    private fun checks(start: PgpMode, times: Int): Pair<PgpMode, List<PgpDropReason>> {
        var mode = start
        val said = mutableListOf<PgpDropReason>()
        repeat(times) {
            val outcome = refresh(mode, available = false, userSet = true)
            mode = outcome.mode
            if (outcome.dropped != PgpDropReason.NONE) said += outcome.dropped
        }
        return mode to said
    }

    @Test fun theRestoreLandingBeforeBothChecksEndsUpOffAndSaysSoOnce() {
        // The restore put ENCRYPT back first; both checks then run against it.
        assertEquals(PgpMode.OFF to listOf(PgpDropReason.NO_PROVIDER), checks(PgpMode.ENCRYPT, times = 2))
    }

    @Test fun theRestoreLandingBetweenTheTwoChecksEndsUpTheSameWay() {
        // The first check finished on an untouched composer and had nothing to report; the restore
        // then put ENCRYPT back, and only the second check sees it.
        val (afterFirst, saidFirst) = checks(PgpMode.OFF, times = 1)
        assertEquals(emptyList<PgpDropReason>(), saidFirst)
        assertEquals(PgpMode.OFF, afterFirst)

        val restored = PgpMode.ENCRYPT
        assertEquals(PgpMode.OFF to listOf(PgpDropReason.NO_PROVIDER), checks(restored, times = 1))
    }

    @Test fun aProviderThatIsStillThereLeavesTheRestoredModeAloneInEitherOrder() {
        // The same two checks on a working provider must not undo the restore: the mode was set by
        // the message, so it counts as the user's and neither pass may rewrite it.
        var mode = PgpMode.SIGN
        repeat(2) { mode = refresh(mode, available = true, encryptByDefault = true, userSet = true).mode }
        assertEquals(PgpMode.SIGN, mode)
    }

    @Test fun anAccountThatEncryptsByDefaultOpensLocked() {
        val outcome = refresh(PgpMode.OFF, available = true, encryptByDefault = true, userSet = false)
        assertEquals(PgpMode.ENCRYPT, outcome.mode)
        assertEquals(PgpDropReason.NONE, outcome.dropped)
    }

    @Test fun aLockTheUserSetIsNeverRewritten() {
        // Hand-set, or carried by a message that was queued as signed: the mode is the user's.
        assertEquals(
            PgpMode.SIGN,
            refresh(PgpMode.SIGN, available = true, encryptByDefault = true, userSet = true).mode,
        )
        assertEquals(
            PgpMode.OFF,
            refresh(PgpMode.OFF, available = true, encryptByDefault = true, userSet = true).mode,
        )
    }

    @Test fun anAvailableProviderLeavesAnOrdinaryComposerAlone() {
        assertEquals(PgpMode.OFF, refresh(PgpMode.OFF, available = true).mode)
        assertEquals(PgpMode.ENCRYPT, refresh(PgpMode.ENCRYPT, available = true).mode)
    }
}
