package app.sterna.core.data.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The outbox badge over time, driven through the shipped [OutboxLogic.badgeCount] on a virtual
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OutboxBadgeCountTest {
    private val grace = OutboxLogic.BADGE_GRACE_MILLIS

    /** The undo window used by a normal send; the row is only allowed to leave once it elapses. */
    private val window = 5_000L

    /** The reporter's case (#82): online the message is gone in seconds and the badge never blinks. */
    @Test fun sendingWhileOnlineNeverMakesTheBadgeAppear() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        runCurrent()

        // The window closes, the worker sends, and the delivered row is dropped a second later.
        advanceTimeBy(window + 1_000)
        rows.value = emptyList()
        advanceTimeBy(grace * 2) // long past the point where it would have counted

        assertEquals("a send that works must never light the badge", listOf(0), seen)
        job.cancel()
    }

    /**
     * #70, the reason the badge exists: offline the delivery worker is gated on connectivity and
     * never runs, so nothing moves the row and nothing fails — the clock alone must raise the badge.
     */
    @Test fun sendingWhileOfflineMakesTheBadgeAppearOnItsOwn() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        runCurrent()
        assertEquals("silent during the undo window", listOf(0), seen)

        advanceTimeBy(window + grace - 1)
        assertEquals("still silent while the message may yet go out", listOf(0), seen)

        advanceTimeBy(2) // the grace runs out; the message is still sitting in the outbox
        assertEquals("counted once it has clearly not gone out", listOf(0, 1), seen)
        job.cancel()
    }

    @Test fun theCountDropsBackToZeroWhenTheMessageLeaves() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        advanceTimeBy(window + grace + 1)
        assertEquals(listOf(0, 1), seen)

        rows.value = emptyList() // the network came back: delivered, and the worker dropped the row
        runCurrent()
        assertEquals(listOf(0, 1, 0), seen)
        job.cancel()
    }

    /** A parked failure gets no grace: it is over, it needs the user, and waiting changes nothing. */
    @Test fun aFailedRowCountsImmediately() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = 0)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        runCurrent()
        assertEquals("a failure is shown at once, with no delay", listOf(1), seen)
        job.cancel()
    }

    /**
     * A transient failure puts the row back to QUEUED (OutboxWorker) without touching its deadline.
     */
    @Test fun aRowRequeuedAfterATransientFailureDoesNotRestartTheGrace() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        advanceTimeBy(window + 100) // the attempt runs and throws; the worker requeues the row
        rows.value = listOf(OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = window))
        runCurrent()
        assertEquals("a retry still inside the grace must not flash the badge", listOf(0), seen)

        advanceTimeBy(grace - 101)
        assertEquals(listOf(0), seen)
        advanceTimeBy(2) // the original deadline plus the grace, reached without the message leaving
        assertEquals(listOf(0, 1), seen)
        job.cancel()
    }

    /** With the undo window turned off (holdMs = 0) the row is QUEUED from the start; still silent. */
    @Test fun aSendQueuedWithNoUndoWindowIsSilentTooThenCounts() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = 0)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        advanceTimeBy(grace - 1)
        assertEquals("no window to hide behind, but still a send in progress", listOf(0), seen)

        advanceTimeBy(2)
        assertEquals(listOf(0, 1), seen)
        job.cancel()
    }

    /**
     * The coupling declared at MailRepository.retryOutbox: Retry rewrites the row's deadline, which
     */
    @Test fun retryingARowTakesItOffTheBadgeForTheGraceThenBringsItBack() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = 0)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        advanceTimeBy(60_000)
        assertEquals(listOf(1), seen)

        // The user taps Retry: state back to QUEUED, deadline reset to now (MailRepository).
        val retriedAt = testScheduler.currentTime
        rows.value = listOf(OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = retriedAt))
        runCurrent()
        assertEquals("a send just relaunched is a send in progress", listOf(1, 0), seen)

        advanceTimeBy(grace + 1) // it still has not gone out
        assertEquals("and the badge comes back on its own", listOf(1, 0, 1), seen)
        job.cancel()
    }

    /** A send in flight is on the same threshold as the row it came from — no reset, no exemption. */
    @Test fun aSendInFlightIsCountedOnTheSameThreshold() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        advanceTimeBy(window + 100) // the worker picks it up
        rows.value = listOf(OutboxBadgeItem(OutboxState.SENDING, notBeforeMillis = window))
        runCurrent()
        assertEquals(listOf(0), seen)

        advanceTimeBy(grace - 101)
        assertEquals(listOf(0), seen)
        advanceTimeBy(2) // a send in flight this long is stuck, not going well
        assertEquals(listOf(0, 1), seen)
        job.cancel()
    }

    /**
     * #70: a row open in the composer has no verdict that can change with time, so the flow stops
     */
    @Test fun aRowInTheComposerStopsTheClockAndReleasingItStartsItAgain() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.EDITING, notBeforeMillis = 0)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        advanceTimeBy(grace * 2)
        assertEquals("no wake-up while the composer holds it", listOf(0), seen)

        rows.value = listOf(OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = 0))
        runCurrent()
        assertEquals("given back, and long past its deadline: counted at once", listOf(0, 1), seen)
        job.cancel()
    }

    /** A row that already counts must not swallow the wake-up of one that is still in its grace. */
    @Test fun aFailedRowDoesNotSuppressTheWakeUpOfARowStillInItsGrace() = runTest {
        val rows = MutableStateFlow(
            listOf(
                OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = 0),
                OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window),
            ),
        )
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        runCurrent()
        assertEquals("the failure alone at first", listOf(1), seen)

        advanceTimeBy(window + grace + 1)
        assertEquals("the second row still gets its own wake-up", listOf(1, 2), seen)
        job.cancel()
    }

    @Test fun eachNewSendIsSilentOnItsOwnDeadline() = runTest {
        val first = OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = window)
        val rows = MutableStateFlow(listOf(first))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        advanceTimeBy(window + grace + 1)
        assertEquals(listOf(0, 1), seen)

        // A second send queued now stays silent for its own window and grace, then joins the count.
        val queuedAt = testScheduler.currentTime
        rows.value = listOf(first, OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = queuedAt + window))
        runCurrent()
        assertEquals("the new row must not flash on the badge", listOf(0, 1), seen)

        advanceTimeBy(window + grace + 1)
        assertEquals(listOf(0, 1, 2), seen)
        job.cancel()
    }
}
