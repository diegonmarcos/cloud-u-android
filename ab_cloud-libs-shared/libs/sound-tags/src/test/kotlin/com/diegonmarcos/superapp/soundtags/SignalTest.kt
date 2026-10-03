package com.diegonmarcos.superapp.soundtags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.fail
import org.junit.Test

class SignalTest {
    @Test fun sameRateIsACopy() {
        val x = floatArrayOf(1f, 2f)
        val y = Signal.resample(x, 16000, 16000)
        assertArrayEquals(x, y, 0f)
        assertNotSame(x, y)
        assertEquals(0, Signal.resample(FloatArray(0), 48000, 16000).size)
    }

    @Test fun downsamplingAveragesEachSpan() {
        val y = Signal.resample(floatArrayOf(0f, 3f, 6f, 9f, 12f, 15f, 1f), 48000, 16000)
        assertArrayEquals(floatArrayOf(3f, 12f), y, 1e-6f)
        // a non-integer ratio still covers the input: 44.1 kHz -> 16 kHz of a constant stays constant
        val c = Signal.resample(FloatArray(441) { 0.25f }, 44100, 16000)
        assertEquals(160, c.size)
        c.forEach { assertEquals(0.25f, it, 1e-6f) }
    }

    @Test fun downsamplingNeverEmptiesAShortClip() {
        assertArrayEquals(floatArrayOf(4f), Signal.resample(floatArrayOf(4f), 48000, 16000), 0f)
    }

    @Test fun upsamplingInterpolates() {
        val y = Signal.resample(floatArrayOf(0f, 2f, 4f), 8000, 16000)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f, 4f, 4f), y, 1e-6f)
    }

    @Test fun ratesMustBePositive() {
        for ((a, b) in listOf(0 to 16000, 16000 to 0, -1 to 16000)) {
            try { Signal.resample(FloatArray(4), a, b); fail("$a -> $b") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun aShortClipIsOnePaddedWindow() {
        val w = Signal.windows(floatArrayOf(1f, 2f, 3f), 5, 2)
        assertEquals(1, w.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 0f, 0f), w[0], 0f)
        assertEquals(1, Signal.windows(FloatArray(0), 4, 2).size)
    }

    @Test fun windowsHopWhileHalfOfTheWindowIsAudio() {
        val x = FloatArray(10) { it.toFloat() }
        val w = Signal.windows(x, 4, 3)
        // starts 0, 3, 6 (6+2 < 10), 9 is out (9+2 >= 10)
        assertEquals(3, w.size)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), w[1], 0f)
        assertArrayEquals(floatArrayOf(6f, 7f, 8f, 9f), w[2], 0f)
        assertEquals(1, Signal.windows(FloatArray(4), 4, 2).size)
        assertEquals(2, Signal.windows(FloatArray(5), 4, 2).size)
    }

    @Test fun windowArgumentsMustBePositive() {
        for ((s, h) in listOf(0 to 1, 1 to 0)) {
            try { Signal.windows(FloatArray(4), s, h); fail("$s/$h") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun peakIsTheLargestMagnitude() {
        assertEquals(0.75f, Signal.peak(floatArrayOf(0.1f, -0.75f, 0.5f)), 0f)
        assertEquals(0f, Signal.peak(FloatArray(0)), 0f)
    }
}
