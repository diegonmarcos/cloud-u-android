package com.diegonmarcos.superapp.bottomnav

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.compose.rememberNavController
import java.io.File
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #565: the six bottom-nav behaviours, MEASURED on the real island rendered under Robolectric.
 * These are geometry proofs, not descriptions (#498). Every expected number is read back from
 * this module's own resources (R.dimen / R.fraction / R.color) or from the live theme, never
 * restated here (#363). The items are cloud-mail's real shipped table, so the labels tested
 * for overflow are the labels that actually ship. mdpi keeps px == dp and every edge on a
 * whole pixel, so a pixel probe reads a pixel that is either fully covered or fully uncovered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomNavIslandTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    // A library has no manifest entry for the activity the compose rule launches. Register it in
    // Robolectric's package manager instead of shipping ui-test-manifest into the release variant.
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
    private val fill get() = Color(res.getColor(R.color.bottom_nav_island_fill, null))

    private val ids = bottomNavItems.map { it.id }
    private val destinations = bottomNavItems.filter { it.action == BottomNavAction.DESTINATION }.map { it.id }

    private var selectedId by mutableStateOf<String?>(null)
    /** null = the island's own default insets (the live window's). */
    private var injected by mutableStateOf<WindowInsets?>(WindowInsets(0, 0, 0, 0))
    private var pill = Color.Unspecified
    private val page = Color.White
    private lateinit var hostView: View
    private lateinit var list: LazyListState

    /** The island over a scrollable page, which is how every app hosts it. */
    private fun show(select: String?) {
        selectedId = select
        compose.setContent {
            pill = MaterialTheme.colorScheme.inverseSurface
            hostView = LocalView.current
            list = rememberLazyListState()
            val collapse = rememberBottomNavCollapse()
            Box(Modifier.fillMaxSize().background(page).testTag(ROOT)) {
                LazyColumn(Modifier.fillMaxSize().nestedScroll(collapse).testTag(LIST), state = list) {
                    items(200) { Spacer(Modifier.fillMaxWidth().height(40.dp)) }
                }
                val insets = injected
                val mod = Modifier.align(Alignment.BottomCenter)
                val onSelect: (BottomNavEntry) -> Unit = { selectedId = it.id }
                if (insets == null) {
                    BottomNavIsland(mailEntries(), selectedId, onSelect, mod, collapse.collapsed)
                } else {
                    BottomNavIsland(mailEntries(), selectedId, onSelect, mod, collapse.collapsed, insets)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** The node's pixels as the view actually draws them. The host view is drawn into a bitmap,
     *  the way superapp's View geometry tests do. captureToImage is not used on purpose: it
     *  waits for a frame-commit callback that Robolectric's paused looper never fires
     *  (ComposeTimeoutException in run 36023376462). Compose draws its layers, clip included,
     *  on a software canvas. */
    private fun pixels(tag: String): PixelMap {
        val b = bounds(tag)
        val full = compose.runOnUiThread {
            Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
                .also { hostView.draw(Canvas(it)) }
        }
        return Bitmap.createBitmap(full, b.left.roundToInt(), b.top.roundToInt(), b.width.roundToInt(), b.height.roundToInt())
            .asImageBitmap().toPixelMap()
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    private fun near(msg: String, expected: Float, actual: Float) = assertEquals(msg, expected, actual, 1f)

    private fun distance(a: Color, b: Color) =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue), abs(a.alpha - b.alpha))

    private fun sameColour(msg: String, expected: Color, actual: Color) =
        assertTrue("$msg: expected $expected, got $actual", distance(expected, actual) <= 3f / 255f)

    private fun otherColour(msg: String, unexpected: Color, actual: Color) =
        assertTrue("$msg: got $actual, which is $unexpected", distance(unexpected, actual) > 16f / 255f)

    private fun inside(msg: String, outer: Rect, inner: Rect) = assertTrue(
        "$msg: $inner not inside $outer",
        inner.left >= outer.left - 0.5f && inner.top >= outer.top - 0.5f &&
            inner.right <= outer.right + 0.5f && inner.bottom <= outer.bottom + 0.5f,
    )

    // ── #536 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `536 island is 80 percent of the screen width with 10 percent clear on each side`() {
        val fraction = res.getFraction(R.fraction.bottom_nav_width_fraction, 1, 1)
        assertEquals("#536 declares the island at 80% of the screen", 0.8f, fraction, 1e-6f)
        show(destinations[0])
        val root = bounds(ROOT)
        val island = bounds(TAG_ISLAND)
        near("#536 island width", root.width * fraction, island.width)
        val clear = root.width * (1 - fraction) / 2
        near("#536 clear space left of the island", clear, island.left - root.left)
        near("#536 clear space right of the island", clear, root.right - island.right)
    }

    // ── #417 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `417 island is a full pill and nothing overflows it`() {
        show(destinations[0])
        val island = bounds(TAG_ISLAND)
        val outline = bottomNavPillShape.createOutline(island.size, LayoutDirection.Ltr, Density(res.displayMetrics.density))
        val rr = (outline as Outline.Rounded).roundRect
        for (r in listOf(rr.topLeftCornerRadius, rr.topRightCornerRadius, rr.bottomLeftCornerRadius, rr.bottomRightCornerRadius)) {
            near("#417 end radius is half the island height (a semicircle)", island.height / 2, r.x)
            near("#417 end radius is half the island height (a semicircle)", island.height / 2, r.y)
        }
        // The island really is CLIPPED to that shape: the corner shows the page, while the
        // left-most point of the end arc is already fill.
        val px = pixels(TAG_ISLAND)
        otherColour("#417 island corner is outside the semicircle", fill, px[1, 1])
        sameColour("#417 end arc reaches the island's left edge at mid-height", fill, px[1, px.height / 2])

        for (id in ids) {
            val item = bounds(itemTag(id))
            inside("#417 capsule $id inside the island", island, item)
            inside("#417 icon $id inside its capsule", item, bounds(iconTag(id)))
            inside("#417 label $id inside its capsule", item, bounds(labelTag(id)))
            val text = textLayout(labelTag(id))
            val why = "'${text.layoutInput.text}' laid out ${text.size.width}px wide, needs " +
                "${text.multiParagraph.intrinsics.maxIntrinsicWidth}px, capsule ${item.width}px"
            assertFalse("#417 label $id overflows: $why", text.hasVisualOverflow)
            assertFalse("#417 label $id is ellipsized: $why", text.isLineEllipsized(0))
        }
    }

    // ── #462 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `462 the selected item is a pill wrapping icon and label and it moves with selection`() {
        val pad = px(R.dimen.bottom_nav_item_vertical_pad)
        fun check(sel: String) {
            compose.onAllNodes(isSelected()).assertCountEquals(1)
            val selected = compose.onAllNodes(isSelected()).onFirst().fetchSemanticsNode().boundsInRoot
            val item = bounds(itemTag(sel))
            assertEquals("#462 the selected node is capsule $sel", item, selected)
            near("#462 pill top to icon top", pad, bounds(iconTag(sel)).top - item.top)
            near("#462 label bottom to pill bottom", pad, item.bottom - bounds(labelTag(sel)).bottom)
            val outline = bottomNavPillShape.createOutline(item.size, LayoutDirection.Ltr, Density(res.displayMetrics.density))
            near("#462 capsule is a full pill", minOf(item.width, item.height) / 2,
                (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x)
            for (id in ids) {
                val px = pixels(itemTag(id))
                // Inside the capsule's left end, clear of the centred icon and label.
                val probe = px[3, px.height / 2]
                if (id == sel) sameColour("#462 capsule $id is painted as the pill", pill, probe)
                else sameColour("#462 capsule $id is unlit (island fill)", fill, probe)
            }
            // The pill is clipped to its stadium: its corner is not pill-coloured. What shows there
            // is the island, or the page for an end capsule, whose corner also lies outside the
            // island's own concentric end arc.
            otherColour("#462 pill corner is outside its semicircle", pill, pixels(itemTag(sel))[1, 1])
        }
        show(destinations[0])
        check(destinations[0])
        selectedId = destinations[1]
        compose.waitForIdle()
        check(destinations[1])
    }

    // ── #473 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `473 end capsules are inset concentrically with even spacing and an opened label gap`() {
        show(destinations[0])
        val island = bounds(TAG_ISLAND)
        val items = ids.map { bounds(itemTag(it)) }
        val vertical = items[0].top - island.top
        near("#473 capsule's vertical inset is bottom_nav_pill_inset", px(R.dimen.bottom_nav_pill_inset), vertical)
        near("#473 first capsule end gap == its vertical gap (concentric arcs)", vertical, items.first().left - island.left)
        near("#473 last capsule end gap == its vertical gap (concentric arcs)", vertical, island.right - items.last().right)
        val widths = items.map { it.width }
        assertTrue("#473 equal cells, widths $widths", widths.max() - widths.min() <= 1f)
        items.zipWithNext { a, b -> near("#473 cells tile with no stray gap", a.right, b.left) }
        val steps = ids.map { bounds(iconTag(it)).center.x }.zipWithNext { a, b -> b - a }
        assertTrue("#473 icon centres evenly spaced, steps $steps", steps.max() - steps.min() <= 1f)
        for (id in ids) near(
            "#473 icon-to-label gap on $id is bottom_nav_icon_label_gap",
            px(R.dimen.bottom_nav_icon_label_gap),
            bounds(labelTag(id)).top - bounds(iconTag(id)).bottom,
        )
    }

    // ── #477 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `477 bottom clearance is one dimen plus the live inset`() {
        val margin = px(R.dimen.bottom_nav_island_bottom_margin)
        show(destinations[0])
        for (inset in listOf(0, 48)) {
            injected = WindowInsets(0, 0, 0, inset)
            compose.waitForIdle()
            near("#477 island clears margin + $inset px inset", margin + inset, bounds(ROOT).bottom - bounds(TAG_ISLAND).bottom)
        }
    }

    @Test
    fun `477 default insets follow the real window including the cutout`() {
        val margin = px(R.dimen.bottom_nav_island_bottom_margin)
        injected = null
        show(destinations[0])
        for ((type, inset) in listOf(WindowInsetsCompat.Type.displayCutout() to 40, WindowInsetsCompat.Type.navigationBars() to 30)) {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(type, Insets.of(0, 0, 0, inset))
                .setVisible(type, true)
                .build()
            compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(hostView, insets) }
            compose.waitForIdle()
            near("#477 island clears margin + a $inset px window inset (type $type)", margin + inset, bounds(ROOT).bottom - bounds(TAG_ISLAND).bottom)
        }
    }

    // ── #532 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `532 scrolling collapses the bar to icons only and back without consuming the scroll`() {
        val c = BottomNavCollapse()
        assertEquals("#532 collapse must not eat the scroll", Offset.Zero, c.onPreScroll(Offset(0f, -12f), NestedScrollSource.UserInput))
        assertTrue("#532 scrolling down collapses", c.collapsed)
        assertEquals("#532 collapse must not eat the scroll", Offset.Zero, c.onPreScroll(Offset(0f, 12f), NestedScrollSource.UserInput))
        assertFalse("#532 scrolling up expands", c.collapsed)

        show(destinations[0])
        val expanded = bounds(TAG_ISLAND).height
        compose.onNodeWithTag(LIST, useUnmergedTree = true).performTouchInput { swipeUp(startY = centerY, endY = top) }
        compose.waitForIdle()
        assertTrue("#532 the page itself still scrolled", list.firstVisibleItemIndex > 0)
        for (id in ids) {
            compose.onAllNodesWithTag(labelTag(id), useUnmergedTree = true).assertCountEquals(0)
            val name = res.getString(itemDescription(id))
            compose.onNodeWithTag(iconTag(id), useUnmergedTree = true).assert(hasContentDescription(name))
        }
        val iconsOnly = 2 * px(R.dimen.bottom_nav_pill_inset) + 2 * px(R.dimen.bottom_nav_item_vertical_pad) + px(R.dimen.bottom_nav_icon_size)
        near("#532 collapsed island is exactly the icon stack", iconsOnly, bounds(TAG_ISLAND).height)
        assertTrue("#532 collapsing actually shrank the bar", expanded > iconsOnly + 1f)

        compose.onNodeWithTag(LIST, useUnmergedTree = true).performTouchInput { swipeDown(startY = top, endY = centerY) }
        compose.waitForIdle()
        near("#532 scrolling back up restores the labels", expanded, bounds(TAG_ISLAND).height)
    }

    // ── #532 the collapse is animated, and its only edge is the oval ───────────────────────

    private var driven by mutableStateOf(false)

    /** The island with `collapsed` driven straight from [driven], so a frame-by-frame test owns
     *  the clock instead of a swipe that runs it. Selection is null: no capsule is lit, so any
     *  colour below a capsule's edge can only be a label. */
    private fun showDriven() {
        driven = false
        compose.setContent {
            hostView = LocalView.current
            Box(Modifier.fillMaxSize().background(page).testTag(ROOT)) {
                BottomNavIsland(mailEntries(), null, {}, Modifier.align(Alignment.BottomCenter), driven, WindowInsets(0, 0, 0, 0))
            }
        }
        compose.waitForIdle()
    }

    private fun iconsOnlyHeight() =
        2 * px(R.dimen.bottom_nav_pill_inset) + 2 * px(R.dimen.bottom_nav_item_vertical_pad) + px(R.dimen.bottom_nav_icon_size)

    /** Collapse from a paused clock and return the island height at every 16ms frame. While it
     *  goes, checks the two things a stale rectangular bound would break: each capsule's edges sit
     *  pillInset inside the island's at every frame, and below a capsule's bottom edge, inside the
     *  island, there is only island fill, i.e. no label ink survives past the capsule's own edge.
     *  [frames] is how many frames to run. */
    private fun collapseFrames(frames: Int): List<Float> {
        val inset = px(R.dimen.bottom_nav_pill_inset)
        val heights = mutableListOf<Float>()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { driven = true }
        repeat(frames) { frame ->
            compose.mainClock.advanceTimeBy(16)
            val island = bounds(TAG_ISLAND)
            heights += island.height
            val mid = bounds(itemTag(ids[ids.size / 2]))
            near("#532 frame $frame: capsule top sits pillInset below the island top", island.top + inset, mid.top)
            near("#532 frame $frame: capsule bottom sits pillInset above the island bottom", island.bottom - inset, mid.bottom)
            val px = pixels(TAG_ISLAND)
            val below = (mid.bottom - island.top).roundToInt() + 1 until px.height - 1
            val xs = (mid.left - island.left).roundToInt() + 1 until (mid.right - island.left).roundToInt() - 1
            for (y in below) for (x in xs) {
                sameColour("#532 frame $frame: label ink below the capsule's own edge at ($x,$y)", fill, px[x, y])
            }
        }
        return heights
    }

    @Test
    fun `532 the collapse is animated, the capsule tracks the island edge, the label leaves through it`() {
        showDriven()
        val expanded = bounds(TAG_ISLAND).height
        val iconsOnly = iconsOnlyHeight()
        val ms = res.getInteger(R.integer.bottom_nav_collapse_ms)
        val heights = collapseFrames(ms / 16 + 4)
        println("#532 MEASURED expanded=${expanded}px iconsOnly=${iconsOnly}px collapse=${ms}ms frames=$heights")
        assertTrue("#532 collapsing started from the expanded bar", heights.first() <= expanded && heights.first() > iconsOnly + 1f)
        assertTrue("#532 the bar passes through in-between heights (it animates, it does not jump)",
            heights.any { it > iconsOnly + 1f && it < expanded - 1f })
        assertEquals("#532 the height never grows while collapsing", heights.sortedDescending(), heights)
        near("#532 it ends on exactly the icon stack", iconsOnly, heights.last())
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { driven = false }
        compose.waitForIdle()
        near("#532 and expands back to the labelled bar", expanded, bounds(TAG_ISLAND).height)
    }

    @Test
    fun `532 under Power Saving the collapse snaps instead of animating`() {
        val power = compose.activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIsPowerSaveMode(true)
        showDriven()
        val ms = res.getInteger(R.integer.bottom_nav_collapse_ms)
        val heights = collapseFrames(3)
        assertTrue("the snap has to be measured well inside the ${ms}ms an animation would take", 3 * 16 < ms)
        near("#532 Power Saving: the bar is already icons-only 48ms into a ${ms}ms collapse", iconsOnlyHeight(), heights.last())
    }

    @Test
    fun `532 motion is held still by Power Saving or by removed animations, and by nothing else`() {
        assertTrue(barMotionEnabled(animatorDurationScale = 1f, powerSaveMode = false))
        assertFalse("battery saver alone", barMotionEnabled(animatorDurationScale = 1f, powerSaveMode = true))
        assertFalse("remove-animations alone", barMotionEnabled(animatorDurationScale = 0f, powerSaveMode = false))
        assertFalse(barMotionEnabled(animatorDurationScale = 0f, powerSaveMode = true))
    }

    @Test
    fun `532 the island is fill only so its edge IS the fill edge`() {
        show(destinations[0])
        val px = pixels(TAG_ISLAND)
        val mid = px.width / 2
        sameColour("#532 island top edge row is fill, not a stroke", fill, px[mid, 0])
        sameColour("#532 island bottom edge row is fill, not a stroke", fill, px[mid, px.height - 1])
        val island = bounds(TAG_ISLAND)
        sameColour("#532 nothing drawn just above the island", page, pixels(ROOT)[island.center.x.toInt(), island.top.toInt() - 1])
    }

    // ── #534 mail's bar hosts its screen ABOVE the island ────────────────────────────────

    /** Mail's real [BottomNavBar] as SternaApp draws it: the screen is its content. [INNER] is a
     *  screen that pads itself for the system bars, the way every M3 Scaffold in the app does. */
    private fun showMailBar() {
        compose.setContent {
            hostView = LocalView.current
            list = rememberLazyListState()
            Box(Modifier.fillMaxSize().testTag(ROOT)) {
                BottomNavBar(rememberNavController(), destinations[0]) {
                    LazyColumn(Modifier.fillMaxSize().testTag(LIST), state = list) {
                        items(200) { Spacer(Modifier.fillMaxWidth().height(40.dp)) }
                    }
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).testTag(INNER))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun navBarInset(px: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px))
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(hostView, insets) }
        compose.waitForIdle()
    }

    /** Nothing the screen draws reaches under the island: the screen's own node ends exactly
     *  where the island begins, and the island sits at its #477 clearance below that. */
    private fun assertScreenAboveIsland(why: String) {
        val island = bounds(TAG_ISLAND)
        near("#534 $why: the screen's content box ends where the island begins", island.top, bounds(TAG_CONTENT).bottom)
        near("#534 $why: the screen's list ends where the island begins", island.top, bounds(LIST).bottom)
    }

    @Test
    fun `534 mail's screen is laid out above the island, not under it`() {
        showMailBar()
        navBarInset(30)
        val island = bounds(TAG_ISLAND)
        // The inset really is live, or the rest proves nothing about a real phone.
        near("#534 island still clears margin + the 30px nav bar", px(R.dimen.bottom_nav_island_bottom_margin) + 30, bounds(ROOT).bottom - island.bottom)
        assertScreenAboveIsland("expanded")
        assertTrue("#534 the island has height, so the lift is real", island.height > 0f)
    }

    @Test
    fun `534 the screen does not clear the nav bar a second time`() {
        showMailBar()
        navBarInset(30)
        near("#534 a screen padding for systemBars gets no extra bottom gap above the island",
            bounds(TAG_CONTENT).bottom, bounds(INNER).bottom)
    }

    @Test
    fun `534 scrolling mail's screen collapses the island and the screen follows it down`() {
        showMailBar()
        val expanded = bounds(TAG_ISLAND).height
        compose.onNodeWithTag(LIST, useUnmergedTree = true).performTouchInput { swipeUp(startY = centerY, endY = top) }
        compose.waitForIdle()
        assertTrue("#534 the page itself still scrolled", list.firstVisibleItemIndex > 0)
        val iconsOnly = 2 * px(R.dimen.bottom_nav_pill_inset) + 2 * px(R.dimen.bottom_nav_item_vertical_pad) + px(R.dimen.bottom_nav_icon_size)
        near("#534 scrolling mail's screen collapsed its island", iconsOnly, bounds(TAG_ISLAND).height)
        assertTrue("#534 collapsing shrank the bar", expanded > iconsOnly + 1f)
        assertScreenAboveIsland("collapsed")
    }

    // ── ported, not wrapped ───────────────────────────────────────────────────────────────

    @Test
    fun `the nav is a Compose port and not a wrapped View`() {
        val src = File("src/main/kotlin").walk().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertTrue("sources not found from ${File(".").absolutePath}", src.contains("fun BottomNavIsland("))
        val wrap = Regex("""AndroidView\s*\(|import androidx\.compose\.ui\.viewinterop|import android\.view\.|import android\.widget\.(?!Toast\b)""")
        assertFalse("the nav wraps the View toolkit: ${wrap.find(src)?.value}", wrap.containsMatchIn(src))
        assertTrue("the nav ships a drawable again", File("src/main/res/drawable").list().isNullOrEmpty())
    }

    private companion object {
        const val ROOT = "test_root"
        const val LIST = "test_list"
        const val INNER = "test_inner"
    }
}
