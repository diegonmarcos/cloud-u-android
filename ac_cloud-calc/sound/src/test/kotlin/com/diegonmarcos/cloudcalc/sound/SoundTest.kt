package com.diegonmarcos.cloudcalc.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * #772 the sound tools' maths against golden signals: every signal is synthesised here (or by the
 * generator under test), so each expectation is a property of a known input — a 440 Hz sine IS
 * A4, a square wave's third harmonic IS 1/3 of its fundamental, five clicks 200 ms apart ARE a
 * 5 Hz train. Tolerances were set from a numpy mirror of the same algorithms.
 */
class SoundTest {
    private val sr = 44100
    private val p = Analysis.Params()

    private fun silence(ms: Int) = DoubleArray(sr * ms / 1000)
    private fun gen(kind: String, wave: String, f1: Double, f2: Double, ms: Int, amp: Double = 0.5, fade: Int = 5, colour: String = "white", log: Boolean = true) =
        Generator.render(Generator.Spec(kind, wave, f1, f2, amp, ms, colour, log), sr, fade)
    private fun tone(wave: String, f: Double, ms: Int, amp: Double = 0.5, fade: Int = 5) = gen(Generator.TONE, wave, f, 0.0, ms, amp, fade)
    private fun cat(vararg xs: DoubleArray): DoubleArray {
        val out = DoubleArray(xs.sumOf { it.size })
        var at = 0
        for (x in xs) { x.copyInto(out, at); at += x.size }
        return out
    }
    private fun pad(x: DoubleArray) = cat(silence(100), x, silence(100))
    private fun one(x: DoubleArray): Analysis.Event {
        val r = Analysis.analyze(x, sr, p)
        assertEquals("events in ${r.events.map { it.kind }}", 1, r.events.size)
        return r.events[0]
    }
    private fun clicks(starts: List<Int>): DoubleArray {
        val x = DoubleArray(sr)
        for (s in starts) for (i in s until s + 44) x[i] = 0.8
        return x
    }

    // ── pitch, note, tone ──────────────────────────────────────────────────────────────────

    @Test fun `a 440 Hz sine is one tone event at A4, where it was put`() {
        val r = Analysis.analyze(pad(tone("sine", 440.0, 500)), sr, p)
        assertEquals(440.0, r.f0!!, 0.5)
        val e = r.events.single()
        assertEquals(Analysis.TONE, e.kind)
        assertEquals(0.1, e.start, 0.01)
        assertEquals(0.5, e.duration, 0.012)
        assertEquals(440.0, e.f0!!, 0.5)
        assertEquals(440.0, e.peakHz, 1.0)
        assertTrue("a sine is narrow: ${e.bandwidth}", e.bandwidth < 50)
        assertTrue("a sine is not noisy: ${e.flatness}", e.flatness < 0.01)
        assertEquals(-6.02, e.peakDb, 0.3)
        val n = Notes.of(r.f0!!, 440.0)!!
        assertEquals("A4", n.label)
        assertEquals(69, n.midi)
        assertTrue("cents ${n.cents}", abs(n.cents) < 5)
    }

    @Test fun `YIN reads fundamentals across the range and refuses noise and silence`() {
        for (f in listOf(82.41, 261.63, 1000.0, 3000.0)) {
            val est = Pitch.yin(tone("sine", f, 100, fade = 0).copyOfRange(0, 2048), sr, p.fmin, p.fmax, p.yinThreshold)
            assertNotNull("no pitch for $f", est)
            assertEquals("f0 of $f", f, est!!.hz, f * 0.005)
            assertTrue("clarity ${est.clarity}", est.clarity > 0.85)
        }
        assertNull(Pitch.yin(gen(Generator.NOISE, "sine", 0.0, 0.0, 100).copyOfRange(0, 2048), sr, p.fmin, p.fmax, p.yinThreshold))
        assertNull(Pitch.yin(DoubleArray(2048), sr, p.fmin, p.fmax, p.yinThreshold))
        // A range the frame cannot hold is no estimate, not a crash.
        assertNull(Pitch.yin(DoubleArray(16), sr, p.fmin, p.fmax, p.yinThreshold))
    }

