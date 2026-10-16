package com.diegonmarcos.superapp.onehand

import com.diegonmarcos.superapp.onehand.EdgeGestureMachine.Outcome
import com.diegonmarcos.superapp.onehand.EdgeGestureMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tap-hold-and-push: a tap never claims the gesture; only hold, then an inward push, opens the menu. */
class EdgeGestureMachineTest {
    private fun m() = EdgeGestureMachine(OneHandConfig.Edge.RIGHT, holdMs = 400, slopPx = 8f, pushPx = 24f)

    @Test fun quickTapIsNotClaimedAndFiresNothing() {
        val g = m(); g.down(1000f, 500f, 0)
        assertFalse(g.claimed)
        assertEquals(Outcome.TAP, g.up(120))
        assertEquals(State.IDLE, g.state)
    }

    @Test fun longPressWithoutPushDoesNothing() {
        val g = m(); g.down(1000f, 500f, 0)
        g.move(1000f, 501f, 500)
        assertEquals(State.ARMED, g.state)
        assertEquals(Outcome.NONE, g.up(900))
    }

    @Test fun pushBeforeHoldIsPassThroughNeverMenu() {
        val g = m(); g.down(1000f, 500f, 0)
        assertEquals(State.PASS, g.move(900f, 500f, 100)) // inward 100px, but only 100ms in
        assertEquals(State.PASS, g.move(800f, 500f, 900)) // staying down later does not revive it
        assertFalse(g.claimed)
        assertEquals(Outcome.TAP, g.up(950))
    }

    @Test fun holdThenInwardPushOpensMenuAndReleaseFires() {
        val g = m(); g.down(1000f, 500f, 0)
        g.tick(450); assertEquals(State.ARMED, g.state); assertFalse(g.state == State.MENU)
        assertEquals(State.ARMED, g.move(990f, 500f, 460)) // 10px < push threshold
        assertEquals(State.MENU, g.move(950f, 500f, 480))  // 50px inward
        assertTrue(g.claimed)
        assertEquals(Outcome.FIRE, g.up(700))
    }

    @Test fun holdThenOutwardDragDoesNotOpenMenu() {
        val g = m(); g.down(1000f, 500f, 0)
        assertEquals(State.ARMED, g.move(1100f, 500f, 500)) // outward = no sector
    }

    @Test fun cancelResets() {
        val g = m(); g.down(1000f, 500f, 0); g.tick(500); g.cancel()
        assertEquals(State.IDLE, g.state); assertFalse(g.claimed)
    }

    @Test fun holdTimeUsesTheConfiguredValueNotAnAccessibilityStretchedSystemOne() {
        assertEquals(300L, EdgeGestureMachine.holdMs(systemMs = 1500, configuredMs = 300))
        assertEquals(400L, EdgeGestureMachine.holdMs(systemMs = 400, configuredMs = 600)) // never slower than system
        assertEquals(400L, EdgeGestureMachine.holdMs(systemMs = 400, configuredMs = 0))   // unset: system
        assertEquals(EdgeGestureMachine.MIN_HOLD_MS, EdgeGestureMachine.holdMs(400, 20))  // a tap must still pass
    }

    @Test fun slightRollOfTheThumbDuringTheHoldStillArms() {
        val slop = EdgeGestureMachine.slopPx(8f)
        val g = EdgeGestureMachine(OneHandConfig.Edge.LEFT, holdMs = 300, slopPx = slop, pushPx = 24f)
        g.down(5f, 500f, 0)
        assertEquals(State.PENDING, g.move(5f, 500f + 12f, 100)) // 12px roll < 16px slop
        assertEquals(State.ARMED, g.move(5f, 512f, 320))
        assertEquals(State.MENU, g.move(60f, 512f, 360))         // straight inward = centre sector
        assertEquals(Outcome.FIRE, g.up(400))
    }

    @Test fun leftEdgeDiagonalPushOpensMenu() {
        val g = EdgeGestureMachine(OneHandConfig.Edge.LEFT, holdMs = 300, slopPx = 16f, pushPx = 24f)
        g.down(5f, 500f, 0); g.tick(300)
        assertEquals(State.MENU, g.move(5f + 40f, 500f + 40f * Math.tan(Math.toRadians(40.0)).toFloat(), 400))
    }

    @Test fun bottomEdgeUpwardPushOpensMenu() {
        val g = EdgeGestureMachine(OneHandConfig.Edge.BOTTOM, holdMs = 300, slopPx = 16f, pushPx = 24f)
        g.down(500f, 2390f, 0); g.tick(300)
        assertEquals(State.MENU, g.move(500f, 2390f - 60f, 400))
    }
}
