package app.sterna.ui.inbox

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** How long a refresh may run before anything is drawn about it (#178), so a folder tap or cold
 * start does not blink the tern. Display only: "a refresh is running" is [MailUi.refreshing] and
 *  is not delayed, so "this folder is empty" still cannot lie during a fetch (#63). */
internal const val REFRESH_INDICATOR_GRACE_MS = 250L

/** Once shown, the smallest time the indicator stays on screen: without it the grace only moves the
 *  flash, a refresh landing at 260 ms drawing the tern for ten milliseconds. Counted from the
 *  instant it became visible, not the start of the refresh, so it is bounded. */
internal const val REFRESH_INDICATOR_MIN_SHOW_MS = 500L

/** One stretch of continuous refreshing as the eye sees it, not as the ViewModel counts jobs: a
 *  reconcile superseding another is the same stretch. Nothing here reads a clock. */
internal data class RefreshRun(
    val startedAt: Long,
    /** This stretch answers an explicit gesture (the pull, a Retry button), which gets no grace: a
     *  pull that draws nothing for a quarter of a second reads as a pull that did nothing. */
    val instant: Boolean = false,
    /** When the refreshing stopped; null while it is still running. */
    val endedAt: Long? = null,
)

/** The instant [run] would first be drawn, ignoring whether it is still running. */
private fun showAt(run: RefreshRun): Long =
    run.startedAt + if (run.instant) 0L else REFRESH_INDICATOR_GRACE_MS

/** Start a stretch at [now], or extend the one already running. A refresh starting on top of a
 *  stretch in flight extends it: restarting grace and floor on each would, given how readily the
 *  is for. One ended and not drawn is replaced; `instant` is promoted, never demoted. */
internal fun startRefreshRun(previous: RefreshRun?, now: Long, instant: Boolean): RefreshRun = when {
    previous == null -> RefreshRun(startedAt = now, instant = instant)
    previous.endedAt == null -> previous.copy(instant = previous.instant || instant)
    refreshIndicatorShowing(previous, now) ->
        previous.copy(endedAt = null, instant = previous.instant || instant)
    else -> RefreshRun(startedAt = now, instant = instant)
}

/** Whether the indicator is drawn at [now]. A stretch that ended before it was due to be shown is
 *  never shown at all (#178) — a blind floor would light the tern 250→750 ms for a refresh already
 *  over. Otherwise from [showAt] to `max(endedAt, showAt + REFRESH_INDICATOR_MIN_SHOW_MS)`. */
internal fun refreshIndicatorShowing(run: RefreshRun?, now: Long): Boolean {
    if (run == null) return false
    val from = showAt(run)
    val ended = run.endedAt ?: return now >= from
    if (ended < from) return false
    return now >= from && now < maxOf(ended, from + REFRESH_INDICATOR_MIN_SHOW_MS)
}

/** The next instant at which [refreshIndicatorShowing] answers differently, or null when it never
 * will again — the single wake-up [refreshIndicatorVisibility] schedules per emission. Null, not
 *  `showAt`, for a stretch that ended inside its grace: it has no visible future. */
internal fun nextRefreshIndicatorChange(run: RefreshRun?, now: Long): Long? {
    if (run == null) return null
    val from = showAt(run)
    val ended = run.endedAt
    if (ended != null && ended < from) return null
    if (now < from) return from
    // Visible from here on: only an END can take it off, and a running stretch has none.
    if (ended == null) return null
    val until = maxOf(ended, from + REFRESH_INDICATOR_MIN_SHOW_MS)
    return if (now < until) until else null
}

/** The indicator's visibility over time: each stretch emitted upstream schedules one wake-up and
 * never polls. [now] must be `SystemClock.elapsedRealtime` (a source lint pins it): a wall clock
 *  would be nudged by NTP mid-refresh while the monotonic `delay` would not. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun refreshIndicatorVisibility(
    runs: Flow<RefreshRun?>,
    now: () -> Long,
): Flow<Boolean> = runs
    .flatMapLatest { run ->
        flow {
            while (true) {
                val instant = now()
                emit(refreshIndicatorShowing(run, instant))
                val next = nextRefreshIndicatorChange(run, instant) ?: break
                delay(next - instant)
            }
        }
    }
    .distinctUntilChanged()