    @Test fun `notes name equal-tempered pitches against the tuning reference`() {
        assertEquals("C4", Notes.of(261.63, 440.0)!!.label)
        assertEquals("A#4", Notes.of(466.16, 440.0)!!.label)
        assertEquals("A3", Notes.of(220.0, 440.0)!!.label)
        assertEquals("C-1", Notes.of(8.1758, 440.0)!!.label)
        assertEquals(50.0, Notes.of(440.0 * Math.pow(2.0, 0.5 / 12), 440.0)!!.cents, 0.01)
        assertEquals("A4", Notes.of(432.0, 432.0)!!.label)
        assertEquals(432.0, Notes.of(432.0, 432.0)!!.hz, 1e-9)
        assertEquals(0.0, Notes.of(432.0, 432.0)!!.cents, 1e-9)
        assertNull(Notes.of(0.0, 440.0))
        assertNull(Notes.of(-5.0, 440.0))
        assertNull(Notes.of(Double.NaN, 440.0))
        assertNull(Notes.of(Double.POSITIVE_INFINITY, 440.0))
        assertNull(Notes.of(440.0, 0.0))
    }

    @Test fun `wavelength follows the speed of sound at the temperature`() {
        assertEquals(331.3, Air.speedOfSound(0.0), 1e-9)
        assertEquals(343.2, Air.speedOfSound(20.0), 0.1)
        assertEquals(1.0, Air.wavelength(343.0, 343.0)!!, 1e-12)
        assertEquals(0.78, Air.wavelength(440.0, 343.2)!!, 0.001)
        assertNull(Air.wavelength(0.0, 343.0))
        assertNull(Air.wavelength(-1.0, 343.0))
        assertNull(Air.wavelength(Double.POSITIVE_INFINITY, 343.0))
    }

    // ── harmonics ───────────────────────────────────────────────────────────────────────────

    @Test fun `a square wave has odd harmonics at 1 over n, a saw every harmonic, a triangle 1 over n squared`() {
        val sq = one(pad(tone("square", 220.0, 500))).harmonicsDb
        assertEquals(0.0, sq[0], 1e-9)
        assertTrue("square h2 ${sq[1]}", sq[1] < -30)
        assertEquals(-9.54, sq[2], 1.0)
        assertTrue("square h4 ${sq[3]}", sq[3] < -30)
        assertEquals(-13.98, sq[4], 1.0)
        val saw = one(pad(tone("saw", 220.0, 500))).harmonicsDb
        assertEquals(-6.02, saw[1], 1.2)
        assertEquals(-9.54, saw[2], 1.0)
        val tri = one(pad(tone("triangle", 220.0, 500))).harmonicsDb
        assertTrue("triangle h2 ${tri[1]}", tri[1] < -30)
        assertEquals(-19.08, tri[2], 1.0)
        assertEquals(p.harmonics, sq.size)
    }

    @Test fun `a pitch with no line of its own has no harmonics`() {
        // 350 + 440 Hz: YIN finds the ~88 Hz periodicity, but nothing sounds there.
        val e = one(pad(gen(Generator.DUAL, "sine", 350.0, 440.0, 500)))
        assertEquals(Analysis.TONE, e.kind)
        assertTrue(e.harmonicsDb.isEmpty())
        assertTrue(Analysis.harmonics(DoubleArray(1024) { 1.0 }, sr, null, 8).isEmpty())
    }

    // ── shapes: chirp, noise, impulse, complex ──────────────────────────────────────────────

    @Test fun `sweeps are chirps, up or down, logarithmic or linear`() {
        val up = one(pad(gen(Generator.SWEEP, "sine", 200.0, 2000.0, 1000)))
        assertEquals(Analysis.CHIRP, up.kind)
        assertTrue("up slope ${up.sweepHzPerS}", up.sweepHzPerS > 500)
        val down = one(pad(gen(Generator.SWEEP, "sine", 2000.0, 200.0, 1000)))
        assertEquals(Analysis.CHIRP, down.kind)
        assertTrue("down slope ${down.sweepHzPerS}", down.sweepHzPerS < -500)
        val lin = one(pad(gen(Generator.SWEEP, "sine", 300.0, 3000.0, 1000, log = false)))
        assertEquals(Analysis.CHIRP, lin.kind)
        assertEquals(2700.0, lin.sweepHzPerS, 300.0)
    }

