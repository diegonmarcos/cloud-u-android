package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant these pin down is the one whose absence hid message bodies for good: the height
 * poll that reveals the body must NEVER end without reporting a usable height.
 */
class BodyRevealTest {

    @Test fun `two agreeing readings settle the height`() {
        val step = BodyReveal.step(px = 900, last = 900, maxSeen = 900, triesLeft = 20, viewHeightPx = 800)
        assertEquals(HeightPoll.Report(900, settled = true), step)
    }

    @Test fun `a changing reading keeps polling while tries remain`() {
        val step = BodyReveal.step(px = 900, last = 400, maxSeen = 900, triesLeft = 20, viewHeightPx = 800)
        assertEquals(HeightPoll.Retry, step)
    }

    @Test fun `a zero reading never settles the height`() {
        // Two agreeing ZERO readings must not be taken as "laid out at height 0".
        val step = BodyReveal.step(px = 0, last = 0, maxSeen = 0, triesLeft = 20, viewHeightPx = 0)
        assertEquals(HeightPoll.Retry, step)
    }

    @Test fun `a range taller than the viewport is a measurement on its own`() {
        // The floor `contentRangePx()` returns before layout is the view's own height, so it can
        // never exceed the viewport: anything taller came from a laid-out document.
        assertTrue(BodyReveal.rangeMeasured(rangePx = 3000, viewportPx = 1080, contentHeightPx = 0))
    }

    @Test fun `a range equal to the viewport with no laid-out document measures nothing`() {
        // THE DEFECT: this reading is the floor, and reading it as "the body fits" is what put the
        // bar on the first message opened after process start.
        assertFalse(BodyReveal.rangeMeasured(rangePx = 1080, viewportPx = 1080, contentHeightPx = 0))
    }

    @Test fun `a range equal to the viewport with a laid-out document is a measurement`() {
        // The same number, but the document says it laid out — so the body genuinely fits.
        assertTrue(BodyReveal.rangeMeasured(rangePx = 1080, viewportPx = 1080, contentHeightPx = 900))
    }

    @Test fun `an oscillating body reports the tallest reading at the cap`() {
        // Never the last reading: pinning a short one would cut the body's tail off.
        val step = BodyReveal.step(px = 700, last = 1200, maxSeen = 1600, triesLeft = 0, viewHeightPx = 800)
        assertEquals(HeightPoll.Report(1600, settled = false), step)
    }

    @Test fun `the cap reports the view height when no reading was ever positive`() {
        // THE REGRESSION: every reading was zero (the view had no size for the whole poll window),
        // the poll used to report nothing at all, and the body stayed invisible for good.
        val step = BodyReveal.step(px = 0, last = 0, maxSeen = 0, triesLeft = 0, viewHeightPx = 1920)
        assertEquals(HeightPoll.Report(1920, settled = false), step)
    }

    @Test fun `the cap still reports when even the view has no size`() {
        val step = BodyReveal.step(px = 0, last = 0, maxSeen = 0, triesLeft = 0, viewHeightPx = 0)
        assertEquals(HeightPoll.Report(1, settled = false), step)
    }

    @Test fun `the cap never reports a height the reader would read as not-ready`() {
        // The reader's readiness test is `height > 0`, so every cap outcome must clear it —
        // whatever the view and the readings did.
        val readings = listOf(-5, 0, 1, 37, 4000)
        for (px in readings) {
            for (last in readings) {
                for (maxSeen in listOf(0, 120, 4000)) {
                    for (viewHeight in listOf(0, 1, 1920)) {
                        val step = BodyReveal.step(px, last, maxSeen, triesLeft = 0, viewHeightPx = viewHeight)
                        val reported = (step as? HeightPoll.Report)?.px
                            ?: error("cap must report, got $step for px=$px last=$last")
                        assertTrue("cap reported $reported for px=$px last=$last", reported > 0)
                    }
                }
            }
        }
    }

