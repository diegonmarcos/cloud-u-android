package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setting that removes the reader's bottom Reply/Forward bar (#63).
 */
class ReplyBarTest {

    // -- whether the bar shows -----------------------------------------------------------------

    @Test fun `switched off, the bar never shows`() {
        assertFalse(replyBarVisible(enabled = false, bodyReady = true, wantsBar = true))
    }

    @Test fun `switched on, the bar shows once the body is ready and the page wants it`() {
        assertTrue(replyBarVisible(enabled = true, bodyReady = true, wantsBar = true))
    }

    @Test fun `the setting does not make the bar appear before the body is measured`() {
        // The reveal machinery is untouched: both reports still have to arrive. Showing the bar
        // before the body is measured is the thing that was refused on 28/07, not what this fixes.
        assertFalse(replyBarVisible(enabled = true, bodyReady = false, wantsBar = true))
        assertFalse(replyBarVisible(enabled = true, bodyReady = true, wantsBar = false))
        assertFalse(replyBarVisible(enabled = true, bodyReady = false, wantsBar = false))
    }

    @Test fun `switched off, no report from the page can bring it back`() {
        listOf(true, false).forEach { ready ->
            listOf(true, false).forEach { wants ->
                assertFalse(
                    "replyBarVisible(enabled = false, bodyReady = $ready, wantsBar = $wants)",
                    replyBarVisible(enabled = false, bodyReady = ready, wantsBar = wants),
                )
            }
        }
    }

    // -- and the blank it sits over ------------------------------------------------------------

    @Test fun `switched off, the body reserves nothing at all`() {
        // Both ways of getting here: before the bar is measured, and after. Neither may leave a
        // strip of blank at the end of every message.
        assertEquals(0, bodyBottomInsetPx(enabled = false, measuredPx = 0, density = 3f))
        assertEquals(0, bodyBottomInsetPx(enabled = false, measuredPx = 168, density = 3f))
    }

    @Test fun `switched on, the body reserves the bar's measured height plus clearance`() {
        // 4 dp of clearance at density 3 = 12 px. This is the case that tells the two dp values
        // apart: with them swapped the answer is 168 + 228, and it is the case a reader meets on
        // every message once the bar has been measured.
        assertEquals(180, bodyBottomInsetPx(enabled = true, measuredPx = 168, density = 3f))
        assertEquals(172, bodyBottomInsetPx(enabled = true, measuredPx = 168, density = 1f))
    }

    @Test fun `before the bar is measured, the body reserves the fallback height`() {
        // First frame: nothing has been measured yet, and a body laid out with no reserve would
        // have its last lines covered when the bar reveals. 76 + 4 dp, in device pixels.
        assertEquals(80, bodyBottomInsetPx(enabled = true, measuredPx = 0, density = 1f))
        assertEquals(240, bodyBottomInsetPx(enabled = true, measuredPx = 0, density = 3f))
        assertEquals(200, bodyBottomInsetPx(enabled = true, measuredPx = 0, density = 2.5f))
    }

    @Test fun `the clearance is a sliver and the fallback is a bar`() {
        // The relation the two constants have to keep, stated where swapping them breaks it: the
        // gap under the last line is a fraction of the bar, not another bar. Swapped, the first
        // frame is unchanged (4 + 76 = 76 + 4) and every measured frame gains ~72 dp of white.
        assertTrue(
            "the clearance ($REPLY_BAR_CLEARANCE_DP dp) must stay far below the bar's fallback " +
                "height ($REPLY_BAR_FALLBACK_DP dp)",
            REPLY_BAR_CLEARANCE_DP < REPLY_BAR_FALLBACK_DP / 4f,
        )
    }
}
