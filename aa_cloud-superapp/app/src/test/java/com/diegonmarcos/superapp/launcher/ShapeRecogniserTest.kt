package com.diegonmarcos.superapp.launcher

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The drawn-shape recogniser against the DECLARED templates (build.json onehand.shape_gestures):
 *   R1 a circle is accepted whatever its size, start point, direction, tilt or slight oval; it is "circle", not "c"
 *   R2 a "C" is accepted at any size and within the declared tilt; it is "c", not "circle"
 *   R3 a line, a zigzag, an S, a U and a half circle are rejected
 *   R4 a long straight stroke is a SWIPE and a long curved one a SHAPE, so the two never conflict
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShapeRecogniserTest {
    private val cfg get() = ShapeRecogniser.declared

    /** An arc from [a0] to [a1] degrees (screen coordinates, y down), [n] points, radii [rx]/[ry], tilted by [tilt] degrees. */
    private fun arc(a0: Double, a1: Double, rx: Float = 1f, ry: Float = 1f, tilt: Double = 0.0, scale: Float = 100f, n: Int = 40): List<Pair<Float, Float>> {
        val t = tilt * PI / 180
        return (0..n).map { i ->
            val a = (a0 + (a1 - a0) * i / n) * PI / 180
            val x = rx * cos(a); val y = -ry * sin(a)
            ((x * cos(t) - y * sin(t)).toFloat() * scale + 500f) to ((x * sin(t) + y * cos(t)).toFloat() * scale + 900f)
        }
    }

    private fun id(p: List<Pair<Float, Float>>) = ShapeRecogniser.recognise(p, cfg)?.id

    @Test fun `R1 circle is accepted at any size start direction and tilt`() {
        assertEquals("circle", id(arc(0.0, 360.0)))
        assertEquals("circle", id(arc(90.0, -270.0)))            // other start, other direction
        assertEquals("circle", id(arc(100.0, 460.0, scale = 40f)))   // small, overshoots the start
        assertEquals("circle", id(arc(0.0, 360.0, scale = 400f)))    // large
        assertEquals("circle", id(arc(30.0, 390.0, ry = 0.75f)))     // oval
        assertEquals("circle", id(arc(0.0, 360.0, tilt = 40.0)))     // a circle has no tilt
    }

    @Test fun `R2 C is accepted at any size and within the declared tilt`() {
        assertEquals("c", id(arc(45.0, 315.0)))
        assertEquals("c", id(arc(45.0, 315.0, scale = 40f)))
        assertEquals("c", id(arc(45.0, 315.0, scale = 400f)))
        assertEquals("c", id(arc(45.0, 315.0, tilt = 15.0)))
        assertEquals("c", id(arc(45.0, 315.0, tilt = -25.0)))
        assertEquals("c", id(arc(60.0, 300.0)))
    }

    @Test fun `R3 lines zigzags and other shapes are rejected`() {
        assertNull(id(listOf(0f to 0f, 100f to 5f, 200f to 0f)))
        assertNull(id(listOf(500f to 900f, 500f to 600f, 505f to 300f)))
        assertNull(id(listOf(0f to 0f, 100f to 200f, 200f to 0f, 300f to 200f, 400f to 0f, 500f to 200f, 600f to 0f)))
        assertNull("U", id(arc(180.0, 360.0)))
        assertNull("half circle", id(arc(90.0, 270.0)))
        assertNull("S", id(arc(90.0, 450.0, n = 40).take(20) + arc(90.0, -270.0, n = 40).drop(20).map { it.first + 100f to it.second - 200f }))
        assertNull("a dot", id(listOf(5f to 5f, 5f to 5f, 5f to 5f)))
    }

    @Test fun `R4 straight strokes are swipes and long curved ones are shapes`() {
        val d = 2f
        assertEquals(ShapeRecogniser.Kind.SWIPE, ShapeRecogniser.classify((0..8).map { 500f to 900f - it * 60f }, cfg, d))
        assertEquals(ShapeRecogniser.Kind.SHAPE, ShapeRecogniser.classify(arc(0.0, 360.0), cfg, d))
        assertEquals(ShapeRecogniser.Kind.SHAPE, ShapeRecogniser.classify(arc(45.0, 315.0), cfg, d))
        // a tiny circle is under the declared minimum length: not a shape
        assertEquals(ShapeRecogniser.Kind.SWIPE, ShapeRecogniser.classify(arc(0.0, 360.0, scale = 10f), cfg, d))
        assertNotNull(cfg.templates.firstOrNull { it.id == "circle" })
        assertTrue(cfg.minLengthDp > 0f && cfg.maxStraightness in 0.1f..0.9f)
    }
}
