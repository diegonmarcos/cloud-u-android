package app.sterna.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advanced-filter panel can now be dragged from three places instead of one: its handle, the
 */
class SearchPanelTest {

    private val height = 400f

    @Test fun `a downward flick opens, however little the panel has moved`() {
        // The point of honouring the throw first: a deliberate flick that only travelled 10 px must
        // not be undone by the halfway line.
        assertTrue(searchPanelSettlesOpen(offsetPx = 10f, panelHeightPx = height, velocity = 2000f))
    }

    @Test fun `an upward flick shuts, however far the panel had come out`() {
        assertFalse(searchPanelSettlesOpen(offsetPx = 390f, panelHeightPx = height, velocity = -2000f))
    }

    @Test fun `a slow drag past halfway opens`() {
        assertTrue(searchPanelSettlesOpen(offsetPx = 201f, panelHeightPx = height, velocity = 0f))
    }

    @Test fun `a slow drag short of halfway falls back shut`() {
        assertFalse(searchPanelSettlesOpen(offsetPx = 199f, panelHeightPx = height, velocity = 0f))
    }

    @Test fun `exactly halfway falls back shut rather than opening`() {
        // The boundary is spelled out so a later `>=` cannot slip in unnoticed: a drag that stopped
        // dead in the middle has not asked for anything, and the panel returning where it came from
        // is the answer that surprises least.
        assertFalse(searchPanelSettlesOpen(offsetPx = 200f, panelHeightPx = height, velocity = 0f))
    }

    @Test fun `a drift below the flick threshold is still judged on distance`() {
        // 799 px/s downwards from a panel barely out: not a throw, and not past halfway, so shut.
        assertFalse(searchPanelSettlesOpen(offsetPx = 20f, panelHeightPx = height, velocity = 799f))
        // The witness: the same drift from a panel already mostly out opens.
        assertTrue(searchPanelSettlesOpen(offsetPx = 380f, panelHeightPx = height, velocity = 799f))
    }

    @Test fun `below the flick threshold the answer flips exactly once, at the halfway line`() {
        // Sweeps the whole travel and asserts the verdict changes sides ONCE and in the right
        // place. Catches a reversed comparison, a threshold written against the wrong quantity, and
        // any condition that is not monotonic in how far the panel has come out — none of which a
        // handful of spot values would necessarily catch.
        val verdicts = (0..400).map { searchPanelSettlesOpen(it.toFloat(), height, velocity = 0f) }
        assertEquals(201, verdicts.count { !it })  // 0..200 shut
        assertEquals(200, verdicts.count { it })   // 201..400 open
        assertEquals(201, verdicts.indexOfFirst { it })
        assertEquals(1, verdicts.zipWithNext().count { (a, b) -> a != b })
    }

    @Test fun `an unmeasured panel takes the flick as written, and that is coherent`() {
        // Before the first layout the height is 0, so the halfway line degenerates to `0 > 0` and a
        // slow release is shut...
        assertFalse(searchPanelSettlesOpen(offsetPx = 0f, panelHeightPx = 0f, velocity = 0f))
        // ...but a FLICK still wins, because the throw is honoured before any distance is measured.
        assertTrue(searchPanelSettlesOpen(offsetPx = 0f, panelHeightPx = 0f, velocity = 2000f))

        // Not guarded against, on purpose. It is unreachable — a height of 0 means the panel has
        // not been laid out, and a frame is always laid out before it can be touched — and were it
    }

    // ---- what the panel takes from a drag, and what it hands back ----

    @Test fun `two deltas in a row accumulate`() {
        // The defect this pins: reading the position and applying it were once separated by a
        // coroutine hop, so two touch events drained in one pass of the event loop both measured
        // from BEFORE either had landed, and the first delta vanished after being reported as
        // consumed. Feeding the result back in is exactly what the caller does.
        var offset = 0f
        offset += searchPanelTakes(offset, height, 30f)
        offset += searchPanelTakes(offset, height, 30f)
        assertEquals(60f, offset, 0f)
    }

    @Test fun `a delta that fits is taken whole`() {
        assertEquals(30f, searchPanelTakes(offsetPx = 100f, panelHeightPx = height, delta = 30f), 0f)
        assertEquals(-30f, searchPanelTakes(offsetPx = 100f, panelHeightPx = height, delta = -30f), 0f)
    }

    @Test fun `pushing past the open end only takes what is left`() {
        // 10 px of travel remain, 50 are offered: the other 40 must be handed back, or a nested
        // scroll would count them as consumed and they would move nothing at all.
        assertEquals(10f, searchPanelTakes(offsetPx = 390f, panelHeightPx = height, delta = 50f), 0f)
    }

    @Test fun `pushing past the shut end only takes what is left`() {
        assertEquals(-10f, searchPanelTakes(offsetPx = 10f, panelHeightPx = height, delta = -50f), 0f)
    }

    @Test fun `a panel already at rest takes nothing and says so`() {
        // The case that decides whether the content gets to scroll: fully out and pushed further
        // out, or fully shut and pushed further shut, the panel must consume zero.
        assertEquals(0f, searchPanelTakes(offsetPx = height, panelHeightPx = height, delta = 50f), 0f)
        assertEquals(0f, searchPanelTakes(offsetPx = 0f, panelHeightPx = height, delta = -50f), 0f)
    }

    @Test fun `what it takes always lands inside the travel, whatever it is offered`() {
        // The invariant the caller relies on: `offsetPx += takes(...)` can never put the panel
        // outside 0..height, so no clamp is needed at the call site and none can be forgotten.
        val offsets = listOf(0f, 1f, 200f, 399f, height)
        val deltas = listOf(-10_000f, -50f, -0.5f, 0f, 0.5f, 50f, 10_000f)
        for (offset in offsets) {
            for (delta in deltas) {
                val landed = offset + searchPanelTakes(offset, height, delta)
                assertTrue("$offset + $delta landed at $landed", landed in 0f..height)
            }
        }
    }
}