    @Test fun `white and pink noise are noise, broad and unpitched`() {
        val w = Analysis.analyze(pad(gen(Generator.NOISE, "sine", 0.0, 0.0, 500, amp = 0.3)), sr, p)
        assertEquals(Analysis.NOISE, w.events.single().kind)
        assertTrue("white flatness ${w.events[0].flatness}", w.events[0].flatness > 0.8)
        assertTrue("white bandwidth ${w.events[0].bandwidth}", w.events[0].bandwidth > 4000)
        assertNull(w.events[0].f0)
        assertTrue(w.events[0].harmonicsDb.isEmpty())
        val pk = one(pad(gen(Generator.NOISE, "sine", 0.0, 0.0, 500, colour = "pink")))
        assertEquals(Analysis.NOISE, pk.kind)
        assertTrue("pink is less flat than white: ${pk.flatness}", pk.flatness < w.events[0].flatness)
    }

    @Test fun `a knock is an impulse that rings at its own pitch`() {
        val knock = DoubleArray((0.4 * sr).toInt()) { val t = it.toDouble() / sr; 0.8 * exp(-t / 0.03) * sin(2 * PI * 600 * t) }
        val e = one(pad(knock))
        assertEquals(Analysis.IMPULSE, e.kind)
        assertEquals(600.0, e.f0!!, 2.0)
        assertTrue("attack ${e.attackMs}", e.attackMs < 10)
        assertTrue("decay ${e.decayMs}", e.decayMs in 20.0..200.0)
    }

    @Test fun `five clicks 200 ms apart are a 5 Hz train of impulses`() {
        val r = Analysis.analyze(clicks((0 until 5).map { 4410 + it * 8820 }), sr, p)
        assertEquals(5, r.events.size)
        r.events.forEach { assertEquals(Analysis.IMPULSE, it.kind) }
        r.events.forEachIndexed { i, e -> assertEquals(0.1 + 0.2 * i, e.start, 0.01) }
        assertTrue(r.periodicity.regular)
        assertEquals(0.2, r.periodicity.periodS!!, 0.005)
        assertEquals(5.0, r.periodicity.rateHz!!, 0.15)
        assertEquals(5, r.periodicity.count)
        assertEquals(5, r.onsets.size)
    }

    @Test fun `uneven clicks are no period`() {
        val r = Analysis.analyze(clicks(listOf(4410, 9000, 22000, 26000, 40000)), sr, p)
        assertEquals(5, r.events.size)
        assertFalse(r.periodicity.regular)
        assertNull(r.periodicity.periodS)
        assertNull(r.periodicity.rateHz)
        assertFalse(Analysis.periodicity(r.events.take(2), p).regular)
    }

    @Test fun `two tones with a gap are two events, each at its own pitch, each an onset`() {
        val r = Analysis.analyze(cat(silence(100), tone("sine", 440.0, 200), silence(200), tone("sine", 880.0, 200), silence(100)), sr, p)
        assertEquals(2, r.events.size)
        assertEquals(440.0, r.events[0].f0!!, 1.0)
        assertEquals(880.0, r.events[1].f0!!, 2.0)
        assertEquals(0.1, r.events[0].start, 0.01)
        assertEquals(0.5, r.events[1].start, 0.01)
        assertEquals(2, r.onsets.size)
        assertEquals(0.1, r.onsets[0], 0.03)
        assertEquals(0.5, r.onsets[1], 0.03)
        assertFalse(r.periodicity.regular)
    }

    @Test fun `a note change with no gap is an onset inside one complex event`() {
        val r = Analysis.analyze(cat(tone("sine", 440.0, 300, fade = 0), tone("sine", 660.0, 300, fade = 0)), sr, p)
        assertEquals(Analysis.COMPLEX, r.events.single().kind)
        assertTrue("onset near 0.3 in ${r.onsets}", r.onsets.any { abs(it - 0.3) < 0.03 })
    }

