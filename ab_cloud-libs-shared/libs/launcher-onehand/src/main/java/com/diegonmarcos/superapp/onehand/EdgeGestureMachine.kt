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
 *   PENDING --inward swipe past the push threshold--> MENU   (a plain edge swipe opens it too)
 *   PENDING --moved past slop, NOT inward, before hold--> PASS (a scroll/sweep: not ours until the finger lifts)
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
            // A direct inward swipe opens the menu WITHOUT the hold. Requiring a still hold first was the
            // 2026-10-16 regression (ddf8e01a2): on Samsung a thumb held still on the curved edge is
            // grip-rejected by the touch firmware, and a natural swipe moved past slop inside the hold time,
            // went to PASS and was ignored for the rest of the stream, so the menu never opened on a phone.
            State.PENDING -> {
                val d = hypot(dx, dy)
                if (inward(edge, dx, dy) && d > pushPx) state = State.MENU
                else if (d > slopPx && !inward(edge, dx, dy)) state = State.PASS
            }
            State.ARMED -> if (inward(edge, dx, dy) && hypot(dx, dy) > pushPx) state = State.MENU
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

    companion object {
        /** Widest tilt from straight-inward that still counts as "into the screen" (the outermost sector edge). */
        const val MAX_INWARD_TILT_DEG = 72.0

        /** True when the drag points into the screen from [edge] (any sector, including the gaps between them). */
        fun inward(edge: OneHandConfig.Edge, dx: Float, dy: Float): Boolean {
            val (inw, lat) = when (edge) {
                OneHandConfig.Edge.RIGHT -> -dx to dy
                OneHandConfig.Edge.LEFT -> dx to dy
                OneHandConfig.Edge.BOTTOM -> -dy to dx
            }
            if (inw <= 0f) return false
            return Math.toDegrees(kotlin.math.atan2(kotlin.math.abs(lat).toDouble(), inw.toDouble())) <= MAX_INWARD_TILT_DEG
        }

        /** A tap lasts well under this; a hold shorter than it would claim taps. */
        const val MIN_HOLD_MS = 150L

        /**
         * Hold time = the build.json value ([configuredMs], > 0) capped by the system long-press time. The
         * system value alone is wrong for the owner: Accessibility "touch and hold delay" can make it 1.5 s,
         * and the menu then feels dead. Never below [MIN_HOLD_MS], so a tap still passes.
         */
        fun holdMs(systemMs: Long, configuredMs: Int): Long =
            (if (configuredMs > 0) minOf(systemMs, configuredMs.toLong()) else systemMs).coerceAtLeast(MIN_HOLD_MS)

        /** A thumb resting on a 24dp strip rolls; allow twice the platform slop before the touch is a sweep. */
        fun slopPx(touchSlopPx: Float): Float = touchSlopPx * 2f
    }
}
