package app.sterna.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which face the two "pick date and time" pickers show (#161), decided by [schedulePickerAsInput]
 */
class SchedulePickerLayoutTest {

    @Test fun `the bench's landscape is the case that was cut off`() {
        assertTrue(
            "914×411 dp landscape is the measured defect: Material lays its dial out Horizontally, " +
                "which is wider than the dialog, and the cut is SIDEWAYS so no scroll saves it — " +
                "PM was gone.",
            schedulePickerAsInput(screenHeightDp = 411, screenWidthDp = 914),
        )
    }

    @Test fun `the same device in portrait keeps the dial`() {
        assertFalse(
            "914 dp of height against 411 of width is where the dial FITS and is the nicer face — " +
                "the keyboard entry must not spread to portrait, which was never broken.",
            schedulePickerAsInput(screenHeightDp = 914, screenWidthDp = 411),
        )
    }

    @Test fun `a square screen keeps the dial, the boundary being on Material's side`() {
        assertFalse(
            "equal height and width is the frontier, and Material itself lays out Vertically when " +
                "height >= width, so the dial is what is really on screen there and it shows fine. " +
                "A `<=` here would swap a working dial for the keyboard entry on that screen.",
            schedulePickerAsInput(screenHeightDp = 600, screenWidthDp = 600),
        )
    }

    @Test fun `one dp either side of the frontier, because the four benches are nowhere near it`() {
        // The cases that pin the comparison itself rather than the two obvious orientations.
        // Every other case here is hundreds of dp from the boundary, so an OFFSET slipped into the
        assertTrue(
            "one dp shorter than it is wide is already landscape to Material, and already the " +
                "horizontal dial an AlertDialog cannot hold.",
            schedulePickerAsInput(screenHeightDp = 599, screenWidthDp = 600),
        )
        assertFalse(
            "one dp taller than it is wide is still the vertical layout, and the dial fits.",
            schedulePickerAsInput(screenHeightDp = 600, screenWidthDp = 599),
        )
    }

    @Test fun `a narrow bench keeps the dial upright and loses it turned`() {
        assertFalse(
            "360 dp wide in portrait is narrow, but the dial is laid out Vertically there and fits.",
            schedulePickerAsInput(screenHeightDp = 760, screenWidthDp = 360),
        )
        assertTrue(
            "the same narrow bench turned is 760 dp of width for 360 of height — Material goes " +
                "Horizontal, and that is the layout an AlertDialog cannot hold.",
            schedulePickerAsInput(screenHeightDp = 360, screenWidthDp = 760),
        )
    }
}
