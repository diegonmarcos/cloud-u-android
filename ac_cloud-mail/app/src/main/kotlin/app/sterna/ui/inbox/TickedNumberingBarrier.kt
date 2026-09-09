package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** How long a gesture waits for the stamps of its own tick before going ahead without them. The
 *  read behind them is database only, so this is not a network budget; it is sized for "Select all"
 *  over a folder of thousands, and [TickedNumberingBarrierTest] executes the default. */
internal const val TICKED_NUMBERING_BUDGET_MS = 1_500L

/** Whether [published] already answers for every key of [awaited]. Keys, not values: a published
 *  `null` is an answer (read, carrying no numbering), and waiting on values would wait for a number
 *  that is never coming. */
internal fun numberingStampsCover(awaited: Set<EmailKey>, published: Map<EmailKey, Long?>): Boolean =
    published.keys.containsAll(awaited)

/**
 * The stamps of the tick for [awaited], waiting — bounded — for the read started at the tick to be
 */
internal suspend fun tickedNumberingWhenCovered(
    awaited: Set<EmailKey>,
    published: StateFlow<Map<EmailKey, Long?>>,
    budgetMs: Long = TICKED_NUMBERING_BUDGET_MS,
): Map<EmailKey, Long?> {
    // Frozen on entry: the fallback must be what the gesture would have sampled at the tap.
    val atTheGesture = published.value
    return withTimeoutOrNull(budgetMs) { published.first { numberingStampsCover(awaited, it) } } ?: atTheGesture
}