    @Test fun `the cap never reports less than the tallest reading seen`() {
        val step = BodyReveal.step(px = 0, last = 0, maxSeen = 2400, triesLeft = 0, viewHeightPx = 1920)
        assertEquals(HeightPoll.Report(2400, settled = false), step)
    }

    @Test fun `only two agreeing readings count as a measurement`() {
        // The reader reveals the bottom bar off a SETTLED height and only off a settled one: a
        // fallback height would let a long body claim it fits and flash the bar (Codeberg #63).
        assertTrue(
            (BodyReveal.step(900, 900, 900, triesLeft = 20, viewHeightPx = 800) as HeightPoll.Report).settled,
        )
        assertFalse(
            (BodyReveal.step(700, 1200, 1600, triesLeft = 0, viewHeightPx = 800) as HeightPoll.Report).settled,
        )
        assertFalse(
            (BodyReveal.step(0, 0, 0, triesLeft = 0, viewHeightPx = 1920) as HeightPoll.Report).settled,
        )
    }
}

/**
 * The Reply/Forward bar's resting rule: a pure function of the measured geometry, so the reader
 */
class BarVisibleTest {

    private val threshold = 12 // ~4dp on a 3x screen

    @Test fun `a body that fits the screen shows the bar`() {
        // Nothing to scroll: the reader is already at the end of the message.
        assertEquals(0, BodyReveal.maxScroll(contentRangePx = 1400, viewportPx = 1920))
        assertTrue(BodyReveal.barVisible(scrollY = 0, maxScrollPx = 0, thresholdPx = threshold))
    }

    @Test fun `a long body at the top hides the bar`() {
        val maxScroll = BodyReveal.maxScroll(contentRangePx = 9000, viewportPx = 1920)
        assertEquals(7080, maxScroll)
        assertFalse(BodyReveal.barVisible(scrollY = 0, maxScrollPx = maxScroll, thresholdPx = threshold))
    }

    @Test fun `a long body at the bottom shows the bar`() {
        val maxScroll = BodyReveal.maxScroll(contentRangePx = 9000, viewportPx = 1920)
        assertTrue(BodyReveal.barVisible(scrollY = maxScroll, maxScrollPx = maxScroll, thresholdPx = threshold))
    }

    @Test fun `the threshold is inclusive at both ends`() {
        // A body exactly `threshold` taller than the viewport counts as fitting…
        assertTrue(BodyReveal.barVisible(scrollY = 0, maxScrollPx = threshold, thresholdPx = threshold))
        // …and one pixel more does not.
        assertFalse(BodyReveal.barVisible(scrollY = 0, maxScrollPx = threshold + 1, thresholdPx = threshold))
        // Stopping exactly `threshold` short of the end still counts as the end…
        assertTrue(BodyReveal.barVisible(scrollY = 7080 - threshold, maxScrollPx = 7080, thresholdPx = threshold))
        // …one pixel further from it does not.
        assertFalse(BodyReveal.barVisible(scrollY = 7080 - threshold - 1, maxScrollPx = 7080, thresholdPx = threshold))
    }

    @Test fun `overscroll past the end still shows the bar`() {
        // Some devices report a scrollY beyond the range at the end of a fling.
        assertTrue(BodyReveal.barVisible(scrollY = 7200, maxScrollPx = 7080, thresholdPx = threshold))
    }

    @Test fun `a body shorter than the viewport never reports a negative range`() {
        assertEquals(0, BodyReveal.maxScroll(contentRangePx = 0, viewportPx = 1920))
    }
}

/**
 * The ORDERING of the bar's two writers — the part [BarVisibleTest] above cannot reach.
 */
class BarOrderingTest {

    private val threshold = 12 // ~4dp on a 3x screen

