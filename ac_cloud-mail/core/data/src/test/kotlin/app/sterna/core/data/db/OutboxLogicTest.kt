package app.sterna.core.data.db

import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.jmap.JmapException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure outbox decisions: when an item is due, retry/give-up, and badge counting. */
class OutboxLogicTest {
    @Test fun heldNotDueBeforeWindow() {
        assertFalse(OutboxLogic.isReadyToSend(OutboxState.HELD, notBeforeMillis = 1_000, now = 500))
    }

    @Test fun heldDueAfterWindow() {
        assertTrue(OutboxLogic.isReadyToSend(OutboxState.HELD, notBeforeMillis = 1_000, now = 1_000))
        assertTrue(OutboxLogic.isReadyToSend(OutboxState.HELD, notBeforeMillis = 1_000, now = 1_500))
    }

    @Test fun queuedIsAlwaysDue() {
        assertTrue(OutboxLogic.isReadyToSend(OutboxState.QUEUED, notBeforeMillis = Long.MAX_VALUE, now = 0))
    }

    /**
     * This assertion used to read the other way round, under the name
     */
    @Test fun aDeliveryAlreadyBegunIsNeverDueAgain() {
        assertFalse(
            "⛔ a SENDING row is a delivery ALREADY BEGUN: the worker writes that state immediately " +
                "before handing the message to the server, so nothing else can have put it there. " +
                "Calling it due is the duplicate measured on the bench — kill the process 1.2 s " +
                "after Send, reopen, and the recipient gets the same message twice 46 s apart " +
                "under two Message-Ids, with the outbox left empty and nothing saying so",
            OutboxLogic.isReadyToSend(OutboxState.SENDING, notBeforeMillis = 0, now = Long.MAX_VALUE),
        )
        assertFalse(
            "⛔ and a row already parked as INTERRUPTED must not be handed back either, or the " +
                "parking buys nothing: the very next pass would deliver it a second time. Getting " +
                "it out again is Retry, which the user presses knowingly",
            OutboxLogic.isReadyToSend(OutboxState.INTERRUPTED, notBeforeMillis = 0, now = Long.MAX_VALUE),
        )
    }

    /**
     * What the send worker must do with the row it has just read, state by state. The expected
     */
    @Test fun onlyADeliveryLeftInFlightIsParkedWhenTheWorkerPicksItUp() {
        assertEquals(
            "⛔ the worker READS the row before it writes SENDING, so no run ever meets its own " +
                "SENDING: finding one means an earlier run wrote it and never came back (process " +
                "death, cancellation, reboot) — and that run may well have reached the server. " +
                "Parking it as INTERRUPTED loses nothing (the row stays in the Outbox, Retry still " +
                "sends it); handing it back to the worker is irreversible for the recipient. " +
                "Everything else has nothing begun to park: HELD and QUEUED were never handed to " +
                "anyone, and FAILED / KEPT_AS_DRAFT / EDITING / INTERRUPTED are already parked — " +
                "re-parking those would overwrite what they say",
            mapOf(
                OutboxState.HELD to null,
                OutboxState.QUEUED to null,
                OutboxState.SENDING to OutboxState.INTERRUPTED,
                OutboxState.FAILED to null,
                OutboxState.KEPT_AS_DRAFT to null,
                OutboxState.EDITING to null,
                OutboxState.INTERRUPTED to null,
            ),
            OutboxState.entries.associateWith { OutboxLogic.parkOnPickup(it) },
        )
    }

    /** The parked row must not be reopenable, or looking at it would send it (#70's door). */
    @Test fun anInterruptedRowCannotBeReopenedInTheComposer() {
        assertFalse(
            "⛔ reopening parks the row in EDITING, and closing the composer untouched runs " +
                "stateAfterEdit(attemptCount = 0), which answers QUEUED. So Edit on an INTERRUPTED " +
                "row means merely LOOKING at the message puts it back on the wire by itself — the " +
                "duplicate again, through the #70 door. Retry and Delete still work on it",
            OutboxLogic.canEdit(pgpMode = null, state = OutboxState.INTERRUPTED, carriesUnreplayablePrebuiltEntity = false),
        )
    }

