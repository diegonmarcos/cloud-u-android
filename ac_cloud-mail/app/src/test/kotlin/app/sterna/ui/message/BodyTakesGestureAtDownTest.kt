package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The verdict the body takes at ACTION_DOWN (Codeberg #152), and the one-sided rule
 */
class BodyTakesGestureAtDownTest {

    /** A plausible `ViewConfiguration.scaledTouchSlop`: 8dp on a 3× screen. */
    private val slop = 24

    @Test fun `the bench's 600px table keeps the gesture`() {
        // 2026-08-12, wide body: hRange=1833 hExtent=720 hOffset=0 → 1113px of course ahead.
        // The #152 report itself; this claim is what gave the table back, and it must not move.
        assertEquals(
            "1113px of course ahead is content shown and unreachable — the body must claim at DOWN, " +
                "and a claim taken later never arrives: the MOVE reaches this view as ACTION_CANCEL",
            true,
            bodyTakesGestureAtDown(travelBack = 0, travelForward = 1113, minTravel = slop),
        )
    }

    @Test fun `the bench's 3px overhang leaves the gesture to the pager`() {
        // 2026-08-12, THE DEFECT: hRange=2403 hExtent=2400 hOffset=0 → 3px of course. The table is
        // entirely visible; claiming here cost the reader both swipe directions.
        assertEquals(
            "3px of course is a border, not content: claiming it moves the body 3px and kills " +
                "swipe-between-messages in both directions",
            false,
            bodyTakesGestureAtDown(travelBack = 0, travelForward = 3, minTravel = slop),
        )
    }

    @Test fun `a body that fits exactly leaves the gesture to the pager`() {
        // hRange=720 hExtent=720 hOffset=0 — the overwhelming majority of mail, and the internal
        // witness of the same bench build: this one always kept its swipe.
        assertEquals(
            "no course at all: swipe-between-messages (#6) is the reading gesture here, exactly as " +
                "in 1.4.9",
            false,
            bodyTakesGestureAtDown(travelBack = 0, travelForward = 0, minTravel = slop),
        )
    }

    @Test fun `two pixels of course are not a course`() {
        assertEquals(
            "2px cannot follow a finger; the drag is a swipe",
            false,
            bodyTakesGestureAtDown(travelBack = 2, travelForward = 2, minTravel = slop),
        )
    }

    @Test fun `one pixel short of the slop is still not a course`() {
        // The boundary from below: 23 against a slop of 24.
        assertEquals(
            "23px is under the slop, so the body cannot follow the finger over the distance that " +
                "makes a drag a drag",
            false,
            bodyTakesGestureAtDown(travelBack = 23, travelForward = 23, minTravel = slop),
        )
    }

    @Test fun `a course of exactly one slop keeps the gesture`() {
        // The boundary from above, on each side in turn: the threshold is INCLUSIVE, and the two
        // parameters are independent — a claim on one side must not need the other.
        assertEquals(
            "a course of exactly one slop is claimable: the body can follow the finger over the " +
                "whole distance the gesture is judged on",
            true,
            bodyTakesGestureAtDown(travelBack = 24, travelForward = 0, minTravel = slop),
        )
        assertEquals(
            "same, on the forward side",
            true,
            bodyTakesGestureAtDown(travelBack = 0, travelForward = 24, minTravel = slop),
        )
    }

    @Test fun `a negative course is never a course`() {
        // Unsettled geometry: extent momentarily above range, so range - extent - offset is
        // negative. It must read as "nothing to show", never as travel.
        assertEquals(
            "a negative course comes from a layout that has not settled; treating it as travel " +
                "would claim gestures on a body that is not even laid out",
            false,
            bodyTakesGestureAtDown(travelBack = -8, travelForward = -1200, minTravel = slop),
        )
    }

    @Test fun `content hidden behind the viewport keeps the gesture`() {
        // Mid-pan, or a dir="rtl" document at rest: the course is BEHIND, offset is large. Reading
        // scrollX == 0 would get this wrong; the offset does not.
        assertEquals(
            "a course behind the viewport is content the reader must be able to bring back",
            true,
            bodyTakesGestureAtDown(travelBack = 1113, travelForward = 0, minTravel = slop),
        )
    }

    // ── bodyCanTravel: the same rule, one side at a time — this is what the ACTION_MOVE branch
    // hands bodyDragOwner instead of raw canScrollHorizontally answers, so that a body whose 3px of
    // course only shows up after the finger landed is not killed by that second door. ──────────────

    @Test fun `one side of the rule answers on the pixels alone`() {
        assertEquals("3px of course is not a course", false, bodyCanTravel(3, slop))
        assertEquals("one pixel short is not a course", false, bodyCanTravel(23, slop))
        assertEquals("exactly one slop counts", true, bodyCanTravel(24, slop))
        assertEquals("the bench's 1113px counts", true, bodyCanTravel(1113, slop))
        assertEquals("no course at all", false, bodyCanTravel(0, slop))
        assertEquals("a negative course is never a course", false, bodyCanTravel(-1200, slop))
    }
}
