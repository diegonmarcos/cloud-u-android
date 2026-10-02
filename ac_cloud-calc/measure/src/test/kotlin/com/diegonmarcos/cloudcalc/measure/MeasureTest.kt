package com.diegonmarcos.cloudcalc.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** #772 the Camera tools' maths against golden geometry: every expected value is a known shape. */
class MeasureTest {
    private val g = 9.81

    // ── reference-object scaling ────────────────────────────────────────────────────────────

    @Test fun `a bank card's long edge scales the photo, and a marked length reads in millimetres`() {
        // An ID-1 card (85.60 mm) is 428 px long on this photo: 0.2 mm per pixel.
        val mmPerPx = Reference.mmPerPx(Px(100.0, 200.0), Px(528.0, 200.0), 85.60)
        assertEquals(0.2, mmPerPx, 1e-12)
        val r = Reference.measure(listOf(Px(0.0, 0.0), Px(300.0, 400.0)), mmPerPx)
        assertEquals(100.0, r.lengthMm, 1e-9)
        assertEquals(100.0, r.pathMm, 1e-9)
        assertNull(r.areaMm2)
    }

    @Test fun `a path sums its legs and a closed shape has its shoelace area`() {
        val square = listOf(Px(0.0, 0.0), Px(100.0, 0.0), Px(100.0, 100.0), Px(0.0, 100.0))
        val r = Reference.measure(square, 0.5)
        assertEquals("straight first-to-last mark, not the diagonal", 50.0, r.lengthMm, 1e-9)
        assertEquals(150.0, r.pathMm, 1e-9)
        assertEquals(2500.0, r.areaMm2!!, 1e-9)
        assertEquals(10000.0, Reference.polygonArea(square.reversed()), 1e-9)
        assertEquals(6.0, Reference.polygonArea(listOf(Px(0.0, 0.0), Px(4.0, 0.0), Px(0.0, 3.0))), 1e-12)
        assertEquals(0.0, Reference.polygonArea(square.take(2)), 0.0)
        assertEquals(0.0, Reference.pathLength(listOf(Px(1.0, 1.0))), 0.0)
        assertEquals(5.0, Reference.distance(Px(1.0, 1.0), Px(4.0, 5.0)), 1e-12)
    }