    /**
     * The same door, on the row a draft save parks (#95 × #70): the message the user chose to KEEP
     * must not be reopenable either, or looking at it sends it.
     */
    @Test fun aRowKeptAsADraftCannotBeReopenedInTheComposer() {
        assertFalse(
            "⛔ reopening parks the row in EDITING, and closing the composer untouched runs " +
                "stateAfterEdit(attemptCount = 0), which answers QUEUED and re-arms delivery. So " +
                "Edit on a KEPT_AS_DRAFT row means merely LOOKING at the message the user chose " +
                "NOT to send puts it on the wire by itself — the very send that state exists to " +
                "refuse. The draft opens from Drafts (LocalDraftRows); Retry stays the one gesture " +
                "that sends the row, and Delete still works on it",
            OutboxLogic.canEdit(pgpMode = null, state = OutboxState.KEPT_AS_DRAFT, carriesUnreplayablePrebuiltEntity = false),
        )
    }

    /**
     * The whole state axis of [OutboxLogic.canEdit], executed against a map written out by hand —
     * never recomputed from the shipped rule, so inverting the rule turns this red.
     */
    @Test fun onlyAGenuinelyWaitingRowCanBeReopened() {
        assertEquals(
            "the only reopenable states are the ones genuinely WAITING (QUEUED, HELD, FAILED): " +
                "every other row is either in flight, already open, or parked on purpose — and " +
                "any new value of the enumeration must be classified here by hand",
            mapOf(
                OutboxState.QUEUED to true,
                OutboxState.HELD to true,
                OutboxState.FAILED to true,
                OutboxState.SENDING to false,
                OutboxState.EDITING to false,
                OutboxState.INTERRUPTED to false,
                OutboxState.KEPT_AS_DRAFT to false,
            ),
            OutboxState.entries.associateWith { OutboxLogic.canEdit(null, it, false) },
        )
    }

    /**
     * The three screen consequences of parking, executed: the row is listed, it counts on the badge
     * at once, and it raises no failure banner.
     */
    @Test fun anInterruptedRowIsShownAtOnceAndAccusesNothing() {
        val now = 10_000L
        assertTrue(
            "the Outbox screen lists exactly the rows this accepts, and the message must be there",
            OutboxLogic.isWaitingInOutbox(OutboxState.INTERRUPTED),
        )
        assertEquals(
            "the badge must show it AT ONCE, with no grace: the delivery has STOPPED, so no delay " +
                "would make the silence any truer (the grace exists so a send that works never " +
                "draws the eye, #82 — this one is not on its way)",
            1,
            OutboxLogic.activeCount(listOf(OutboxBadgeItem(OutboxState.INTERRUPTED, notBeforeMillis = now)), now),
        )
        assertNull(
            "and a row that already counts has no badge wake-up left to schedule",
            OutboxLogic.nextBadgeChange(listOf(OutboxBadgeItem(OutboxState.INTERRUPTED, notBeforeMillis = now)), now),
        )
        assertFalse(
            "⛔ and it must NOT raise the Inbox's 'Some messages didn't send. Tap to review.' " +
                "banner: nothing failed and the message may well have gone out. That banner is " +
                "cleared by nothing, so it would be a permanent accusation over a delivered mail",
            OutboxLogic.needsFailureBanner(OutboxState.INTERRUPTED),
        )
        assertEquals(
            "the same, through the count the banner is built on",
            0,
            OutboxLogic.failedCount(listOf(OutboxState.INTERRUPTED)),
        )
    }

    @Test fun failedNeverAutoDue() {
        assertFalse(OutboxLogic.isReadyToSend(OutboxState.FAILED, notBeforeMillis = 0, now = Long.MAX_VALUE))
    }

    /** #70: an item open in the composer is held back from the worker, however overdue it looks. */
    @Test fun editingNeverDue() {
        assertFalse(OutboxLogic.isReadyToSend(OutboxState.EDITING, notBeforeMillis = 0, now = Long.MAX_VALUE))
    }

