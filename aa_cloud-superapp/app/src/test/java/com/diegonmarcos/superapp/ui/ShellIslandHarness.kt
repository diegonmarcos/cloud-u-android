package com.diegonmarcos.superapp.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.google.android.material.color.MaterialColors
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.math.abs
import com.diegonmarcos.superapp.bottomnav.R as NavR

/**
 * The shell's REAL bottom nav, hosted the way activity_main hosts it: a BottomNavIslandView at
 * the bottom of a full-screen frame, under Theme.Superapp, configured by the same
 * [ShellBottomNav.configure] ShellActivity calls. Nothing here builds a nav of its own, so what
 * the tests measure is what the launcher shows (#531). Every expected number is read back from
 * libs:bottomnav's resources or the resolved theme, never restated (#363/#498).
 */
abstract class ShellIslandHarness {

    protected val compose = createAndroidComposeRule<ComponentActivity>()

    // The app manifest declares no ComponentActivity. Register it in Robolectric's package
    // manager instead of shipping ui-test-manifest into the debug APK.
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

    protected lateinit var nav: BottomNavIslandView
    protected lateinit var frame: FrameLayout
    /** activity_main's content host, stood up here so the bar's scroll-collapse driver has the
     *  same thing to observe that the shell gives it (#673). */
    protected lateinit var content: FrameLayout
    protected lateinit var scroller: ScrollView
    protected lateinit var themed: ContextThemeWrapper
    protected val picked = mutableListOf<String>()
    protected val repicked = mutableListOf<String>()

    protected val res get() = themed.resources
    protected fun px(id: Int): Float = res.getDimension(id)
    protected val ids: List<String> get() = nav.items.map { it.id }

    /** activity_main's host, configured exactly as ShellActivity configures it, then selected. */
    protected fun showShellNav(select: String) {
        val activity = compose.activity
        compose.runOnUiThread {
            themed = ContextThemeWrapper(activity, R.style.Theme_Superapp)
            nav = BottomNavIslandView(themed)
            // activity_main's content host. EMPTY here, and laid out by nobody: the geometry tests
            // measure exact pixels off this frame, so nothing is added to it and no layout pass is
            // forced. A test that needs a scrolling page calls [givePageToScroll] itself.
            content = FrameLayout(themed)
            ShellBottomNav.configure(nav, com.diegonmarcos.superapp.launcher.Sections.defaultMode(), content)
            nav.selectedId = select
            nav.onSelect = { picked += it; nav.selectedId = it }
            nav.onReselect = { repicked += it }
            frame = FrameLayout(themed).apply {
                addView(content, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(nav, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            }
            activity.setContentView(frame)
        }
        compose.waitForIdle()
    }

    /**
     * Put a page taller than the viewport inside the content host and lay it out, so the host can
     * really scroll. Robolectric lays nothing out on its own and ScrollView.scrollTo CLAMPS to its
     * child's measured height, so without the explicit pass a scroll would silently stay at 0 and
     * a collapse test would fail for the wrong reason.
     */
    protected fun givePageToScroll() {
        compose.runOnUiThread {
            scroller = ScrollView(themed).apply { addView(page(), pageLayout()) }
            content.addView(scroller, FrameLayout.LayoutParams(VIEWPORT_PX, VIEWPORT_PX))
            layOut(content)
        }
        compose.waitForIdle()
    }

    /**
     * A page taller than any viewport, for a ScrollView that has to really scroll.
     *
     * minimumHeight, and not [pageLayout]'s height alone: ScrollView measures its child with an
     * UNSPECIFIED height spec, and a bare View answers UNSPECIFIED with its SUGGESTED MINIMUM — 0 —
     * whatever its LayoutParams ask for. A 0-tall page makes ScrollView.scrollTo clamp every scroll
     * to 0, so the bar is never told about one and a collapse test fails reporting that nothing
     * drives it. Every ScrollView in these tests is built from this, so none of them can scroll
     * only in appearance.
     */
    protected fun page(): View = View(themed).apply { minimumHeight = PAGE_PX }

    protected fun pageLayout(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(VIEWPORT_PX, PAGE_PX)

    protected fun layOut(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_PX, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, VIEWPORT_PX, VIEWPORT_PX)
    }

    /** Scroll the content host by [dy] px and let the bar react, the way a finger would. */
    protected fun scrollContentBy(dy: Int) {
        compose.runOnUiThread {
            val want = (scroller.scrollY + dy).coerceAtLeast(0)
            scroller.scrollTo(0, want)
            // A page that did not move is a bar that is RIGHT not to collapse, so without this the
            // collapse assertion blames the driver for the page's defect — which is how a scroll
            // clamped to 0 read as a dead driver for two shipped commits.
            org.junit.Assert.assertEquals("the page did not scroll", want, scroller.scrollY)
        }
        compose.waitForIdle()
    }

    companion object {
        const val VIEWPORT_PX: Int = 1080
        const val PAGE_PX: Int = 6000
    }

    /** Bounds in the island host's own coordinates (px). */
    protected fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    protected fun island() = bounds(BottomNavTags.ISLAND)
    protected fun cell(id: String) = bounds(BottomNavTags.item(id))
    protected fun icon(id: String) = bounds(BottomNavTags.icon(id))
    protected fun label(id: String) = bounds(BottomNavTags.label(id))

    /** What the host view actually paints. */
    protected fun paint(): Bitmap = compose.runOnUiThread {
        Bitmap.createBitmap(nav.width, nav.height, Bitmap.Config.ARGB_8888).also { nav.draw(Canvas(it)) }
    }

    /** The island's colour for the role [attr] names: the FLEET palette (libs:bottomnav FleetChrome), which is
     *  what SuperApp's launcher theme resolves to and what every other app now shows too. */
    protected fun themeColor(attr: Int): Color {
        val p = com.diegonmarcos.superapp.bottomnav.FleetChrome.palette(themed)
        return Color(
            when (attr) {
                com.google.android.material.R.attr.colorSurfaceInverse -> p.pill
                com.google.android.material.R.attr.colorOnSurfaceInverse -> p.onPill
                else -> p.idle
            },
        )
    }
    protected val islandFill get() = Color(res.getColor(NavR.color.bottom_nav_island_fill, null))

    protected fun Bitmap.at(x: Float, y: Float) = Color(getPixel(x.toInt(), y.toInt()))

    protected fun distance(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue), abs(a.alpha - b.alpha))

    protected fun near(msg: String, expected: Float, actual: Float) =
        org.junit.Assert.assertEquals("$msg: expected $expected px, measured $actual px", expected, actual, 1f)
}
