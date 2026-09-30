package com.diegonmarcos.cloudme

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.junit.Assert.assertFalse
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
    private lateinit var contentHost: FrameLayout
    private lateinit var scroller: ScrollView
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
            // The real content host — what the bar's collapse driver observes (#673). Left EMPTY
            // and unlaid-out here: the geometry tests read exact pixels off this layout. A test
            // that needs a scrolling page calls [givePageToScroll] itself.
            contentHost = root.findViewById(R.id.fragment_container)
            MeBottomNav.configure(
                nav,
                content = contentHost,
                onOpen = { opened += it },
                onTarget = { launched += it },
            )
            MeBottomNav.sync(nav, select)
            activity.setContentView(root)
        }
        compose.waitForIdle()
    }

    /**
     * Put a page taller than the viewport inside the content host and lay it out, so it can really
     * scroll. Robolectric lays nothing out by itself and ScrollView.scrollTo CLAMPS to its child's
     * measured height, so without the explicit pass a scroll would stay at 0 and the collapse test
     * would fail for the wrong reason.
     */
    private fun givePageToScroll() {
        compose.runOnUiThread {
            scroller = ScrollView(themed).apply {
                addView(View(themed), FrameLayout.LayoutParams(VIEWPORT_PX, PAGE_PX))
            }
            contentHost.addView(scroller, FrameLayout.LayoutParams(VIEWPORT_PX, VIEWPORT_PX))
            contentHost.measure(
                View.MeasureSpec.makeMeasureSpec(VIEWPORT_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEWPORT_PX, View.MeasureSpec.EXACTLY),
            )
            contentHost.layout(0, 0, VIEWPORT_PX, VIEWPORT_PX)
        }
        compose.waitForIdle()
    }

    /** Scroll the content host by [dy] px and let the bar react, the way a finger would. */
    private fun scrollContentBy(dy: Int) {
        compose.runOnUiThread { scroller.scrollTo(0, (scroller.scrollY + dy).coerceAtLeast(0)) }
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

    /**
     * #673 the bar collapses to icons when the page scrolls, and returns when it scrolls back.
     *
     * NOT "the collapse parameter exists" and NOT "setting collapsed shrinks the bar": both of
     * those passed for as long as #532's collapse sat unused in this app, because nothing in a
     * View shell ever wrote BottomNavIslandView.collapsed. This scrolls the content host that
     * MeBottomNav was handed and asserts the RENDERED bar moved, which needs a live driver.
     */
    @Test
    fun `scrolling the page collapses the bar to icons, and scrolling up brings the labels back`() {
        show(pages.first().id)
        givePageToScroll()
        val expanded = island().height
        assertFalse("the bar started collapsed, so the scroll proves nothing", nav.collapsed)
        assertTrue("no labels are drawn before any scroll", labelCount() > 0)

        scrollContentBy(SCROLL_PX)

        assertTrue(
            "scrolling the page did not collapse the bar: nothing drives collapsed in this " +
                "shell, which is how #532 reached Cloud Me as a no-op",
            nav.collapsed,
        )
        assertEquals("labels survived the collapse", 0, labelCount())
        val collapsed = island().height
        assertTrue(
            "the collapse did not shrink the island: $expanded px expanded, $collapsed px collapsed",
            collapsed < expanded - 1f,
        )

        scrollContentBy(-SCROLL_PX)

        assertFalse("scrolling up left the bar collapsed", nav.collapsed)
        assertEquals("the labels did not come back", nav.items.size, labelCount())
        near("the island did not return to its expanded height", expanded, island().height)
    }

    /** How many of the bar's items currently draw a label node. */
    private fun labelCount() = nav.items.count {
        compose.onAllNodesWithTag(BottomNavTags.label(it.id), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        const val VIEWPORT_PX = 1080
        const val PAGE_PX = 6000
        const val SCROLL_PX = 400
    }
}
