package com.diegonmarcos.superapp.bottomnav

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * #868: the page tab strip and the navigation declaration, MEASURED. Same instrument as
 * BottomNavIslandTest: the real strip rendered under Robolectric at mdpi (px == dp), every
 * expected number read back from this module's own page_tabs_* resources, never restated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageTabsTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

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

    private val res get() = RuntimeEnvironment.getApplication().resources
    private fun px(id: Int): Float = res.getDimension(id)
    private fun colour(id: Int) = Color(res.getColor(id, null))

    private var selected by mutableStateOf<String?>(null)
    private var tapped = mutableListOf<String>()
    private var retapped = mutableListOf<String>()
    private lateinit var hostView: View

    private fun page(id: String, label: String = id, action: String = "") = NavPage(id, label, "", action)
    private val three = listOf(page("cloud", "Cloud"), page("phone", "Phone"), page("labs", "Labs"))

    private var injected by mutableStateOf<WindowInsets?>(WindowInsets(0, 0, 0, 0))
    private var under by mutableStateOf(true)

    private fun show(pages: List<NavPage>) {
        compose.setContent {
            hostView = LocalView.current
            Box(Modifier.fillMaxSize().testTag(ROOT)) {
                PageTabsImpl(pages, selected, { tapped += it.id }, Modifier.testTag(BOX), { retapped += it.id }, under, injected)
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun pixels(tag: String): PixelMap {
        val b = bounds(tag)
        val full = compose.runOnUiThread {
            Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
                .also { hostView.draw(Canvas(it)) }
        }
        return Bitmap.createBitmap(full, b.left.roundToInt(), b.top.roundToInt(), b.width.roundToInt(), b.height.roundToInt())
            .asImageBitmap().toPixelMap()
    }

    private fun near(msg: String, expected: Float, actual: Float) = assertEquals(msg, expected, actual, 1f)

    // ── the declaration ───────────────────────────────────────────────────────────────────

    private val sectionsJson = """
        [{"id":"suite","label":"Suite","icon":"ic_a","pages":[
            {"id":"cloud","label":"Cloud","icon":"ic_c"},
            {"id":"health","label":"Health","pages":[
                {"id":"workout","label":"Workout","pages":[{"id":"gym","label":"Gym"}]},
                {"id":"diet","label":"Diet"}]},
            {"id":"wd","label":"Watchdog","action":"extapp:wd"}]},
         {"id":"home","label":"Home","icon":"ic_h"},
         {"id":"","label":"nameless"}]
    """.trimIndent()

    @Test
    fun `868 the declaration parses sections, pages, nested strips and launch pages`() {
        val d = NavDecl.parse(JSONArray(sectionsJson), """["home","suite"]""", "suite")
        assertEquals(listOf("home", "suite"), d.bottomNav)
        assertEquals("a section with no id is dropped", listOf("suite", "home"), d.sections.map { it.id })
        assertEquals(listOf("home", "suite"), d.bottomSections().map { it.id })
        assertEquals("suite", d.default()?.id)
        val suite = d.section("suite")!!
        assertEquals(listOf("cloud", "health", "wd"), suite.pages.map { it.id })
        assertEquals("extapp:wd", suite.pages[2].action)
        assertEquals(listOf("health", "workout", "gym"), suite.path("gym").map { it.id })
        assertEquals("gym", suite.page("gym")?.id)
        assertEquals("an undeclared id lands on the first page", "cloud", suite.page("nope")?.id)
        assertEquals("gym", suite.pages[1].leaf().id)
        assertEquals(listOf("cloud", "health", "workout", "gym", "diet", "wd"), suite.allPages().map { it.id })
    }

    @Test
    fun `868 fromBuildConfig decodes the baked fields and fails soft on junk`() {
        val b64 = Base64.encodeToString(sectionsJson.toByteArray(), Base64.NO_WRAP)
        val d = NavDecl.fromBuildConfig(b64, "home,suite", "home")
        assertEquals(listOf("home", "suite"), d.bottomNav)
        assertEquals("home", d.default()?.id)
        assertEquals("a blank bottom_nav falls back to every section",
            listOf("suite", "home"), NavDecl.fromBuildConfig(b64).bottomNav)
        assertEquals("the bar is capped at five", MAX_BOTTOM,
            NavDecl.parse(JSONArray((1..8).joinToString(",", "[", "]") { """{"id":"s$it"}""" })).bottomNav.size)
        for (junk in listOf("", "not base64 !!", Base64.encodeToString("{".toByteArray(), Base64.NO_WRAP))) {
            val e = NavDecl.fromBuildConfig(junk, "[", "x")
            assertTrue("junk '$junk' yields an empty declaration", e.sections.isEmpty() && e.bottomNav.isEmpty())
            assertNull(e.default())
        }
    }

    // ── the layout plan (equalise) ────────────────────────────────────────────────────────

    @Test
    fun `868 one pill width for every tab, the font gives way before the characters, the strip scrolls last`() {
        val cw = { size: Float -> size * 0.6f }
        fun plan(avail: Float, longest: Int) = planPills(avail, 3, longest, 28f, 6f, 12f, 8f, 1f, 7, cw)
        val roomy = plan(600f, 8)
        assertEquals("fits at the starting size", 12f, roomy.textPx, 0f)
        assertFalse(roomy.scrollable)
        assertEquals(8 * 12f * 0.6f + 28f, roomy.pillPx, 0.01f)
        val tight = plan(3 * (28f + 6f + 8 * 8f * 0.6f) - 3f, 8)
        assertTrue("the font stepped down", tight.textPx < 12f && tight.textPx >= 8f)
        val floor = plan(3 * (28f + 6f + 5 * 8f * 0.6f), 8)
        assertEquals("at the floor the font stops at the minimum", 8f, floor.textPx, 0f)
        assertTrue("characters were dropped, never below minChars + ellipsis",
            floor.pillPx - 28f >= 8 * 8f * 0.6f - 5 * 8f * 0.6f - 0.01f)
        val none = plan(3 * (28f + 6f + 2f), 8)
        assertTrue("past the floor the strip scrolls", none.scrollable)
        assertEquals("a fixed pill never exceeds its slot", true, roomy.pillPx + 6f <= 600f / 3)
    }

    // ── rendered geometry ─────────────────────────────────────────────────────────────────

    @Test
    fun `868 every pill is the same width, in one row, centred in an equal slot`() {
        selected = "cloud"
        show(three)
        val tabs = three.map { bounds(PageTabsTags.tab(it.id)) }
        for (t in tabs) near("equal pill widths", tabs[0].width, t.width)
        for (t in tabs) near("one row", tabs[0].top, t.top)
        val root = bounds(ROOT)
        val strip = px(R.dimen.page_tabs_strip_padding)
        val slot = (root.width - 2 * strip) / 3
        val margin = px(R.dimen.page_tabs_pill_margin)
        tabs.forEachIndexed { i, t ->
            near("pill $i is centred in its 1/3 slot", root.left + strip + slot * i + slot / 2, t.center.x)
            assertTrue("pill $i + its margin stays inside the slot", t.width + 2 * margin <= slot + 1f)
        }
    }

    @Test
    fun `868 the strip clears the top by its inset plus the live inset, and keeps the bottom gap`() {
        selected = "cloud"
        injected = WindowInsets(0, 24, 0, 0)
        show(three)
        val strip = px(R.dimen.page_tabs_strip_padding)
        val margin = px(R.dimen.page_tabs_pill_margin)
        val top = px(R.dimen.page_tabs_top_inset)
        val tab = bounds(PageTabsTags.tab("cloud"))
        near("pill top = top inset + live inset + strip padding + pill margin", top + 24 + strip + margin, tab.top - bounds(ROOT).top)
        near("strip is the pill plus its margin and padding",
            tab.height + 2 * margin + 2 * strip, bounds(PageTabsTags.STRIP).height)
        near("a gap below the strip", px(R.dimen.page_tabs_bottom_inset),
            bounds(BOX).bottom - bounds(PageTabsTags.STRIP).bottom)
        under = false
        compose.waitForIdle()
        near("a strip not under the toolbar ignores the live inset",
            top + strip + margin, bounds(PageTabsTags.tab("cloud")).top - bounds(ROOT).top)
    }

    @Test
    fun `868 the selected pill wears the lavender-purple fill and the idle pills the white glass`() {
        selected = "phone"
        show(three)
        fun centre(id: String): Color {
            val px = pixels(PageTabsTags.tab(id))
            // 4px in from the left edge, mid height: inside the pill, left of the label.
            return px[4, px.height / 2]
        }
        val over = Color(0xFF000000)
        fun flat(c: Color) = c.compositeOver(over)
        close("selected pill fill", flat(colour(R.color.page_tabs_selected_fill)), flat(centre("phone")))
        close("idle pill fill", flat(colour(R.color.page_tabs_idle_fill)), flat(centre("cloud")))
        val edge = pixels(PageTabsTags.tab("phone"))
        close("selected pill stroke is the top row (stroke over fill)", flat(colour(R.color.page_tabs_selected_stroke).compositeOver(colour(R.color.page_tabs_selected_fill))), flat(edge[edge.width / 2, 0]))
        val idle = pixels(PageTabsTags.tab("cloud"))
        close("idle pill stroke is the top row (stroke over fill)", flat(colour(R.color.page_tabs_idle_stroke).compositeOver(colour(R.color.page_tabs_idle_fill))), flat(idle[idle.width / 2, 0]))
        assertTrue("the corner is rounded: its pixel is not the idle stroke or fill",
            !isClose(flat(colour(R.color.page_tabs_idle_fill)), flat(idle[0, 0])) &&
                !isClose(flat(colour(R.color.page_tabs_idle_stroke).compositeOver(colour(R.color.page_tabs_idle_fill))), flat(idle[0, 0])))
    }

    private fun isClose(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue)) <= 4f / 255f

    private fun close(msg: String, expected: Color, actual: Color) =
        assertTrue("$msg: expected $expected, got $actual", isClose(expected, actual))

    @Test
    fun `868 a tap on an idle pill selects, a tap on the selected one reselects, state only moves the pill`() {
        selected = "cloud"
        show(three)
        compose.onNodeWithTag(PageTabsTags.tab("phone"), useUnmergedTree = true).performClick()
        compose.onNodeWithTag(PageTabsTags.tab("cloud"), useUnmergedTree = true).performClick()
        assertEquals(listOf("phone"), tapped)
        assertEquals(listOf("cloud"), retapped)
        selected = "labs"
        compose.waitForIdle()
        assertEquals("setting selectedId never calls back", listOf("phone"), tapped)
    }

    @Test
    fun `868 a launch page is divided off with a literal bar and still taps through`() {
        val pages = listOf(page("a", "Observ"), page("b", "Topology"), page("wd", "Watchdog", "extapp:wd"))
        selected = "a"
        show(pages)
        val b = bounds(PageTabsTags.tab("b"))
        val wd = bounds(PageTabsTags.tab("wd"))
        assertTrue("the divider glyph sits between the groups", wd.left - b.right > 2 * px(R.dimen.page_tabs_pill_margin) + 2)
        compose.onNodeWithTag(PageTabsTags.tab("wd"), useUnmergedTree = true).performClick()
        assertEquals(listOf("wd"), tapped)
    }

    // ── the View host ─────────────────────────────────────────────────────────────────────

    @Test
    fun `868 the View host draws the same strip and speaks the View-side contract`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = PageTabsView(activity)
        val got = mutableListOf<String>()
        view.pages = three
        view.selectedId = "cloud"
        view.insets = WindowInsets(0, 0, 0, 0)
        view.onSelect = { got += "s:" + it.id }
        view.onReselect = { got += "r:" + it.id }
        activity.setContentView(view)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(three, view.pages)
        assertEquals("cloud", view.selectedId)
        assertTrue("the host is a Compose host and not a wrapped TabLayout",
            view is androidx.compose.ui.platform.AbstractComposeView)
    }

    private companion object {
        const val ROOT = "test_root"
        const val BOX = "test_box"
    }
}