    @Test fun retriesUntilCapThenGivesUp() {
        assertTrue(OutboxLogic.shouldRetry(1))
        assertTrue(OutboxLogic.shouldRetry(OutboxLogic.MAX_ATTEMPTS - 1))
        assertFalse(OutboxLogic.shouldRetry(OutboxLogic.MAX_ATTEMPTS))
        assertFalse(OutboxLogic.shouldRetry(OutboxLogic.MAX_ATTEMPTS + 1))
    }

    /**
     * #183: a delivery the SERVER refused is over. Whatever the attempt count, the row is parked —
     */
    @Test fun aRefusedDeliveryIsParkedAtOnce() {
        val refused = JmapException("Not delivered to nope@masto.top: 550 5.1.2 Mailbox does not exist.", permanent = true)
        for (attempts in listOf(0, 1, 4, OutboxLogic.MAX_ATTEMPTS, 99)) {
            assertEquals(
                "⛔ attemptCount=$attempts: a refusal the server has already ruled on must be " +
                    "FAILED on the spot. Sent back to QUEUED it is re-delivered up to " +
                    "${OutboxLogic.MAX_ATTEMPTS} times: five copies in the user's Sent folder, and " +
                    "on a partial refusal the recipient who DOES exist receives the message five " +
                    "times over — worse than the silence of #183",
                OutboxState.FAILED,
                OutboxLogic.stateAfterFailure(attempts, refused),
            )
        }
    }

    /**
     * THE CHAIN, not the links. Parking a refusal as FAILED is worth nothing on its own:
     */
    @Test fun aRefusedDeliveryStaysParkedEvenAfterBeingReopened() {
        val refused = JmapException("Not delivered to nope@masto.top: 550 5.1.2 Mailbox does not exist.", permanent = true)
        for (previous in listOf(0, 1, 4)) {
            val attempts = OutboxLogic.attemptsAfterFailure(previous, refused)
            assertEquals(
                "the refusal itself parks the row",
                OutboxState.FAILED,
                OutboxLogic.stateAfterFailure(attempts, refused),
            )
            assertTrue(
                "⛔ an attempt is never un-counted: $attempts must be at least ${previous + 1}",
                attempts >= previous + 1,
            )
            assertEquals(
                "⛔ from previous=$previous the row was parked with $attempts attempts, and " +
                    "stateAfterEdit answers QUEUED for it: opening the refused line to fix the " +
                    "address and coming straight back out puts the message on the wire again, " +
                    "with no gesture asking for it (#70's door onto #183)",
                OutboxState.FAILED,
                OutboxLogic.stateAfterEdit(attempts),
            )
            // The second door, in the terms the SQL uses: OutboxDao.revertEditingExhaustedToFailed
            // claims EDITING rows at `attemptCount >= MAX_ATTEMPTS` at startup, and the rest are
            assertTrue(
                "⛔ a refused row stranded in EDITING by a process death must be claimed by the " +
                    "exhausted statement first (attemptCount >= ${OutboxLogic.MAX_ATTEMPTS}), or " +
                    "the startup sweep parks it as 'editing was interrupted' and the server's own " +
                    "reply is lost: got $attempts",
                attempts >= OutboxLogic.MAX_ATTEMPTS,
            )
        }
    }

    /**
     * The counter-test of the ruse above, and it is the expensive one to get wrong: forcing the
     */
    @Test fun anOrdinaryFailureCountsOneAttemptAndNothingMore() {
        for (failure in listOf(
            java.io.IOException("connection reset"),
            JmapException("JMAP request failed: HTTP 503 Service Unavailable", httpCode = 503),
        )) {
            for (previous in listOf(0, 1, 4)) {
                assertEquals(
                    "$failure from previous=$previous: exactly one more attempt",
                    previous + 1,
                    OutboxLogic.attemptsAfterFailure(previous, failure),
                )
            }
            assertEquals(
                "⛔ and it still comes back out of the composer as a QUEUED send while it is " +
                    "under the cap — #70's contract, unchanged",
                OutboxState.QUEUED,
                OutboxLogic.stateAfterEdit(OutboxLogic.attemptsAfterFailure(0, failure)),
            )
        }
    }

