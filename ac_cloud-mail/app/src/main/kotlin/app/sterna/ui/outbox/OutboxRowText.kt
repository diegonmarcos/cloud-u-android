package app.sterna.ui.outbox

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxState

/** What an Outbox row SAYS about itself, and in which colour — pure functions a JVM test can run.
 * The three answers are separate on purpose: a single `failed = state == FAILED` used to decide
 *  the wording, the substituted reason AND the tint at once, under an `else ->` that swept every
 *  other state into "waiting", so a message saved as a draft (#95) had no honest branch. */

/** The line the row prints for [state]. Exhaustive, with NO `else`: a new state must be given words
 *  of its own rather than inheriting another's sentence. `outbox_state_kept_as_draft` is a WHOLE
 *  SENTENCE — it used to be substituted into "Failed: %1$s", announcing a failure over a save that
 *  worked. */
@StringRes
fun outboxStateLabel(state: OutboxState): Int = when (state) {
    OutboxState.SENDING -> R.string.outbox_state_sending
    OutboxState.FAILED -> R.string.outbox_state_failed
    OutboxState.KEPT_AS_DRAFT -> R.string.outbox_state_kept_as_draft
    OutboxState.INTERRUPTED -> R.string.outbox_state_interrupted
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.EDITING -> R.string.outbox_state_waiting
}

/** Whether [outboxStateLabel]'s string takes the row's `lastError` as its one argument. Asked
 *  separately from [outboxStateIsError] even though both answer "FAILED" today: one is about the
 *  not about it. */
fun outboxStateShowsReason(state: OutboxState): Boolean = when (state) {
    OutboxState.FAILED -> true
    OutboxState.SENDING, OutboxState.KEPT_AS_DRAFT, OutboxState.INTERRUPTED,
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.EDITING,
    -> false
}

/**
 * Whether a WAITING row prints its `lastError` as an EXTRA line — not the same question as
 */
fun outboxShowsWaitingReason(state: OutboxState, lastError: String?): Boolean = when (state) {
    OutboxState.QUEUED, OutboxState.HELD -> !lastError.isNullOrBlank()
    OutboxState.SENDING, OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT,
    OutboxState.INTERRUPTED, OutboxState.EDITING,
    -> false
}

/** The string a WAITING row's complement is FRAMED with — "Last error: %1$s". The frame exists
 *  because `lastError` is the LAST reason recorded and nothing clears it, so an offline row can
 * carry the reason of an online failure for days: framed as history it is true either way. It
 *  does not say "Failed": the row is queued and waiting. */
@StringRes
fun outboxWaitingReasonLabel(): Int = R.string.outbox_waiting_reason

/**
 * The reason a FAILED row substitutes into "Failed: %1$s" — words of ours to translate, or text to
 * print as it is.
 */
sealed class OutboxFailureReason {
    /** A sentence of the app's own, resolved in the user's language at display time. */
    data class Words(@StringRes val id: Int) : OutboxFailureReason()

    /** Text stored on the row and printed verbatim — the server's own words (#183). */
    data class Text(val text: String) : OutboxFailureReason()
}

/**
 * What a row's `lastError` means on screen. A row parked because its EDIT WAS INTERRUPTED carries a
 */
fun outboxFailureReason(lastError: String?): OutboxFailureReason = when {
    lastError == null -> OutboxFailureReason.Words(R.string.outbox_state_failed_unknown)
    lastError == OutboxLogic.EDIT_INTERRUPTED ->
        OutboxFailureReason.Words(R.string.outbox_error_edit_interrupted)
    lastError == OutboxLogic.NOT_ARMED ->
        OutboxFailureReason.Words(R.string.outbox_error_not_armed)
    lastError.startsWith(OutboxLogic.NOT_ARMED_LEGACY_PREFIX) ->
        OutboxFailureReason.Words(R.string.outbox_error_not_armed)
    else -> OutboxFailureReason.Text(lastError)
}

/**
 * Whether the line is painted in the error colour. KEPT_AS_DRAFT is not an error (#95): the user
 */
fun outboxStateIsError(state: OutboxState): Boolean = when (state) {
    OutboxState.FAILED -> true
    OutboxState.SENDING, OutboxState.KEPT_AS_DRAFT, OutboxState.INTERRUPTED,
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.EDITING,
    -> false
}

/**
 * The body of the delete confirmation for a row in [state] — read at the one moment the only copy of
 */
@StringRes
fun outboxDeleteBody(state: OutboxState): Int = when (state) {
    OutboxState.INTERRUPTED -> R.string.outbox_delete_body_interrupted
    OutboxState.SENDING, OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT,
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.EDITING,
    -> R.string.outbox_delete_body
}
