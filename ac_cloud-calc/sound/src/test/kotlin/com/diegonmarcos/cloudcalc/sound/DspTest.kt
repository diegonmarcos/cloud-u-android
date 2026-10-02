package com.diegonmarcos.cloudcalc.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** The sound meter's maths against reference values (IEC 61672-1 A-weighting table, dBFS). */
class DspTest {
    private val sr = 44100
    private val n = 4096

    /** A sine exactly on FFT bin [bin], at [amp] of full scale. */
    private fun sine(bin: Int, amp: Double) = ShortArray(n) { (amp * 32767 * sin(2 * PI * bin * it / n)).toInt().toShort() }

    @Test fun `a full-scale sine is 0 dBFS and half scale is -6 dB`() {
        assertEquals(0.0, Dsp.levelDbfs(sine(93, 1.0)), 0.01)
        assertEquals(-6.02, Dsp.levelDbfs(sine(93, 0.5)), 0.02)
        assertEquals(Dsp.FLOOR_DB, Dsp.levelDbfs(ShortArray(n)), 0.0)
    }

    @Test fun `the fft finds a sine on its bin at its level`() {
        val s = Dsp.spectrumDb(sine(93, 1.0), n)
        assertEquals(n / 2, s.size)
        assertEquals(0.0, s[93], 0.05)
        assertEquals(93.0 * sr / n, Dsp.peakHz(s, sr), 1e-9)
        assertTrue("leakage two bins away", s[96] < -40)
    }

    @Test fun `fft of an impulse is flat`() {
        val re = DoubleArray(8).also { it[0] = 1.0 }
        val im = DoubleArray(8)
        Dsp.fft(re, im)
        re.forEach { assertEquals(1.0, it, 1e-12) }
        im.forEach { assertEquals(0.0, it, 1e-12) }
    }

    @Test fun `A-weighting matches the IEC 61672-1 table`() {
        assertEquals(0.0, Dsp.aWeightingDb(1000.0), 0.05)
        assertEquals(-19.1, Dsp.aWeightingDb(100.0), 0.1)
        assertEquals(-2.5, Dsp.aWeightingDb(10000.0), 0.1)
        assertEquals(-39.4, Dsp.aWeightingDb(31.5), 0.2)
    }

    @Test fun `A-weighted level keeps 1 kHz and drops 100 Hz by 19 dB`() {
        val k1 = (1000.0 * n / sr).toInt()
        val s1 = sine(k1, 1.0)
        val l1 = Dsp.levelDbfs(s1)
        assertEquals(l1 + Dsp.aWeightingDb(k1.toDouble() * sr / n), Dsp.aWeightedDbfs(l1, Dsp.spectrumDb(s1, n), sr), 0.3)
        val k2 = (100.0 * n / sr).toInt()
        val s2 = sine(k2, 1.0)
        val l2 = Dsp.levelDbfs(s2)
        assertEquals(l2 - 19.1, Dsp.aWeightedDbfs(l2, Dsp.spectrumDb(s2, n), sr), 1.5)
    }
}
