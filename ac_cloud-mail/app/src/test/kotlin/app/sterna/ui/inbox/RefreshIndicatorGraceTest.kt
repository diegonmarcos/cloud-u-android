package app.sterna.ui.inbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The refresh indicator's grace and floor (#178), executed — never read.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RefreshIndicatorGraceTest {

    private val grace = REFRESH_INDICATOR_GRACE_MS
    private val floor = REFRESH_INDICATOR_MIN_SHOW_MS

    // -- the decision itself ----------------------------------------------------------------------

    /** #178's nominal case: the folder tap whose fetch is back before the eye could follow it. */
    @Test fun `a refresh that lands inside the grace is never drawn, at any instant`() {
        val run = RefreshRun(startedAt = 0, endedAt = 80)

        for (now in listOf(0L, 79L, 80L, 81L, grace - 1, grace, grace + 1, 400L, 749L, 750L, 5_000L)) {
            assertFalse(
                "a refresh over at 80 ms must draw nothing at $now ms — applying the floor to it " +
                    "would light the indicator from $grace to ${grace + floor} for a refresh that " +
                    "had already been finished for ${grace - 80} ms, which is #178 moved, not fixed",
                refreshIndicatorShowing(run, now),
            )
        }
    }

    /**
     * The two numbers themselves, EXECUTED on literal instants rather than read back from the
     */
    @Test fun `a refresh of 240 ms is never drawn and one of 260 ms is`() {
        val short = RefreshRun(startedAt = 0, endedAt = 240)
        for (now in 0..1_000 step 20) {
            assertFalse(
                "a refresh over in 240 ms is inside the quarter-second grace and must draw nothing " +
                    "at $now ms",
                refreshIndicatorShowing(short, now.toLong()),
            )
        }

        val long = RefreshRun(startedAt = 0, endedAt = 260)
        assertFalse("nothing at 240 ms: the grace is a quarter of a second", refreshIndicatorShowing(long, 240))
        assertTrue(
            "a refresh still out at 260 ms is past the grace and must be drawn — a grace stretched " +
                "to seconds would silence the tern for good, with every rule spelled in terms of " +
                "the constant still green",
            refreshIndicatorShowing(long, 260),
        )
    }

    @Test fun `an indicator that appeared is held half a second, no more and no less`() {
        val run = RefreshRun(startedAt = 0, endedAt = 260) // drawn at 250 ms

        assertTrue("still up at 740 ms: half a second has not passed since it appeared", refreshIndicatorShowing(run, 740))
        assertFalse("and down by 760 ms: the floor is half a second, not more", refreshIndicatorShowing(run, 760))
    }

    @Test fun `a long refresh is drawn at the grace and not a millisecond before`() {
        val run = RefreshRun(startedAt = 0)

        assertFalse("nothing at all at the start", refreshIndicatorShowing(run, 0))
        assertFalse("still nothing one millisecond short of the grace", refreshIndicatorShowing(run, grace - 1))
        assertTrue("drawn once the grace has run out", refreshIndicatorShowing(run, grace))
        assertTrue("and stays while it runs", refreshIndicatorShowing(run, 8_000))
    }

    @Test fun `a refresh drawn for an instant is held for the floor, not until it ended`() {
        val run = RefreshRun(startedAt = 0, endedAt = 300)

        assertFalse("not before the grace", refreshIndicatorShowing(run, grace - 1))
        assertTrue("drawn at the grace", refreshIndicatorShowing(run, grace))
        assertTrue(
            "the refresh ended at 300 ms but the indicator had only been up for 50 ms: taking it " +
                "down there is the blink the floor exists to stop",
            refreshIndicatorShowing(run, 301),
        )
        assertTrue("still up one millisecond short of the floor", refreshIndicatorShowing(run, grace + floor - 1))
        assertFalse("gone once the floor is served", refreshIndicatorShowing(run, grace + floor))
    }

    @Test fun `a refresh the reader asked for in so many words is drawn at once`() {
        val run = RefreshRun(startedAt = 1_000, instant = true)

        assertTrue("the pull's answer is the indicator: no grace", refreshIndicatorShowing(run, 1_000))
        assertFalse("and nothing before it started", refreshIndicatorShowing(run, 999))
        assertTrue("held for the floor like any other", refreshIndicatorShowing(run.copy(endedAt = 1_010), 1_400))
        assertFalse("and no longer", refreshIndicatorShowing(run.copy(endedAt = 1_010), 1_000 + floor))
    }

    @Test fun `nothing at all when no refresh has ever run`() {
        assertFalse(refreshIndicatorShowing(null, 10_000))
        assertNull(nextRefreshIndicatorChange(null, 10_000))
    }

    // -- stretches --------------------------------------------------------------------------------

    @Test fun `a refresh starting on top of one in flight restarts neither the grace nor the floor`() {
        val first = startRefreshRun(null, now = 0, instant = false)
        val second = startRefreshRun(first, now = 200, instant = false)

        assertEquals("the stretch is the one already running, not a new one", 0L, second.startedAt)
        assertTrue(
            "and it is therefore drawn at the original grace — a new stretch per reconcile would " +
                "push the indicator away for ever, or blink it once per reconcile",
            refreshIndicatorShowing(second, grace),
        )
    }

    /**
     * The case the first version of [startRefreshRun] got wrong, and the one the floor is FOR.
     */
    @Test fun `a refresh starting while the indicator is still drawn does not blink it off`() {
        val shown = RefreshRun(startedAt = 0, endedAt = 300)
        assertTrue("the premise: it is on screen at 400, serving its floor", refreshIndicatorShowing(shown, 400))

        val second = startRefreshRun(shown, now = 400, instant = false)

        assertEquals("the stretch on screen is reopened, not replaced", 0L, second.startedAt)
        assertNull("and it is running again", second.endedAt)
        assertTrue("so nothing goes dark at the instant of the second refresh", refreshIndicatorShowing(second, 400))
        assertTrue("nor a beat later, where a new stretch would still owe its grace", refreshIndicatorShowing(second, 650))
    }

    @Test fun `a refresh starting after the floor has been served opens a fresh stretch`() {
        val shown = RefreshRun(startedAt = 0, endedAt = 300)
        assertFalse("the premise: the floor is over and nothing is drawn", refreshIndicatorShowing(shown, 800))

        val second = startRefreshRun(shown, now = 800, instant = false)

        assertEquals("nothing on screen to keep continuous, so this is a stretch of its own", 800L, second.startedAt)
        assertFalse("with its own grace to serve", refreshIndicatorShowing(second, 800 + grace - 1))
        assertTrue(refreshIndicatorShowing(second, 800 + grace))
    }

    @Test fun `a refresh starting after the previous one ended opens a fresh stretch`() {
        val first = RefreshRun(startedAt = 0, endedAt = 80)
        val second = startRefreshRun(first, now = 1_000, instant = false)

        assertEquals(1_000L, second.startedAt)
        assertNull("the new stretch is running", second.endedAt)
        assertFalse("with its own grace to serve", refreshIndicatorShowing(second, 1_000 + grace - 1))
        assertTrue(refreshIndicatorShowing(second, 1_000 + grace))
    }

    @Test fun `a pull over a background reconcile promotes the stretch and drops its grace`() {
        val background = startRefreshRun(null, now = 0, instant = false)
        val pulled = startRefreshRun(background, now = 100, instant = true)

        assertEquals("still the same stretch", 0L, pulled.startedAt)
        assertTrue("but the reader is owed an answer now", pulled.instant)
        assertTrue(refreshIndicatorShowing(pulled, 100))
    }

    @Test fun `an ordinary refresh over a pull does not demote it back into the grace`() {
        val pulled = startRefreshRun(null, now = 0, instant = true)
        val background = startRefreshRun(pulled, now = 10, instant = false)

        assertTrue("the pull's answer must not go dark because something else refreshed", background.instant)
        assertTrue(refreshIndicatorShowing(background, 10))
    }

    // -- wake-ups ---------------------------------------------------------------------------------

    @Test fun `a stretch that ended inside its grace schedules no wake-up at all`() {
        val run = RefreshRun(startedAt = 0, endedAt = 80)

        assertNull("nothing to wake up for at the start", nextRefreshIndicatorChange(run, 0))
        assertNull("nor once it ended", nextRefreshIndicatorChange(run, 80))
    }

    @Test fun `a running stretch wakes up once at the grace and then never`() {
        val run = RefreshRun(startedAt = 0)

        assertEquals(grace, nextRefreshIndicatorChange(run, 0))
        assertNull("drawn and staying drawn: nothing more to schedule", nextRefreshIndicatorChange(run, grace))
    }

    @Test fun `an ended stretch wakes up at the grace, then at the floor, then never`() {
        val run = RefreshRun(startedAt = 0, endedAt = 300)

        assertEquals(grace, nextRefreshIndicatorChange(run, 0))
        assertEquals(grace + floor, nextRefreshIndicatorChange(run, grace))
        assertNull(nextRefreshIndicatorChange(run, grace + floor))
    }

    @Test fun `a long refresh that ended wakes up at its end, not at the floor`() {
        val run = RefreshRun(startedAt = 0, endedAt = 8_000)

        assertEquals(8_000L, nextRefreshIndicatorChange(run, grace))
        assertNull(nextRefreshIndicatorChange(run, 8_000))
    }

    // -- the flow, on a virtual clock --------------------------------------------------------------

    @Test fun `the flow of a folder tap that fetched in 80 ms emits nothing but false`() = runTest {
        val runs = MutableStateFlow<RefreshRun?>(null)
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            refreshIndicatorVisibility(runs) { testScheduler.currentTime }.toList(seen)
        }
        runCurrent()

        runs.value = startRefreshRun(null, testScheduler.currentTime, instant = false)
        advanceTimeBy(80)
        runs.value = runs.value?.copy(endedAt = testScheduler.currentTime)
        advanceTimeBy(10_000)

        assertEquals("the tern must not blink for a refresh nobody had time to see", listOf(false), seen)
        job.cancel()
    }

    @Test fun `the flow draws at the grace and holds for the floor`() = runTest {
        val runs = MutableStateFlow<RefreshRun?>(null)
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            refreshIndicatorVisibility(runs) { testScheduler.currentTime }.toList(seen)
        }
        runCurrent()

        runs.value = startRefreshRun(null, testScheduler.currentTime, instant = false)
        advanceTimeBy(grace) // up to, but not including, the wake-up scheduled at the grace
        assertEquals("nothing drawn one tick short of the grace", listOf(false), seen)

        advanceTimeBy(1)
        assertEquals("drawn at the grace", listOf(false, true), seen)

        advanceTimeBy(300 - grace - 1)
        runs.value = runs.value?.copy(endedAt = testScheduler.currentTime) // the refresh lands at 300 ms
        advanceTimeBy(floor - (300 - grace))
        assertEquals("still up: the floor is not served yet", listOf(false, true), seen)

        advanceTimeBy(1)
        assertEquals("taken down once the floor is served", listOf(false, true, false), seen)
        job.cancel()
    }

    @Test fun `the flow answers a pull with no delay at all`() = runTest {
        val runs = MutableStateFlow<RefreshRun?>(null)
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            refreshIndicatorVisibility(runs) { testScheduler.currentTime }.toList(seen)
        }
        runCurrent()

        runs.value = startRefreshRun(null, testScheduler.currentTime, instant = true)
        runCurrent()

        assertEquals("the pull's indicator is the answer to the gesture", listOf(false, true), seen)
        assertEquals("and it is drawn at the instant of the gesture", 0L, testScheduler.currentTime)
        job.cancel()
    }

    /**
     * One wake-up per transition, never a poll: the clock is counted, not just read. A loop ticking
     */
    @Test fun `the flow reads the clock a handful of times, not on a tick`() = runTest {
        var reads = 0
        val runs = MutableStateFlow<RefreshRun?>(null)
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            refreshIndicatorVisibility(runs) { reads++; testScheduler.currentTime }.toList(seen)
        }
        runCurrent()

        runs.value = startRefreshRun(null, testScheduler.currentTime, instant = false)
        advanceTimeBy(301)
        runs.value = runs.value?.copy(endedAt = testScheduler.currentTime)
        advanceTimeBy(10_000)

        assertEquals(listOf(false, true, false), seen)
        assertTrue(
            "the visibility flow must schedule ONE wake-up per transition; $reads clock reads over " +
                "10 s of virtual time is a poll",
            reads <= 6,
        )
        job.cancel()
    }
}
