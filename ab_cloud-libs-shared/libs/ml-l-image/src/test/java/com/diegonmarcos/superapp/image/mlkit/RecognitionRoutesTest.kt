package com.diegonmarcos.superapp.image.mlkit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * #799 the route switch, read from THIS module's recognition.json: Model (Jev) is the default,
 * on-device ML answers whenever the model cannot (offline, no token, an error, a throw) and says
 * so, the switch is per type, and the last route is recorded per type. Pure JVM.
 */
class RecognitionRoutesTest {
    private val decl = JSONObject(File("recognition.json").readText())
    private val ML = RecognitionConfig.ML
    private val MODEL = RecognitionConfig.OPENROUTER

    private fun answer(route: String, vararg labels: String) =
        Recognition.failed(route, "").copy(ok = true, error = null, labels = labels.map { Recognition.Label(it, 0.9) })

    @Before fun clean() = RecognitionRoutes.reset()

    @Test fun `Model (Jev) on typesafe jev-1_13 is the declared default, on-device the other route`() {
        assertEquals(MODEL, decl.getString("default_route"))
        assertEquals("Model (Jev)", RecognitionConfig.routes(decl)[MODEL])
        assertEquals("On-device ML (offline)", RecognitionConfig.routes(decl)[ML])
        assertEquals("typesafe/jev-1.13", RecognitionConfig.defaultModel(decl))
        assertTrue(decl.getBoolean("fallback_to_ml"))
    }

    @Test fun `the plan asks the model only when it can run`() {
        assertEquals(RecognitionRoutes.Plan(false, ""), RecognitionRoutes.plan(ML, true, true))
        assertEquals(RecognitionRoutes.Plan(false, RecognitionRoutes.OFFLINE), RecognitionRoutes.plan(MODEL, false, true))
        assertEquals(RecognitionRoutes.Plan(false, RecognitionRoutes.NO_TOKEN), RecognitionRoutes.plan(MODEL, true, false))
        assertEquals(RecognitionRoutes.Plan(true, ""), RecognitionRoutes.plan(MODEL, true, true))
        assertEquals("unknown is asked", RecognitionRoutes.Plan(true, ""), RecognitionRoutes.plan(MODEL, null, null))
    }

    @Test fun `the model answers on its route and on-device never runs`() {
        var device = 0
        val r = RecognitionRoutes.routed(RecognitionRoutes.SOUND, MODEL, true, true, true, { device++; answer(ML, "Speech") }, { answer(MODEL, "speech") })
        assertEquals(MODEL, r.route)
        assertFalse(r.fellBack)
        assertEquals("speech", r.labels.single().label)
        assertEquals(0, device)
    }

    @Test fun `a failing, throwing, offline or tokenless model falls back on device and says why`() {
        val failing = RecognitionRoutes.routed(RecognitionRoutes.SOUND, MODEL, true, true, true, { answer(ML, "Speech") }, { Recognition.failed(MODEL, "HTTP 503") })
        assertEquals(ML, failing.route); assertEquals(MODEL, failing.requested); assertTrue(failing.fellBack)
        assertEquals("HTTP 503", failing.reason); assertEquals("Speech", failing.labels.single().label)

        val thrown = RecognitionRoutes.routed(RecognitionRoutes.SOUND, MODEL, true, true, true, { answer(ML, "Beep") }, { throw java.net.SocketTimeoutException("timeout") })
        assertTrue(thrown.fellBack); assertEquals("timeout", thrown.reason); assertTrue(thrown.ok)

        var asked = 0
        val offline = RecognitionRoutes.routed(RecognitionRoutes.IMAGE, MODEL, false, null, true, { answer(ML, "Fruit") }, { asked++; answer(MODEL, "food") })
        assertEquals(0, asked); assertTrue(offline.fellBack); assertEquals(RecognitionRoutes.OFFLINE, offline.reason)

        val tokenless = RecognitionRoutes.routed(RecognitionRoutes.SOUND, MODEL, true, false, true, { answer(ML, "Dog") }, { asked++; answer(MODEL, "dog") })
        assertEquals(0, asked); assertEquals(RecognitionRoutes.NO_TOKEN, tokenless.reason); assertEquals(ML, tokenless.route)
    }

