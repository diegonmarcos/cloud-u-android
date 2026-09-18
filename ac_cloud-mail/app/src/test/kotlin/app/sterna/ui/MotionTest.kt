package app.sterna.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [motionEnabled] is the ONE decision every animated view in this app reads through
 * [rememberMotionEnabled] — [app.sterna.ui.components.LoadingRing], the inbox, search, settings and
 * compose screen transitions, and (#501) the Home screen's stat count-up. This is the sensor for
 * that decision: it fails the instant battery saver stops holding an animation still, independently
 * of whether the reader has also zeroed the animator duration scale.
 *
 * #435 deleted cloud-power-saving, the SuperApp launcher's OWN mode — a different app entirely.
 * [motionEnabled] must never be satisfied by that; it reads the system's real
 * `PowerManager.isPowerSaveMode` (piped in as [motionEnabled]'s `powerSaveMode` parameter by
 * [rememberMotionEnabled]) and nothing named after the deleted launcher mode.
 */
class MotionTest {

    @Test fun `both signals off, motion plays`() {
        assertTrue(motionEnabled(animatorDurationScale = 1f, powerSaveMode = false))
    }

    @Test fun `battery saver alone holds every animation still`() {
        // THE REGRESSION THIS GUARDS: before #501, this function had no powerSaveMode parameter at
        // all — the animator-scale check was the only signal, so a device in battery saver with
        // ordinary animator settings played every decorative animation the app draws, in flat
        // contradiction of the ticket's second hard constraint. Flip this back to `true` with no
        // regard for powerSaveMode and this is the line that goes red.
        assertFalse(
            "battery saver is on: motion must stop even though the animator duration scale is 1x",
            motionEnabled(animatorDurationScale = 1f, powerSaveMode = true),
        )
    }

    @Test fun `remove-animations alone holds every animation still`() {
        assertFalse(
            "the animator duration scale is 0 (Remove animations): motion must stop even with " +
                "battery saver off",
            motionEnabled(animatorDurationScale = 0f, powerSaveMode = false),
        )
    }

    @Test fun `both signals on, still no motion`() {
        assertFalse(motionEnabled(animatorDurationScale = 0f, powerSaveMode = true))
    }
}
