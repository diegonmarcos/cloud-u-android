package com.diegonmarcos.superapp.soundtags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TagsTest {
    private val names = listOf("Speech", "Silence", "Beep, bleep", "Dog")

    private fun agg(vararg w: FloatArray, topK: Int = 3, min: Double = 0.1, seg: Double = 0.3, duration: Long = 10_000) =
        Tags.aggregate(w.toList(), names, topK, min, hopMs = 480, windowMs = 975, durationMs = duration, segmentMin = seg)

    @Test fun meanOverWindowsThenRanked() {
        val t = agg(floatArrayOf(0.2f, 0.0f, 0.9f, 0.0f), floatArrayOf(0.6f, 0.0f, 0.5f, 0.05f))
        assertEquals(listOf("Beep, bleep", "Speech"), t.labels.map { it.label })
        assertEquals(0.7, t.labels[0].p, 1e-9)
        assertEquals(0.4, t.labels[1].p, 1e-9)
        assertEquals(2, t.labels[0].index)
        assertEquals(2, t.windows)
    }

    @Test fun topKAndTheFloorBothCut() {
        val w = floatArrayOf(0.5f, 0.4f, 0.3f, 0.1f)
        assertEquals(2, agg(w, topK = 2).labels.size)
        assertEquals(listOf("Speech", "Silence", "Beep, bleep", "Dog"), agg(w, topK = 9).labels.map { it.label })
        assertEquals("every class over the floor is kept", 4, agg(w, topK = 9, min = 0.1).labels.size)
        assertEquals(3, agg(w, topK = 9, min = 0.2).labels.size)
        assertTrue(agg(w, min = 0.9).labels.isEmpty())
        assertEquals("a class exactly at the floor is kept", 1, agg(floatArrayOf(0.5f, 0f, 0f, 0f), min = 0.5).labels.size)
    }

    @Test fun aRepeatedBestClassIsOneSegment() {
        val beep = floatArrayOf(0f, 0f, 0.8f, 0f)
        val t = agg(beep, floatArrayOf(0f, 0f, 0.9f, 0f), beep)
        assertEquals(1, t.segments.size)
        val s = t.segments[0]
        assertEquals("Beep, bleep", s.label)
        assertEquals(0.9, s.p, 1e-9)
        assertEquals(0L, s.startMs)
        assertEquals(2 * 480L + 975, s.endMs)
    }

    @Test fun aChangeOrAGapStartsANewSegment() {
        val speech = floatArrayOf(0.7f, 0f, 0f, 0f)
        val dog = floatArrayOf(0f, 0f, 0f, 0.6f)
        val quiet = floatArrayOf(0.2f, 0f, 0f, 0f)
        val t = agg(speech, dog, quiet, speech)
        assertEquals(listOf("Speech", "Dog", "Speech"), t.segments.map { it.label })
        assertEquals(480L, t.segments[1].startMs)
        assertEquals(1440L, t.segments[2].startMs)
        assertEquals("a window below the segment floor is skipped", 3, t.segments.size)
        assertEquals("a window exactly at the segment floor is kept", 1, agg(floatArrayOf(0.5f, 0f, 0f, 0f), quiet, seg = 0.5).segments.size)
    }

    @Test fun windowsThatDoNotTouchDoNotMerge() {
        val speech = floatArrayOf(0.7f, 0f, 0f, 0f)
        val t = Tags.aggregate(listOf(speech, speech), names, 3, 0.1, hopMs = 2000, windowMs = 975, durationMs = 10_000, segmentMin = 0.3)
        assertEquals(2, t.segments.size)
        assertEquals(975L, t.segments[0].endMs)
    }

    @Test fun theLastSegmentEndsWithTheClip() {
        val t = agg(floatArrayOf(0f, 0.9f, 0f, 0f), duration = 600)
        assertEquals(600L, t.segments[0].endMs)
        val tiny = agg(floatArrayOf(0f, 0.9f, 0f, 0f), duration = 0)
        assertEquals("a zero-length clip still has a non-empty segment", 1L, tiny.segments[0].endMs)
    }

    @Test fun refusesAMismatchOrNothing() {
        try { agg(floatArrayOf(0.1f, 0.2f)); fail("2 scores for 4 names") } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("2 classes; the model names 4"))
        }
        try { Tags.aggregate(emptyList(), names, 3, 0.1, 480, 975, 1000, 0.3); fail("no windows") } catch (e: IllegalArgumentException) { }
    }

    @Test fun theAnswerIsRecognitionShaped() {
        val t = agg(floatArrayOf(0f, 0.81234f, 0f, 0f))
        val o = Tags.json(t, "yamnet", 42, 975, 0.5f)
        assertEquals(true, o.getBoolean("ok"))
        assertEquals("ml", o.getString("route"))
        assertEquals("ml", o.getString("requested"))
        assertEquals(false, o.getBoolean("fell_back"))
        assertEquals("Silence", o.getJSONArray("labels").getJSONObject(0).getString("label"))
        assertEquals(0.812, o.getJSONArray("labels").getJSONObject(0).getDouble("p"), 1e-9)
        assertEquals(1, o.getJSONArray("labels").getJSONObject(0).getInt("index"))
        val seg = o.getJSONArray("segments").getJSONObject(0)
        assertEquals("Silence", seg.getString("label"))
        assertEquals(0L, seg.getLong("start_ms"))
        assertEquals(975L, seg.getLong("end_ms"))
        assertEquals(0.812, seg.getDouble("p"), 1e-9)
        assertEquals("yamnet", o.getString("model"))
        assertEquals(42L, o.getLong("latency_ms"))
        assertEquals(975L, o.getLong("duration_ms"))
        assertEquals(1, o.getInt("windows"))
        assertEquals(0.5, o.getDouble("peak"), 1e-9)
        assertEquals(0, o.getJSONArray("boxes").length())
        assertTrue(o.isNull("barcode"))
        assertEquals(0, o.getInt("width"))
    }

    @Test fun roundsToThreePlaces() {
        assertEquals(0.123, Tags.round3(0.12345), 0.0)
        assertEquals(0.124, Tags.round3(0.1236), 1e-12)
    }
}