    @Test fun `when on-device cannot answer either, the model's failure is what is reported`() {
        val r = RecognitionRoutes.routed(RecognitionRoutes.IMAGE, MODEL, true, null, true, { Recognition.failed(ML, "engine not installed") }, { Recognition.failed(MODEL, "engine not installed") })
        assertFalse(r.ok); assertEquals(MODEL, r.route); assertFalse(r.fellBack)
        val off = RecognitionRoutes.routed(RecognitionRoutes.IMAGE, MODEL, false, null, true, { Recognition.failed(ML, "engine not installed") }, { answer(MODEL) })
        assertFalse(off.ok); assertEquals(MODEL, off.route)
        assertEquals(RecognitionRoutes.OFFLINE + "; on device: engine not installed", off.error)
    }

    @Test fun `with fallback off a failed model stays failed`() {
        var device = 0
        val r = RecognitionRoutes.routed(RecognitionRoutes.SOUND, MODEL, true, true, false, { device++; answer(ML, "x") }, { Recognition.failed(MODEL, "HTTP 401") })
        assertFalse(r.ok); assertEquals("HTTP 401", r.error); assertEquals(0, device)
    }

    @Test fun `the on-device route never asks the model`() {
        var asked = 0
        val r = RecognitionRoutes.routed(RecognitionRoutes.IMAGE, ML, true, true, true, { answer(ML, "Cup") }, { asked++; answer(MODEL, "object") })
        assertEquals(0, asked); assertEquals(ML, r.route); assertFalse(r.fellBack)
    }

    @Test fun `the last route is recorded per type, and reported with the active one`() {
        assertNull(RecognitionRoutes.last(RecognitionRoutes.IMAGE))
        RecognitionRoutes.routed(RecognitionRoutes.IMAGE, MODEL, true, null, true, { answer(ML) }, { answer(MODEL, "food") }, clock = { 7L })
        RecognitionRoutes.routed(RecognitionRoutes.SOUND, MODEL, false, null, true, { answer(ML, "Speech") }, { answer(MODEL) }, clock = { 9L })
        assertEquals(MODEL, RecognitionRoutes.last(RecognitionRoutes.IMAGE)!!.route)
        val s = RecognitionRoutes.last(RecognitionRoutes.SOUND)!!
        assertEquals(ML, s.route); assertTrue(s.fellBack); assertEquals(MODEL, s.chosen); assertEquals(9L, s.atMs)

        val j = RecognitionRoutes.status(RecognitionRoutes.SOUND, MODEL, RecognitionConfig.routes(decl), decl.getString("default_route"))
        assertEquals(MODEL, j.getString("active_route")); assertEquals("Model (Jev)", j.getString("active_label"))
        assertEquals(MODEL, j.getString("default_route"))
        assertEquals(ML, j.getJSONObject("last").getString("route")); assertTrue(j.getJSONObject("last").getBoolean("fell_back"))
        assertEquals(RecognitionRoutes.OFFLINE, j.getJSONObject("last").getString("reason"))
        assertTrue(RecognitionRoutes.status(RecognitionRoutes.IMAGE, ML, RecognitionConfig.routes(decl), MODEL).getJSONObject("last").getString("route") == MODEL)
    }

    @Test fun `an image request offline is sent on device, online it goes as chosen`() {
        val sent = mutableListOf<String>()
        val req = RecognitionConfig.request(MODEL, "", decl = decl)
        val off = RecognitionRoutes.image(req, false) { sent += it.getString("route"); answer(it.getString("route"), "Fruit") }
        assertEquals(listOf(ML), sent); assertTrue(off.fellBack)
        sent.clear()
        RecognitionRoutes.image(req, null) { sent += it.getString("route"); answer(it.getString("route"), "food") }
        assertEquals(listOf(MODEL), sent)
        assertEquals(MODEL, req.getString("route"))
    }

    @Test fun `a fall back reads as such`() {
        val fb = RecognitionRoutes.fellBack(answer(ML, "Beep"), "HTTP 500")
        assertEquals("On-device ML (offline) — fell back: HTTP 500", RecognitionRoutes.answeredBy(fb, RecognitionConfig.routes(decl)))
        assertEquals("Model (Jev)", RecognitionRoutes.answeredBy(answer(MODEL), RecognitionConfig.routes(decl)))
    }
}
