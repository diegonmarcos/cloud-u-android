package com.diegonmarcos.cloudcalc

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import com.diegonmarcos.cloudcalc.measure.Sensors
import com.diegonmarcos.cloudcalc.sensors.SensorHub
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.cloudcalc.ui.ModeScreen
import com.diegonmarcos.cloudcalc.ui.SensorTags
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Measure > Sensors under Robolectric, against a stand-in for the phone (SensorHub.platform): what is present, granted and reported is
 * decided here, so the screens are held to showing real readings, listing the absent as "not on this device" and never reading
 * before a permission is granted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SensorsAppTest {
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

    private class FakePhone(val has: Set<String>, val denied: Set<String> = emptySet()) : SensorHub.Platform {
        val feeds = mutableMapOf<String, (DoubleArray) -> Unit>()
        val started = mutableListOf<String>()
        override fun present(ctx: Context, d: Sensors.Decl) = d.id in has
        override fun granted(ctx: Context, d: Sensors.Decl) = d.id !in denied
        override fun listen(ctx: Context, d: Sensors.Decl, onValues: (DoubleArray) -> Unit): () -> Unit {
            started += d.id
            feeds[d.id] = onValues
            return { feeds.remove(d.id) }
        }
    }

    @After fun backToThePhone() { SensorHub.platform = null }

    private fun str(id: Int, vararg a: Any) = RuntimeEnvironment.getApplication().getString(id, *a)

    private val shown = mutableStateOf<Declarations.Mode?>(null)
    private var contentSet = false

    private fun show(m: Declarations.Mode) {
        if (!contentSet) {
            compose.setContent { CalcTheme { shown.value?.let { key(it.id) { ModeScreen(it) } } } }
            contentSet = true
        }
        compose.runOnIdle { shown.value = m }
        compose.waitForIdle()
    }

    private fun show(modeId: String) = show(Declarations.mode(modeId)!!)

    /** One reading per recomposition: effects keyed on the newest reading see every one. */
    private fun FakePhone.feed(id: String, vararg v: Double) {
        compose.runOnIdle { feeds.getValue(id)(v) }
        compose.waitForIdle()
    }

    @Test fun `Motion shows the accelerometer live with its derived g force, converts on a tap, and lists the rest as not on this device`() {
        val p = FakePhone(setOf("accelerometer")).also { SensorHub.platform = it }
        show("sx_motion")
        assertTrue("the present sensor is read", "accelerometer" in p.started)
        assertFalse("an absent sensor is never read", "gyroscope" in p.started)
        compose.onNodeWithText(str(R.string.sensor_waiting)).assertExists()
        p.feed("accelerometer", 0.0, 0.0, 9.80665)
        compose.onNodeWithText("9.81 m/s²").assertExists()
        compose.onNodeWithText("1.00 g").assertExists()
        compose.onNodeWithTag(SensorTags.absent("gyroscope")).assertExists()
        compose.onNodeWithTag(SensorTags.absent("gravity")).assertExists()
        assertEquals("no made-up value on an absent sensor", 0, compose.onAllNodesWithTag(SensorTags.value("gyroscope")).fetchSemanticsNodes().size)
        compose.onNodeWithText("g").performClick()
        compose.waitForIdle()
        assertEquals("the z axis and the derived value both read 1.00 g", 2, compose.onAllNodesWithText("1.00 g").fetchSemanticsNodes().size)
    }

    @Test fun `a sensor that needs a permission reads nothing until it is granted, and says so`() {
        val p = FakePhone(setOf("step_counter", "step_detector"), denied = setOf("step_counter", "step_detector")).also { SensorHub.platform = it }
        show("sx_activity")
        compose.onNodeWithTag(SensorTags.allow("step_counter")).assertExists()
        compose.onNodeWithTag(SensorTags.allow("step_detector")).assertExists()
        compose.onAllNodesWithText(str(R.string.sensor_perm_why)).fetchSemanticsNodes().let { assertTrue(it.size >= 2) }
        assertTrue("nothing is read before Allow", p.started.isEmpty())
        assertEquals("nothing but the Allow button is offered for a value", 0, compose.onAllNodesWithTag(SensorTags.value("step_counter")).fetchSemanticsNodes().size)
        compose.onNodeWithTag(SensorTags.absent("heart_rate")).assertExists()
    }

    @Test fun `a granted step counter shows its count with the distance and energy worked out of it`() {
        val p = FakePhone(setOf("step_counter")).also { SensorHub.platform = it }
        show("sx_activity")
        p.feed("step_counter", 1000.0)
        compose.onNodeWithTag(SensorTags.value("step_counter")).assertTextContains("1000")
        compose.onNodeWithText("750 m").assertExists()
        compose.onNodeWithText("40 kcal").assertExists()
    }

    @Test fun `a derived value that needs another sensor says which one the phone lacks, and appears once it has it`() {
        val p = FakePhone(setOf("humidity")).also { SensorHub.platform = it }
        show("sx_environment")
        p.feed("humidity", 60.0)
        assertEquals(3, compose.onAllNodesWithText(str(R.string.sensor_needs, "Ambient temperature")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("16.7 °C", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun `with the temperature present the dew point is worked out of both readings`() {
        val p = FakePhone(setOf("humidity", "ambient_temperature")).also { SensorHub.platform = it }
        show("sx_environment")
        p.feed("ambient_temperature", 25.0)
        p.feed("humidity", 60.0)
        compose.onNodeWithText("16.7 °C").assertExists()
        assertEquals(0, compose.onAllNodesWithText(str(R.string.sensor_needs, "Ambient temperature")).fetchSemanticsNodes().size)
    }

    @Test fun `the compass turns gravity and the magnetic field into a heading and says when a sensor is missing`() {
        val p = FakePhone(setOf("gravity", "magnetometer")).also { SensorHub.platform = it }
        show("sx_compass")
        compose.onNodeWithTag(SensorTags.COMPASS_HEADING).assertTextContains(str(R.string.compass_waiting))
        p.feed("gravity", 0.0, 0.0, 9.81)
        p.feed("magnetometer", 30.0, 0.0, -40.0)
        compose.onNodeWithTag(SensorTags.COMPASS_HEADING).assertTextContains("270")
        compose.onNodeWithTag(SensorTags.COMPASS_HEADING).assertTextContains("W")
        compose.onNodeWithText("50.0 µT").assertExists()
    }

    @Test fun `the compass falls back to the accelerometer for gravity`() {
        val p = FakePhone(setOf("accelerometer", "magnetometer")).also { SensorHub.platform = it }
        show("sx_compass")
        p.feed("accelerometer", 0.0, 0.0, 9.81)
        p.feed("magnetometer", 0.0, 30.0, -40.0)
        compose.onNodeWithTag(SensorTags.COMPASS_HEADING).assertTextContains("0")
        compose.onNodeWithTag(SensorTags.COMPASS_HEADING).assertTextContains("N")
    }

    @Test fun `the compass without a magnetometer says what it lacks and draws no heading`() {
        SensorHub.platform = FakePhone(setOf("gravity"))
        show("sx_compass")
        compose.onNodeWithText(str(R.string.compass_missing)).assertExists()
        assertEquals(0, compose.onAllNodesWithTag(SensorTags.COMPASS_HEADING).fetchSemanticsNodes().size)
    }

    @Test fun `the seismometer reports the shake about the window's own mean in words`() {
        val p = FakePhone(setOf("accelerometer")).also { SensorHub.platform = it }
        show("sx_seismo")
        repeat(10) { i -> p.feed("accelerometer", 0.0, 0.0, if (i % 2 == 0) 9.0 else 11.0) }
        compose.onNodeWithText("1.000 m/s²").assertExists()
        compose.onNodeWithText("Noticeable").assertExists()
        compose.onNodeWithText(str(R.string.seismo_reset)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.sensor_waiting)).assertExists()
    }

    @Test fun `the metal detector takes a baseline first, then reports the change from it`() {
        val p = FakePhone(setOf("magnetometer")).also { SensorHub.platform = it }
        val need = JSONObject(Declarations.mode("sx_metal")!!.spec).getInt("baseline_samples")
        show("sx_metal")
        p.feed("magnetometer", 0.0, 0.0, 40.0)
        compose.onNodeWithText(str(R.string.metal_calibrating, 1, need)).assertExists()
        repeat(need - 1) { p.feed("magnetometer", 0.0, 0.0, 40.0) }
        compose.onNodeWithText("Nothing nearby").assertExists()
        assertEquals("the field now and the baseline both read 40.0", 2, compose.onAllNodesWithText("40.0 µT").fetchSemanticsNodes().size)
        p.feed("magnetometer", 0.0, 0.0, 110.0)
        compose.onNodeWithText("Strong metal or a magnet").assertExists()
        compose.onNodeWithText("+70.0 µT").assertExists()
        compose.onNodeWithText(str(R.string.metal_reset)).performClick()
        compose.waitForIdle()
        p.feed("magnetometer", 0.0, 0.0, 110.0)
        compose.onNodeWithText(str(R.string.metal_calibrating, 1, need)).assertExists()
    }

    @Test fun `All sensors says so when the phone reports none`() {
        show("sx_all")
        compose.onNodeWithText(str(R.string.sensor_all_none)).assertExists()
    }

    @Test fun `the ruler draws and switches unit, and the protractor reads the angle of a touch`() {
        show("sx_ruler")
        compose.onNodeWithTag(SensorTags.RULER).assertExists()
        compose.onNodeWithText(str(R.string.ruler_unit_in)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SensorTags.RULER).assertExists()
    }

    @Test fun `the protractor reads 90 degrees straight above its centre`() {
        show("sx_protractor")
        compose.onNodeWithTag(SensorTags.PROTRACTOR_ANGLE).assertTextContains("45.0")
        compose.onNodeWithTag(SensorTags.PROTRACTOR).performTouchInput { click(Offset(width / 2f, height * 0.5f)) }
        compose.waitForIdle()
        compose.onNodeWithTag(SensorTags.PROTRACTOR_ANGLE).assertTextContains("90.0")
        assertEquals("the supplement reads 90 too", 2, compose.onAllNodesWithText("90.0°").fetchSemanticsNodes().size)
    }

    @Test fun `every group of the real catalog composes on a phone with no sensors at all and lists each as not on this device`() {
        SensorHub.platform = FakePhone(emptySet())
        val sensors = com.diegonmarcos.cloudcalc.sensors.SensorDecl.catalog.sensors
        Declarations.modes.filter { it.kind == "sensor_group" }.forEach { m ->
            show(m)
            val group = JSONObject(m.spec).getString("group")
            val first = sensors.first { it.group == group }
            compose.onNodeWithTag(SensorTags.absent(first.id)).assertExists()
        }
    }
}
