package app.sterna.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How tall the ventilated cell asks to be, RUN — the one decision of this fix that can be executed
 */
class UnreadVentilatedHeightTest {

    /**
     * The numbers, pinned one by one. Each is `16 + rows × (8 + 20 × fontScale)`, and it is written
     */
    @Test fun `the height asked for is the padding plus one row per account, at this font scale`() {
        assertEquals(
            "one account line: 16dp of container padding (8 top, 8 bottom) plus one 30dp row.",
            46f,
            ventilatedHeightDp(rows = 1, fontScale = 1f),
            TOLERANCE,
        )
        assertEquals(
            "three lines — what the old fixed 110dp happened to hold, and the only case the " +
                "shipped constant was ever right about.",
            106f,
            ventilatedHeightDp(rows = 3, fontScale = 1f),
            TOLERANCE,
        )
        assertEquals(
            "FOUR lines. This is the whole defect: 128dp is more than the 110dp that used to be " +
                "offered whatever the account count, so the fourth account was drawn into a cell " +
                "that had room for three and was cut off by the edge in silence.",
            136f,
            ventilatedHeightDp(rows = 4, fontScale = 1f),
            TOLERANCE,
        )
    }

    /**
     * THE SYSTEM FONT SCALE IS IN THE NUMBER, and it is not a detail: the row's text is in `sp`
     */
    @Test fun `a larger system font asks for a taller cell, because only the text grows`() {
        assertEquals(
            "four lines at font scale 1.3: the 20dp of text becomes 26dp, the 8dp of row padding " +
                "does not move.",
            160f,
            ventilatedHeightDp(rows = 4, fontScale = 1.3f),
            TOLERANCE,
        )
        assertEquals(
            "three lines at font scale 2.0 — the largest the system offers — need more room than " +
                "four lines at 1.0.",
            166f,
            ventilatedHeightDp(rows = 3, fontScale = 2f),
            TOLERANCE,
        )
        assertEquals(
            "and one line at 2.0 is still one line.",
            66f,
            ventilatedHeightDp(rows = 1, fontScale = 2f),
            TOLERANCE,
        )
    }

    /**
     * A SHRUNK font buys nothing back, deliberately — the same one-sided clamp
     */
    @Test fun `shrinking the system font does not shrink the room asked for`() {
        assertEquals(
            "font scale 0.85 must ask for the same 46dp as 1.0, not less.",
            46f,
            ventilatedHeightDp(rows = 1, fontScale = 0.85f),
            TOLERANCE,
        )
    }

    /**
     * THE FLOOR, and it is the reason the launcher's ordering still means anything.
     */
    @Test fun `the height asked for is never at or under the small entry's own 40dp`() {
        assertEquals(
            "zero lines must still not undercut the declared minimum cell.",
            41f,
            ventilatedHeightDp(rows = 0, fontScale = 1f),
            TOLERANCE,
        )
        for (rows in 0..12) {
            for (scale in listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 2f)) {
                val height = ventilatedHeightDp(rows, scale)
                assertTrue(
                    "ventilatedHeightDp($rows, $scale) = $height must stay strictly above the 40dp " +
                        "height of the small entry, or the launcher may hand the account lines to " +
                        "a cell dragged to the widget's declared minimum.",
                    height > 40f,
                )
            }
        }
        assertTrue(
            "and the width offered with it must stay strictly above the small entry's 110dp, for " +
                "the same reason — closest-fit orders the two entries by both dimensions.",
            VENTILATED_WIDTH_DP > 110f,
        )
    }

    /**
     * PAST FOUR ACCOUNTS, WHICH IS WHERE THE BENCH ACTUALLY WENT (E3bis went to five, then six).
     */
    @Test fun `each further account asks for a further row's worth of room, without end`() {
        assertEquals(
            "five accounts — the count at which the bench first saw a line sliced.",
            166f,
            ventilatedHeightDp(rows = 5, fontScale = 1f),
            TOLERANCE,
        )
        assertEquals(
            "six accounts: the bench's second reading, where the sixth account was not drawn at all.",
            196f,
            ventilatedHeightDp(rows = 6, fontScale = 1f),
            TOLERANCE,
        )
        assertEquals(
            "and twelve, because nothing in the app bounds how many accounts a reader may add.",
            376f,
            ventilatedHeightDp(rows = 12, fontScale = 1f),
            TOLERANCE,
        )
        for (scale in listOf(1f, 1.3f, 2f)) {
            for (rows in 1..24) {
                assertTrue(
                    "ventilatedHeightDp($rows, $scale) must ask for strictly more than " +
                        "ventilatedHeightDp(${rows - 1}, $scale): an account that adds no room is " +
                        "an account drawn into a cell that has none left for it.",
                    ventilatedHeightDp(rows, scale) > ventilatedHeightDp(rows - 1, scale),
                )
            }
        }
    }

    /**
     * The two sides of the bargain this fix makes, stated as the user would see them: at three
     */
    @Test fun `three accounts still fit the cell that used to be assumed, four no longer do`() {
        assertTrue(
            "three lines must still be offered at or under the 110dp the shipped constant used " +
                "to claim, or this fix takes the ventilated cell away from the users who had it.",
            ventilatedHeightDp(rows = 3, fontScale = 1f) <= 110f,
        )
        assertTrue(
            "four lines must ask for more than 110dp: that cell holds three, and the fourth " +
                "account is what was being erased.",
            ventilatedHeightDp(rows = 4, fontScale = 1f) > 110f,
        )
    }

    /** Floats, compared in dp: 0.01dp is far below anything a screen can show. */
    private companion object {
        const val TOLERANCE = 0.01f
    }
}
