package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [bulkOutcome] — what a finished bulk action says. It used to say "Couldn't complete the action"
 * as soon as ONE id was rejected, so 1 500 messages archived out of 2 000 read as a total failure.
 */
class BulkOutcomeTest {

    @Test fun nothingFailedSaysNothing() {
        assertEquals(BulkOutcome.NONE, bulkOutcome(attempted = 2_000, failed = 0))
        assertEquals(BulkOutcome.NONE, bulkOutcome(attempted = 1, failed = 0))
    }

    @Test fun everythingFailedIsATotalFailure() {
        assertEquals(BulkOutcome.TOTAL, bulkOutcome(attempted = 2_000, failed = 2_000))
        assertEquals(BulkOutcome.TOTAL, bulkOutcome(attempted = 1, failed = 1))
    }

    @Test fun someFailedIsPartial() {
        assertEquals(BulkOutcome.PARTIAL, bulkOutcome(attempted = 2_000, failed = 1))
        assertEquals(BulkOutcome.PARTIAL, bulkOutcome(attempted = 2_000, failed = 1_999))
        assertEquals(BulkOutcome.PARTIAL, bulkOutcome(attempted = 2, failed = 1))
    }

    @Test fun nothingWasEvenAttempted() {
        // Nothing was selected at all — an empty leg of an action (a delete with nothing to move,
        // say): there is nothing to report, and no division of zero into a "total". A selection
        // whose keys resolve to no row is NOT this case any more: those keys are attempts that
        // failed, so it arrives here as attempted = N, failed = N, i.e. TOTAL.
        assertEquals(BulkOutcome.NONE, bulkOutcome(attempted = 0, failed = 0))
        assertEquals(BulkOutcome.NONE, bulkOutcome(attempted = 0, failed = 3))
    }

    @Test fun moreFailuresThanRowsIsStillATotalFailure() {
        // Defensive: the two counts come from different sets, so failed > attempted must not fall
        // through to "partial" and hide a wholesale failure.
        assertEquals(BulkOutcome.TOTAL, bulkOutcome(attempted = 3, failed = 5))
    }
}