    /** The reader's fold, spelled out so a sequence reads like the one the device produces. */
    private fun replay(vararg reports: Pair<Int, Int>): List<BarState> {
        var state = BarState()
        return reports.map { (scrollY, maxScrollPx) ->
            state = BodyReveal.barAfterReport(state, scrollY, maxScrollPx, threshold)
            state
        }
    }

    @Test fun `nothing reported yet means no bar`() {
        // Not seeded from "this message has no body": that seeding WAS the 1.3.12 blink.
        assertFalse(BarState().shown)
        assertEquals(0, BarState().maxScrollPx)
    }

    @Test fun `a body that fits shows the bar on the very first report`() {
        // One report, one reveal: the bar lands in the same frame as the body, which is the whole
        // point of carrying the resting geometry on the readiness callback.
        assertTrue(replay(0 to 0).single().shown)
    }

    @Test fun `a late measurement does not take back a bar already shown`() {
        // THE DEFECT, in the order the device produces it on a cold open:
        //   1. the height poll settles while the newsletter's remote images are still decoding —
        val states = replay(0 to 0, 0 to 7080)
        assertTrue("the bar was shown on report 1", states[0].shown)
        assertTrue("the bar came up and then went away again (#63)", states[1].shown)
    }

    @Test fun `DELIBERATE - a bar left on a body that grew under it stays until the reader scrolls`() {
        // The no-retraction rule is asymmetric on purpose, and this is the case where it leaves the
        // bar somewhere it does not belong: the body was measured as fitting, so the bar came up at
        var state = BarState()
        state = BodyReveal.barAfterReport(state, scrollY = 0, maxScrollPx = 0, thresholdPx = threshold)
        assertTrue(state.shown)
        state = BodyReveal.barAfterReport(state, scrollY = 0, maxScrollPx = 7080, thresholdPx = threshold)
        assertTrue("the bar stays put on a body that grew under it", state.shown)
        // …and it really is stale: the reader is at the top of a body it can scroll for 7080px, so
        // the resting rule on its own would say no. The fold overrides it knowingly.
        assertEquals(0, state.scrollY)
        assertFalse(BodyReveal.barVisible(state.scrollY, state.maxScrollPx, threshold))
        // It self-corrects on the reader's very first scroll, however small.
        assertFalse(
            BodyReveal.barAfterReport(state, scrollY = 1, maxScrollPx = 7080, thresholdPx = threshold).shown,
        )
    }

    @Test fun `growth keeps arriving and the bar still never leaves`() {
        // A newsletter that relayouts for a while: several reports, each taller than the last, none
        // of them a scroll. `shown` must be monotone across all of them.
        val states = replay(0 to 0, 0 to 400, 0 to 2200, 0 to 6400, 0 to 7080, 0 to 7080)
        assertTrue(states.all { it.shown })
    }

    @Test fun `a long body still keeps the bar down until the reader reaches the end`() {
        // The other half of the promise: no bar shown early on a body that does not fit.
        val states = replay(0 to 7080, 0 to 7080)
        assertFalse(states[0].shown)
        assertFalse(states[1].shown)
    }

    @Test fun `the reader reaching the end shows the bar and scrolling away hides it again`() {
        // The end-of-message affordance itself must NOT be frozen by the no-retraction rule: it is
        // the reader's own gesture, not a measurement landing late.
        val states = replay(0 to 7080, 7080 to 7080, 3000 to 7080)
        assertFalse("at the top", states[0].shown)
        assertTrue("at the end", states[1].shown)
        assertFalse("scrolled back up", states[2].shown)
    }

    @Test fun `a shorter range arriving later cannot make a long body claim it fits`() {
        // Layout reports a body shorter than it has already measured only mid-reflow (or on a
        // load-time scroll reset). Taking that number would show the bar on a five-screen body.
        val states = replay(0 to 7080, 0 to 0)
        assertFalse("a reflow blip revealed the bar on a long body", states[1].shown)
        assertEquals(7080, states[1].maxScrollPx)
    }

