package com.diegonmarcos.superapp.bottomnav

import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * The fleet island's and the page-tab strip's ACCESSIBILITY, measured on the real composables under
 * Robolectric, in the instrument BottomNavIslandTest uses (mdpi, so 1dp == 1px, on a 360dp phone,
 * the narrowest the fleet targets):
 *
 *  - TalkBack: every item is a Tab with a name and a selected state, inside a tab list that carries
 *    its collection info (so TalkBack can say "2 of 7"); the name survives an ellipsized label.
 *  - Touch: every item's touch target is at least 48dp x 48dp and no two overlap, collapsed or not,
 *    at five items AND at seven (Cloud Code's declaration), which is what the scroll fallback is for.
 *  - Keyboard / D-pad / switch access: focus walks the items left to right, Enter selects, and the
 *    focused item draws a ring you can see on the dark island.
 *  - Font scale 1.3 and 2.0: nothing crashes, overlaps or leaves its cell.
 *  - Contrast: WCAG AA computed from [NavTokens] (4.5:1 text, 3:1 icons and indicators) for the web
 *    literals and for Android's resolved tokens in light AND dark mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavAccessibilityTest {

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
    private fun px(id: Int): Float = res.getDimension(id)

    private var selectedId by mutableStateOf<String?>(null)
    private var collapsed by mutableStateOf(false)
    private val picked = mutableListOf<String>()
    private lateinit var hostView: View
    private lateinit var focus: FocusManager
    private lateinit var inputMode: InputModeManager
    private var ink = FleetChrome.Ink(Color.Unspecified, Color.Unspecified, Color.Unspecified)

    @Composable
    private fun entries(fixture: List<Triple<String, String, ImageVector>>) =
        fixture.map { BottomNavEntry(it.first, it.second, rememberVectorPainter(it.third)) }

    private var fixture by mutableStateOf(MAIL)
    private var pages by mutableStateOf<List<NavPage>?>(null)
    private var contentSet = false

    /** One setContent per test (the rule allows no second); later calls only move state. [pages]
     *  non-null draws the page-tab strip over a black ground instead of the island. */
    private fun ensureContent() {
        if (contentSet) { compose.waitForIdle(); return }
        contentSet = true
        compose.setContent {
            hostView = LocalView.current
            focus = LocalFocusManager.current
            inputMode = LocalInputModeManager.current
            ink = FleetChrome.ink()
            val strip = pages
            if (strip == null) {
                Box(Modifier.fillMaxSize().background(Color(0xFF808080)).testTag(ROOT)) {
                    BottomNavIslandImpl(
                        entries(fixture), selectedId, { picked += it.id; selectedId = it.id },
                        Modifier.align(Alignment.BottomCenter), collapsed, WindowInsets(0, 0, 0, 0),
                    ) { Modifier }
                }
            } else {
                Column(Modifier.fillMaxSize().background(Color.Black).testTag(ROOT)) {
                    PageTabsImpl(strip, selectedId, { picked += it.id }, Modifier, { picked += "again:" + it.id }, false, WindowInsets(0, 0, 0, 0))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun showIsland(items: List<Triple<String, String, ImageVector>>, select: String?) {
        pages = null
        fixture = items
        selectedId = select
        ensureContent()
    }

    private fun node(tag: String): SemanticsNode = compose.onNodeWithTag(tag).fetchSemanticsNode()
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** Where the node is laid out, NOT clipped by a scrolling island: an item scrolled out of view
     *  still has its real width here. */
    private fun laidOut(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }

    /** What TalkBack reads for a merged node: its content description, else its text. */
    private fun spoken(n: SemanticsNode): String =
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

    private fun pixels(b: Rect): PixelMap {
        val full = compose.runOnUiThread {
            Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888).also { hostView.draw(Canvas(it)) }
        }
        return Bitmap.createBitmap(full, b.left.roundToInt(), b.top.roundToInt(), b.width.roundToInt(), b.height.roundToInt())
            .asImageBitmap().toPixelMap()
    }

    private fun distance(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    /**
     * Every touch target at least 48 x 48, and no two overlap. A touch target is what Compose hit-tests
     * and reports to TalkBack: the node's own bounds, grown to the 48dp minimum around its centre when
     * smaller. It is computed from the UNCLIPPED layout bounds, so an item scrolled out of a scrolling
     * island is judged too; an item fully in view is also checked against Compose's own touchBoundsInRoot.
     */
    private fun assertTouchTargets(why: String, tags: List<String>) {
        val floor = px(R.dimen.bottom_nav_min_touch_target)
        val touch = tags.map { tag ->
            val r = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
            val w = maxOf(r.width, floor); val h = maxOf(r.height, floor)
            Rect(r.center.x - w / 2, r.center.y - h / 2, r.center.x + w / 2, r.center.y + h / 2)
        }
        println("#a11y MEASURED $why touch targets: " + touch.joinToString { "${it.width.roundToInt()}x${it.height.roundToInt()}@${it.left.roundToInt()}" })
        touch.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("$why: the touch targets of ${tags[i]} ($a) and ${tags[i + 1]} ($b) overlap", a.right <= b.left + 0.5f)
        }
        tags.forEachIndexed { i, tag ->
            val n = compose.onNodeWithTag(tag).fetchSemanticsNode()
            // Partly or wholly scrolled out of the island: its on-screen touch area is what is in view.
            if (n.boundsInRoot.width < compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.right - it.left }.value - 0.5f) return@forEachIndexed
            val t = n.touchBoundsInRoot
            assertTrue("$why: $tag touch target ${t.width}x${t.height} is under ${floor}dp", t.width >= floor - 0.5f && t.height >= floor - 0.5f)
        }
    }

    // ── TalkBack ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a11y every island item is a Tab with a name and a selected state, expanded and collapsed`() {
        for (state in listOf(false, true)) {
            collapsed = state
            showIsland(MAIL, "home")
            for ((id, label) in MAIL.map { it.first to it.second }) {
                val n = node(itemTag(id))
                val why = "${if (state) "collapsed" else "expanded"} item $id"
                assertEquals("$why: role", Role.Tab, n.config.getOrNull(SemanticsProperties.Role))
                assertEquals("$why: selected state", id == "home", n.config.getOrNull(SemanticsProperties.Selected))
                assertEquals("$why: TalkBack name", label, spoken(n))
            }
        }
    }

    @Test
    fun `a11y the island is a tab list that carries its collection info, and each item its index`() {
        showIsland(CLOUD_CODE, "repos")
        val info = node(TAG_ISLAND).config.getOrNull(SemanticsProperties.CollectionInfo)
        assertNotNull("the island exposes no collection info, so TalkBack cannot say 'tab 3 of 7'", info)
        assertEquals(CollectionInfo(rowCount = 1, columnCount = CLOUD_CODE.size).let { it.rowCount to it.columnCount }, info!!.rowCount to info.columnCount)
        assertTrue("the island is a selectable group", node(TAG_ISLAND).config.contains(SemanticsProperties.SelectableGroup))
        CLOUD_CODE.forEachIndexed { i, e ->
            val item = node(itemTag(e.first)).config.getOrNull(SemanticsProperties.CollectionItemInfo)
            assertNotNull("item ${e.first} carries no collection item info", item)
            assertEquals("item ${e.first} column", i, item!!.columnIndex)
            assertEquals("item ${e.first} row", 0, item.rowIndex)
        }
    }

    @Test
    fun `a11y an ellipsized label keeps its whole name for TalkBack`() {
        showIsland(CLOUD_CODE, "editor")
        assertEquals("MyTerminal", spoken(node(itemTag("myterminal"))))
    }

    // ── touch targets ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a11y five items on 360dp - every touch target is 48dp or more and none overlap, expanded and collapsed`() {
        for (state in listOf(false, true)) {
            collapsed = state
            showIsland(MAIL, "home")
            assertTouchTargets("5 items ${if (state) "collapsed" else "expanded"}", MAIL.map { itemTag(it.first) })
        }
    }

    @Test
    fun `a11y seven items on 360dp - every touch target is 48dp or more and none overlap, expanded and collapsed`() {
        for (state in listOf(false, true)) {
            collapsed = state
            showIsland(CLOUD_CODE, "editor")
            assertTouchTargets("7 items ${if (state) "collapsed" else "expanded"}", CLOUD_CODE.map { itemTag(it.first) })
        }
    }

    @Test
    fun `a11y a tap on each of seven items selects exactly that item`() {
        showIsland(CLOUD_CODE, "editor")
        for (e in CLOUD_CODE) {
            // An item scrolled out of the island is scrolled in first, as a finger would swipe to it.
            val item = compose.onNodeWithTag(itemTag(e.first))
            if (item.fetchSemanticsNode().boundsInRoot.width < laidOut(itemTag(e.first)).width - 0.5f) item.performScrollTo()
            item.performClick()
            compose.waitForIdle()
        }
        assertEquals(CLOUD_CODE.map { it.first }, picked)
    }

    // ── narrow width: the scroll fallback ─────────────────────────────────────────────────

    @Test
    fun `a11y the plan keeps equal cells while they fit and scrolls below the floor`() {
        val t = NavTokens()
        fun plan(width: Int, n: Int) = planIsland(width.dp, t.widthFraction, t.endInset, n, t)
        // 360dp: the island is 288dp, 276dp of cells.
        assertFalse("five items on 360dp keep equal 55dp cells", plan(360, 5).scrolls)
        assertEquals(55.2f, plan(360, 5).cell.value, 0.01f)
        assertTrue("six items on 360dp (46dp) are under the 56dp floor: scroll", plan(360, 6).scrolls)
        assertTrue("seven items on 360dp (39dp) scroll", plan(360, 7).scrolls)
        assertEquals("a scrolling cell is minCellWidth", t.minCellWidth, plan(360, 7).cell)
        assertTrue("seven items on a 412dp phone (45dp) still scroll", plan(412, 7).scrolls)
        assertFalse("seven items on a 600dp tablet (67dp) keep equal cells", plan(600, 7).scrolls)
        assertTrue("five items in a 280dp split window (44dp) fall to the 48dp floor and scroll", plan(280, 5).scrolls)
        assertEquals(t.minTouchTarget, plan(280, 5).cell)
        // The selected cell is centred, clamped to the row's ends.
        val p = plan(360, 7)
        assertEquals(0f, islandScrollTarget(p, 0).value, 0.01f)
        val max = (p.endInset * 2 + p.cell * 7 - p.viewport).value
        assertEquals(max, islandScrollTarget(p, 6).value, 0.01f)
        assertEquals(6f + 56f * 3 + 28f - 144f, islandScrollTarget(p, 3).value, 0.01f)
    }

    @Test
    fun `a11y seven items scroll in 56dp cells inside the 80 percent pill and keep the selected item in view`() {
        showIsland(CLOUD_CODE, "editor")
        val island = bounds(TAG_ISLAND)
        assertEquals("the island is still 80% of the screen", bounds(ROOT).width * 0.8f, island.width, 1f)
        for (e in CLOUD_CODE) {
            assertEquals("cell ${e.first} is minCellWidth wide, not crushed", px(R.dimen.bottom_nav_min_cell_width), laidOut(itemTag(e.first)).width, 1f)
        }
        for (sel in listOf("myterminal", "home", "backlog", "browser")) {
            selectedId = sel
            compose.waitForIdle()
            val item = laidOut(itemTag(sel))
            println("#a11y MEASURED selected $sel at ${item.left}..${item.right} in island ${island.left}..${island.right}")
            assertTrue("selected $sel ($item) is scrolled out of the island ($island)",
                item.left >= island.left - 0.5f && item.right <= island.right + 0.5f)
        }
    }

    @Test
    fun `a11y five items keep the equal cells they always had`() {
        showIsland(MAIL, "home")
        val island = bounds(TAG_ISLAND)
        val inset = px(R.dimen.bottom_nav_end_inset)
        val cells = MAIL.map { bounds(itemTag(it.first)).width }
        for (w in cells) assertEquals("five equal cells", (island.width - 2 * inset) / 5, w, 1f)
    }

    // ── font scale ────────────────────────────────────────────────────────────────────────

    private fun assertNoOverlapAtThisFontScale(fixture: List<Triple<String, String, ImageVector>>) {
        showIsland(fixture, fixture[1].first)
        val island = bounds(TAG_ISLAND)
        val cells = fixture.map { laidOut(itemTag(it.first)) }
        println("#a11y MEASURED fontScale=${res.configuration.fontScale} ${fixture.size} items: island ${island.width}x${island.height}")
        cells.zipWithNext().forEach { (a, b) -> assertTrue("cells overlap: $a / $b", a.right <= b.left + 0.5f) }
        fixture.forEach { e ->
            val cell = laidOut(itemTag(e.first))
            val label = laidOut(labelTag(e.first))
            assertTrue("label ${e.first} ($label) leaves its cell ($cell)",
                label.left >= cell.left - 0.5f && label.right <= cell.right + 0.5f && label.bottom <= cell.bottom + 0.5f)
            assertTrue("icon and label of ${e.first} overlap", laidOut(iconTag(e.first)).bottom <= label.top + 0.5f)
            assertEquals("${e.first} keeps its whole name for TalkBack", e.second, spoken(node(itemTag(e.first))))
        }
        assertTrue("the island is taller than the 48dp touch floor", island.height >= px(R.dimen.bottom_nav_min_touch_target))
    }

    @Test
    @Config(fontScale = 1.3f)
    fun `a11y font scale 1_3 - labels stay in their cells`() {
        assertEquals(1.3f, res.configuration.fontScale, 0.01f)
        assertNoOverlapAtThisFontScale(MAIL)
        assertNoOverlapAtThisFontScale(CLOUD_CODE)
    }

    @Test
    @Config(fontScale = 2.0f)
    fun `a11y font scale 2_0 - no crash, no overlap, labels ellipsize in their cells`() {
        assertEquals(2.0f, res.configuration.fontScale, 0.01f)
        assertNoOverlapAtThisFontScale(MAIL)
        assertNoOverlapAtThisFontScale(CLOUD_CODE)
        showPageTabs(THREE, "phone")
        THREE.forEach { assertEquals(it.label, spoken(node(PageTabsTags.tab(it.id)))) }
    }

    // ── keyboard, D-pad, switch access ────────────────────────────────────────────────────

    private fun key(code: Int) = compose.runOnUiThread {
        hostView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        hostView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun focusedTag(): String? =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.TestTag)

    /** The focused tag at each of [n] stops: wherever keyboard mode put focus first (or the first
     *  Next), then Next, Next... */
    private fun walk(n: Int): List<String?> {
        if (focusedTag() == null) { compose.runOnUiThread { focus.moveFocus(FocusDirection.Next) }; compose.waitForIdle() }
        val out = mutableListOf(focusedTag())
        repeat(n - 1) {
            compose.runOnUiThread { focus.moveFocus(FocusDirection.Next) }
            compose.waitForIdle()
            out += focusedTag()
        }
        return out
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun keyboardMode() {
        compose.runOnUiThread { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.waitForIdle()
    }

    @Test
    fun `a11y focus walks the island left to right, Enter selects, and the focused item shows a ring`() {
        showIsland(MAIL, "mail")
        keyboardMode()
        val order = walk(MAIL.size)
        assertEquals("focus order", MAIL.map { itemTag(it.first) }, order)
        // Focus is on the last item, Video. Enter selects it.
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitForIdle()
        assertEquals("Enter on the focused item selects it", listOf("video"), picked)
        // Back to an IDLE item: its ring must be visible against the island's dark fill.
        compose.runOnUiThread { focus.moveFocus(FocusDirection.Previous) }
        compose.waitForIdle()
        assertEquals(itemTag("rss"), focusedTag())
        val cell = bounds(itemTag("rss"))
        val probe = pixels(cell)[1, (cell.height / 2).roundToInt()]
        val fill = Color(res.getColor(R.color.bottom_nav_island_fill, null))
        println("#a11y MEASURED focused idle capsule edge $probe (idle ink ${ink.idle}, fill $fill)")
        assertTrue("no visible focus: the focused capsule's edge is $probe, the island fill is $fill", distance(probe, fill) > 0.25f)
        assertTrue("the focus ring is the item's ink ${ink.idle}, got $probe", distance(probe, ink.idle) <= 24f / 255f)
    }

    @Test
    fun `a11y seven items - D-pad focus on an off-screen item scrolls it into the island`() {
        showIsland(CLOUD_CODE, "backlog")
        keyboardMode()
        assertEquals(CLOUD_CODE.map { itemTag(it.first) }, walk(CLOUD_CODE.size))
        val island = bounds(TAG_ISLAND)
        val item = laidOut(itemTag("myterminal"))
        assertTrue("the focused last item ($item) is outside the island ($island)", item.right <= island.right + 0.5f && item.left >= island.left - 0.5f)
    }

    // ── PageTabs ──────────────────────────────────────────────────────────────────────────

    private fun showPageTabs(strip: List<NavPage>, select: String?) {
        pages = strip
        selectedId = select
        ensureContent()
    }

    @Test
    fun `a11y every page tab is a named Tab with a selected state in a tab list, a launch pill is a Button`() {
        showPageTabs(WITH_LAUNCH, "topology")
        val strip = node(PageTabsTags.STRIP).config
        assertTrue("the strip is a selectable group", strip.contains(SemanticsProperties.SelectableGroup))
        assertEquals("the strip's collection info", 1 to WITH_LAUNCH.size,
            strip.getOrNull(SemanticsProperties.CollectionInfo)?.let { it.rowCount to it.columnCount })
        WITH_LAUNCH.forEachIndexed { i, p ->
            val n = node(PageTabsTags.tab(p.id))
            val launch = p.action.isNotBlank()
            assertEquals("${p.id} role", if (launch) Role.Button else Role.Tab, n.config.getOrNull(SemanticsProperties.Role))
            assertEquals("${p.id} selected", p.id == "topology", n.config.getOrNull(SemanticsProperties.Selected))
            assertEquals("${p.id} is read as its declared label, not the drawn caps", p.label, spoken(n))
            assertEquals("${p.id} column", i, n.config.getOrNull(SemanticsProperties.CollectionItemInfo)?.columnIndex)
        }
        assertTrue("the '|' divider is not read out",
            compose.onAllNodes(androidx.compose.ui.test.hasText("|"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a11y page tabs - touch targets are 48dp or more and none overlap`() {
        showPageTabs(WITH_LAUNCH, "observ")
        assertTouchTargets("page tabs", WITH_LAUNCH.map { PageTabsTags.tab(it.id) })
        showPageTabs(MANY, "p1")
        assertTouchTargets("page tabs, 6 pills", MANY.map { PageTabsTags.tab(it.id) })
    }

    @Test
    fun `a11y page tabs - focus walks left to right, Enter selects, the focused pill shows a ring`() {
        showPageTabs(THREE, "cloud")
        val before = pixels(bounds(PageTabsTags.tab("labs")))
        keyboardMode()
        val order = walk(THREE.size)
        assertEquals(THREE.map { PageTabsTags.tab(it.id) }, order)
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitForIdle()
        assertEquals(listOf("labs"), picked)
        val pill = bounds(PageTabsTags.tab("labs"))
        val after = pixels(pill)
        val y = (pill.height / 2).roundToInt()
        println("#a11y MEASURED focused pill edge ${after[1, y]} (unfocused ${before[1, y]})")
        assertTrue("no visible focus on the pill: ${before[1, y]} -> ${after[1, y]}", distance(before[1, y], after[1, y]) > 0.25f)
    }

    // ── contrast ──────────────────────────────────────────────────────────────────────────

    /** WCAG 2.x contrast ratio of two opaque colours. */
    private fun ratio(a: Color, b: Color): Float {
        val la = a.luminance(); val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun assertIslandContrast(name: String, t: NavTokens) {
        val idle = ratio(t.idleInk, t.islandFill)
        val selected = ratio(t.pillInk, t.pillFill)
        val pill = ratio(t.pillFill, t.islandFill)
        println("#a11y MEASURED contrast [$name] idle label+icon on island %.2f:1, selected label+icon on pill %.2f:1, pill vs island %.2f:1"
            .format(idle, selected, pill))
        assertTrue("[$name] idle label on the island is %.2f:1, under 4.5:1".format(idle), idle >= 4.5f)
        assertTrue("[$name] selected label on the pill is %.2f:1, under 4.5:1".format(selected), selected >= 4.5f)
        assertTrue("[$name] the selected pill against the island is %.2f:1, under 3:1".format(pill), pill >= 3f)
        // The focus ring is drawn in the item's ink, so it contrasts exactly as the label does.
    }

    @Test
    fun `a11y island contrast meets WCAG AA from NavTokens, web literals and Android light and dark`() {
        assertIslandContrast("web / static", NavTokens())
        val app = RuntimeEnvironment.getApplication()
        for ((mode, night) in listOf("light" to Configuration.UI_MODE_NIGHT_NO, "dark" to Configuration.UI_MODE_NIGHT_YES)) {
            val cfg = Configuration(app.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
            val ctx = app.createConfigurationContext(cfg)
            assertIslandContrast("android $mode (dynamic palette, sdk 34)", buildNavTokens(ctx, Density(1f, 1f)))
            val statik = FleetChrome.staticScheme(ctx)
            assertIslandContrast("android $mode (static palette, < Android 12)",
                buildNavTokens(ctx, Density(1f, 1f)).copy(pillFill = statik.inverseSurface, pillInk = statik.inverseOnSurface, idleInk = statik.onSurfaceVariant))
        }
    }

    @Test
    fun `a11y page tab contrast meets WCAG AA over the fleet's dark grounds`() {
        val t = NavTokens()
        // SuperApp's window: the purple-black-purple gradient (#2D1B69 / #000000), and the Minimalist
        // Black mode's #000000. The strip is translucent, so text is judged over pill over ground.
        for ((name, ground) in listOf("purple #2D1B69" to Color(0xFF2D1B69), "black #000000" to Color.Black)) {
            val sel = t.tabsSelectedFill.compositeOver(ground)
            val idle = t.tabsIdleFill.compositeOver(ground)
            val selText = ratio(t.tabsSelectedText.compositeOver(sel), sel)
            val idleText = ratio(t.tabsIdleText.compositeOver(idle), idle)
            println("#a11y MEASURED contrast [page tabs over $name] selected %.2f:1, idle %.2f:1".format(selText, idleText))
            assertTrue("[page tabs over $name] selected text %.2f:1".format(selText), selText >= 4.5f)
            assertTrue("[page tabs over $name] idle text %.2f:1".format(idleText), idleText >= 4.5f)
        }
        // Reported, not asserted: the strip is white glass, so over a LIGHT page (an app in light
        // mode) its white text has no contrast at all. The island is immune (its own opaque dark fill).
        for ((name, ground) in listOf("white" to Color.White, "M3 light background" to Color(0xFFFEF7FF))) {
            val idle = t.tabsIdleFill.compositeOver(ground)
            println("#a11y MEASURED contrast [page tabs over $name, NOT AA] idle %.2f:1".format(ratio(t.tabsIdleText.compositeOver(idle), idle)))
        }
    }

    private companion object {
        const val ROOT = "a11y_root"

        /** cloud-mail's five, the fleet's normal case. */
        val MAIL = listOf(
            Triple("mail", "Mail", Icons.Filled.Mail), Triple("chat", "Chat", Icons.Filled.ChatBubble),
            Triple("home", "Home", Icons.Filled.Home), Triple("rss", "News", Icons.Filled.RssFeed),
            Triple("video", "Video", Icons.Filled.Videocam),
        )

        /** Cloud Code's seven, as ac_cloud-code/src/cloud/nav.json::tabs declares them (ids and labels,
         *  in order), the widest bar any fleet app declares. A fixture, not a read of that file: this lib's
         *  tests must not change outcome when another app's tree changes (#870). */
        val CLOUD_CODE = listOf(
            Triple("backlog", "Backlog", Icons.Filled.Inbox), Triple("editor", "Editor", Icons.Filled.Code),
            Triple("repos", "Repos", Icons.Filled.Build), Triple("home", "Home", Icons.Filled.Home),
            Triple("agents", "Agents", Icons.Filled.Star), Triple("browser", "Browser", Icons.Filled.Public),
            Triple("myterminal", "MyTerminal", Icons.Filled.Terminal),
        )

        val THREE = listOf(NavPage("cloud", "Cloud"), NavPage("phone", "Phone"), NavPage("labs", "Labs"))
        val WITH_LAUNCH = listOf(NavPage("observ", "Observ"), NavPage("topology", "Topology"), NavPage("wd", "Watchdog", action = "extapp:wd"))
        val MANY = (1..6).map { NavPage("p$it", "Page $it") }
    }
}
