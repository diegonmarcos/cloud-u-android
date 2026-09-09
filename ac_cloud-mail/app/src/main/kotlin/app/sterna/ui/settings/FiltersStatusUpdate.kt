package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterScriptStatus
import app.sterna.core.data.filter.ForeignScript
import app.sterna.core.data.filter.refreshedFilterWarning
import app.sterna.core.data.filter.rulesAreNotRunning

/**
 * Fold a status read into the filters screen's state.
 */
internal fun filtersStateWithStatus(
    state: FiltersUiState,
    status: FilterScriptStatus?,
    foreignFromRulesRead: Boolean = false,
    unreadableFromRulesRead: Boolean = false,
    foreignScriptFromRulesRead: ForeignScript? = null,
): FiltersUiState {
    val next = if (status == null) {
        state
    } else {
        state.copy(
            // responderEnabled = null: this screen never loads the responder, so only the
            // statement of fact is read out of the verdict, not the prediction.
            rulesNotRunning = rulesAreNotRunning(
                refreshedFilterWarning(previous = null, status = status, responderEnabled = null),
            ),
            foreignActive = status.foreignActive,
            vacationScriptActive = status.vacationScriptActive,
            // A successful read supersedes whatever the previous rules read concluded; only this
            // call's argument may raise it again, one line below.
            scriptUnreadable = false,
        )
    }
    return next.copy(
        foreignActive = next.foreignActive || foreignFromRulesRead,
        scriptUnreadable = next.scriptUnreadable || unreadableFromRulesRead,
        // The script's text outlives the read that produced it, EXCEPT when a status read that
        // succeeded says nothing foreign is active any more — which is exactly the post-save
        // refresh, where ours has just become the active script. Keeping the old body past that
        // would leave the screen warning about a script it no longer switches off.
        foreignScript = when {
            foreignScriptFromRulesRead != null -> foreignScriptFromRulesRead
            status != null && !status.foreignActive -> null
            else -> state.foreignScript
        },
    )
}