    @Test fun `a shorter range does not survive into the reader's own scroll either`() {
        // Same guard one step further out: after the blip, the reader scrolls a little. The range
        // it is judged against must still be the tallest measured, not the blip's.
        val states = replay(0 to 7080, 0 to 0, 300 to 0)
        assertFalse(states[2].shown)
        assertEquals(7080, states[2].maxScrollPx)
    }

    @Test fun `the writers may arrive in either order`() {
        // The height poll normally settles before the settle poll's terminal report, but a load
        // whose height came from a fallback first can be followed by a real measurement, so the
        // measured report can land second. Neither order may retract, and neither may shrink.
        val settleFirst = replay(0 to 0, 0 to 7080)
        val measuredFirst = replay(0 to 7080, 0 to 0)
        assertTrue(settleFirst.last().shown)
        assertFalse(measuredFirst.last().shown)
        assertEquals(7080, settleFirst.last().maxScrollPx)
        assertEquals(7080, measuredFirst.last().maxScrollPx)
    }

    @Test fun `a report that both moves the reader and grows the body follows the reader`() {
        // The reader scrolled: this report is not a late measurement, so it decides normally.
        val states = replay(0 to 0, 900 to 7080)
        assertTrue(states[0].shown)
        assertFalse("the reader moved off the end", states[1].shown)
    }

    @Test fun `a reload puts the reader back at the top`() {
        // Toggling "show images" reloads the same message: the scroll resets to 0, which IS a move,
        // so a bar shown at the old end is re-decided against the new geometry rather than frozen.
        val states = replay(0 to 7080, 7080 to 7080, 0 to 9000)
        assertTrue(states[1].shown)
        assertFalse(states[2].shown)
    }

    @Test fun `at a fixed scroll offset the bar can only ever turn on`() {
        // The invariant itself, over every sequence of ranges a laying-out body could report at
        // rest: once up, never down. This is the promise made publicly on 28 July.
        val ranges = listOf(0, 1, threshold, threshold + 1, 400, 7080, 40, 9000, 0)
        for (start in ranges.indices) {
            var state = BarState()
            var everShown = false
            for (i in start until ranges.size) {
                state = BodyReveal.barAfterReport(state, scrollY = 0, ranges[i], threshold)
                if (state.shown) everShown = true
                assertTrue(
                    "bar retracted at rest: ranges=${ranges.drop(start)} step=$i",
                    state.shown || !everShown,
                )
            }
        }
    }

    @Test fun `the measured range never decreases`() {
        val ranges = listOf(0, 4000, 120, 9000, 30, 9000)
        var state = BarState()
        var high = 0
        for (r in ranges) {
            state = BodyReveal.barAfterReport(state, scrollY = 0, r, threshold)
            high = maxOf(high, r)
            assertEquals(high, state.maxScrollPx)
        }
    }
}

/**
 * [BarReveal] — the seam the reader's two callbacks delegate to, so what each writer is ALLOWED to
 */
class BarRevealTest {

    private val threshold = 12

    /** A report from a body that HAS laid out, with [maxScroll] px left to scroll in a 1080 px view. */
    private fun laidOut(scrollY: Int, maxScroll: Int, viewportPx: Int = 1080) = BodyMetrics(
        scrollY = scrollY,
        rangePx = viewportPx + maxScroll,
        viewportPx = viewportPx,
        contentHeightPx = viewportPx + maxScroll,
    )

    /** What every reading returns before the document is laid out: the floor, and no document. */
    private fun floorOf(viewportPx: Int = 1080) =
        BodyMetrics(scrollY = 0, rangePx = viewportPx, viewportPx = viewportPx, contentHeightPx = 0)