    /**
     * And the other side of it, which is the easy thing to break: every ORDINARY failure keeps
     */
    @Test fun anOrdinaryFailureStillRetriesUntilTheCap() {
        for (failure in listOf(
            java.io.IOException("connection reset"),
            // A JmapException that is NOT permanent: the type is not the rule, the flag is.
            JmapException("JMAP request failed: HTTP 503 Service Unavailable", httpCode = 503),
        )) {
            assertEquals("$failure at 0", OutboxState.QUEUED, OutboxLogic.stateAfterFailure(0, failure))
            assertEquals("$failure at 1", OutboxState.QUEUED, OutboxLogic.stateAfterFailure(1, failure))
            assertEquals("$failure at 4", OutboxState.QUEUED, OutboxLogic.stateAfterFailure(4, failure))
            assertEquals(
                "$failure at the cap",
                OutboxState.FAILED,
                OutboxLogic.stateAfterFailure(OutboxLogic.MAX_ATTEMPTS, failure),
            )
            assertEquals(
                "$failure past the cap",
                OutboxState.FAILED,
                OutboxLogic.stateAfterFailure(OutboxLogic.MAX_ATTEMPTS + 1, failure),
            )
        }
    }

    /**
     * #70 regression: a reopened item closed untouched returns to QUEUED only while it still has
     */
    @Test fun aReopenedItemUnderTheRetryCapGoesBackToTheQueue() {
        assertEquals(OutboxState.QUEUED, OutboxLogic.stateAfterEdit(0))
        assertEquals(OutboxState.QUEUED, OutboxLogic.stateAfterEdit(OutboxLogic.MAX_ATTEMPTS - 1))
    }

    @Test fun aReopenedItemWhoseRetriesWereExhaustedStaysFailed() {
        assertEquals(OutboxState.FAILED, OutboxLogic.stateAfterEdit(OutboxLogic.MAX_ATTEMPTS))
        assertEquals(OutboxState.FAILED, OutboxLogic.stateAfterEdit(OutboxLogic.MAX_ATTEMPTS + 1))
    }

