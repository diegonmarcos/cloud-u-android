package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.appstore.ProgressBarModel.Draw
import org.junit.Assert.assertEquals
import org.junit.Test

/** #894 The Store bar: indeterminate only until the first byte count, then determinate, never back. */
class ProgressBarModelTest {

    private val indeterminate0 = Draw(true, 0)

    @Test fun `no byte yet - indeterminate`() {
        val m = ProgressBarModel()
        assertEquals(indeterminate0, m.step("a", 0, 0, false))
        assertEquals(indeterminate0, m.step("a", 0, -1, false))
    }

    @Test fun `bytes with no known total stay indeterminate, never a made-up percentage`() {
        val m = ProgressBarModel()
        assertEquals(indeterminate0, m.step("a", 500_000, -1, false))
        assertEquals(Draw(false, 5), m.step("a", 600_000, 5, false))
    }

    @Test fun `first byte count makes it determinate`() =
        assertEquals(Draw(false, 7), ProgressBarModel().apply { step("a", 0, 0, false) }.step("a", 1000, 7, false))

    @Test fun `a state without a percent never turns it indeterminate again`() {
        val m = ProgressBarModel()
        m.step("a", 5000, 40, false)
        assertEquals(Draw(false, 40), m.step("a", 0, -1, false))      // installing / checking
        assertEquals(Draw(false, 40), m.step("", 0, -1, false))       // the chain's phase line names no item
    }

    @Test fun `a restarted download does not move the bar back`() {
        val m = ProgressBarModel()
        m.step("a", 9000, 60, false)
        assertEquals(Draw(false, 60), m.step("a", 0, 0, false))       // next source opens at 0
        assertEquals(Draw(false, 60), m.step("a", 100, 2, false))
        assertEquals(Draw(false, 75), m.step("a", 9500, 75, false))
    }

    @Test fun `the next item starts over, indeterminate`() {
        val m = ProgressBarModel()
        m.step("a", 9000, 100, false)
        assertEquals(indeterminate0, m.step("b", 0, 0, false))
        assertEquals(Draw(false, 3), m.step("b", 300, 3, false))
    }

    @Test fun `a failure is empty and determinate and the next item starts over`() {
        val m = ProgressBarModel()
        m.step("a", 9000, 50, false)
        assertEquals(Draw(false, 0), m.step("a", 0, -1, true))
        assertEquals(indeterminate0, m.step("a", 0, 0, false))
    }

    @Test fun `reset and complete`() {
        val m = ProgressBarModel()
        m.step("a", 9000, 50, false)
        m.reset()
        assertEquals(indeterminate0, m.step("a", 0, 0, false))
        assertEquals(Draw(false, 100), m.complete())
    }

    @Test fun `percent is clamped`() =
        assertEquals(Draw(false, 100), ProgressBarModel().step("a", 10, 250, false))
}