    @Test fun `the same body decides the same bar read cold or read warm`() {
        // THE DEFECT this volet is about. The FIRST message opened after process start owns the
        // process's first WebView: its renderer is still booting while the settle poll reads, so
        //
        // Both sequences are folded in WHOLE: nothing here picks which report counts — that is the
        // decision under test.
        val floor = floorOf()
        val laid = BodyMetrics(scrollY = 0, rangePx = 3000, viewportPx = 1080, contentHeightPx = 2900)

        val cold = BarReveal()
        cold.bodyReady(floor, threshold)   // height poll settled on two agreeing floor readings
        cold.scrolled(floor, threshold)    // settle poll's terminal report, still the floor
        cold.scrolled(laid, threshold)     // layout lands: the real, tall range

        val warm = BarReveal()
        warm.bodyReady(laid, threshold)
        warm.scrolled(laid, threshold)

        assertEquals(
            "the bar followed the rank of the message, not the body's geometry",
            warm.shown,
            cold.shown,
        )
        assertFalse("a body taller than the viewport showed the end-of-message bar at its top", cold.shown)
    }

    @Test fun `a body that genuinely fits still gets its bar once the document has laid out`() {
        // The other side of the same guard: holding back an unmeasured report must not hold back a
        // bar that belongs. Same cold/warm pair on a SHORT body — the bar is there either way.
        val floor = floorOf()
        val laid = BodyMetrics(scrollY = 0, rangePx = 1080, viewportPx = 1080, contentHeightPx = 900)

        val cold = BarReveal()
        cold.bodyReady(floor, threshold)
        cold.scrolled(floor, threshold)
        cold.scrolled(laid, threshold)

        val warm = BarReveal()
        warm.bodyReady(laid, threshold)

        assertTrue("a short body lost the bar it has had since #63", cold.shown)
        assertTrue(warm.shown)
    }

    @Test fun `the settle poll's capped report is taken even though it measured nothing`() {
        // Discarding a report is only safe while another one can still arrive. The settle poll keeps
        // polling until the document lays out, but it is capped, and on a body that FITS there is no
        val bar = BarReveal()
        bar.bodyReady(floorOf(), threshold)          // height poll settled on the floor: inert
        bar.scrolled(floorOf(), threshold)           // a poll tick, still the floor: inert
        assertFalse("an ordinary floor reading revealed the bar", bar.shown)

        assertTrue(
            "the capped report is the reader's last word and must be folded in",
            bar.scrolled(floorOf(), threshold, lastWord = true),
        )
    }

    @Test fun `the last word does not license every unmeasured report`() {
        // The escape is the CAP's alone: a live scroll report that measured nothing stays inert, or
        // the guard is worth nothing.
        val bar = BarReveal()
        bar.scrolled(floorOf(), threshold)
        bar.scrolled(floorOf(), threshold)
        assertFalse(bar.shown)
    }

    @Test fun `a fresh body has no bar and nothing measured`() {
        val bar = BarReveal()
        assertFalse(bar.shown)
        assertEquals(BarState(), bar.state)
    }

    @Test fun `the readiness report and the scroll reports fold into one state`() {
        // The wiring #63 was missing: the height poll reveals the bar on a body that fits, and the
        // load's settle poll then fires its terminal report on the OTHER callback with the grown
        // range. Two separate states here would let the second undo the first.
        val bar = BarReveal()
        assertTrue(bar.bodyReady(laidOut(0, 0), threshold))
        assertTrue("the two writers kept separate books", bar.scrolled(laidOut(0, 7080), threshold))
        assertEquals("the scroll report's range was not carried over", 7080, bar.state.maxScrollPx)
    }

    @Test fun `a range measured by the height poll is carried into the scroll reports`() {
        // The other direction: the poll measured a long body, then a mid-reflow scroll report
        // arrives with a briefly tiny range. Sharing the state is what stops it claiming "it fits".
        val bar = BarReveal()
        assertFalse(bar.bodyReady(laidOut(0, 7080), threshold))
        assertFalse("a reflow blip revealed the bar", bar.scrolled(laidOut(0, 0), threshold))
    }

