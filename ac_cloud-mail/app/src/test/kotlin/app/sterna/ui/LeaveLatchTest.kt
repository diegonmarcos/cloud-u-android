package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The four ways a tap that leaves the app can go, decided by the shipped [LeaveLatch] itself.
 */
class LeaveLatchTest {

    /** Counts hand-offs and reports whether each one got off the ground. */
    private class Launcher(private val succeeds: Boolean = true) : () -> Boolean {
        var launches = 0
        override fun invoke(): Boolean {
            launches++
            return succeeds
        }
    }

    @Test
    fun `the second tap while the browser is coming up changes nothing`() {
        val latch = LeaveLatch()
        val launcher = Launcher()
        latch.leave(settled = true, action = launcher)
        latch.leave(settled = true, action = launcher)
        assertEquals("one browser, not two", 1, launcher.launches)
    }

    @Test
    fun `coming back to the screen makes the next tap the user's again`() {
        val latch = LeaveLatch()
        val launcher = Launcher()
        latch.leave(settled = true, action = launcher)
        latch.release()
        latch.leave(settled = true, action = launcher)
        assertEquals("a tap after the user returned must be honoured", 2, launcher.launches)
    }

    /**
     * A device with no app for the URL: nothing happened on screen, so the control must stay live
     * rather than go quietly dead until the user walks out of the screen and back.
     */
    @Test
    fun `a launch that reported nothing happened leaves the control live`() {
        val latch = LeaveLatch()
        val launcher = Launcher(succeeds = false)
        latch.leave(settled = true, action = launcher)
        latch.leave(settled = true, action = launcher)
        assertEquals("a failed hand-off must not arm the latch", 2, launcher.launches)
    }

    /** Mid-transition, or a screen already navigated away from: the tap is not for this screen. */
    @Test
    fun `a screen that is not settled hands nothing off`() {
        val latch = LeaveLatch()
        val launcher = Launcher()
        latch.leave(settled = false, action = launcher)
        assertEquals("an unsettled screen must not launch", 0, launcher.launches)
    }
}
