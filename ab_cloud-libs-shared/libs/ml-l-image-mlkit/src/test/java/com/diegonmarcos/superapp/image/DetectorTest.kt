package com.diegonmarcos.superapp.image.mlkit

import android.graphics.Bitmap
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * #798 live identification's engine half over the golden images: the request's mode, stream flag,
 * thresholds and frame size reach ML Kit's stand-in exactly as declared (recognition.json's detect
 * block), and every answer is the uniform Recognition shape with boxes in the sized frame's pixels.
 * ML Kit cannot run off a phone, so a recording fake stands in for the detector; the classifier it
 * would run is checked here as a FILE (the pinned sha256, its 1000 metadata labels) and through the
 * real TFLite runtime by lib-apks/test/test-ml-goldens.sh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetectorTest {
    private class Fake : Detector.OnDevice {
        val calls = mutableListOf<String>()
        var sizes = mutableListOf<Pair<Int, Int>>()
        override fun objects(bitmap: Bitmap, stream: Boolean, minConfidence: Double, max: Int, labelsPerObject: Int): List<Detector.Obj> {
            calls += "objects stream=$stream min=$minConfidence max=$max per=$labelsPerObject"
            sizes += bitmap.width to bitmap.height
            return listOf(
                Detector.Obj(7, listOf(Recognizer.Label("military uniform", 0.62), Recognizer.Label("suit", 0.2)), 1, 2, 30, 40),
                Detector.Obj(8, listOf(Recognizer.Label("suit", 0.7)), 5, 6, 10, 10),
                Detector.Obj(null, emptyList(), 0, 0, 4, 4),
                Detector.Obj(9, listOf(Recognizer.Label("suit", 0.5)), 9, 9, 3, 3),
            ).take(max)
        }
        override fun labels(bitmap: Bitmap, minConfidence: Double, max: Int): List<Recognizer.Label> {
            calls += "labels min=$minConfidence max=$max"
            return listOf(Recognizer.Label("Person", 0.9), Recognizer.Label("Uniform", 0.6)).take(max)
        }
        override fun model(mode: String) = "model-$mode"
    }

    private val fake = Fake()
    private var t = 1000L
    private val detector = Detector(ImageScanner(), fake) { t.also { t += 25 } }

    private fun golden(name: String): ByteArray = javaClass.getResourceAsStream("/golden/$name")!!.readBytes()

    /** RecognitionConfig.detectRequest's shape, from the shipped recognition.json. */
    private fun request(mode: String, live: Boolean): JSONObject {
        val d = JSONObject(JSONObject(File("../ml-l-image/recognition.json").readText()).getJSONObject("detect").toString())
        if (live) d.put("max_side", d.getInt("live_side"))
        return JSONObject().put("mode", mode).put("stream", live).put("detect", d)
    }

    private fun labels(o: JSONObject, key: String = "labels") = o.getJSONArray(key).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("label") } }

    @Test fun `objects mode - boxes with tracking ids and alternatives, the frame sized for live`() {
        val o = detector.detect(golden("grace_hopper.jpg"), request("objects", live = true))
        assertTrue(o.toString(), o.getBoolean("ok"))
        assertEquals("ml", o.getString("route"))
        assertEquals("objects", o.getString("mode"))
        assertEquals("model-objects", o.getString("model"))
        assertEquals(listOf("objects stream=true min=0.4 max=5 per=3"), fake.calls)
        // grace_hopper.jpg is 512x600: live_side 480 -> 409x480, and the answer says so
        assertEquals(409 to 480, fake.sizes.single())
        assertEquals(409, o.getInt("width"))
        assertEquals(480, o.getInt("height"))
        val boxes = o.getJSONArray("boxes")
        assertEquals(4, boxes.length())
        val b = boxes.getJSONObject(0)
        assertEquals("military uniform", b.getString("label"))
        assertEquals(0.62, b.getDouble("p"), 0.0)
        assertEquals(7, b.getInt("id"))
        assertEquals(listOf(1, 2, 30, 40), listOf("x", "y", "w", "h").map { b.getInt(it) })
        assertEquals(listOf("military uniform", "suit"), labels(b, "alts"))
        val unnamed = boxes.getJSONObject(2)
        assertEquals("a box nothing named is still an object", Detector.UNKNOWN, unnamed.getString("label"))
        assertTrue(unnamed.isNull("id"))
        assertEquals(0.0, unnamed.getDouble("p"), 0.0)
        // the frame's labels: each object's best label once, at its best probability, best first
        assertEquals(listOf("suit", "military uniform"), labels(o))
        assertEquals(0.7, o.getJSONArray("labels").getJSONObject(0).getDouble("p"), 0.0)
        assertEquals(25L, o.getLong("latency_ms"))
    }

    @Test fun `a single photo is not a stream and keeps its own size up to max_side`() {
        val o = detector.detect(golden("red.png"), request("objects", live = false))
        assertEquals(listOf("objects stream=false min=0.4 max=5 per=3"), fake.calls)
        assertEquals(64, o.getInt("width"))
    }

    @Test fun `labels mode - the whole frame, max_labels from the declaration`() {
        val o = detector.detect(golden("red.png"), request("labels", live = true))
        assertTrue(o.getBoolean("ok"))
        assertEquals(listOf("labels min=0.4 max=8"), fake.calls)
        assertEquals(listOf("Person", "Uniform"), labels(o))
        assertEquals(0, o.getJSONArray("boxes").length())
        assertEquals("model-labels", o.getString("model"))
    }

    @Test fun `text mode never fakes a reading - ML Kit OCR off a phone is a stated failure`() {
        val o = detector.detect(golden("red.png"), request("text", live = true))
        if (o.getBoolean("ok")) {
            assertEquals("text", o.getString("mode"))
        } else {
            assertTrue(o.getString("error"), o.getString("error").startsWith("text: "))
        }
        assertTrue("text mode asks no object detector", fake.calls.isEmpty())
    }

    @Test fun `refusals are answers, not exceptions`() {
        val unknown = detector.detect(golden("red.png"), request("objects", true).put("mode", "faces"))
        assertFalse(unknown.getBoolean("ok"))
        assertTrue(unknown.getString("error").contains("unknown mode faces"))
        val garbage = JSONObject(detector.json("not an image".toByteArray(), request("objects", true).toString()))
        assertFalse(garbage.getBoolean("ok"))
        assertEquals(ImageScanner.CANNOT_DECODE, garbage.getString("error"))
        assertFalse(JSONObject(detector.json(golden("red.png"), "{")).getBoolean("ok"))
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun `the summary keeps each best label once at its highest probability`() {
        val s = Detector.summary(listOf(
            Detector.Obj(1, listOf(Recognizer.Label("cup", 0.3)), 0, 0, 1, 1),
            Detector.Obj(2, listOf(Recognizer.Label("cup", 0.8), Recognizer.Label("mug", 0.9)), 0, 0, 1, 1),
            Detector.Obj(3, emptyList(), 0, 0, 1, 1),
        ))
        assertEquals(listOf(Recognizer.Label("cup", 0.8)), s)
    }

    @Test fun `the classifier object detection runs is the pinned file with 1000 ImageNet labels`() {
        val pin = JSONObject(File("data/models.json").readText()).getJSONObject("models").getJSONObject("detect")
        val model = File(System.getProperty("detect.model") ?: error("detect.model is not set: the suite runs through gradle, after fetchModels"))
        val bytes = model.readBytes()
        assertEquals(pin.getLong("bytes"), bytes.size.toLong())
        assertEquals(pin.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val names = ZipFile(model).use { z -> z.getInputStream(z.getEntry(pin.getString("labels_entry"))).readBytes().toString(Charsets.UTF_8) }
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(pin.getInt("classes"), names.size)
        assertTrue(names.contains("military uniform"))
        assertEquals("tench", names[0])
    }
}
