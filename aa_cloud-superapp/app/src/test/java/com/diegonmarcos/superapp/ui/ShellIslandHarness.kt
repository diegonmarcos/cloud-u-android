package com.diegonmarcos.superapp.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
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
            ShellBottomNav.configure(nav, com.diegonmarcos.superapp.launcher.Sections.defaultMode())
            nav.selectedId = select
            nav.onSelect = { picked += it; nav.selectedId = it }
            nav.onReselect = { repicked += it }
            frame = FrameLayout(themed).apply {
                addView(nav, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            }
            activity.setContentView(frame)
        }
        compose.waitForIdle()
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

    protected fun themeColor(attr: Int) = Color(MaterialColors.getColor(themed, attr, "test"))
    protected val islandFill get() = Color(res.getColor(NavR.color.bottom_nav_island_fill, null))

    protected fun Bitmap.at(x: Float, y: Float) = Color(getPixel(x.toInt(), y.toInt()))

    protected fun distance(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue), abs(a.alpha - b.alpha))

    protected fun near(msg: String, expected: Float, actual: Float) =
        org.junit.Assert.assertEquals("$msg: expected $expected px, measured $actual px", expected, actual, 1f)
}
