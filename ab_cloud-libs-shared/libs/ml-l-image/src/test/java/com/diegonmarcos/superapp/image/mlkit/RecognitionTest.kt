package com.diegonmarcos.superapp.image.mlkit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * #772 the contract half of image recognition: the uniform result both routes answer in, read
 * back field by field, and the request built from THIS module's recognition.json. Pure JVM, so
 * it runs in every consumer's unit phase.
 */
class RecognitionTest {
    /** The suite runs from this module's directory, wherever a consumer links it. */
    private val decl = JSONObject(File("recognition.json").readText())

    @Test fun `an ml answer reads back whole`() {
        val r = Recognition.parse(JSONObject()
            .put("ok", true).put("route", "ml").put("requested", "ml").put("fell_back", false).put("reason", "")
            .put("labels", org.json.JSONArray().put(JSONObject().put("label", "Fruit").put("p", 0.9)))
            .put("boxes", org.json.JSONArray().put(JSONObject().put("label", "Food").put("p", 0.7).put("x", 1).put("y", 2).put("w", 3).put("h", 4)))
            .put("text", "TOTAL 12.50").put("colours", org.json.JSONArray().put(JSONObject().put("hex", "#E53935").put("name", "red").put("share", 0.6)))
            .put("barcode", JSONObject().put("format", "QR_CODE").put("raw", "https://example.org"))
            .put("answers", JSONObject()).put("model", "").put("latency_ms", 42).put("cost", JSONObject.NULL).put("width", 64).put("height", 48).toString(), "ml")
        assertTrue(r.ok)
        assertEquals(listOf(Recognition.Label("Fruit", 0.9)), r.labels)
        assertEquals(Recognition.Box("Food", 0.7, 1, 2, 3, 4), r.boxes.single())
        assertEquals("TOTAL 12.50", r.text)
        assertEquals(Recognition.Colour("#E53935", "red", 0.6), r.colours.single())
        assertEquals("QR_CODE", r.barcode!!.format)
        assertTrue(r.barcode!!.payload is BarcodePayload.Url)
        assertEquals(42L, r.latencyMs)
        assertNull(r.cost)
        assertEquals(64, r.width)
        assertNull(r.error)
    }

    @Test fun `an openrouter answer that fell back says so, with its answers and cost`() {
        val r = Recognition.parse(JSONObject().put("ok", true).put("route", "ml").put("requested", "openrouter").put("fell_back", true)
            .put("reason", "HTTP 503: overloaded").put("answers", JSONObject().put("damaged", org.json.JSONArray().put(JSONObject().put("label", "no").put("p", 0.8))))
            .put("cost", 0.00004).put("barcode", JSONObject.NULL).toString(), "openrouter")
        assertTrue(r.fellBack)
        assertEquals("openrouter", r.requested)
        assertEquals("ml", r.route)
        assertEquals("HTTP 503: overloaded", r.reason)
        assertEquals(0.8, r.answers.getValue("damaged").single().p, 0.0)
        assertEquals(0.00004, r.cost!!, 0.0)
        assertNull(r.barcode)
    }

    @Test fun `a failure, an error and garbage are failed results with the reason`() {
        val e = Recognition.parse("""{"ok":false,"route":"openrouter","error":"no OpenRouter token"}""", "openrouter")
        assertFalse(e.ok)
        assertEquals("no OpenRouter token", e.error)
        val g = Recognition.parse("<html>", "ml")
        assertFalse(g.ok)
        assertTrue(g.error!!.contains("not JSON"))
        assertEquals("ml", g.route)
        val engineError = Recognition.parse("""{"error":"cannot decode the image"}""", "ml")
        assertFalse(engineError.ok)
        assertEquals("ml", engineError.route)
        assertFalse(Recognition.failed("ml", "x").ok)
    }

