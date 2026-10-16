package com.diegonmarcos.superapp.onehand

import kotlin.math.hypot

/**
 * PURE tap-hold-and-push state machine for an edge handle (no Android deps, JVM-testable).
 *
 * The edge menu must react ONLY to "hold on the edge, then push inward". A tap, or a drag that starts
 * moving before the hold time has passed, is not ours: [claimed] stays false, nothing is drawn, nothing
 * fires. (A separate overlay window cannot hand a consumed touch stream back to the app below without an
 * Accessibility service, which this library deliberately does not use, so the handle is also kept
 * very thin - see [EdgeHandleWindow]; this machine guarantees we never ACT on a tap.)
 *
 *   IDLE --down--> PENDING --hold elapsed--> ARMED --inward push past threshold--> MENU
 *   PENDING --moved past slop before hold--> PASS (not ours until the finger lifts)
 *   MENU --up--> [Outcome.FIRE]; ARMED --up--> [Outcome.NONE]; PENDING/PASS --up--> [Outcome.TAP]
 */
class EdgeGestureMachine(
    private val edge: OneHandConfig.Edge,
    private val holdMs: Long,
    private val slopPx: Float,
    private val pushPx: Float,
) {
    enum class State { IDLE, PENDING, PASS, ARMED, MENU }
    enum class Outcome { TAP, NONE, FIRE }

    var state = State.IDLE
        private set
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    /** True once the user has held long enough: only then is the gesture ours. */
    val claimed: Boolean get() = state == State.ARMED || state == State.MENU

    fun down(x: Float, y: Float, t: Long) { downX = x; downY = y; downT = t; state = State.PENDING }

    /** Advance the hold clock (call from a timer and from every move). Returns true on the PENDING->ARMED edge. */
    fun tick(t: Long): Boolean {
        if (state == State.PENDING && t - downT >= holdMs) { state = State.ARMED; return true }
        return false
    }

    fun move(x: Float, y: Float, t: Long): State {
        tick(t)
        val dx = x - downX; val dy = y - downY
        when (state) {
            State.PENDING -> if (hypot(dx, dy) > slopPx) state = State.PASS
            State.ARMED -> if (SwipeClassifier.sector(edge, dx, dy) != null && hypot(dx, dy) > pushPx) state = State.MENU
            else -> {}
        }
        return state
    }

    fun up(t: Long): Outcome {
        tick(t)
        val o = when (state) {
            State.MENU -> Outcome.FIRE
            State.ARMED -> Outcome.NONE
            else -> Outcome.TAP
        }
        state = State.IDLE
        return o
    }

    fun cancel() { state = State.IDLE }
}
