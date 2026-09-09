package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Leaving the filter rules or the vacation responder with unwritten edits (#34). Both screens ask
 */
class UnsavedExitTest {

    // --- Save chosen on the way out -------------------------------------------------------------

    @Test fun nothingLeftToSaveLeavesAtOnce() {
        assertEquals(
            PendingExit.LEAVE,
            pendingExitStep(saving = false, dirty = false, failed = false),
        )
    }

    @Test fun aWriteInFlightKeepsTheScreen() {
        assertEquals(
            PendingExit.WAIT,
            pendingExitStep(saving = true, dirty = true, failed = false),
        )
    }

    @Test fun aRequestNotYetSeenByTheViewModelKeepsWaiting() {
        // Between the tap and the view model flipping `saving`, the screen still sees the old
        // state: it must not read that as "saved" and close on edits nobody has written.
        assertEquals(
            PendingExit.WAIT,
            pendingExitStep(saving = false, dirty = true, failed = false),
        )
    }

    @Test fun aRefusedSaveStaysOnTheScreen() {
        // The server said no (or the dates are back to front): the edits are still unwritten, and
        // leaving here would lose them exactly as the missing guard did — with the error out of
        // sight, which would read as a success.
        assertEquals(
            PendingExit.STAY,
            pendingExitStep(saving = false, dirty = true, failed = true),
        )
    }

    @Test fun aRefusalIsOnlyReadOnceTheWriteIsOver() {
        // A stale error from a previous attempt must not cut a write that is running.
        assertEquals(
            PendingExit.WAIT,
            pendingExitStep(saving = true, dirty = true, failed = true),
        )
    }

    // --- What "unsaved" is measured on ----------------------------------------------------------

    @Test fun aVacationEditUndoneByHandIsBackToWhatTheServerHolds() {
        val onServer = VacationUiState(
            enabled = true, subject = "Away", message = "Back Monday", fromDate = 1_000, toDate = 2_000,
        ).edits()
        val edited = VacationUiState(
            enabled = true, subject = "Away!", message = "Back Monday", fromDate = 1_000, toDate = 2_000,
        ).edits()
        assertNotEquals(onServer, edited)

        val undone = VacationUiState(
            enabled = true, subject = "Away", message = "Back Monday", fromDate = 1_000, toDate = 2_000,
        ).edits()
        assertEquals(onServer, undone)
    }

    @Test fun everyVacationFieldTheFormOffersCounts() {
        val base = VacationUiState()
        assertNotEquals(base.edits(), base.copy(enabled = true).edits())
        assertNotEquals(base.edits(), base.copy(subject = "Away").edits())
        assertNotEquals(base.edits(), base.copy(message = "Back Monday").edits())
        assertNotEquals(base.edits(), base.copy(fromDate = 1_000).edits())
        assertNotEquals(base.edits(), base.copy(toDate = 2_000).edits())
    }

    @Test fun aRuleToggledBackOffIsBackToWhatTheServerHolds() {
        val onServer = listOf(FilterRule(name = "Newsletters", value = "news@example.org"))
        val edited = onServer.map { it.copy(enabled = !it.enabled) }
        assertNotEquals(onServer, edited)
        assertEquals(onServer, edited.map { it.copy(enabled = !it.enabled) })
    }

    @Test fun aRuleAddedThenRemovedIsBackToWhatTheServerHolds() {
        val onServer = listOf(FilterRule(name = "Newsletters", value = "news@example.org"))
        val added = onServer + FilterRule()
        assertNotEquals(onServer, added)
        assertEquals(onServer, added.dropLast(1))
    }
}
