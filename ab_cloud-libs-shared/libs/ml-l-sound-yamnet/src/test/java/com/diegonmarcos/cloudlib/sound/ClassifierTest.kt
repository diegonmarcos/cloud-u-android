package com.diegonmarcos.cloudlib.sound

import com.diegonmarcos.superapp.soundtags.ModelZip
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin

/**
 * #798 the engine's pipeline over a GOLDEN CLIP with the REAL model's scores. The TFLite runtime
 * has no desktop JVM build on Maven, so the interpreter itself cannot run here; instead
 * src/test/resources/golden/tone_then_silence.json holds what the pinned YAMNet file answered
 * (through LiteRT's own runtime) for every window of the clip, and the stand-in scorer replays
 * them — after checking that each window the Kotlin pipeline cut is the window the model scored.
 * So WAV encoding, decoding, resampling, windowing, averaging, the timeline and the answer's shape
 * are all the real code, against real model output, and the label list is read out of the real,
 * sha-verified model file fetchModels staged.
 */
class ClassifierTest {
    private val golden = JSONObject(javaClass.getResource("/golden/tone_then_silence.json")!!.readText())
    private val pin = JSONObject(File("data/models.json").readText()).getJSONObject("models").getJSONObject("yamnet")
    private val model: ByteArray by lazy {
        File(System.getProperty("yamnet.model") ?: error("yamnet.model is not set: the suite runs through gradle, after fetchModels")).readBytes()
    }
    private val labels by lazy { ModelZip.lines(model, pin.getString("labels_entry")) }

    private val request = JSONObject().put("ml", JSONObject().put("top_k", 5).put("min_score", 0.05).put("segment_min", 0.3).put("max_ms", 30000))

    /** The golden clip at [rate] Hz: 1.5 s of 1 kHz at 0.5, then 1.5 s of silence, 16-bit (round half to even, as numpy). */
    private fun clip(rate: Int): ShortArray = ShortArray(3 * rate) { i ->
        if (i < 3 * rate / 2) round(0.5 * sin(2 * PI * 1000 * i / rate) * 32767).toInt().toShort() else 0
    }

    /** Replays the golden scores in order and keeps every window it was handed. */
    private inner class Replay : Classifier.Scorer {
        val seen = mutableListOf<FloatArray>()
        override fun score(window: FloatArray): FloatArray {
            val row = golden.getJSONArray("scores").getJSONArray(seen.size)
            seen.add(window)
            return FloatArray(row.length()) { row.getDouble(it).toFloat() }
        }
    }

    private fun classifier(scorer: Classifier.Scorer, clock: () -> Long = System::currentTimeMillis) =
        Classifier(scorer, { labels }, "YAMNet", golden.getInt("window"), golden.getInt("rate"), golden.getInt("hop"), clock)

    private fun energy(w: FloatArray) = w.fold(0.0) { a, v -> a + v.toDouble() * v }

    @Test fun theModelFileIsThePinnedOneAndNamesItsClasses() {
        assertEquals(pin.getLong("bytes"), model.size.toLong())
        val sha = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
        assertEquals(pin.getString("sha256"), sha)
        assertEquals(pin.getInt("classes"), labels.size)
        assertEquals("Speech", labels[0])
        assertEquals("Beep, bleep", labels[475])
        assertEquals("Silence", labels[494])
    }

    @Test fun theGoldenClipIsHeardAsABeepThenSilence() {
        val replay = Replay()
        var t = 100L
        val o = classifier(replay) { t.also { t += 42 } }.classify(com.diegonmarcos.superapp.sound.SoundEngine.wav16(clip(16000), 16000), request)

        // the windows the Kotlin pipeline cut are the windows the model scored
        val sums = golden.getJSONArray("window_sums")
        val energies = golden.getJSONArray("window_energy")
        assertEquals(golden.getJSONArray("starts").length(), replay.seen.size)
        replay.seen.forEachIndexed { k, w ->
            assertEquals("window $k length", golden.getInt("window"), w.size)
            assertEquals("window $k sum", sums.getDouble(k), w.sum().toDouble(), 1e-3)
            assertEquals("window $k energy", energies.getDouble(k), energy(w), 1e-3)
        }

        assertTrue(o.getBoolean("ok"))
        val top = o.getJSONArray("labels")
        assertEquals(listOf("Beep, bleep", "Silence", "Telephone", "Dial tone", "Telephone bell ringing"),
            (0 until top.length()).map { top.getJSONObject(it).getString("label") })
        assertEquals(0.436, top.getJSONObject(0).getDouble("p"), 0.002)
        assertEquals(475, top.getJSONObject(0).getInt("index"))
        val seg = o.getJSONArray("segments")
        assertEquals(listOf("Beep, bleep", "Telephone", "Silence"), (0 until seg.length()).map { seg.getJSONObject(it).getString("label") })
        assertEquals(0L, seg.getJSONObject(0).getLong("start_ms"))
        assertEquals(1935L, seg.getJSONObject(0).getLong("end_ms"))
        assertEquals(1920L, seg.getJSONObject(2).getLong("start_ms"))
        assertEquals(3000L, seg.getJSONObject(2).getLong("end_ms"))
        assertEquals(3000L, o.getLong("duration_ms"))
        assertEquals(6, o.getInt("windows"))
        assertEquals(42L, o.getLong("latency_ms"))
        assertEquals("YAMNet", o.getString("model"))
        assertEquals(0.5, o.getDouble("peak"), 0.001)
    }

    @Test fun aFortyEightKilohertzRecordingReachesTheSameWindows() {
        val replay = Replay()
        val o = classifier(replay).classify(com.diegonmarcos.superapp.sound.SoundEngine.wav16(clip(48000), 48000), request)
        val energies = golden.getJSONArray("window_energy")
        assertEquals(energies.length(), replay.seen.size)
        replay.seen.forEachIndexed { k, w ->
            // the box filter attenuates a 1 kHz tone by ~1% at 48 -> 16 kHz; the windows still line up
            assertEquals("window $k energy", energies.getDouble(k), energy(w), 0.03 * energies.getDouble(k) + 1e-3)
        }
        assertEquals("Beep, bleep", o.getJSONArray("labels").getJSONObject(0).getString("label"))
    }

    @Test fun maxMsCapsWhatIsRead() {
        val replay = Replay()
        val capped = JSONObject(request.toString()).apply { getJSONObject("ml").put("max_ms", 1000) }
        val o = classifier(replay).classify(com.diegonmarcos.superapp.sound.SoundEngine.wav16(clip(16000), 16000), capped)
        assertEquals(1000L, o.getLong("duration_ms"))
        assertEquals(2, replay.seen.size)
        assertEquals("Beep, bleep", o.getJSONArray("labels").getJSONObject(0).getString("label"))
    }

    @Test fun failuresAreAnswersNotExceptions() {
        val bad = JSONObject(classifier(Replay()).json("not audio".toByteArray(), request.toString()))
        assertFalse(bad.getBoolean("ok"))
        assertTrue(bad.getString("error").contains("not a RIFF/WAVE"))
        val empty = JSONObject(classifier(Replay()).json(com.diegonmarcos.superapp.sound.SoundEngine.wav16(ShortArray(0), 16000), request.toString()))
        assertFalse(empty.getBoolean("ok"))
        assertTrue(empty.getString("error").contains("no samples"))
        val notJson = JSONObject(classifier(Replay()).json(ByteArray(0), "{"))
        assertFalse(notJson.getBoolean("ok"))
        assertEquals("ml", notJson.getString("route"))
    }
}
