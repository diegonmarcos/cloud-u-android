package app.sterna.ui.inbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** How a refresh ended, as far as the list has to render it — four ends, including "never came
 *  back" (#130). A closed type, so the mapping at the call site cannot forget an arm. */
internal sealed interface RefreshOutcome {
    /** The work returned: the list has fresh mail. */
    data object Delivered : RefreshOutcome

    /** The work threw, and this is what it threw — the text the banner shows, and the throwable
     *  the connectivity probe judges. Cancellation is NOT here: see [refreshWithin]. */
    data class Failed(val cause: Throwable) : RefreshOutcome

    /** The budget ran out with the work still in flight. An ERROR, never an offline claim. */
    data object AbandonedAtBudget : RefreshOutcome
}

/** How long a pull-to-refresh may keep the spinner turning: one JMAP call is already 20 s connect
 *  plus 30 s read, so this bound fixes "never", not "slow", and sits below the worst legitimate case
 * (the unified inbox walking accounts in series — accepted debt). Not a `callTimeout`: the push
 *  client derives from the shared one and would reconnect for ever. */
internal const val REFRESH_BUDGET_MS = 180_000L

/**
 * Run [work] under [budgetMs] and answer with which end it reached.
 */
internal suspend fun refreshWithin(
    /** Defaulted on purpose, production passing nothing: the shipped bound then lives only in
     *  [REFRESH_BUDGET_MS], which a test pins. */
    budgetMs: Long = REFRESH_BUDGET_MS,
    work: suspend () -> Unit,
): RefreshOutcome {
    val done = CompletableDeferred<RefreshOutcome>()
    // The verdict readable without awaiting: at the deadline it may be in while the resumption
    // carrying it has not been dispatched. Atomic — the work lands on another thread.
    val landed = AtomicReference<RefreshOutcome?>(null)
    // A child of the caller — leaving the screen still cancels the work — that is never joined.
    val running = Job(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + running)
    try {
        // UNDISPATCHED so the work has begun before the clock below starts.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                work()
                landed.set(RefreshOutcome.Delivered)
                done.complete(RefreshOutcome.Delivered)
            } catch (c: CancellationException) {
                // Not a verdict: `landed` stays null, so a deadline racing it reads AbandonedAtBudget.
                done.completeExceptionally(c)
                throw c
            } catch (t: Throwable) {
                landed.set(RefreshOutcome.Failed(t))
                done.complete(RefreshOutcome.Failed(t))
            }
        }
        return withTimeoutOrNull(budgetMs) { done.await() }
            ?: landed.get()
            ?: RefreshOutcome.AbandonedAtBudget
    } finally {
        // A request to stop, not a wait for it: a blocking read dies on its own socket's terms.
        running.cancel()
    }
}