    @Test fun `silence and a whisper under the floor are no events`() {
        val s = Analysis.analyze(silence(500), sr, p)
        assertTrue(s.events.isEmpty())
        assertTrue(s.onsets.isEmpty())
        assertNull(s.f0)
        assertEquals(Dsp.FLOOR_DB, s.levelDb, 0.0)
        assertEquals(Dsp.FLOOR_DB, s.peakDb, 0.0)
        val q = Analysis.analyze(pad(tone("sine", 440.0, 500, amp = 0.0002)), sr, p)
        assertTrue(q.events.isEmpty())
        assertNull(q.f0)
        assertTrue(Analysis.analyze(DoubleArray(0), sr, p).events.isEmpty())
    }

    @Test fun `a buffer that is all tone is one event across it`() {
        val r = Analysis.analyze(tone("sine", 440.0, 1000), sr, p)
        val e = r.events.single()
        assertEquals(0.0, e.start, 0.01)
        assertEquals(1.0, e.duration, 0.01)
        assertEquals(Analysis.TONE, e.kind)
    }

    @Test fun `a short buffer is analysed in one padded frame`() {
        val r = Analysis.analyze(tone("sine", 1000.0, 30, fade = 0), sr, p)
        assertEquals(1, r.frames.size)
        assertEquals(1000.0, r.peakHz, 25.0)
    }

    @Test fun `the envelope is at most 32 points and peaks where the sound is loudest`() {
        val e = one(pad(tone("sine", 440.0, 500)))
        assertEquals(Analysis.ENVELOPE_POINTS, e.envelope.size)
        assertEquals(e.peakDb, e.envelope.max(), 1e-9)
        assertEquals(listOf(1.0, 3.0), Analysis.envelope(doubleArrayOf(1.0, 3.0), 0, 1))
    }

