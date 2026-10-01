package com.diegonmarcos.superapp.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One drag from the Configs star to Store must open Store and nothing else.
 *
 * Reported: One-hand > Configs star > Store also opened the last app used
 * before it. ShellActivity.dispatchTouchEvent feeds EVERY event to the home
 * swipe detector before the views get it; the drag from the star out to Store
 * is a fast move to the right on the Home root, which the detector maps to
 * home_swipes.swipe_right = open_last_android_app. The star then received the
 * same release and opened Store. Two dispatches for one gesture.
 *
 * This replays that gesture through the same order ShellActivity uses: gate,
 * then detector, then the star. The detector stand-in fires on release exactly
 * when ShellActivity's onFling would for a horizontal move (|dx| past the
 * minimum and dominant over |dy|).
 */
class StarGestureGateTest {

    private data class Ev(val down: Boolean, val up: Boolean, val x: Float, val y: Float)

    // Star at the bottom centre of a 1080-wide screen, as on the phone.
    private val star = floatArrayOf(500f, 1700f, 580f, 1780f)
    private fun onStar(x: Float, y: Float) = x >= star[0] && x < star[2] && y >= star[1] && y < star[3]

    /** Runs one gesture; returns every destination it opened, in order. */
    private fun play(gate: StarGestureGate, gesture: List<Ev>): List<String> {
        val opened = mutableListOf<String>()
        var downX = 0f; var downY = 0f; var detectorSawDown = false
        var starPressed = false
        for (e in gesture) {
            if (gate.admits(e.down, e.x, e.y)) {
                if (e.down) { downX = e.x; downY = e.y; detectorSawDown = true }
                if (e.up && detectorSawDown) {
                    val dx = e.x - downX; val dy = e.y - downY
                    if (Math.abs(dx) >= 250f && Math.abs(dx) >= Math.abs(dy) * 1.4f)
                        opened += if (dx > 0) "open_last_android_app" else "open_last_superapp_page"
                }
            }
            if (e.down) starPressed = onStar(e.x, e.y)
            if (e.up && starPressed) opened += "page:config/store"
        }
        return opened
    }

    private val dragToStore = listOf(
        Ev(down = true, up = false, x = 540f, y = 1740f),
        Ev(down = false, up = false, x = 700f, y = 1700f),
        Ev(down = false, up = true, x = 860f, y = 1660f),
    )

    @Test fun `drag from the Configs star to Store opens Store only`() {
        assertEquals(listOf("page:config/store"), play(StarGestureGate(::onStar), dragToStore))
    }

    @Test fun `a swipe that starts off the stars still reaches the home swipes`() {
        val gate = StarGestureGate(::onStar)
        // A star gesture first: its ownership must end with it.
        play(gate, dragToStore)
        val swipe = listOf(
            Ev(down = true, up = false, x = 200f, y = 900f),
            Ev(down = false, up = false, x = 400f, y = 900f),
            Ev(down = false, up = true, x = 700f, y = 920f),
        )
        assertEquals(listOf("open_last_android_app"), play(gate, swipe))
    }
}
