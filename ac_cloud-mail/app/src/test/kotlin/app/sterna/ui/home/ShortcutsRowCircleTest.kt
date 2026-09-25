package app.sterna.ui.home

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/**
 * #534: the Home shortcuts are round coins, MEASURED on the real [ShortcutsRow] rendered under
 * Robolectric. A circle is asserted the only way a circle can be told from a rounded square: the
 * coin's bounding-box corner shows the page, while a point just inside its rim shows the coin.
 * Colours are read from the live theme, never restated. mdpi keeps px == dp.
 * The plain Application stands in for SternaApplication, whose onCreate wires the whole mail
 * stack; a row of three icons needs none of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortcutsRowCircleTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    // The app's manifest has no entry for the activity the compose rule launches.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager)
                    .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private var page = Color.Unspecified
    private lateinit var hostView: View
    private val taps = mutableListOf<String>()
    private val actions = listOf(Icons.Filled.Create to "compose", Icons.Filled.Search to "search", Icons.Filled.Settings to "settings")
        .map { (icon, id) -> HomeAction(icon, id) { taps += id } }

    private fun show() {
        compose.setContent {
            MaterialTheme {
                page = MaterialTheme.colorScheme.background
                hostView = LocalView.current
                Box(Modifier.fillMaxSize().background(page)) { ShortcutsRow(actions) }
            }
        }
        compose.waitForIdle()
    }

    private fun circles() = compose.onAllNodesWithTag(SHORTCUT_CIRCLE_TAG)

    private fun pixels(b: Rect): PixelMap {
        val full = compose.runOnUiThread {
            Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
                .also { hostView.draw(Canvas(it)) }
        }
        return Bitmap.createBitmap(full, b.left.roundToInt(), b.top.roundToInt(), b.width.roundToInt(), b.height.roundToInt())
            .asImageBitmap().toPixelMap()
    }

    private fun distance(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    @Test
    fun `534 every shortcut icon sits in a round coin`() {
        show()
        circles().assertCountEquals(actions.size)
        for (i in actions.indices) {
            val b = circles()[i].fetchSemanticsNode().boundsInRoot
            assertEquals("#534 coin ${actions[i].label} is as tall as it is wide", b.width, b.height, 0.5f)
            val px = pixels(b)
            val w = px.width
            val h = px.height
            // 10% in from each bounding-box corner: outside a circle, inside any rounded square
            // whose radius is under ~a third of its side.
            for ((x, y) in listOf(w / 10 to h / 10, w - 1 - w / 10 to h / 10, w / 10 to h - 1 - h / 10, w - 1 - w / 10 to h - 1 - h / 10)) {
                assertTrue(
                    "#534 coin ${actions[i].label}: ($x,$y) is outside the circle and must show the page $page, got ${px[x, y]}",
                    distance(page, px[x, y]) <= 3f / 255f,
                )
            }
            // Just inside the rim at the top and the left, clear of the centred icon: the coin.
            for ((x, y) in listOf(w / 2 to 3, 3 to h / 2)) {
                assertTrue(
                    "#534 coin ${actions[i].label}: ($x,$y) is inside the circle and must show the coin, got the page ${px[x, y]}",
                    distance(page, px[x, y]) > 8f / 255f,
                )
            }
        }
    }

    @Test
    fun `534 the coin is filled with the shared glass orb token, not a private colour`() {
        show()
        // Superapp's bg_liquid_glass_pill resolves the same glass_orb_fill (libs:bottomnav), so a
        // coin that matches it here matches superapp home's orbs. Resolved from the app's own
        // resources at run time, never restated as a literal.
        val orb = Color(RuntimeEnvironment.getApplication().getColor(GlassOrb.fill))
        for (i in actions.indices) {
            val px = pixels(circles()[i].fetchSemanticsNode().boundsInRoot)
            // Top centre, 5px in: inside the 1dp ring, above the icon.
            assertTrue(
                "#534 coin ${actions[i].label} must be filled with the shared glass_orb_fill $orb, got ${px[px.width / 2, 5]}",
                distance(orb, px[px.width / 2, 5]) <= 3f / 255f,
            )
        }
    }

    @Test
    fun `534 each coin is the tap target and carries its action's name`() {
        show()
        for (i in actions.indices) {
            circles()[i].assert(hasClickAction()).assert(hasContentDescription(actions[i].label))
            circles()[i].performClick()
        }
        assertEquals("#534 each coin fires its own action, in order", actions.map { it.label }, taps)
    }
}
