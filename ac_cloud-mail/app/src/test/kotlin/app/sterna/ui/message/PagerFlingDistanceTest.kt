package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How far the FINGER must have travelled before a flick may change message.
 */
class PagerFlingDistanceTest {

    /** 25 dp at a density of 2.0 — the 360 dp bench the no-go was measured on. */
    private val floorPx = 50f

    /** Well past the library's own 400 dp/s (800 px/s here): a real flick. */
    private val flick = 1200f

    private fun velocity(travelPx: Float, velocityPxPerSec: Float = flick): Float =
        flingVelocityForTravel(
            travelPx = travelPx,
            minDistancePx = floorPx,
            velocityPxPerSec = velocityPxPerSec,
        )

    @Test fun `the floor is androidx ViewPager's own 25 dp`() {
        // Pinned as a number because nothing else can see it: this file works in px, and the dp→px
        // conversion happens in the composable. 25 dp is ViewPager's MIN_DISTANCE_FOR_FLING, the
        assertEquals(25, MIN_FLING_DISTANCE_DP)
    }

    // -- the floor: under it the accident, at it and above it the swipe --------------------------

    @Test fun `a fast nudge shorter than the floor is given no speed at all`() {
        // 49 px = 24.5 dp on the bench, flicked at 1200 px/s. This IS the report: the library
        // would take it to the next message.
        assertEquals(0f, velocity(49f), 0f)
    }

    @Test fun `the same nudge leftwards is given no speed either`() {
        assertEquals(0f, velocity(-49f, velocityPxPerSec = -flick), 0f)
    }

    @Test fun `a nudge that moved nothing at all is given no speed`() {
        assertEquals(0f, velocity(0f), 0f)
    }

    @Test fun `at the floor exactly the flick passes`() {
        // The comparison must be `<`, so equality passes: at exactly the distance ViewPager asked
        // for, the gesture is a swipe, not a brush.
        assertEquals(1200f, velocity(50f), 0f)
    }

    @Test fun `at the floor exactly the leftward flick passes with its sign`() {
        assertEquals(-1200f, velocity(-50f, velocityPxPerSec = -flick), 0f)
    }

    @Test fun `the 40 dp flick the bench measured passes`() {
        // 80 px = 40 dp at density 2.0: the gesture that changed no page at all, 0 out of 4, when
        // this guard was reading the pager's offset instead of the finger's travel.
        assertEquals(1200f, velocity(80f), 0f)
    }

    @Test fun `past the floor the velocity is handed on unchanged`() {
        // Unchanged, not re-tuned: the library decides the page turn exactly as it did before this
        // guard existed.
        assertEquals(1200f, velocity(120f), 0f)
    }

    @Test fun `past the floor a leftward velocity keeps its sign and its size`() {
        assertEquals(-1200f, velocity(-120f, velocityPxPerSec = -flick), 0f)
    }

    @Test fun `a slow release past the floor still gets its zero, unchanged`() {
        // A drag let go of at rest already has no fling: the guard must hand on the 0f it was
        // given, which is what drops the gesture into the library's ClosestItem path — the slow
        // swipe, still decided by snapPositionalThreshold, untouched by any of this.
        assertEquals(0f, velocity(120f, velocityPxPerSec = 0f), 0f)
    }

    // -- the travel machine, executed -------------------------------------------------------------

    @Test fun `a touch down takes the origin and starts from zero`() {
        assertEquals(
            PointerTravel(downX = 200f, travelPx = 0f, fingerDown = true),
            advanceTravel(PointerTravel(), isPress = true, anyPressed = true, x = 200f),
        )
    }

    @Test fun `a finger still down reports its distance from the origin, rightwards`() {
        assertEquals(
            PointerTravel(downX = 200f, travelPx = 60f, fingerDown = true),
            advanceTravel(
                PointerTravel(downX = 200f, travelPx = 10f, fingerDown = true),
                isPress = false,
                anyPressed = true,
                x = 260f,
            ),
        )
    }

    @Test fun `a finger still down reports a negative travel going left`() {
        assertEquals(
            PointerTravel(downX = 200f, travelPx = -60f, fingerDown = true),
            advanceTravel(
                PointerTravel(downX = 200f, travelPx = -10f, fingerDown = true),
                isPress = false,
                anyPressed = true,
                x = 140f,
            ),
        )
    }

