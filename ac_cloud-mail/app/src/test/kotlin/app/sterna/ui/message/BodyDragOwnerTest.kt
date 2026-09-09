package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The body's once-per-gesture axis decision (Codeberg #152): a message wider than the viewport — a
 */
class BodyDragOwnerTest {

    private val slop = 10f

    @Test fun `a clearly horizontal drag with travel left that way scrolls the body`() {
        // THE #152 failure: dragging right with content still hidden off the left edge. Before the
        // fix this was handed to the pager and the message changed under the reader.
        assertEquals(
            BodyDrag.CLAIM,
            bodyDragOwner(dx = 30f, dy = 5f, slop = slop, canScrollBack = true, canScrollForward = false),
        )
    }

    @Test fun `dragging right at the left edge goes to the pager`() {
        // No travel back, but travel forward: proves the SIGN of dx picks which answer applies —
        // a decision that read canScrollForward here would keep the gesture and freeze the pager.
        assertEquals(
            BodyDrag.RELEASE,
            bodyDragOwner(dx = 30f, dy = 5f, slop = slop, canScrollBack = false, canScrollForward = true),
        )
    }

    @Test fun `dragging left with travel left that way scrolls the body`() {
        assertEquals(
            BodyDrag.CLAIM,
            bodyDragOwner(dx = -30f, dy = 5f, slop = slop, canScrollBack = false, canScrollForward = true),
        )
    }

    @Test fun `dragging left at the right edge goes to the pager`() {
        assertEquals(
            BodyDrag.RELEASE,
            bodyDragOwner(dx = -30f, dy = 5f, slop = slop, canScrollBack = true, canScrollForward = false),
        )
    }

    @Test fun `a body that fits has no travel either way and always yields to the pager`() {
        assertEquals(
            BodyDrag.RELEASE,
            bodyDragOwner(dx = 30f, dy = 5f, slop = slop, canScrollBack = false, canScrollForward = false),
        )
        assertEquals(
            BodyDrag.RELEASE,
            bodyDragOwner(dx = -30f, dy = 5f, slop = slop, canScrollBack = false, canScrollForward = false),
        )
    }

    @Test fun `an unsettled geometry answers false both ways and behaves like 1_4_9`() {
        // Before layout settles the measured courses are zero or negative, so both answers are
        // false; ceding then is the deliberate choice.
        assertEquals(
            BodyDrag.RELEASE,
            bodyDragOwner(dx = 40f, dy = 0f, slop = slop, canScrollBack = false, canScrollForward = false),
        )
    }

    @Test fun `a vertical drag is kept by the body whatever the sideways travel`() {
        assertEquals(
            BodyDrag.CLAIM,
            bodyDragOwner(dx = 2f, dy = 30f, slop = slop, canScrollBack = false, canScrollForward = false),
        )
        assertEquals(
            BodyDrag.CLAIM,
            bodyDragOwner(dx = 2f, dy = -30f, slop = slop, canScrollBack = false, canScrollForward = false),
        )
    }

    @Test fun `a diagonal drag is kept by the body, unchanged by this fix`() {
        // 30:20 is under the 3:1 dominance, so travel is never consulted: reading wins the tie.
        assertEquals(
            BodyDrag.CLAIM,
            bodyDragOwner(dx = 30f, dy = 20f, slop = slop, canScrollBack = false, canScrollForward = false),
        )
    }

    @Test fun `the dominance ratio is still 3 to 1 and still strict`() {
        // Exactly 3:1 is NOT clearly horizontal — the body keeps it.
        assertEquals(
            BodyDrag.CLAIM,
            bodyDragOwner(dx = 30f, dy = 10f, slop = slop, canScrollBack = false, canScrollForward = false),
        )
        // Just past 3:1, with no travel that way, it is the pager's.
        assertEquals(
            BodyDrag.RELEASE,
            bodyDragOwner(dx = 31f, dy = 10f, slop = slop, canScrollBack = false, canScrollForward = false),
        )
        // ... and with travel that way the body keeps it, at the very same angle.
        assertEquals(
            BodyDrag.CLAIM,
            bodyDragOwner(dx = 31f, dy = 10f, slop = slop, canScrollBack = true, canScrollForward = false),
        )
    }

    @Test fun `no verdict before either side clears the touch slop`() {
        assertEquals(
            BodyDrag.PENDING,
            bodyDragOwner(dx = 9f, dy = 4f, slop = slop, canScrollBack = true, canScrollForward = true),
        )
        assertEquals(
            BodyDrag.PENDING,
            bodyDragOwner(dx = -10f, dy = -10f, slop = slop, canScrollBack = false, canScrollForward = false),
        )
        // One px past the slop on the vertical side and the verdict is due.
        assertEquals(
            BodyDrag.CLAIM,
            bodyDragOwner(dx = 0f, dy = 11f, slop = slop, canScrollBack = false, canScrollForward = false),
        )
    }
}
