package com.diegonmarcos.superapp.wallet

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
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

/**
 * #531/#533 — Cloud Wallet's top-level tabs are the fleet's bottom nav, MEASURED.
 *
 * Renders the real [WalletBottomNav] the way WalletScreen places it (at the bottom of the
 * screen) and reads back what it draws. Expected values come from [WalletNavItem] (the one item
 * table), [walletNavScheme] and libs:bottomnav's dimens — nothing is restated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WalletBottomNavTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    // A library has no manifest entry for the activity the compose rule launches.
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

    private var tab by mutableStateOf(WalletTab.Pay)
    private val picked = mutableListOf<WalletTab>()
    private var meLaunches = 0
    private lateinit var host: View

    private val res get() = RuntimeEnvironment.getApplication().resources

    private fun show(start: WalletTab) {
        tab = start
        compose.setContent {
            host = LocalView.current
            Box(Modifier.fillMaxSize().testTag(ROOT)) {
                WalletBottomNav(
                    selected = tab,
                    onSelect = { picked += it; tab = it },
                    onOpenMe = { meLaunches++ },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun cell(item: WalletNavItem) = bounds(BottomNavTags.item(item.name))
    private fun paint(): Bitmap = compose.runOnUiThread {
        Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888).also { host.draw(Canvas(it)) }
    }
    private fun probe(bmp: Bitmap, item: WalletNavItem): Color {
        val c = cell(item)
        return Color(bmp.getPixel((c.left + 3 * res.displayMetrics.density).toInt(), c.center.y.toInt()))
    }
    private fun same(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue), abs(a.alpha - b.alpha)) <= 3f / 255f
    private val pill get() = walletNavScheme.inverseSurface
    private val fill get() = Color(res.getColor(NavR.color.bottom_nav_island_fill, null))
    private fun near(msg: String, expected: Float, actual: Float) =
        assertEquals("$msg: expected $expected px, measured $actual px", expected, actual, 1f)
    private fun of(t: WalletTab) = WalletNavItem.entries.first { it.tab == t }

    @Test
    fun `the tabs are the item table, left to right, labelled by it`() {
        show(WalletTab.Pay)
        val xs = WalletNavItem.entries.map { cell(it).left }
        assertEquals("the items are not drawn in WalletNavItem order: $xs", xs.sorted(), xs)
        for (item in WalletNavItem.entries) {
            compose.onNodeWithTag(BottomNavTags.label(item.name), useUnmergedTree = true).assertTextEquals(item.label)
        }
    }

    @Test
    fun `the bar sits at the bottom, 80 percent wide, clearing one dimen and no inset`() {
        show(WalletTab.Pay)
        val margin = res.getDimension(NavR.dimen.bottom_nav_island_bottom_margin)
        val fraction = res.getFraction(NavR.fraction.bottom_nav_width_fraction, 1, 1)
        for (inset in listOf(0, 48)) {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, inset))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
            compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host, insets) }
            compose.waitForIdle()
            near("island clearance above the screen's bottom edge ($inset px nav-bar inset)",
                margin, bounds(ROOT).bottom - bounds(BottomNavTags.ISLAND).bottom)
        }
        near("island width", bounds(ROOT).width * fraction, bounds(BottomNavTags.ISLAND).width)
    }

    @Test
    fun `only the selected tab wears the light pill`() {
        show(WalletTab.Pay)
        assertTrue("the pill IS the island fill, the probe cannot tell them apart", !same(pill, fill))
        val bmp = paint()
        for (item in WalletNavItem.entries) {
            val got = probe(bmp, item)
            if (item.tab == WalletTab.Pay) assertTrue("${item.name} painted $got, not the pill $pill", same(got, pill))
            else assertTrue("${item.name} painted $got, not the island fill $fill", same(got, fill))
        }
    }

    @Test
    fun `a tab tap moves the pill, Me launches and the pill stays`() {
        show(WalletTab.Pay)
        val target = WalletNavItem.entries.last { it.tab != null && it.tab != WalletTab.Pay }
        compose.onNodeWithTag(BottomNavTags.item(target.name), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("a tap on ${target.name} did not select its tab", listOf(target.tab), picked)
        assertTrue("the pill did not move to ${target.name}", same(probe(paint(), target), pill))

        val me = WalletNavItem.entries.first { it.tab == null }
        compose.onNodeWithTag(BottomNavTags.item(me.name), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("${me.name} did not launch", 1, meLaunches)
        assertEquals("${me.name} selected a tab inside the wallet", listOf(target.tab), picked)
        val bmp = paint()
        assertTrue("the pill left ${target.name}", same(probe(bmp, target), pill))
        assertTrue("${me.name} lit up", same(probe(bmp, me), fill))
    }

    @Test
    fun `Config lights no item, and returning to a tab lights exactly that one`() {
        show(WalletTab.Config)
        compose.onAllNodes(isSelected()).assertCountEquals(0)
        compose.runOnUiThread { tab = WalletTab.IDs }
        compose.waitForIdle()
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        assertEquals("the lit item is not IDs' capsule", cell(of(WalletTab.IDs)),
            compose.onAllNodes(isSelected())[0].fetchSemanticsNode().boundsInRoot)
    }

    private companion object { const val ROOT = "wallet_root" }
}