    @Test fun `the lift writes the final travel and closes the gesture`() {
        // The up event carries the pointer's true final position whatever the slop swallowed, so
        // the travel is recomputed from it — 280 − 200 = 80 px — and NOT left at the last move's
        // value (60 px here).
        assertEquals(
            PointerTravel(downX = 200f, travelPx = 80f, fingerDown = false),
            advanceTravel(
                PointerTravel(downX = 200f, travelPx = 60f, fingerDown = true),
                isPress = false,
                anyPressed = false,
                x = 280f,
            ),
        )
    }

    @Test fun `the travel outlives the lift`() {
        // THE reason it is not cleared at the lift: the fling runs after the release, and this is
        // the state it reads. Clear it there and the guard sees 0 px forever — no flick ever
        // changes message, which is exactly the failure the bench measured.
        val lifted = PointerTravel(downX = 200f, travelPx = 80f, fingerDown = false)
        assertEquals(lifted, advanceTravel(lifted, isPress = false, anyPressed = false, x = 999f))
    }

    @Test fun `the next touch down clears the previous gesture's travel`() {
        // The case that tells the real machine from one that resets on every event: the travel of
        // the finished gesture must survive until a NEW finger goes down, and then go to zero with
        // a new origin.
        assertEquals(
            PointerTravel(downX = 500f, travelPx = 0f, fingerDown = true),
            advanceTravel(
                PointerTravel(downX = 200f, travelPx = 80f, fingerDown = false),
                isPress = true,
                anyPressed = true,
                x = 500f,
            ),
        )
    }

    @Test fun `a press after a gesture that was never closed starts a fresh origin`() {
        // The cancelled gesture: the shade, or the system back gesture, takes the touch and no
        // "nothing is pressed any more" event ever reaches this machine, so fingerDown is still
        assertEquals(
            PointerTravel(downX = 500f, travelPx = 0f, fingerDown = true),
            advanceTravel(
                PointerTravel(downX = 200f, travelPx = 60f, fingerDown = true),
                isPress = true,
                anyPressed = true,
                x = 500f,
            ),
        )
    }

    @Test fun `a press wins over anyPressed, whatever the event says about pressure`() {
        // Order of the branches, pinned: isPress is read FIRST and alone. Written
        // `isPress && anyPressed ->`, or placed under the anyPressed branch, a press whose change
        // no longer reports itself pressed would fall through to the move branch and measure from
        // the previous gesture's origin.
        assertEquals(
            PointerTravel(downX = 400f, travelPx = 0f, fingerDown = true),
            advanceTravel(
                PointerTravel(downX = 100f, travelPx = 90f, fingerDown = true),
                isPress = true,
                anyPressed = false,
                x = 400f,
            ),
        )
    }

    @Test fun `an event with no finger and no gesture open changes nothing`() {
        assertEquals(
            PointerTravel(),
            advanceTravel(PointerTravel(), isPress = false, anyPressed = false, x = 700f),
        )
    }

    @Test fun `a there-and-back gesture ends up with a short travel`() {
        // Signed and final, as ViewPager compared it: 120 px out and back to 5 px from the origin
        // is a 5 px gesture, not a 120 px one.
        var travel = advanceTravel(PointerTravel(), isPress = true, anyPressed = true, x = 200f)
        travel = advanceTravel(travel, isPress = false, anyPressed = true, x = 320f)
        travel = advanceTravel(travel, isPress = false, anyPressed = false, x = 205f)
        assertEquals(5f, travel.travelPx, 0f)
        assertEquals(0f, velocity(5f), 0f)
    }

    @Test fun `a real swipe ends up with the travel the finger covered`() {
        var travel = advanceTravel(PointerTravel(), isPress = true, anyPressed = true, x = 300f)
        travel = advanceTravel(travel, isPress = false, anyPressed = true, x = 260f)
        travel = advanceTravel(travel, isPress = false, anyPressed = false, x = 220f)
        assertEquals(-80f, travel.travelPx, 0f)
        assertEquals(-1200f, velocity(-80f, velocityPxPerSec = -flick), 0f)
    }
}