    @Test fun `an unmeasured height decides nothing`() {
        // Guard: a fallback height (the poll capped out) is not a measurement. It may neither
        // reveal the bar nor take one back, and it must not even be folded in — letting it through
        // is how a long body claims it fits and blinks.
        val bar = BarReveal()
        assertFalse("a fallback height revealed the bar", bar.bodyReady(null, threshold))
        assertEquals("a fallback height was folded in", BarState(), bar.state)
        assertTrue(bar.scrolled(laidOut(0, 0), threshold))
        assertTrue("a fallback height took the bar back", bar.bodyReady(null, threshold))
        assertEquals(0, bar.state.maxScrollPx)
    }

    @Test fun `shown always mirrors the folded state`() {
        val bar = BarReveal()
        val reports = listOf(0 to 0, 0 to 7080, 7080 to 7080, 3000 to 7080, 3000 to 9000)
        for ((y, max) in reports) {
            val returned = bar.scrolled(laidOut(y, max), threshold)
            assertEquals(bar.state.shown, bar.shown)
            assertEquals(bar.shown, returned)
        }
    }

    @Test fun `switching reading mode forgets the range of the document it replaced`() {
        // #149: the toggle swaps a 7080 px newsletter for its flattening, which FITS the screen.
        // The state is kept per message, so without the reset "a measured range only grows" keeps
        // 7080 — a range the short body can never reach — and the bar never comes back, in either
        // mode, until the message is closed.
        val bar = BarReveal()
        assertFalse("a long body at the top wants no bar", bar.bodyReady(laidOut(0, 7080), threshold))

        bar.documentReplaced()
        assertFalse(bar.shown)
        assertEquals(BarState(), bar.state)

        assertTrue("the shorter body cannot reach the old range", bar.bodyReady(laidOut(0, 0), threshold))
    }

    @Test fun `switching reading mode does not carry a shown bar onto the new body`() {
        // The same mistake from the other side: the bar earned by a body that fitted would survive
        // onto a long one, because "a shown bar is only taken back by the reader moving" and the
        // reader did not move — they toggled the mode.
        val bar = BarReveal()
        assertTrue(bar.bodyReady(laidOut(0, 0), threshold))

        bar.documentReplaced()

        assertFalse("the bar rode over onto a body it was never measured on", bar.scrolled(laidOut(0, 7080), threshold))
    }

    @Test fun `the reader reaching the end and scrolling away still works through the seam`() {
        val bar = BarReveal()
        assertFalse(bar.bodyReady(laidOut(0, 7080), threshold))
        assertTrue(bar.scrolled(laidOut(7080, 7080), threshold))
        assertFalse(bar.scrolled(laidOut(3000, 7080), threshold))
    }

    @Test fun `folding the quoted history away gives back the bar the long body took`() {
        // The bench sequence, report by report: the reply opens with its history folded, the reader
        // unfolds it (five screens), reads to the end, comes back to the top, and folds it away
        // again. The last event is not a scroll — the body is at the top and stays there — it is a
        // body that SHRANK under a still reader, and only this door can say so.
        val bar = BarReveal()
        assertFalse(bar.scrolled(laidOut(0, 7080), threshold))
        assertTrue(bar.scrolled(laidOut(7080, 7080), threshold))
        assertFalse(bar.scrolled(laidOut(0, 7080), threshold))

        assertTrue(
            "the folded body still carried the unfolded body's range, so Reply/Forward were gone " +
                "for the life of the page",
            bar.resized(laidOut(0, 0), threshold),
        )
        assertEquals("the range of the unfolded body was kept", 0, bar.state.maxScrollPx)
    }

    @Test fun `a resize report that measured nothing changes nothing`() {
        // A reading of the pre-layout floor is SHORTER than a measured long body, so it looks
        // exactly like a fold from here — and rebasing on it would hand the bar to a body that is
        // still five screens tall. Inert, like every other unmeasured report (there is no last
        // word on this door: a gesture that changed nothing is followed by the next gesture).
        val bar = BarReveal()
        assertFalse(bar.scrolled(laidOut(0, 7080), threshold))
        val before = bar.state

        assertFalse("an unmeasured resize report revealed the bar", bar.resized(floorOf(), threshold))
        assertEquals("an unmeasured resize report was folded in", before, bar.state)
    }
}