    @Test fun `scaling refuses what cannot be a reference`() {
        for ((why, run) in listOf<Pair<String, () -> Unit>>(
            "same point" to { Reference.mmPerPx(Px(1.0, 1.0), Px(1.0, 1.0), 10.0) },
            "zero length" to { Reference.mmPerPx(Px(0.0, 0.0), Px(1.0, 1.0), 0.0) },
            "negative length" to { Reference.mmPerPx(Px(0.0, 0.0), Px(1.0, 1.0), -1.0) },
            "one mark" to { Reference.measure(listOf(Px(0.0, 0.0)), 1.0) },
        )) {
            try { run(); fail("accepted $why") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.isNotBlank()) }
        }
    }

    // ── the AR route's geometry ─────────────────────────────────────────────────────────────

    @Test fun `distance, path and height between world anchors`() {
        val a = Vec(0.0, 0.0, 0.0); val b = Vec(1.0, 2.0, 2.0); val c = Vec(1.0, 2.0, 5.0)
        assertEquals(3.0, Space.distance(a, b), 1e-12)
        assertEquals(6.0, Space.pathLength(listOf(a, b, c)), 1e-12)
        assertEquals(2.0, Space.height(a, b), 0.0)
        assertEquals(2.0, Space.height(b, a), 0.0)
        assertEquals(0.0, Space.pathLength(listOf(a)), 0.0)
    }

    @Test fun `a 2 by 3 rectangle has area 6 on the floor, on a wall and tilted 30 degrees`() {
        val floor = listOf(Vec(0.0, 0.0, 0.0), Vec(2.0, 0.0, 0.0), Vec(2.0, 0.0, 3.0), Vec(0.0, 0.0, 3.0))
        assertEquals(6.0, Space.polygonArea(floor), 1e-12)
        val wall = listOf(Vec(0.0, 0.0, 0.0), Vec(2.0, 0.0, 0.0), Vec(2.0, 3.0, 0.0), Vec(0.0, 3.0, 0.0))
        assertEquals(6.0, Space.polygonArea(wall), 1e-12)
        val side = listOf(Vec(0.0, 0.0, 0.0), Vec(0.0, 0.0, 2.0), Vec(0.0, 3.0, 2.0), Vec(0.0, 3.0, 0.0))
        assertEquals(6.0, Space.polygonArea(side), 1e-12)
        val t = Math.toRadians(30.0)
        val tilted = floor.map { Vec(it.x, it.z * sin(t), it.z * cos(t)) }
        assertEquals(6.0, Space.polygonArea(tilted), 1e-9)
        assertEquals(0.0, Space.polygonArea(floor.take(2)), 0.0)
        assertEquals(1.0, Space.polygonArea(listOf(Vec(0.0, 0.0, 0.0), Vec(2.0, 0.0, 0.0), Vec(0.0, 0.0, 1.0))), 1e-12)
    }

    // ── the inclinometer ────────────────────────────────────────────────────────────────────

    @Test fun `flat face up is level, standing is 90 degrees of tilt`() {
        val flat = Level.tilt(0.0, 0.0, g)!!
        assertEquals(0.0, flat.tiltDeg, 1e-9)
        assertEquals(0.0, flat.pitchDeg, 1e-9)
        assertEquals(0.0, flat.rollDeg, 1e-9)
        assertTrue(Level.isLevel(flat, 1.0))
        val standing = Level.tilt(0.0, g, 0.0)!!
        assertEquals(90.0, standing.tiltDeg, 1e-9)
        assertEquals(90.0, standing.pitchDeg, 1e-9)
        assertEquals(0.0, standing.edgeDeg, 1e-9)
        assertFalse(Level.isLevel(standing, 1.0))
        assertEquals(180.0, Level.tilt(0.0, 0.0, -g)!!.tiltDeg, 1e-9)
    }

    @Test fun `known angles read back - 10 degrees of pitch, 5 of roll, 12 on an edge`() {
        val p = Math.toRadians(10.0)
        assertEquals(10.0, Level.tilt(0.0, g * sin(p), g * cos(p))!!.pitchDeg, 1e-9)
        assertEquals(10.0, Level.tilt(0.0, g * sin(p), g * cos(p))!!.tiltDeg, 1e-9)
        val r = Math.toRadians(5.0)
        val rolled = Level.tilt(g * sin(r), 0.0, g * cos(r))!!
        assertEquals(5.0, rolled.rollDeg, 1e-9)
        assertEquals(0.0, rolled.pitchDeg, 1e-9)
        assertTrue(Level.isLevel(rolled, 5.0))
        assertFalse(Level.isLevel(rolled, 4.9))
        val e = Math.toRadians(12.0)
        assertEquals(12.0, Level.tilt(g * sin(e), g * cos(e), 0.0)!!.edgeDeg, 1e-9)
        assertEquals(-12.0, Level.tilt(-g * sin(e), g * cos(e), 0.0)!!.edgeDeg, 1e-9)
    }

    @Test fun `no gravity is no reading, and the zero is subtracted and wrapped`() {
        assertNull(Level.tilt(0.0, 0.0, 0.5))
        assertNull(Level.tilt(0.0, 0.0, 0.0))
        val zero = Level.Tilt(2.0, 1.0, -1.0, 179.0)
        val now = Level.Tilt(5.0, 4.0, 1.0, -179.0)
        val rel = Level.relative(now, zero)
        assertEquals(3.0, rel.tiltDeg, 1e-12)
        assertEquals(3.0, rel.pitchDeg, 1e-12)
        assertEquals(2.0, rel.rollDeg, 1e-12)
        assertEquals(2.0, rel.edgeDeg, 1e-12)
        assertEquals(180.0, Level.wrap(180.0), 0.0)
        assertEquals(180.0, Level.wrap(-180.0), 0.0)
        assertEquals(-170.0, Level.wrap(190.0), 0.0)
        assertEquals(10.0, Level.wrap(370.0), 0.0)
        assertEquals(-10.0, Level.wrap(-370.0), 0.0)
    }

    // ── OCR numbers for the calculator ──────────────────────────────────────────────────────

    @Test fun `numbers read off a receipt, in order, with the decimal mark the calculator takes`() {
        assertEquals(listOf("12.50"), Numbers.of("TOTAL 12,50 EUR"))
        assertEquals(listOf("1234.50"), Numbers.of("Amount: 1,234.50"))
        assertEquals(listOf("1234.50"), Numbers.of("Importe 1.234,50 €"))
        assertEquals(listOf("1234567.89"), Numbers.of("1.234.567,89"))
        assertEquals(listOf("1234567"), Numbers.of("1,234,567"))
        assertEquals(listOf("1234"), Numbers.of("1,234"))
        assertEquals(listOf("3.14159"), Numbers.of("pi 3.14159"))
        assertEquals(listOf("0.125", "0.500"), Numbers.of("0.125 and 0,500"))
        assertEquals(listOf("-7.5"), Numbers.of("delta -7.5 K"))
        assertEquals(listOf("2026", "10", "02"), Numbers.of("2026-10-02"))
        assertEquals(listOf("42", "7"), Numbers.of("x 42 y 7"))
        assertTrue(Numbers.of("no digits here").isEmpty())
        assertEquals("12.50 + 3 + 4.75", Numbers.sum(listOf("12.50", "3", "4.75")))
        assertEquals("", Numbers.sum(emptyList()))
    }
}
