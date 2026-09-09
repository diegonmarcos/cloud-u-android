package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direction-lock discrimination for a list-row drag (Codeberg #97): a mostly-vertical
 */
class SwipeDirectionLockTest {

    private val slop = 10f

    @Test fun `tiny travel within the slop is still undecided`() {
        assertEquals(SwipeLock.PENDING, swipeDirectionLock(dx = 4f, dy = 3f, slop = slop))
    }

    @Test fun `a flat deliberate sideways swipe arms`() {
        // Near-zero vertical: exactly the intentional gesture we must keep responsive.
        assertEquals(SwipeLock.HORIZONTAL, swipeDirectionLock(dx = 20f, dy = 2f, slop = slop))
    }

    @Test fun `a clean vertical scroll is abandoned to the list`() {
        assertEquals(SwipeLock.VERTICAL, swipeDirectionLock(dx = 2f, dy = 20f, slop = slop))
    }

    @Test fun `a mostly-vertical arc that leads horizontally still scrolls`() {
        // The #97 failure: dx currently leads dy (a 1:1 test would have armed here),
        // but the vertical component is meaningful, so it must resolve to scroll.
        assertEquals(SwipeLock.VERTICAL, swipeDirectionLock(dx = 30f, dy = 20f, slop = slop))
    }

    @Test fun `a diagonal drag resolves to scroll rather than swipe`() {
        assertEquals(SwipeLock.VERTICAL, swipeDirectionLock(dx = 25f, dy = 25f, slop = slop))
    }

    @Test fun `horizontal must dominate by the full ratio to arm`() {
        // Just under 2:1 with a real vertical component → not yet a swipe.
        assertEquals(SwipeLock.VERTICAL, swipeDirectionLock(dx = 30f, dy = 16f, slop = slop))
        // At the 2:1 ratio it arms.
        assertEquals(SwipeLock.HORIZONTAL, swipeDirectionLock(dx = 40f, dy = 20f, slop = slop))
    }

    @Test fun `horizontal lead below the slop factor waits even when vertical is nil`() {
        // |dx| clears touch-slop but not slop × SWIPE_SLOP_FACTOR: keep watching.
        assertEquals(SwipeLock.PENDING, swipeDirectionLock(dx = 12f, dy = 0f, slop = slop))
    }

    @Test fun `direction is sign-agnostic (left swipe and upward scroll)`() {
        assertEquals(SwipeLock.HORIZONTAL, swipeDirectionLock(dx = -20f, dy = 1f, slop = slop))
        assertEquals(SwipeLock.VERTICAL, swipeDirectionLock(dx = -30f, dy = -20f, slop = slop))
    }
}