    @Test fun `the request carries the route, the model or its default, and the declaration's blocks`() {
        assertEquals(setOf("ml", "openrouter"), RecognitionConfig.routes(decl).keys)
        assertTrue(decl.getString("default_route") in RecognitionConfig.routes(decl))
        val q = RecognitionConfig.request("openrouter", "", decl = decl, context = "kitchen")
        assertEquals("openrouter", q.getString("route"))
        assertEquals(RecognitionConfig.defaultModel(decl), q.getString("model"))
        assertEquals("kitchen", q.getString("context"))
        assertTrue(q.getBoolean("fallback"))
        assertTrue(q.getJSONObject("openrouter").getString("endpoint").startsWith("https://"))
        assertTrue(q.getJSONObject("colours").getJSONObject("names").has("red"))
        assertEquals(decl.getJSONObject("ml").getInt("max_side"), q.getJSONObject("ml").getInt("max_side"))
        assertFalse("no token unless the caller holds one", q.has("token"))
        assertEquals("tok", RecognitionConfig.request("ml", "m/x", token = "tok", decl = decl).getString("token"))
        assertEquals("m/x", RecognitionConfig.request("ml", "m/x", decl = decl).getString("model"))
        try { RecognitionConfig.request("pixie", "", decl = decl); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("pixie")) }
    }

    @Test fun `the declaration's categories and questions are what the engine can send`() {
        val o = decl.getJSONObject("openrouter")
        assertTrue(o.getJSONObject("categories").length() >= 2)
        val qs = o.getJSONObject("questions")
        qs.keys().forEach { k ->
            val t = qs.getJSONObject(k).getString("type")
            assertTrue("$k: $t", t in setOf("noul", "choice", "score"))
            if (t != "noul") assertTrue("$k needs criteria", qs.getJSONObject(k).has("criteria"))
        }
        decl.getJSONObject("colours").getJSONObject("names").let { n -> n.keys().forEach { assertTrue(n.getString(it).matches(Regex("#[0-9A-Fa-f]{6}"))) } }
    }

    @Test fun `a detection reads back its mode, tracking ids and alternatives, a sound answer its timeline`() {
        val r = Recognition.parse(JSONObject().put("ok", true).put("route", "ml").put("mode", "objects")
            .put("boxes", org.json.JSONArray()
                .put(JSONObject().put("label", "suit").put("p", 0.7).put("x", 1).put("y", 2).put("w", 3).put("h", 4).put("id", 7)
                    .put("alts", org.json.JSONArray().put(JSONObject().put("label", "suit").put("p", 0.7)).put(JSONObject().put("label", "bow tie").put("p", 0.1))))
                .put(JSONObject().put("label", "object").put("p", 0.0).put("x", 0).put("y", 0).put("w", 1).put("h", 1).put("id", JSONObject.NULL)))
            .put("segments", org.json.JSONArray().put(JSONObject().put("label", "Beep, bleep").put("p", 0.9).put("start_ms", 0).put("end_ms", 1935)))
            .toString(), "ml")
        assertEquals("objects", r.mode)
        assertEquals(7, r.boxes[0].id)
        assertEquals(listOf("suit", "bow tie"), r.boxes[0].alts.map { it.label })
        assertNull("a box with no tracking id says so", r.boxes[1].id)
        assertTrue(r.boxes[1].alts.isEmpty())
        assertEquals(Recognition.Segment("Beep, bleep", 0.9, 0, 1935), r.segments.single())
        assertTrue("an answer with no timeline has none", Recognition.parse("""{"ok":true}""", "ml").segments.isEmpty())
    }

    @Test fun `a detect request is on device, its mode declared, live frames streamed at live_side`() {
        val modes = RecognitionConfig.detectModes(decl)
        assertEquals(listOf("objects", "labels", "text"), modes)
        assertTrue(RecognitionConfig.defaultDetectMode(decl) in modes)
        val live = RecognitionConfig.detectRequest("objects", live = true, decl = decl)
        assertEquals("objects", live.getString("mode"))
        assertTrue(live.getBoolean("stream"))
        assertEquals(decl.getJSONObject("detect").getInt("live_side"), live.getJSONObject("detect").getInt("max_side"))
        assertFalse("detection never carries a route or a token", live.has("route") || live.has("token"))
        val photo = RecognitionConfig.detectRequest("text", live = false, decl = decl)
        assertFalse(photo.getBoolean("stream"))
        assertEquals(decl.getJSONObject("detect").getInt("max_side"), photo.getJSONObject("detect").getInt("max_side"))
        assertEquals("a live request copies the declaration, never edits it", 1024, decl.getJSONObject("detect").getInt("max_side"))
        try { RecognitionConfig.detectRequest("faces", live = true, decl = decl); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("faces")) }
    }
}
