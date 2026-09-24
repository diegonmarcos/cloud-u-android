package com.diegonmarcos.cloudme

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
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
import com.diegonmarcos.superapp.bottomnav.R as NavR
import com.google.android.material.R as MR

/**
 * #531 — Cloud Me's bottom nav is the fleet's Compose island, MEASURED on the real layout.
 *
 * Inflates the real activity_main under Theme.CloudMe and configures its bottom_nav exactly as
 * MainActivity does (MeBottomNav.configure). Every expected value is read back from build.json
 * (through Sections), libs:bottomnav's dimens, or the resolved theme; nothing is restated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBottomNavTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    // The app manifest declares no ComponentActivity; register it instead of shipping
    // ui-test-manifest into the APK.
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

    private lateinit var root: View
    private lateinit var nav: BottomNavIslandView
    private lateinit var themed: ContextThemeWrapper
    private val opened = mutableListOf<String>()
    private val launched = mutableListOf<String>()

    private val bar get() = Sections.bottom()
    private val pages get() = bar.filter { it.target.isBlank() }

    private fun show(select: String?) {
        val activity = compose.activity
        compose.runOnUiThread {
            themed = ContextThemeWrapper(activity, R.style.Theme_CloudMe)
            root = LayoutInflater.from(themed).inflate(R.layout.activity_main, null)
            nav = root.findViewById(R.id.bottom_nav)
            MeBottomNav.configure(nav, onOpen = { opened += it }, onTarget = { launched += it })
            MeBottomNav.sync(nav, select)
            activity.setContentView(root)
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun cell(id: String) = bounds(BottomNavTags.item(id))
    private fun island() = bounds(BottomNavTags.ISLAND)
    private fun px(id: Int) = themed.resources.getDimension(id)
    private fun near(msg: String, expected: Float, actual: Float) =
        assertEquals("$msg: expected $expected px, measured $actual px", expected, actual, 1f)

    private fun paint(): Bitmap = compose.runOnUiThread {
        Bitmap.createBitmap(nav.width, nav.height, Bitmap.Config.ARGB_8888).also { nav.draw(Canvas(it)) }
    }
    private fun probe(bmp: Bitmap, id: String): Color {
        val c = cell(id)
        return Color(bmp.getPixel((c.left + 3 * themed.resources.displayMetrics.density).toInt(), c.center.y.toInt()))
    }
    private fun same(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue), abs(a.alpha - b.alpha)) <= 3f / 255f
    private val pill get() = Color(MaterialColors.getColor(themed, MR.attr.colorSurfaceInverse, "test"))
    private val fill get() = Color(themed.resources.getColor(NavR.color.bottom_nav_island_fill, null))

    @Test
    fun `the bar is build_json's bar sections, left to right in their order`() {
        show(pages.first().id)
        assertTrue("build.json declares no bar sections", bar.isNotEmpty())
        assertEquals("the island's items are not Sections.bottom()", bar.map { it.id }, nav.items.map { it.id })
        val xs = bar.map { cell(it.id).left }
        assertEquals("the items are not drawn in `order`, left to right: $xs", xs.sorted(), xs)
        assertEquals("the island labels are not the sections' labels", bar.map { it.label }, nav.items.map { it.label })
    }

    @Test
    fun `the page ends where the island begins and the island clears one dimen`() {
        show(pages.first().id)
        val margin = px(NavR.dimen.bottom_nav_island_bottom_margin)
        val content = root.findViewById<View>(R.id.fragment_container)
        for (inset in listOf(0, 48)) {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, inset))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
            compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(nav, insets) }
            compose.waitForIdle()
            near("the page's bottom edge vs the island host's top ($inset px inset)", nav.top.toFloat(), content.bottom.toFloat())
            near("the island's clearance below it ($inset px nav-bar inset)", margin, nav.height - island().bottom)
        }
        near("the island is the width fraction of the bar", nav.width * themed.resources.getFraction(NavR.fraction.bottom_nav_width_fraction, 1, 1), island().width)
    }

    @Test
    fun `the selected page wears the theme's light pill and the rest the island fill`() {
        val sel = pages.first().id
        show(sel)
        assertTrue("the theme's pill IS the island fill, the probe cannot tell them apart", !same(pill, fill))
        val bmp = paint()
        for (s in bar) {
            val got = probe(bmp, s.id)
            if (s.id == sel) assertTrue("${s.id} painted $got, not colorSurfaceInverse $pill", same(got, pill))
            else assertTrue("${s.id} painted $got, not the island fill $fill", same(got, fill))
        }
    }

    @Test
    fun `a page tap opens it and moves the pill, a launch tap leaves and the pill stays`() {
        val start = pages.first().id
        val next = pages.last().id
        val launch = bar.firstOrNull { it.target.isNotBlank() }
        show(start)
        compose.onNodeWithTag(BottomNavTags.item(next), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("a tap on $next did not open it", listOf(next), opened)
        assertTrue("the pill did not move to $next", same(probe(paint(), next), pill))
        if (launch != null) {
            compose.onNodeWithTag(BottomNavTags.item(launch.id), useUnmergedTree = true).performClick()
            compose.waitForIdle()
            assertEquals("a tap on ${launch.id} did not launch its target", listOf(launch.target), launched)
            assertEquals("a launch section was opened inside the app", listOf(next), opened)
            val bmp = paint()
            assertTrue("the pill left $next for the launch section", same(probe(bmp, next), pill))
            assertTrue("the launch section ${launch.id} lit up", same(probe(bmp, launch.id), fill))
        }
    }

    @Test
    fun `an off-bar section leaves no item lit`() {
        val offBar = Sections.all().first { s -> bar.none { it.id == s.id } }.id
        show(offBar)
        compose.onAllNodes(isSelected()).assertCountEquals(0)
        compose.runOnUiThread { MeBottomNav.sync(nav, pages.first().id) }
        compose.waitForIdle()
        compose.onAllNodes(isSelected()).assertCountEquals(1)
    }
}
