package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Executes [paneLayout] and [paneSplit] — the two decisions the inbox host spends when it puts a
 */
class PaneLayoutTest {

    @Test fun `under 600 dp the window is one pane`() {
        for (w in listOf(0, 360, 599)) {
            assertEquals(
                "at $w dp a phone held upright would be split in two: the list would lose half its " +
                    "width and every row would truncate its sender and time",
                PaneLayout.Narrow, paneLayout(w),
            )
        }
    }

    @Test fun `from 600 dp to 1199 dp the window is list plus reader`() {
        for (w in listOf(600, 640, 914, 1199)) {
            assertEquals(
                "at $w dp (M3 Medium: a phone in landscape, a small tablet) the reading pane must " +
                    "appear beside the list; kept Narrow, the whole window stays one list",
                PaneLayout.Wide, paneLayout(w),
            )
        }
    }

    @Test fun `from 1200 dp the window is a desk`() {
        for (w in listOf(1200, 1280)) {
            assertEquals(
                "at $w dp (M3 Large) the layout must be Desk, the one that will reserve 300 dp for " +
                    "a permanent drawer; reported Wide, the list is sized without that reserve and " +
                    "the drawer will overlap it",
                PaneLayout.Desk, paneLayout(w),
            )
        }
    }

    @Test fun `a narrow window has no split`() {
        assertNull("at 599 dp a non-null split would draw a reading pane on a phone held upright", paneSplit(599))
    }

    @Test fun `a wide window gives the list 40 percent, never less than 280 dp`() {
        assertEquals(
            "at 600 dp the list would be 240 dp: sender and time are cut on every row",
            PaneSplit(PaneLayout.Wide, 280), paneSplit(600),
        )
        assertEquals(
            "at 640 dp (a phone in landscape) the list would be 256 dp: sender and time are cut",
            PaneSplit(PaneLayout.Wide, 280), paneSplit(640),
        )
        assertEquals(
            "at 700 dp the list is exactly at the floor: 40 % is 280 dp, no rounding may lose a dp",
            PaneSplit(PaneLayout.Wide, 280), paneSplit(700),
        )
        assertEquals(
            "at 720 dp the floor no longer applies: the list must grow to 288 dp, not stay pinned",
            PaneSplit(PaneLayout.Wide, 288), paneSplit(720),
        )
        assertEquals(
            "at 914 dp (a large phone in landscape) the list must be 365 dp, 40 % rounded down",
            PaneSplit(PaneLayout.Wide, 365), paneSplit(914),
        )
        assertEquals(
            "at 1199 dp, the last Wide width, the list must be 479 dp — a 300 dp drawer reserve " +
                "taken here would shrink it to 359 dp on a window that has no permanent drawer",
            PaneSplit(PaneLayout.Wide, 479), paneSplit(1199),
        )
    }

    @Test fun `a desk window sizes the list after a 300 dp drawer`() {
        assertEquals(
            "at 1200 dp the list must be 40 % of the 900 dp left beside a 300 dp drawer = 360 dp; " +
                "480 dp means the drawer reserve was forgotten and the reader loses 120 dp",
            PaneSplit(PaneLayout.Desk, 360), paneSplit(1200),
        )
        assertEquals(
            "at 1280 dp the list must be 40 % of 980 dp = 392 dp",
            PaneSplit(PaneLayout.Desk, 392), paneSplit(1280),
        )
    }
}