/**
 * The bar's THIRD door: the same document, still on screen, that changed size under a reader who
 */
class BarResizeTest {

    private val threshold = 12 // ~4dp on a 3x screen

    @Test fun `a bar lost to the unfolded history comes back when the history is folded away`() {
        // THE DEFECT, in the order the bench produced it on ultranabla2: open, unfold, read to the
        // bottom (bar), scroll back to the top (bar goes, correctly), fold away. The body is now
        // two screens shorter than the range the state remembers.
        var state = BarState()
        state = BodyReveal.barAfterReport(state, 0, 7080, threshold)
        state = BodyReveal.barAfterReport(state, 7080, 7080, threshold)
        state = BodyReveal.barAfterReport(state, 0, 7080, threshold)
        assertFalse("the reader is back at the top of a long body", state.shown)

        assertTrue(
            "the folded body fits the screen, so the reader is at its end and the bar belongs there",
            BodyReveal.barAfterResize(state, scrollY = 0, maxScrollPx = 0, thresholdPx = threshold).shown,
        )
        assertFalse(
            "the SAME report through barAfterReport is what loses the bar for good — if this ever " +
                "becomes true, the two functions are the same one and this volet is undone",
            BodyReveal.barAfterReport(state, 0, 0, threshold).shown,
        )
    }

    @Test fun `folding away forgets the range the unfolded body reached`() {
        // Not just the verdict: the RANGE. Kept, it would decide every later report of this body —
        // a scroll inside the folded body would answer "there is more" all over again.
        val prev = BarState(shown = false, maxScrollPx = 7080, scrollY = 0)
        val after = BodyReveal.barAfterResize(prev, scrollY = 0, maxScrollPx = 400, thresholdPx = threshold)

        assertEquals("the unfolded body's range was dragged along", 400, after.maxScrollPx)
        assertEquals(0, after.scrollY)
        assertFalse("400 px left to read is not the end of the message", after.shown)
    }

    @Test fun `folding away keeps a bar the reader had already earned`() {
        // THE MISSING QUADRANT: every other case here starts with the bar OFF. A short reply whose
        // unfolded history is barely taller than the screen shows the bar as soon as she reaches
        //
        // Rebasing "only when the bar is off" (shrank && !prev.shown) survives every other test in
        // this file, and turns the fix into its opposite: the range of the unfolded body survives,
        // the moved scrollY counts as the reader moving, and the bar goes away AT THE INSTANT the
        // fold should have kept it. Nothing brings it back.
        val prev = BarState(shown = true, maxScrollPx = 300, scrollY = 300)
        val after = BodyReveal.barAfterResize(prev, scrollY = 0, maxScrollPx = 0, thresholdPx = threshold)

        assertTrue("the fold took away the bar the reader had already earned", after.shown)
        assertEquals("the unfolded body's range survived the fold", 0, after.maxScrollPx)
    }

    @Test fun `a body that GREW is folded in exactly as any other report`() {
        // Unfolding is measured green on the bench: the bar stays where it is until the reader
        // moves. Re-founding on a growth would send a bar that is already on screen away with no
        // gesture at all — the blink of Codeberg #63, back through a new door. So growth is not
        // "handled like" barAfterReport, it IS barAfterReport, and this pins the whole state.
        val prev = BarState(shown = true, maxScrollPx = 1200, scrollY = 300)
        val reports = listOf(0 to 1200, 300 to 1200, 300 to 5000, 5000 to 5000, 1200 to 9000)
        for ((scrollY, maxScrollPx) in reports) {
            assertEquals(
                "report ($scrollY, $maxScrollPx) took a different course through barAfterResize",
                BodyReveal.barAfterReport(prev, scrollY, maxScrollPx, threshold),
                BodyReveal.barAfterResize(prev, scrollY, maxScrollPx, threshold),
            )
        }
    }
}

