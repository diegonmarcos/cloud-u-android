package com.diegonmarcos.cloudcalc

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudcalc.camera.ArMeasureActivity
import com.diegonmarcos.cloudcalc.camera.Vision
import com.diegonmarcos.cloudcalc.debugapi.CameraDebugApi
import com.diegonmarcos.cloudcalc.measure.Vec
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.cloudcalc.ui.CameraTags
import com.diegonmarcos.cloudcalc.ui.ImageRouteMode
import com.diegonmarcos.superapp.image.mlkit.RecognitionConfig
import com.diegonmarcos.superapp.image.mlkit.RecognitionPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #772 the Camera tools in the real app, with NO image engine installed (Robolectric has none):
 * the debug routes say so instead of answering nothing; the photo pipeline (upright, at the
 * declared size), the colour read-out, the AR route's measurement text, the per-app route choice
 * and the Image route screen run for real. The engine's own routes are tested where they live
 * (ab_cloud-libs-shared/libs/ml-l-image-mlkit, RecognizerTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraAppTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val app: Context get() = RuntimeEnvironment.getApplication()

    @After fun down() {
        app.getSharedPreferences("cloud_image_recognition", Context.MODE_PRIVATE).edit().clear().commit()
        Vision.last(app).delete()
    }

    private fun photo(w: Int, h: Int, colour: Int): File {
        val f = Vision.last(app)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return f
    }

    @Test fun `the baked camera declaration is usable as shipped`() {
        val c = com.diegonmarcos.cloudcalc.camera.CameraDecl.config
        assertEquals(85.6, c.references.first { it.id == "card_long" }.mm, 0.0)
        assertTrue(c.references.all { it.mm > 0 && it.label.isNotBlank() })
        assertTrue(c.maxSide > 0 && c.jpegQuality in 1..100 && c.levelToleranceDeg > 0 && c.colourSamplePx > 0)
        assertTrue("OCR numbers go to a real mode", Declarations.mode(c.ocrSendTo) != null)
    }

    @Test fun `camera status reports permission, engine, route and the default model`() {
        val s = CameraDebugApi.status(app)
        assertFalse(s.getBoolean("camera_granted"))
        assertTrue(s.getString("image_engine"), s.getString("image_engine").contains("not installed"))
        assertEquals(RecognitionConfig.json.getString("default_route"), s.getString("route"))
        assertEquals(RecognitionConfig.defaultModel(), s.getString("model"))
        assertTrue(s.getJSONObject("routes").has("openrouter"))
        assertTrue(s.isNull("last_photo"))
        assertFalse("no ARCore in Robolectric", s.getBoolean("arcore_installed"))
        assertTrue(s.getBoolean("ar_offered"))
    }

    @Test fun `recognize refuses what it cannot read and says the engine is missing for what it can`() {
        val none = CameraDebugApi.recognize(app, mapOf("path" to "last"))
        assertFalse(none.getBoolean("ok"))
        assertTrue(none.getString("error").startsWith("cannot read"))
        val bad = CameraDebugApi.recognize(app, mapOf("route" to "pixie"))
        assertTrue(bad.getString("error").contains("route must be one of"))
        photo(64, 48, Color.RED)
        val r = CameraDebugApi.recognize(app, mapOf("route" to "openrouter"))
        assertFalse(r.getBoolean("ok"))
        assertEquals("openrouter", r.getString("route"))
        assertTrue(r.getString("error"), r.getString("error").contains("not installed"))
        assertEquals(Vision.last(app).path, r.getString("path"))
    }

    @Test fun `a photo is normalised upright and at the declared size`() {
        val f = photo(4000, 3000, Color.BLUE)
        val b = Vision.normalise(f, 2048, 90)!!
        assertEquals(2048, b.width)
        assertEquals(1536, b.height)
        val again = android.graphics.BitmapFactory.decodeFile(f.path)
        assertEquals(2048, again.width)
        val small = Vision.normalise(photo(100, 80, Color.BLUE), 2048, 90)!!
        assertEquals(100, small.width)
        File(app.cacheDir, "not.jpg").apply { writeText("text") }.let { assertEquals(null, Vision.normalise(it, 2048, 90)) }
    }

    @Test fun `a tapped colour reads as hex, RGB and HSL, averaged over the sample square`() {
        val b = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 0, 0)) }
        for (x in 5 until 10) for (y in 0 until 10) b.setPixel(x, y, Color.rgb(0, 0, 200))
        assertEquals("#C80000", Vision.hex(Vision.average(b, 1, 5, 3)))
        assertEquals("#0000C8", Vision.hex(Vision.average(b, 5, 5, 1)))
        val pair = Bitmap.createBitmap(intArrayOf(Color.rgb(200, 0, 0), Color.rgb(0, 0, 200)), 2, 1, Bitmap.Config.ARGB_8888)
        assertEquals("#640064", Vision.hex(Vision.average(pair, 0, 0, 3)))
        assertEquals("#0000C8", Vision.hex(Vision.average(b, 9, 9, 9)))
        assertEquals(Triple(0, 100, 50), Vision.hsl(Color.rgb(255, 0, 0)))
        assertEquals(Triple(120, 100, 25), Vision.hsl(Color.rgb(0, 128, 0)))
        assertEquals(Triple(240, 100, 50), Vision.hsl(Color.rgb(0, 0, 255)))
        assertEquals(Triple(0, 0, 50), Vision.hsl(Color.rgb(128, 128, 128)))
        assertEquals("#FFFFFF", Vision.hex(Color.WHITE))
    }

    @Test fun `the AR route reads distance, path, area and height off its anchors`() {
        val a = Vec(0.0, 0.0, 0.0); val b = Vec(1.0, 0.0, 0.0); val c = Vec(1.0, 0.0, 1.0); val d = Vec(0.0, 0.0, 1.0)
        assertEquals("", ArMeasureActivity.measure(ArMeasureActivity.DISTANCE, listOf(a)))
        assertEquals("1.000 m", ArMeasureActivity.measure(ArMeasureActivity.DISTANCE, listOf(a, b)))
        assertEquals("1.000 m", ArMeasureActivity.measure(ArMeasureActivity.DISTANCE, listOf(a, c, d)))
        assertEquals("3.000 m", ArMeasureActivity.measure(ArMeasureActivity.PATH, listOf(a, b, c, d)))
        assertEquals("1.000 m²", ArMeasureActivity.measure(ArMeasureActivity.AREA, listOf(a, b, c, d)))
        assertEquals("", ArMeasureActivity.measure(ArMeasureActivity.AREA, listOf(a, b)))
        assertEquals("2.500 m", ArMeasureActivity.measure(ArMeasureActivity.HEIGHT, listOf(a, Vec(0.3, 2.5, 0.1))))
    }

    @Test fun `the route choice round-trips per app and refuses a route that does not exist`() {
        assertEquals(RecognitionConfig.json.getString("default_route"), RecognitionPrefs.route(app))
        RecognitionPrefs.set(app, "openrouter", "  inception/mercury-decide:free ")
        assertEquals("openrouter", RecognitionPrefs.route(app))
        assertEquals("inception/mercury-decide:free", RecognitionPrefs.model(app))
        RecognitionPrefs.set(app, "ml", "")
        assertEquals(RecognitionConfig.defaultModel(), RecognitionPrefs.model(app))
        try { RecognitionPrefs.set(app, "pixie", ""); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("pixie")) }
        val q = Vision.request(app, "openrouter", "kitchen")
        assertEquals("openrouter", q.getString("route"))
        assertEquals("kitchen", q.getString("context"))
        assertFalse("no manual token set: the engine reads the Account's", q.has("token"))
    }

    @Test fun `the Image route screen shows the engine's state, both routes, and saves the choice`() {
        compose.setContent { CalcTheme { ImageRouteMode(Declarations.modes.first { it.kind == "image_route" }) } }
        compose.waitUntil(5000) { runCatching { compose.onNodeWithTag(CameraTags.ENGINE).assertTextContains("not installed", substring = true); true }.getOrDefault(false) }
        compose.onNodeWithTag(CameraTags.route("ml")).assertIsDisplayed()
        compose.onNodeWithTag(CameraTags.route("openrouter")).performClick()
        compose.onNodeWithTag(CameraTags.SAVE).performClick()
        compose.waitUntil(5000) { RecognitionPrefs.route(app) == "openrouter" }
        assertEquals(RecognitionConfig.defaultModel(), RecognitionPrefs.model(app))
    }
}