    /** #82: a failed item is the only one that needs the user right away; the rest get their grace. */
    @Test fun badgeCountsAFailedItemAtOnceAndTheOthersOnlyAfterTheGrace() {
        val now = 10_000L
        val items = listOf(
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = now), // window just closed
            OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = now),
            OutboxBadgeItem(OutboxState.SENDING, notBeforeMillis = now),
            OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = now),
        )
        assertEquals(1, OutboxLogic.activeCount(items, now))
        assertEquals(4, OutboxLogic.activeCount(items, now + OutboxLogic.BADGE_GRACE_MILLIS))
        assertEquals(1, OutboxLogic.failedCount(items.map { it.state }))
    }

    /**
     * #70: offline nothing takes the row out of HELD, so the row must count on the clock alone —
     * #82 only pushes that instant [OutboxLogic.BADGE_GRACE_MILLIS] past the end of the window.
     */
    @Test fun badgeCountsAHeldRowOnceItsWindowAndTheGraceHaveElapsed() {
        val item = OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 5_000)
        val due = 5_000 + OutboxLogic.BADGE_GRACE_MILLIS
        assertEquals(0, OutboxLogic.activeCount(listOf(item), now = 4_999))
        assertEquals("still on its way, not yet worth a badge", 0, OutboxLogic.activeCount(listOf(item), now = due - 1))
        assertEquals(1, OutboxLogic.activeCount(listOf(item), now = due))
        assertEquals(1, OutboxLogic.activeCount(listOf(item), now = due + 60_000))
    }

    /**
     * #82: the grace hangs off the row's own deadline and is never restarted. A row put back to
     */
    @Test fun aRequeuedRowDoesNotGetAFreshGrace() {
        val requeued = OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = 5_000) // attempt failed at ~5_100
        val due = 5_000 + OutboxLogic.BADGE_GRACE_MILLIS
        assertEquals("a hiccup healing inside the grace stays silent", 0, OutboxLogic.activeCount(listOf(requeued), 5_100))
        assertEquals(1, OutboxLogic.activeCount(listOf(requeued), now = due))
    }

    /**
     * #82: Retry rewrites the row's deadline (MailRepository.retryOutbox), and that deadline is the
     */
    @Test fun aRowWhoseDeadlineWasJustResetByRetryDoesNotCount() {
        val retriedAt = 100_000L
        val row = OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = retriedAt)
        assertEquals("a send just relaunched is in progress", 0, OutboxLogic.activeCount(listOf(row), retriedAt))
        assertEquals(1, OutboxLogic.activeCount(listOf(row), retriedAt + OutboxLogic.BADGE_GRACE_MILLIS))
    }

    /** #82: a send queued with no undo window (holdMs = 0) is silent from the instant it was queued. */
    @Test fun aRowQueuedWithoutAnUndoWindowIsSilentForTheGraceToo() {
        val queuedAt = 5_000L
        val item = OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = queuedAt) // insert sets both to `now`
        assertEquals(0, OutboxLogic.activeCount(listOf(item), now = queuedAt))
        assertEquals(0, OutboxLogic.activeCount(listOf(item), now = queuedAt + OutboxLogic.BADGE_GRACE_MILLIS - 1))
        assertEquals(1, OutboxLogic.activeCount(listOf(item), now = queuedAt + OutboxLogic.BADGE_GRACE_MILLIS))
    }

    @Test fun badgeCountsNothingWhenTheOutboxIsEmpty() {
        assertEquals(0, OutboxLogic.activeCount(emptyList(), now = 1))
    }

    /** #70: a row open in the composer is being handled, not waiting — keep it off the badge. */
    @Test fun badgeIgnoresAnItemOpenForEditing() {
        val queuedAt = 10_000L
        val items = listOf(
            OutboxBadgeItem(OutboxState.EDITING, notBeforeMillis = queuedAt),
            OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = queuedAt),
        )
        // Well past the grace, so only the composer's own row is left out of the count.
        assertEquals(1, OutboxLogic.activeCount(items, queuedAt + OutboxLogic.BADGE_GRACE_MILLIS))
        assertNull("an EDITING row never schedules a wake-up", OutboxLogic.nextBadgeChange(listOf(items[0]), queuedAt))
    }

    /** The self-wake-up: the earliest row whose grace has not run out yet, whatever its state. */
    @Test fun theNextBadgeChangeIsTheEarliestThresholdStillAhead() {
        val grace = OutboxLogic.BADGE_GRACE_MILLIS
        val items = listOf(
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 9_000),
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 7_000),
            OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = 3_000), // earliest deadline of the four
            OutboxBadgeItem(OutboxState.SENDING, notBeforeMillis = 8_000),
        )
        assertEquals(3_000 + grace, OutboxLogic.nextBadgeChange(items, now = 5_000))
        // Once that one counts, the next wake-up is the following threshold, not a repeat.
        assertEquals(7_000 + grace, OutboxLogic.nextBadgeChange(items, now = 3_000 + grace))
    }

    /** Nothing left to wait for: an already-counting row and a FAILED one schedule no wake-up. */
    @Test fun thereIsNoNextBadgeChangeWhenEveryRowHasSettled() {
        val grace = OutboxLogic.BADGE_GRACE_MILLIS
        val items = listOf(
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 1_000),
            OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = 9_000),
            OutboxBadgeItem(OutboxState.EDITING, notBeforeMillis = 9_000),
        )
        assertNull(OutboxLogic.nextBadgeChange(items, now = 1_000 + grace))
    }

    /**
     * The three rows below are the ONLY three shapes production ever writes, and the tests that
     */
    private fun signed() = OutboxLogic.isUnreplayablePrebuiltEntity("SIGN", "/outbox/1/prebuilt-entity.mime")
    private fun encrypted() = OutboxLogic.isUnreplayablePrebuiltEntity("ENCRYPT", "/outbox/2/prebuilt-entity.mime")
    private fun receipt() = OutboxLogic.isUnreplayablePrebuiltEntity(null, "/outbox/3/prebuilt-entity.mime")
    private fun ordinary() = OutboxLogic.isUnreplayablePrebuiltEntity(null, null)

    @Test fun anEncryptedItemCannotBeReopenedInTheComposer() {
        assertEquals(false, OutboxLogic.canEdit("ENCRYPT", OutboxState.QUEUED, encrypted()))
        assertEquals(false, OutboxLogic.canEdit("encrypt", OutboxState.QUEUED, encrypted()))
    }

    /**
     * A read receipt cannot be reopened: nothing can build its entity a second time.
     */
    @Test fun aReadReceiptCannotBeReopened() {
        assertEquals(true, receipt())
        assertEquals(false, OutboxLogic.canEdit(null, OutboxState.QUEUED, receipt()))
        assertEquals(false, OutboxLogic.canEdit(null, OutboxState.HELD, receipt()))
        assertEquals(false, OutboxLogic.canEdit(null, OutboxState.FAILED, receipt()))
    }

    /**
     * THE WITNESS, and the regression it caught. A SIGNED row carries a pre-built entity too —
     */
    @Test fun aSignedItemStillOffersEditAlthoughItCarriesAnEntity() {
        assertEquals("a signed row's entity is one the composer can produce again", false, signed())
        assertEquals(true, OutboxLogic.canEdit("SIGN", OutboxState.QUEUED, signed()))
        assertEquals(true, OutboxLogic.canEdit("SIGN", OutboxState.HELD, signed()))
        assertEquals(true, OutboxLogic.canEdit("SIGN", OutboxState.FAILED, signed()))
    }

    /**
     * WHAT THE OUTBOX COLUMN IS FOR, executed end to end: [OutboxLogic.outboxPgpModeName] writes
     */
    @Test fun anUnsignedEncryptedRowIsFiledAsEncryptedAndOffersNoEdit() {
        assertEquals(
            "the unsigned encrypted mode is stored under a name of its own. canEdit compares " +
                "this column as TEXT: the Edit button comes back on a row with no body, and the " +
                "message is re-sent empty.",
            "ENCRYPT",
            OutboxLogic.outboxPgpModeName(PgpMode.ENCRYPT_UNSIGNED),
        )
        assertEquals(
            "Edit is offered on an unsigned encrypted row — the composer would open empty.",
            false,
            OutboxLogic.canEdit(
                OutboxLogic.outboxPgpModeName(PgpMode.ENCRYPT_UNSIGNED),
                OutboxState.QUEUED,
                false,
            ),
        )
    }

    /** The rest of the column, unchanged: OFF and "no PGP" store nothing, SIGN stores itself. */
    @Test fun theOutboxColumnStoresNothingForAPlainMessage() {
        assertNull(OutboxLogic.outboxPgpModeName(null))
        assertNull(
            "PgpMode.OFF is never stored — null in that column is what says no PGP was involved, " +
                "and isUnreplayablePrebuiltEntity reads it that way.",
            OutboxLogic.outboxPgpModeName(PgpMode.OFF),
        )
        assertEquals("SIGN", OutboxLogic.outboxPgpModeName(PgpMode.SIGN))
        assertEquals("ENCRYPT", OutboxLogic.outboxPgpModeName(PgpMode.ENCRYPT))
    }

    @Test fun aWaitingUnencryptedItemCanBeReopened() {
        assertEquals(true, OutboxLogic.canEdit(null, OutboxState.QUEUED, ordinary()))
        assertEquals(true, OutboxLogic.canEdit(null, OutboxState.FAILED, ordinary()))
    }

    /** #70: Edit must never be offered or accepted on a row whose send is in flight or already open. */
    @Test fun anInFlightOrAlreadyOpenItemCannotBeReopened() {
        assertEquals(false, OutboxLogic.canEdit(null, OutboxState.SENDING, ordinary()))
        assertEquals(false, OutboxLogic.canEdit(null, OutboxState.EDITING, ordinary()))
    }
}
