package com.diegonmarcos.superapp.sound

import com.diegonmarcos.superapp.image.mlkit.Recognition
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * #798 the contract half of sound identification, read from THIS module's sound.json: on device is
 * the default route, a capture never exceeds its cap, a photo is tagged only with what clears the
 * declared floor, and the clip crosses as the WAV the engine decodes. Pure JVM, so it runs in every
 * consumer's unit phase.
 */
class SoundConfigTest {
    /** The suite runs from this module's directory, wherever a consumer links it. */
    private val decl = JSONObject(File("sound.json").readText())

    @Test fun `on device is the declared default, and both routes are declared`() {
        assertEquals("ml", decl.getString("default_route"))
        assertEquals(setOf(SoundConfig.ML, SoundConfig.OPENROUTER), SoundConfig.routes(decl).keys)
    }

    @Test fun `the request carries the declared on-device thresholds and nothing else`() {
        val q = SoundConfig.request(decl)
        assertTrue("only the ml block", q.length() == 1 && q.has("ml"))
        assertEquals(decl.getJSONObject("ml").getInt("top_k"), q.getJSONObject("ml").getInt("top_k"))
        assertTrue(q.getJSONObject("ml").getLong("max_ms") >= decl.getJSONObject("capture").getLong("max_ms"))
    }

    @Test fun `a capture is the declared default, a requested length, never over the cap`() {
        val c = decl.getJSONObject("capture")
        assertEquals(c.getLong("default_ms"), SoundConfig.captureMs(null, decl))
        assertEquals(1500L, SoundConfig.captureMs(1500, decl))
        assertEquals(c.getLong("max_ms"), SoundConfig.captureMs(c.getLong("max_ms") * 10, decl))
        assertEquals(1L, SoundConfig.captureMs(-5, decl))
        assertEquals(16000, SoundConfig.sampleRate(decl))
    }

    @Test fun `a photo is tagged with at most the declared number of classes over the floor`() {
        val t = decl.getJSONObject("tags")
        val labels = listOf(0.9, 0.5, t.getDouble("min_score"), t.getDouble("min_score") - 0.01, 0.8).mapIndexed { i, p -> Recognition.Label("c$i", p) }
        val r = Recognition.failed("ml", "").copy(ok = true, error = null, labels = labels)
        val tags = SoundConfig.tags(r, decl)
        assertEquals(t.getInt("max"), tags.size)
        assertEquals(listOf("c0", "c1", "c2"), tags.map { it.label })
        assertTrue("a failed answer tags nothing", SoundConfig.tags(r.copy(ok = false), decl).isEmpty())
    }

    @Test fun `the clip crosses as a canonical 16-bit mono WAV`() {
        val b = SoundEngine.wav16(shortArrayOf(1, -2, 32767), 16000)
        assertEquals(44 + 6, b.size)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(b, 0, 4)); assertEquals(36 + 6, bb.getInt(4)); assertEquals("WAVE", String(b, 8, 4))
        assertEquals("fmt ", String(b, 12, 4)); assertEquals(16, bb.getInt(16)); assertEquals(1, bb.getShort(20).toInt())
        assertEquals(1, bb.getShort(22).toInt()); assertEquals(16000, bb.getInt(24)); assertEquals(32000, bb.getInt(28))
        assertEquals(2, bb.getShort(32).toInt()); assertEquals(16, bb.getShort(34).toInt())
        assertEquals("data", String(b, 36, 4)); assertEquals(6, bb.getInt(40))
        assertArrayEquals(shortArrayOf(1, -2, 32767), shortArrayOf(bb.getShort(44), bb.getShort(46), bb.getShort(48)))
    }

    @Test fun `test clips are the declared kinds, the right length, and only a tone or noise is loud`() {
        assertEquals(listOf("tone", "silence", "noise"), SoundCapture.TESTS)
        assertEquals(1600, SoundCapture.testClip("tone", 100, 16000).size)
        assertEquals(0.0, SoundCapture.peak(SoundCapture.testClip("silence", 100, 16000)), 0.0)
        assertEquals(0.5, SoundCapture.peak(SoundCapture.testClip("tone", 100, 16000)), 0.01)
        assertTrue(SoundCapture.peak(SoundCapture.testClip("noise", 100, 16000)) > 0.3)
        try { SoundCapture.testClip("music", 100, 16000); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("music") || e.message!!.contains("tone")) }
    }
}
