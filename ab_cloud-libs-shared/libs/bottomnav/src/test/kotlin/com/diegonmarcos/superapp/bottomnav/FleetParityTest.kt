package com.diegonmarcos.superapp.bottomnav

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The fleet's navigation is ONE look (#868 parity): Cloud SuperApp's. This is the proof, in three
 * layers.
 *
 *  1. GOLDEN. Every number and colour SuperApp's island and tab strip are made of, restated here
 *     ON PURPOSE as literals (the other tests read the resources back, so they cannot catch a
 *     resource that was edited). Changing one is a design decision and must change this file in the
 *     same commit.
 *  2. RENDERED. The island and the strip are drawn, then measured against those literals, and then
 *     drawn again inside a hostile app theme (a light scheme with red/green/blue roles, a serif
 *     typography with a 40sp line height): not one pixel or bound moves.
 *  3. ONE TREE. [BottomNavHost] (Compose shells) and [BottomNavIslandView] (View shells) render
 *     the same island, pixel for pixel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FleetParityTest {

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

    private val res get() = RuntimeEnvironment.getApplication().resources
    private fun dp(id: Int) = res.getDimension(id) // mdpi: 1dp == 1px
    private fun colour(id: Int) = Color(res.getColor(id, null))
    private lateinit var hostView: View

    // ── 1. GOLDEN: SuperApp's values ──────────────────────────────────────────────────────

    @Test
    fun `golden SuperApp island tokens are exactly SuperApp's`() {
        assertEquals(6f, dp(R.dimen.bottom_nav_pill_inset), 0f)
        assertEquals(6f, dp(R.dimen.bottom_nav_end_inset), 0f)
        assertEquals(6f, dp(R.dimen.bottom_nav_item_vertical_pad), 0f)
        assertEquals(8f, dp(R.dimen.bottom_nav_icon_label_gap), 0f)
        assertEquals(24f, dp(R.dimen.bottom_nav_icon_size), 0f)
        assertEquals(12f, dp(R.dimen.bottom_nav_label_text_size), 0f)
        assertEquals(12f, dp(R.dimen.bottom_nav_island_bottom_margin), 0f)
        assertEquals(0.8f, res.getFraction(R.fraction.bottom_nav_width_fraction, 1, 1), 1e-6f)
        assertEquals(220, res.getInteger(R.integer.bottom_nav_collapse_ms))
        assertEquals(Color(0xFF140B26), colour(R.color.bottom_nav_island_fill))
        assertEquals(Color(0xFFE6E0E9), colour(R.color.bottom_nav_pill_fill))
        assertEquals(Color(0xFF322F35), colour(R.color.bottom_nav_pill_ink))
        assertEquals(Color(0xFFCAC4D0), colour(R.color.bottom_nav_idle_ink))
    }

    @Test
    fun `golden the island's static ink is Material 3 dark, what SuperApp's theme resolves to`() {
        val m3 = darkColorScheme()
        assertEquals(m3.inverseSurface, colour(R.color.bottom_nav_pill_fill))
        assertEquals(m3.inverseOnSurface, colour(R.color.bottom_nav_pill_ink))
        assertEquals(m3.onSurfaceVariant, colour(R.color.bottom_nav_idle_ink))
    }

    @Test
    fun `golden SuperApp tab strip tokens are exactly SuperApp's`() {
        assertEquals(4f, dp(R.dimen.page_tabs_strip_padding), 0f)
        assertEquals(10f, dp(R.dimen.page_tabs_top_inset), 0f)
        assertEquals(10f, dp(R.dimen.page_tabs_bottom_inset), 0f)
        assertEquals(14f, dp(R.dimen.page_tabs_pill_pad_h), 0f)
        assertEquals(8f, dp(R.dimen.page_tabs_pill_pad_v), 0f)
        assertEquals(3f, dp(R.dimen.page_tabs_pill_margin), 0f)
        assertEquals(18f, dp(R.dimen.page_tabs_pill_radius), 0f)
        assertEquals(1f, dp(R.dimen.page_tabs_pill_stroke), 0f)
        assertEquals(12f, dp(R.dimen.page_tabs_text_size), 0f)
        assertEquals(8f, dp(R.dimen.page_tabs_text_min_size), 0f)
        assertEquals(4f, dp(R.dimen.page_tabs_divider_pad), 0f)
        assertEquals(80, res.getInteger(R.integer.page_tabs_letter_spacing_milli))
        assertEquals(7, res.getInteger(R.integer.page_tabs_min_chars))
        assertEquals(Color(0x447C3AED), colour(R.color.page_tabs_selected_fill))
        assertEquals(Color(0x66E9D8FD), colour(R.color.page_tabs_selected_stroke))
        assertEquals(Color(0xFFFFFFFF), colour(R.color.page_tabs_selected_text))
        assertEquals(Color(0x22FFFFFF), colour(R.color.page_tabs_idle_fill))
        assertEquals(Color(0x33FFFFFF), colour(R.color.page_tabs_idle_stroke))
        assertEquals(Color(0xAAFFFFFF), colour(R.color.page_tabs_idle_text))
    }

    @Test
    fun `golden SuperApp's press feedback is Compose's default indication`() {
        assertEquals(0.3f, FleetIndication.PRESSED, 0f)
        assertEquals(0.1f, FleetIndication.HOVERED, 0f)
    }

    // ── 2. RENDERED ───────────────────────────────────────────────────────────────────────

    @Composable
    private fun entries(): List<BottomNavEntry> = listOf("Mail", "Chat", "Home").map {
        BottomNavEntry(it.lowercase(), it, painterResource(android.R.drawable.ic_menu_add))
    }

    @Composable
    private fun androidx.compose.foundation.layout.BoxScope.HostedIsland(tag: String) {
        BottomNavIsland(entries(), "chat", {}, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).testTag(tag))
    }

    private fun bounds(tag: String, index: Int = 0): Rect =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)[index].fetchSemanticsNode().boundsInRoot

    private fun windowBounds(tag: String, index: Int): Rect =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)[index].fetchSemanticsNode().boundsInWindow

    private fun textLayout(tag: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    private fun capture(): Bitmap = compose.runOnUiThread {
        Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888).also { hostView.draw(Canvas(it)) }
    }

    /** [b] is in window coordinates; the bitmap is the host view's, so shift by where the view sits. */
    private fun crop(full: Bitmap, b: Rect, windowSpace: Boolean = false): PixelMap {
        val at = IntArray(2).also { if (windowSpace) hostView.getLocationInWindow(it) }
        val x = (b.left - at[0]).roundToInt()
        val y = (b.top - at[1]).roundToInt()
        assertTrue("crop $b (shift ${at.toList()}) is outside the ${full.width}x${full.height} capture", x >= 0 && y >= 0 && x + b.width.roundToInt() <= full.width && y + b.height.roundToInt() <= full.height)
        return Bitmap.createBitmap(full, x, y, b.width.roundToInt(), b.height.roundToInt()).asImageBitmap().toPixelMap()
    }

    private fun distance(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue), abs(a.alpha - b.alpha))

    @Test
    fun `rendered island matches SuperApp's geometry and type`() {
        compose.setContent {
            hostView = LocalView.current
            Box(Modifier.fillMaxSize().background(Color.Black).testTag("root")) { HostedIsland("slot") }
        }
        compose.waitForIdle()
        val root = bounds("root")
        val island = bounds(TAG_ISLAND)
        assertEquals("island width is 80% of the screen", 288f, island.width, 1f)
        assertEquals("island is centred", 36f, island.left, 1f)
        assertEquals("one 12dp clearance below it (no inset in this window)", 12f, root.bottom - island.bottom, 1f)
        assertEquals("the icon is 24dp", 24f, bounds(iconTag("chat")).width, 1f)
        assertEquals("the icon is 24dp", 24f, bounds(iconTag("chat")).height, 1f)
        assertEquals("capsule inset from the island's top", 6f, bounds(itemTag("chat")).top - island.top, 1f)
        assertEquals("icon to label gap", 8f, bounds(labelTag("chat")).top - bounds(iconTag("chat")).bottom, 1f)
        val style = textLayout(labelTag("chat")).layoutInput.style
        assertEquals("label is 12sp", 12f, style.fontSize.value, 0.01f)
        assertEquals("label uses the platform default family", FontFamily.Default, style.fontFamily)
        assertEquals("label is regular weight", FontWeight.Normal, style.fontWeight)
        assertTrue("no letter spacing is inherited", style.letterSpacing == androidx.compose.ui.unit.TextUnit.Unspecified)
        assertTrue("no line height is inherited", style.lineHeight == androidx.compose.ui.unit.TextUnit.Unspecified)
    }

    @Test
    fun `an app's MaterialTheme cannot change one pixel or bound of the island`() {
        val hostile = lightColorScheme(inverseSurface = Color.Red, inverseOnSurface = Color.Green, onSurfaceVariant = Color.Blue, background = Color.Magenta)
        val loud = Typography(
            bodyLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, lineHeight = 40.sp, letterSpacing = 4.sp),
        )
        var themed by mutableStateOf(false)
        compose.setContent {
            hostView = LocalView.current
            Box(Modifier.fillMaxSize().background(Color.Black).testTag("root")) {
                if (themed) MaterialTheme(colorScheme = hostile, typography = loud) { HostedIsland("slot") }
                else HostedIsland("slot")
            }
        }
        compose.waitForIdle()
        val plainBounds = bounds(TAG_ISLAND)
        val plainLabel = bounds(labelTag("chat"))
        val plainPixels = crop(capture(), plainBounds)
        val plainStyle = textLayout(labelTag("chat")).layoutInput.style

        themed = true
        compose.waitForIdle()
        assertEquals("island bounds moved under an app theme", plainBounds, bounds(TAG_ISLAND))
        assertEquals("label bounds moved under an app theme", plainLabel, bounds(labelTag("chat")))
        val style = textLayout(labelTag("chat")).layoutInput.style
        assertEquals(plainStyle.fontFamily, style.fontFamily)
        assertEquals(plainStyle.fontWeight, style.fontWeight)
        assertEquals(plainStyle.lineHeight, style.lineHeight)
        assertEquals(plainStyle.letterSpacing, style.letterSpacing)
        val themedPixels = crop(capture(), bounds(TAG_ISLAND))
        var worst = 0f
        for (x in 0 until plainPixels.width) for (y in 0 until plainPixels.height) {
            worst = maxOf(worst, distance(plainPixels[x, y], themedPixels[x, y]))
        }
        assertTrue("an app theme changed the island's pixels (worst channel delta $worst)", worst <= 3f / 255f)
    }

    @Test
    fun `the selected pill and the idle ink are the fleet's scheme, whatever the app wears`() {
        compose.setContent {
            hostView = LocalView.current
            val ink = FleetChrome.ink()
            androidx.compose.runtime.SideEffect { expected = ink }
            MaterialTheme(colorScheme = lightColorScheme(inverseSurface = Color.Red, onSurfaceVariant = Color.Blue)) {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("root")) { HostedIsland("slot") }
            }
        }
        compose.waitForIdle()
        val pill = bounds(itemTag("chat"))
        val full = capture()
        // The capsule's top-left is a corner of a stadium; the centre of its top band is solid pill.
        val sample = crop(full, Rect(pill.center.x - 1f, pill.top + 1f, pill.center.x + 1f, pill.top + 3f))[0, 0]
        assertTrue("the selected capsule is $sample, not the fleet pill ${expected.pill}", distance(sample, expected.pill) <= 3f / 255f)
        assertFalse("the app's red leaked into the island", distance(sample, Color.Red) < 0.2f)
    }

    private var expected = FleetChrome.Ink(Color.Unspecified, Color.Unspecified, Color.Unspecified)

    @Test
    fun `FleetChrome puts every window on SuperApp's transparent edge-to-edge chrome`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.window.statusBarColor = android.graphics.Color.RED
        activity.window.navigationBarColor = android.graphics.Color.RED
        FleetChrome.apply(activity)
        assertEquals(android.graphics.Color.TRANSPARENT, activity.window.statusBarColor)
        assertEquals(android.graphics.Color.TRANSPARENT, activity.window.navigationBarColor)
        assertFalse(activity.window.isStatusBarContrastEnforced)
        assertFalse(activity.window.isNavigationBarContrastEnforced)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, activity.window.attributes.layoutInDisplayCutoutMode)
        assertEquals(FleetChrome.BLUR_BEHIND_RADIUS, activity.window.attributes.blurBehindRadius)
    }

    // ── 3. ONE TREE ───────────────────────────────────────────────────────────────────────

    @Test
    fun `BottomNavHost and BottomNavIslandView render the same island pixel for pixel`() {
        compose.setContent {
            hostView = LocalView.current
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    BottomNavHost(entries(), "chat", {}) {}
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                    AndroidView(factory = { ctx ->
                        BottomNavIslandView(ctx).apply {
                            items = listOf("mail" to "Mail", "chat" to "Chat", "home" to "Home")
                                .map { BottomNavViewItem(it.first, it.second, android.R.drawable.ic_menu_add) }
                            selectedId = "chat"
                        }
                    }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(TAG_ISLAND, useUnmergedTree = true).let {
            assertEquals("expected one island per host", 2, it.fetchSemanticsNodes().size)
        }
        val a = windowBounds(TAG_ISLAND, 0)
        val b = windowBounds(TAG_ISLAND, 1)
        assertEquals("size differs between the two hosts", a.width to a.height, b.width to b.height)
        val full = capture()
        val pa = crop(full, a, true)
        val pb = crop(full, b, true)
        var worst = 0f
        for (x in 0 until pa.width) for (y in 0 until pa.height) worst = maxOf(worst, distance(pa[x, y], pb[x, y]))
        assertTrue("the two hosts draw different pixels (worst channel delta $worst)", worst <= 3f / 255f)
        for (id in listOf("mail", "chat", "home")) {
            val ia = windowBounds(itemTag(id), 0)
            val ib = windowBounds(itemTag(id), 1)
            assertEquals("item $id differs in size", ia.width to ia.height, ib.width to ib.height)
            assertEquals("item $id sits differently inside its island", ia.left - a.left, ib.left - b.left, 0.5f)
            assertEquals("item $id sits differently inside its island", ia.top - a.top, ib.top - b.top, 0.5f)
        }
        // And both hosts end in the one composable: there is no second drawing path to drift.
        val hostSrc = java.io.File("src/main/kotlin/com/diegonmarcos/superapp/bottomnav").walk().filter { it.extension == "kt" }
            .associate { it.name to it.readText() }
        assertTrue(hostSrc.getValue("BottomNavIslandView.kt").contains("BottomNavIslandImpl("))
        assertTrue(hostSrc.getValue("BottomNavBar.kt").contains("BottomNavIslandImpl("))
        assertFalse("the View host must not carry its own colour scheme", hostSrc.getValue("BottomNavIslandView.kt").contains("colorScheme"))
        assertFalse("the tab strip view must not carry its own colour scheme", hostSrc.getValue("PageTabsView.kt").contains("colorScheme"))
    }
}
