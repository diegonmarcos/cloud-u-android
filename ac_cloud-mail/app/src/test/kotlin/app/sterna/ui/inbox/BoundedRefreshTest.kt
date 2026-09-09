package app.sterna.ui.inbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [refreshWithin]: the fourth end of a refresh — the one that never comes back (#130).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BoundedRefreshTest {

    private val budget = 180_000L

    @Test
    fun `work that never returns is abandoned exactly at the budget`() = runTest {
        val outcome = refreshWithin(budget) { awaitCancellation() }

        assertEquals(RefreshOutcome.AbandonedAtBudget, outcome)
        assertEquals(budget, currentTime)
    }

    /**
     * The bound the app actually ships with — the default, spelled nowhere else (the call site in
     */
    @Test
    fun `the shipped budget is three minutes, and it is what a bare call spends`() = runTest {
        assertEquals(180_000L, REFRESH_BUDGET_MS)

        val outcome = refreshWithin { awaitCancellation() }

        assertEquals(RefreshOutcome.AbandonedAtBudget, outcome)
        assertEquals(180_000L, currentTime)
    }

    @Test
    fun `the budget is spent even behind work that ignores cancellation`() = runTest {
        // The blocking `execute()` the real refresh sits in: it does not notice being cancelled.
        val outcome = refreshWithin(budget) {
            withContext(NonCancellable) { delay(budget * 10) }
        }

        assertEquals(RefreshOutcome.AbandonedAtBudget, outcome)
        assertEquals(
            "the caller must be handed back at the budget, not when the deaf work finishes: a " +
                "refresh whose socket has nothing to time out on (name resolution) is #130 itself",
            budget,
            currentTime,
        )
    }

    @Test
    fun `work that throws fails with its own throwable, well before the budget`() = runTest {
        val boom = IOException("unexpected end of stream")

        val outcome = refreshWithin(budget) {
            delay(1_000L)
            throw boom
        }

        assertEquals(RefreshOutcome.Failed(boom), outcome)
        assertSame(
            "the banner shows this throwable's own text and the connectivity probe judges the " +
                "throwable itself, so it must be the very instance work threw",
            boom,
            (outcome as RefreshOutcome.Failed).cause,
        )
        assertEquals(1_000L, currentTime)
    }

    /**
     * The photo finish: work that lands at the very instant the deadline fires must be
     */
    @Test
    fun `work that lands exactly at the budget is delivered, not abandoned`() = runTest {
        val outcome = refreshWithin(budget) { delay(budget) }

        assertEquals(
            "the work completed; a verdict that has landed must win over the deadline that fired " +
                "on the same instant, or the screen calls a refresh that worked a failure",
            RefreshOutcome.Delivered,
            outcome,
        )
        assertEquals(budget, currentTime)
    }

    @Test
    fun `work that returns is delivered`() = runTest {
        val outcome = refreshWithin(budget) { delay(2_500L) }

        assertEquals(RefreshOutcome.Delivered, outcome)
        assertEquals(2_500L, currentTime)
    }

    @Test
    fun `a caller cancelled during the wait gets its cancellation, and no outcome`() = runTest {
        val outcome = CompletableDeferred<RefreshOutcome>()
        val caller = async { outcome.complete(refreshWithin(budget) { awaitCancellation() }) }

        delay(1_000L)
        caller.cancel()
        val thrown = runCatching { caller.await() }.exceptionOrNull()

        assertTrue(
            "the caller's own cancellation must come out of refreshWithin untouched — refresh() " +
                "rethrows it and deliberately leaves the status alone. Got: $thrown",
            thrown is CancellationException,
        )
        assertTrue(
            "a superseding refresh cancelled us: refresh() rethrows and deliberately does not " +
                "touch the status, so this envelope must produce no verdict at all",
            !outcome.isCompleted,
        )
    }

    @Test
    fun `cancellation thrown by the work itself leaves unchanged`() = runTest {
        val thrown = CancellationException("superseded")

        val out = runCatching { refreshWithin(budget) { throw thrown } }

        assertTrue(
            "a CancellationException is not something the refresh said: it must leave for the " +
                "caller, not be folded into Failed. Got: ${out.exceptionOrNull() ?: out.getOrNull()}",
            out.exceptionOrNull() is CancellationException,
        )
    }
}