    @Test fun `helpers - median, percentile, stddev, regression`() {
        assertNull(Analysis.median(emptyList()))
        assertEquals(2.0, Analysis.median(listOf(3.0, 1.0, 2.0))!!, 0.0)
        assertEquals(2.5, Analysis.median(listOf(4.0, 1.0, 2.0, 3.0))!!, 0.0)
        assertEquals(2.0, Analysis.percentile(doubleArrayOf(5.0, 1.0, 3.0, 2.0, 4.0, 9.0, 8.0, 7.0, 6.0, 10.0, 11.0), 0.1), 0.0)
        assertEquals(1.0, Analysis.percentile(doubleArrayOf(3.0, 1.0, 2.0), 0.1), 0.0)
        assertEquals(0.0, Analysis.stddev(listOf(4.0)), 0.0)
        assertEquals(2.0, Analysis.stddev(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)), 1e-12)
        val (slope, r2) = Analysis.regression(listOf(0.0 to 1.0, 1.0 to 3.0, 2.0 to 5.0))
        assertEquals(2.0, slope, 1e-12)
        assertEquals(1.0, r2, 1e-12)
        assertEquals(0.0 to 0.0, Analysis.regression(listOf(1.0 to 1.0)))
        assertEquals(0.0 to 0.0, Analysis.regression(listOf(1.0 to 1.0, 1.0 to 2.0)))
        assertEquals(0.0, Analysis.regression(listOf(0.0 to 1.0, 1.0 to 1.0)).second, 0.0)
    }

    @Test fun `spectral measures - centroid, spread and flatness of known spectra`() {
        val flat = DoubleArray(512) { 1.0 }
        assertEquals(1.0, Analysis.flatness(flat), 1e-9)
        val line = DoubleArray(512).also { it[100] = 1.0 }
        assertTrue(Analysis.flatness(line) < 1e-6)
        assertEquals(0.0, Analysis.flatness(DoubleArray(1)), 0.0)
        val (c, s) = Analysis.centroidSpread(line, 1024)
        assertEquals(100.0, c, 1e-9)
        assertEquals(0.0, s, 1e-9)
        val two = DoubleArray(512).also { it[100] = 1.0; it[300] = 1.0 }
        val (c2, s2) = Analysis.centroidSpread(two, 1024)
        assertEquals(200.0, c2, 1e-9)
        assertEquals(100.0, s2, 1e-9)
        assertEquals(0.0 to 0.0, Analysis.centroidSpread(DoubleArray(512), 1024))
    }

    // ── levels and weighting ────────────────────────────────────────────────────────────────

    @Test fun `C-weighting matches the IEC 61672-1 table`() {
        assertEquals(0.0, Dsp.cWeightingDb(1000.0), 0.05)
        assertEquals(-3.0, Dsp.cWeightingDb(31.5), 0.2)
        assertEquals(-0.3, Dsp.cWeightingDb(100.0), 0.1)
        assertEquals(-4.4, Dsp.cWeightingDb(10000.0), 0.2)
        assertEquals(Dsp.FLOOR_DB, Dsp.cWeightingDb(0.0), 0.0)
        assertEquals(Dsp.FLOOR_DB, Dsp.aWeightingDb(-1.0), 0.0)
    }

    @Test fun `C keeps 100 Hz where A drops it`() {
        val n = 4096
        val k = (100.0 * n / sr).toInt()
        val pcm = ShortArray(n) { (32767 * sin(2 * PI * k * it / n)).toInt().toShort() }
        val l = Dsp.levelDbfs(pcm)
        val s = Dsp.spectrumDb(pcm, n)
        assertEquals(l - 0.3, Dsp.cWeightedDbfs(l, s, sr), 0.6)
        assertTrue(Dsp.aWeightedDbfs(l, s, sr) < l - 15)
        assertEquals(Dsp.FLOOR_DB, Dsp.weightedDbfs(0.0, DoubleArray(8) { Dsp.FLOOR_DB * 10 }, sr) { Dsp.FLOOR_DB * 10 }, 0.0)
    }

    @Test fun `peak and RMS - a full-scale square peaks at 0 dB and is 3 dB over a sine in RMS`() {
        val sq = ShortArray(4410) { if ((it / 50) % 2 == 0) 32767 else -32767 }
        assertEquals(0.0, Dsp.peakDbfs(sq), 0.01)
        assertEquals(3.01, Dsp.levelDbfs(sq), 0.02)
        assertEquals(Dsp.FLOOR_DB, Dsp.peakDbfs(ShortArray(10)), 0.0)
        assertEquals(-6.02, Dsp.peakDbfs(shortArrayOf(0, 16384, -100)), 0.01)
        assertEquals(0.5, Dsp.toDoubles(shortArrayOf(16384))[0], 1e-12)
    }

    @Test fun `the peak frequency between two bins is interpolated to within half a hertz`() {
        val n = 4096
        for (f in listOf(1001.3, 440.0, 2500.7)) {
            val pcm = ShortArray(n) { (16000 * sin(2 * PI * f * it / sr)).toInt().toShort() }
            val s = Dsp.spectrumDb(pcm, n)
            assertEquals(f, Dsp.peakHzInterpolated(s, sr), 0.5)
            assertTrue(abs(Dsp.peakHz(s, sr) - f) <= sr.toDouble() / n)
        }
        assertEquals(3 * 10.0, Dsp.peakHzInterpolated(doubleArrayOf(0.0, 1.0, 2.0, 5.0), 80), 1e-9)
    }

    // ── generator and its safety guard ──────────────────────────────────────────────────────

    private val limits = Generator.Limits(maxAmplitude = 0.5, minHz = 20.0, maxHz = 20000.0, maxBeatHz = 20.0, maxMs = 10000, fadeMs = 5, loudVolume = 0.7, loudAmplitude = 0.25)

    @Test fun `the guard clamps amplitude, frequency and duration and says so`() {
        val g = Generator.guard(Generator.Spec(Generator.SWEEP, "sine", 5.0, 30000.0, 0.9, 60000), limits, sr, 0.3)
        assertEquals(0.5, g.spec.amplitude, 0.0)
        assertEquals(20.0, g.spec.f1, 0.0)
        assertEquals(0.45 * sr, g.spec.f2, 1e-9)
        assertEquals(10000, g.spec.ms)
        assertEquals(4, g.notes.size)
        assertTrue(g.notes.any { it.startsWith("amplitude 0.9") })
        val ok = Generator.guard(Generator.Spec(Generator.TONE, "sine", 440.0, 0.0, 0.3, 500), limits, sr, 0.3)
        assertTrue(ok.notes.isEmpty())
        assertEquals(0.3, ok.spec.amplitude, 0.0)
        assertEquals(0.0, ok.spec.f2, 0.0)
        assertEquals(10, Generator.guard(Generator.Spec(Generator.TONE, "sine", 440.0, 0.0, 0.3, 1), limits, sr, 0.0).spec.ms)
        assertEquals(0.0, Generator.guard(Generator.Spec(Generator.TONE, "sine", 440.0, 0.0, Double.NaN, 100), limits, sr, 0.0).spec.amplitude, 0.0)
        assertEquals(0.0, Generator.guard(Generator.Spec(Generator.TONE, "sine", 440.0, 0.0, -1.0, 100), limits, sr, 0.0).spec.amplitude, 0.0)
        assertEquals(20.0, Generator.guard(Generator.Spec(Generator.TONE, "sine", Double.NaN, 0.0, 0.1, 100), limits, sr, 0.0).spec.f1, 0.0)
    }

    @Test fun `a loud device volume lowers the amplitude further, a quiet one does not`() {
        val loud = Generator.guard(Generator.Spec(Generator.TONE, "sine", 440.0, 0.0, 0.5, 500), limits, sr, 0.9)
        assertEquals(0.25, loud.spec.amplitude, 0.0)
        assertTrue(loud.notes.single().startsWith("device volume 90%"))
        assertEquals(0.5, Generator.guard(Generator.Spec(Generator.TONE, "sine", 440.0, 0.0, 0.5, 500), limits, sr, 0.7).spec.amplitude, 0.0)
        assertEquals(0.2, Generator.guard(Generator.Spec(Generator.TONE, "sine", 440.0, 0.0, 0.2, 500), limits, sr, 0.9).spec.amplitude, 0.0)
    }

    @Test fun `beats are clamped to the beat limit and noise keeps no frequency rule`() {
        val b = Generator.guard(Generator.Spec(Generator.BEATS, "sine", 440.0, 100.0, 0.3, 500), limits, sr, 0.0)
        assertEquals(20.0, b.spec.f2, 0.0)
        assertEquals(0.1, Generator.guard(Generator.Spec(Generator.BEATS, "sine", 440.0, 0.0, 0.3, 500), limits, sr, 0.0).spec.f2, 0.0)
        assertEquals(1.0, Generator.guard(Generator.Spec(Generator.BEATS, "sine", 440.0, Double.NaN, 0.3, 500), limits, sr, 0.0).spec.f2, 0.0)
        assertTrue(Generator.guard(Generator.Spec(Generator.BEATS, "sine", 19840.0, 10.0, 0.3, 500), limits, sr, 0.0).notes.any { it.startsWith("beats need") })
        val n = Generator.guard(Generator.Spec(Generator.NOISE, "sine", 0.0, 0.0, 0.3, 500), limits, sr, 0.0)
        assertTrue(n.notes.isEmpty())
        assertEquals(0.0, n.spec.f1, 0.0)
    }

    @Test fun `the guard refuses what does not exist`() {
        for (s in listOf(
            Generator.Spec("hum", "sine", 440.0, 0.0, 0.3, 500),
            Generator.Spec(Generator.TONE, "pulse", 440.0, 0.0, 0.3, 500),
            Generator.Spec(Generator.NOISE, "sine", 0.0, 0.0, 0.3, 500, colour = "brown"),
        )) {
            try { Generator.guard(s, limits, sr, 0.0); fail("accepted $s") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.startsWith("unknown")) }
        }
    }

    @Test fun `renders are the right length, bounded, faded, and noise is reproducible`() {
        val x = tone("square", 440.0, 250, amp = 0.4)
        assertEquals(sr / 4, x.size)
        assertEquals(0.4, x.maxOf { abs(it) }, 1e-9)
        assertEquals(0.0, x[0], 0.0)
        assertTrue("faded end ${x.last()}", abs(x.last()) < 0.01)
        assertTrue(gen(Generator.NOISE, "sine", 0.0, 0.0, 100).contentEquals(gen(Generator.NOISE, "sine", 0.0, 0.0, 100)))
        assertFalse(Generator.render(Generator.Spec(Generator.NOISE, "sine", 0.0, 0.0, 0.5, 100), sr, 5, seed = 2).contentEquals(gen(Generator.NOISE, "sine", 0.0, 0.0, 100)))
        assertTrue(gen(Generator.NOISE, "sine", 0.0, 0.0, 200, colour = "pink").all { abs(it) <= 0.5 })
        val unfaded = tone("sine", 1000.0, 10, amp = 0.5, fade = 0)
        assertEquals(0.5 * sin(2 * PI * 1000 * 5 / sr), unfaded[5], 1e-12)
        val dual = gen(Generator.DUAL, "sine", 1000.0, 1000.0, 10, fade = 0)
        assertEquals(unfaded[7], dual[7], 1e-12)
    }

    @Test fun `waveforms take their textbook values`() {
        assertEquals(1.0, Generator.wave("square", 0.25), 0.0)
        assertEquals(-1.0, Generator.wave("square", 0.75), 0.0)
        assertEquals(1.0, Generator.wave("triangle", 0.0), 1e-12)
        assertEquals(-1.0, Generator.wave("triangle", 0.5), 1e-12)
        assertEquals(0.0, Generator.wave("triangle", 0.25), 1e-12)
        assertEquals(-1.0, Generator.wave("saw", 0.0), 1e-12)
        assertEquals(0.5, Generator.wave("saw", 0.75), 1e-12)
        assertEquals(1.0, Generator.wave("sine", 0.25), 1e-12)
        assertEquals(1.0, Generator.wave("sine", 3.25), 1e-12)
        assertEquals(1.0, Generator.wave("square", -0.75), 0.0)
    }

    @Test fun `pcm16 clips and scales`() {
        assertEquals(listOf<Short>(32767, -32767, 0, 16384), Generator.pcm16(doubleArrayOf(2.0, -2.0, 0.0, 0.5)).toList())
    }

    // ── loopback: generator → WAV → analyser ───────────────────────────────────────────────

    @Test fun `generator to analyser loopback - each generated tone is read back at its frequency`() {
        for ((wave, f) in listOf("sine" to 440.0, "square" to 330.0, "triangle" to 1000.0, "saw" to 196.0)) {
            val g = Generator.guard(Generator.Spec(Generator.TONE, wave, f, 0.0, 0.5, 500), limits, sr, 0.0)
            val wav = Wav.encode(Generator.pcm16(Generator.render(g.spec, sr, limits.fadeMs)), sr)
            val back = Wav.decode(wav)
            assertEquals(sr, back.sampleRate)
            val r = Analysis.analyze(Dsp.toDoubles(back.pcm), back.sampleRate, p)
            assertEquals("$wave $f", f, r.f0!!, f * 0.003)
            assertEquals(Analysis.TONE, r.events.single().kind)
        }
        val beats = Analysis.analyze(pad(gen(Generator.BEATS, "sine", 440.0, 4.0, 1000)), sr, p)
        assertEquals(442.0, beats.f0!!, 1.0)
    }

    // ── files ───────────────────────────────────────────────────────────────────────────────

    @Test fun `WAV round-trips, averages stereo and refuses what it cannot read`() {
        val pcm = shortArrayOf(0, 1000, -1000, 32767, -32768)
        val bytes = Wav.encode(pcm, 8000)
        assertEquals(44 + 10, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        val back = Wav.decode(bytes)
        assertEquals(8000, back.sampleRate)
        assertEquals(pcm.toList(), back.pcm.toList())

        val st = ByteBuffer.allocate(44 + 8).order(ByteOrder.LITTLE_ENDIAN)
        st.put("RIFF".toByteArray()).putInt(44).put("WAVE".toByteArray())
        st.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(8000).putInt(32000).putShort(4).putShort(16)
        st.put("data".toByteArray()).putInt(8).putShort(100).putShort(300).putShort(-50).putShort(-150)
        assertEquals(listOf<Short>(200, -100), Wav.decode(st.array()).pcm.toList())

        fun refused(b: ByteArray, why: String) {
            try { Wav.decode(b); fail("accepted: $why") } catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains(why)) }
        }
        refused(ByteArray(20), "RIFF")
        val float = bytes.copyOf().also { it[20] = 3 }
        refused(float, "16-bit PCM")
        val eight = bytes.copyOf().also { it[34] = 8 }
        refused(eight, "16-bit PCM")
        val six = bytes.copyOf().also { it[22] = 6 }
        refused(six, "mono or stereo")
        refused(bytes.copyOf(36), "no data chunk")
        val long = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 999) }
        refused(long, "past the end")
    }

    @Test fun `CSV quotes only what it must and keeps empty cells empty`() {
        val s = Csv.write(listOf("a", "b,c"), listOf(listOf(1, "x\"y"), listOf(null, Double.NaN), listOf(2.5, "line\nbreak")))
        assertEquals("a,\"b,c\"\r\n1,\"x\"\"y\"\r\n,\r\n2.5,\"line\nbreak\"\r\n", s)
    }

    @Test fun `history CSV has its header and a row per sample, unpitched cells empty`() {
        val csv = Session.csv(listOf(
            Session.Sample(0.1234, -20.123, -22.0, -20.5, 440.123, 440.0, "A4", 0.78),
            Session.Sample(0.2, -60.0, -70.0, -61.0, 50.0, null, null, null),
        ))
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(Session.CSV_HEADER.joinToString(","), lines[0])
        assertEquals("0.123,-20.12,-22.0,-20.5,440.12,440.0,A4,0.78", lines[1])
        assertEquals("0.2,-60.0,-70.0,-61.0,50.0,,,", lines[2])
    }

    // ── the waterfall row and what a decision model is told ────────────────────────────────

    @Test fun `log bands put a 1 kHz tone in the band that covers 1 kHz`() {
        val n = 4096
        val k = (1000.0 * n / sr).toInt()
        val pcm = ShortArray(n) { (16000 * sin(2 * PI * k * it / n)).toInt().toShort() }
        val bands = Session.logBands(Dsp.spectrumDb(pcm, n), sr, 64, 20.0)
        assertEquals(64, bands.size)
        val loud = bands.indices.maxBy { bands[it] }
        val lo = Math.exp(Math.log(20.0) + (Math.log(sr / 2.0) - Math.log(20.0)) * loud / 64)
        val hi = Math.exp(Math.log(20.0) + (Math.log(sr / 2.0) - Math.log(20.0)) * (loud + 1) / 64)
        val f = k.toDouble() * sr / n
        assertTrue("$f in [$lo, $hi]", f >= lo * 0.97 && f <= hi * 1.03)
        assertTrue(Session.logBands(DoubleArray(2048) { Dsp.FLOOR_DB }, sr, 8, 20.0).all { it == Dsp.FLOOR_DB })
    }

    @Test fun `describe tells the model what was measured, calibrated and rounded`() {
        val r = Analysis.analyze(pad(tone("sine", 440.0, 500)), sr, p)
        val d = Session.describe(r, 94.0 - 6.0, 440.0)
        assertEquals("A4", d.getString("note"))
        assertEquals(440.0, d.getDouble("fundamental_hz"), 0.5)
        assertTrue(d.getBoolean("calibrated"))
        assertEquals(r.levelDb + 88.0, d.getDouble("level_db"), 0.06)
        val e = d.getJSONArray("events").getJSONObject(0)
        assertEquals("tone", e.getString("kind"))
        assertEquals(0.1, e.getDouble("start_s"), 0.01)
        assertEquals(r.events[0].levelDb + 88.0, e.getDouble("level_db"), 0.06)
        assertEquals(p.harmonics, e.getJSONArray("harmonics_db").length())
        assertFalse(d.getBoolean("periodic"))
        assertTrue(d.isNull("period_s"))
        assertEquals(1, d.getInt("onsets"))
        val s = Session.describe(Analysis.analyze(silence(100), sr, p), 0.0, 440.0)
        assertTrue(s.isNull("note"))
        assertTrue(s.isNull("fundamental_hz"))
        assertFalse(s.getBoolean("calibrated"))
        assertEquals(0, s.getJSONArray("events").length())
        val clicks = Session.describe(Analysis.analyze(clicks((0 until 5).map { 4410 + it * 8820 }), sr, p), 0.0, 440.0)
        assertTrue(clicks.getBoolean("periodic"))
        assertEquals(0.2, clicks.getDouble("period_s"), 0.005)
        assertTrue(clicks.getJSONArray("events").getJSONObject(0).isNull("fundamental_hz"))
        // Six decimals of a Hz say nothing more to a model reading text: one place is sent.
        assertEquals(d.getDouble("dominant_hz"), Math.round(d.getDouble("dominant_hz") * 10) / 10.0, 0.0)
        assertTrue(d.getDouble("dominant_hz") != r.peakHz)
    }
}