/**
 * The tick of the probe that watches for that shrink ([BodyReveal.resizeStep]).
 */
class ResizeProbeTest {

    @Test fun `a reading that differs is NOT reported until it repeats`() {
        // THE DEFECT this rule exists for. A fold's relayout lands in STEPS on a long thread with
        // inline images: the first reading that differs is an intermediate one (3000 px on the way
        assertEquals(
            ResizeProbe.Retry,
            BodyReveal.resizeStep(now = 3000, atGesture = 7800, last = 7800, triesLeft = 5),
        )
    }

    @Test fun `two agreeing readings that differ from the gesture are reported`() {
        assertEquals(
            ResizeProbe.Report,
            BodyReveal.resizeStep(now = 900, atGesture = 7800, last = 900, triesLeft = 5),
        )
    }

    @Test fun `a relayout that lands in steps is reported only once it has stopped moving`() {
        // The bench sequence, tick by tick, through the decision itself: 7800 at the gesture, then
        // 3000 (mid-relayout), then 900, then 900. Only the last one may report, and it must report
        // 900 — the height the reader is actually left looking at.
        var last = 7800
        val verdicts = listOf(3000, 900, 900).map { now ->
            BodyReveal.resizeStep(now, atGesture = 7800, last = last, triesLeft = 10).also { last = now }
        }
        assertEquals(listOf(ResizeProbe.Retry, ResizeProbe.Retry, ResizeProbe.Report), verdicts)
    }

    @Test fun `an unfold settles the same way as a fold`() {
        // Growing changes the height as much as shrinking; which of the two it was is
        // barAfterResize's question, not this one's.
        assertEquals(
            ResizeProbe.Report,
            BodyReveal.resizeStep(now = 7800, atGesture = 2400, last = 7800, triesLeft = 5),
        )
    }

    @Test fun `a reading unchanged since the gesture polls again while ticks remain`() {
        // The relayout does not land in the same frame as the tap, and a tap on a paragraph never
        // lands at all — both look like this, and neither may report.
        assertEquals(
            ResizeProbe.Retry,
            BodyReveal.resizeStep(now = 2400, atGesture = 2400, last = 2400, triesLeft = 1),
        )
    }

    @Test fun `a height that never left the gesture's value is never reported`() {
        // However many times it repeats: repeating the value it had at the gesture is not a resize.
        assertEquals(
            ResizeProbe.Retry,
            BodyReveal.resizeStep(now = 2400, atGesture = 2400, last = 2400, triesLeft = 99),
        )
    }

    @Test fun `the probe expires without reporting anything`() {
        // THE GUARD: not Report. A tap that opened nothing (a paragraph, a link, a scroll that
        // ended in a tap) is the common case, and reporting "nothing moved" would re-found the
        // bar's remembered range on an arbitrary instant of a body that may still be laying out.
        assertEquals(
            ResizeProbe.Done,
            BodyReveal.resizeStep(now = 2400, atGesture = 2400, last = 2400, triesLeft = 0),
        )
    }

    @Test fun `a relayout still moving when the ticks run out is dropped, not reported`() {
        // The other expiry, and the one the report-on-first-difference version got wrong: the
        // budget ran out on a body that is STILL moving (3000 now, 900 a tick ago, neither is the
        assertEquals(
            ResizeProbe.Done,
            BodyReveal.resizeStep(now = 3000, atGesture = 7800, last = 900, triesLeft = 0),
        )
    }

    @Test fun `a plateau reached on the very last tick is still reported`() {
        assertEquals(
            ResizeProbe.Report,
            BodyReveal.resizeStep(now = 900, atGesture = 7800, last = 900, triesLeft = 0),
        )
    }
}
