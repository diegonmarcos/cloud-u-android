package com.diegonmarcos.superapp.launcher

/**
 * Decides, per touch event, whether the activity-level home-swipe detector may
 * see it. A gesture that STARTS on a home star is the star's for its whole
 * length (press, drag, release); everything else still reaches the detector.
 *
 * Decided once on ACTION_DOWN and held until the next one, because the drag
 * leaves the star: the release over Store is nowhere near it, so asking per
 * event would hand the tail of the gesture back to the detector.
 *
 * [onStar] is the hit-test in window coordinates; it stays outside so this is
 * plain Kotlin and the JVM test drives it without views.
 */
class StarGestureGate(private val onStar: (x: Float, y: Float) -> Boolean) {
    private var starOwned = false

    fun admits(isDown: Boolean, x: Float, y: Float): Boolean {
        if (isDown) starOwned = onStar(x, y)
        return !starOwned
    }
}
