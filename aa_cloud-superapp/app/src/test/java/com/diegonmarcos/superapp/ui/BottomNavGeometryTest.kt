package com.diegonmarcos.superapp.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #498 defect 2, third attempt — the bottom bar's GEOMETRY, measured.
 *
 * WHY THIS FILE EXISTS. #462/#473/#477/#498 each nudged a dp and shipped
 * green, and Diego kept seeing the same wrong bar. #511 found out why: every
 * tester this widget has ever had reads XML text. The worst of them,
 * test-bottom-nav-selected-pill.sh T5, printed four lines beginning
 * "measured:" whose numbers were the string "0dp" typed into the script — it
 * asserted that two attributes both say @dimen/bottom_nav_item_vertical_pad
 * and then declared the centring delta zero "by construction". Nothing in
 * this repository had ever looked at a laid-out nav bar.
 *
 * So this test takes its numbers from nowhere but the view. It inflates the
 * real activity_main under Theme.Superapp, measures and lays out the real
 * hierarchy, and reads pixels back off the children Material built. Every
 * child is found STRUCTURALLY (the ImageView is the icon, the TextViews are
 * the labels) rather than by a Material id, so a Material upgrade that
 * renames an internal id cannot make this go quietly blind the way the class
 * name did in #511.
 *
 * The contract, all of it measured against the app's OWN declared dimens:
 *   M1 the bar is exactly as tall as the declared item geometry needs
 *   M2 the icon-to-label gap is @dimen/bottom_nav_icon_label_gap
 *   M3 the icon+label ink is centred in its cell, at pill inset + pad
 *   M4 the selected pill wraps that ink concentrically, by the declared pad
 *   M5 the icon is @dimen/bottom_nav_icon_size
 *   M6 the bar's end insets are @dimen/bottom_nav_end_inset, equal both ends
 *   M7 includeFontPadding is OFF on the real label — #498's whole fix, proven
 *      by its effect on a TextView instance and not by grepping for the word
 *   M8 selecting an item does not move its icon or its label
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomNavGeometryTest {

    private lateinit var nav: CloudBottomNavView
    private lateinit var cells: List<ViewGroup>
    private var selected = 0

    /** Declared geometry — the app's own tokens, never a literal in here. */
    private var pad = 0            // @dimen/bottom_nav_item_vertical_pad
    private var pillInset = 0      // @dimen/bottom_nav_pill_inset
    private var iconLabelGap = 0   // @dimen/bottom_nav_icon_label_gap
    private var endInset = 0       // @dimen/bottom_nav_end_inset
    private var iconSize = 0       // @dimen/bottom_nav_icon_size
    private var density = 0f

    @Before
    fun inflateMeasureAndLayout() {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_Superapp)
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null)
        nav = root.findViewById(R.id.bottom_nav)
        selected = nav.menu.getItem(0).itemId
        nav.selectedItemId = selected

        val dm = ctx.resources.displayMetrics
        density = dm.density
        root.measure(
            MeasureSpec.makeMeasureSpec(dm.widthPixels, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dm.heightPixels, MeasureSpec.EXACTLY))
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)

        val res = ctx.resources
        pad = res.getDimensionPixelSize(R.dimen.bottom_nav_item_vertical_pad)
        pillInset = res.getDimensionPixelSize(R.dimen.bottom_nav_pill_inset)
        iconLabelGap = res.getDimensionPixelSize(R.dimen.bottom_nav_icon_label_gap)
        endInset = res.getDimensionPixelSize(R.dimen.bottom_nav_end_inset)
        iconSize = res.getDimensionPixelSize(R.dimen.bottom_nav_icon_size)

        cells = (0 until nav.menu.size()).map { nav.findViewById<ViewGroup>(nav.menu.getItem(it).itemId) }
        dump()
    }

    // ── the contract ──────────────────────────────────────────────────────

    @Test
    fun barIsExactlyAsTallAsTheDeclaredItemGeometryNeeds() {
        val cell = cells[0]
        val ink = ink(cell)
        // The pill is an InsetDrawable, and View.setBackground() folds a
        // background's getPadding() into the view's own padding — so the
        // capsule's inset is also item padding, on every item. The ink
        // therefore starts at (pillInset + pad), not at pad.
        val need = 2 * (pillInset + pad) + icon(cell).height() + iconLabelGap + label(cell).height()
        assertEquals(
            "the bar measures ${dp(nav.height)} but the declared geometry " +
                "(${dp(pillInset)} pill inset + ${dp(pad)} pad + ${dp(icon(cell).height())} icon + " +
                "${dp(iconLabelGap)} gap + ${dp(label(cell).height())} label + ${dp(pad)} pad + " +
                "${dp(pillInset)} pill inset) needs ${dp(need)} — " +
                "${dp(nav.height - need)} of slack that no declared value asks for, and " +
                "Material drops all of it between the icon and the label. ink=$ink cell=${cell.height}",
            need, nav.height)
    }

    @Test
    fun theIconToLabelGapIsTheDeclaredOne() {
        for (cell in cells) {
            val gap = label(cell).top - icon(cell).bottom
            assertEquals(
                "item '${title(cell)}': the rendered gap between the icon and its label is " +
                    "${dp(gap)}, but the app declares @dimen/bottom_nav_icon_label_gap = " +
                    "${dp(iconLabelGap)}. icon=${icon(cell)} label=${label(cell)}",
                iconLabelGap, gap)
        }
    }

    @Test
    fun theIconAndLabelInkIsCentredInItsCellByTheDeclaredPad() {
        for (cell in cells) {
            val ink = ink(cell)
            val above = ink.top
            val below = cell.height - ink.bottom
            assertEquals(
                "item '${title(cell)}': ${dp(above)} above the icon vs ${dp(below)} below the " +
                    "label — the stack is not centred in its cell. ink=$ink cell=${cell.height}",
                above, below)
            assertEquals(
                "item '${title(cell)}': the stack sits ${dp(above)} from the cell edge, but the " +
                    "declared geometry puts it at @dimen/bottom_nav_pill_inset (${dp(pillInset)}) " +
                    "+ @dimen/bottom_nav_item_vertical_pad (${dp(pad)}) = ${dp(pillInset + pad)}. " +
                    "ink=$ink",
                pillInset + pad, above)
        }
    }

    @Test
    fun theSelectedPillWrapsThatInkConcentrically() {
        val bitmap = drawNav()
        val cell = cells.first { it.id == selected }
        val ink = ink(cell)
        val pill = pill(cell)
        assertTrue(
            "the pill $pill does not contain the icon+label ink $ink",
            pill.contains(ink.left, ink.top, ink.right, ink.bottom))
        assertEquals(
            "the pill's top is ${dp(ink.top - pill.top)} above the ink but its bottom is " +
                "${dp(pill.bottom - ink.bottom)} below it — the capsule is not concentric with " +
                "what it highlights. pill=$pill ink=$ink",
            ink.top - pill.top, pill.bottom - ink.bottom)
        assertEquals(
            "the pill breathes ${dp(ink.top - pill.top)} around the ink, but the declared " +
                "breathing room is @dimen/bottom_nav_item_vertical_pad = ${dp(pad)}",
            pad, ink.top - pill.top)
        bitmap.recycle()
    }

    @Test
    fun theBarIsNotAllowedToFillItsParent() {
        // The whole point of stating a height. minimumHeight is a CAP in
        // BottomNavigationView.makeMinHeightSpec — min(available, minHeight),
        // EXACTLY — and BottomNavigationMenuView takes whatever it is handed,
        // so a bar with no cap swallows its parent. 2026-09-18 shipped 788dp
        // of bottom bar this way (CI 35402703581).
        assertTrue(
            "the bar declares no minimumHeight, so nothing caps it and it fills its parent",
            nav.minimumHeight > 0)
        assertEquals(
            "the bar measures ${dp(nav.height)} but its own minimumHeight cap is " +
                "${dp(nav.minimumHeight)} — something else is driving the height",
            nav.minimumHeight, nav.height)
    }

    @Test
    fun theIconIsTheDeclaredSize() {
        for (cell in cells) {
            val r = icon(cell)
            assertEquals("item '${title(cell)}': icon width is ${dp(r.width())}, declared " +
                "@dimen/bottom_nav_icon_size is ${dp(iconSize)}", iconSize, r.width())
            assertEquals("item '${title(cell)}': icon height is ${dp(r.height())}, declared " +
                "@dimen/bottom_nav_icon_size is ${dp(iconSize)}", iconSize, r.height())
        }
    }

    @Test
    fun theBarsEndInsetsAreTheDeclaredOneOnBothEnds() {
        assertEquals("the bar's left inset measures ${dp(nav.paddingLeft)}, declared " +
            "@dimen/bottom_nav_end_inset is ${dp(endInset)}", endInset, nav.paddingLeft)
        assertEquals("the bar's right inset measures ${dp(nav.paddingRight)} but its left inset " +
            "measures ${dp(nav.paddingLeft)} — the ends are not symmetric",
            nav.paddingLeft, nav.paddingRight)
    }

    @Test
    fun everyLabelHasFontPaddingOff() {
        val labels = nav.descendants().filterIsInstance<TextView>()
        assertTrue("the nav bar has no label TextViews at all — nothing was inflated",
            labels.isNotEmpty())
        for (t in labels) {
            assertEquals(
                "a nav label TextView still has includeFontPadding ON, so its line box reserves " +
                    "asymmetric ascent/descent room and the glyphs ride high inside it. That is " +
                    "the whole reason CenteredLabelBottomNavigationView exists (#498); the " +
                    "subclass is not reaching this label. text='${t.text}'",
                false, t.includeFontPadding)
        }
    }

    @Test
    fun selectingAnItemDoesNotMoveItsIconOrItsLabel() {
        val geometry = cells.map { icon(it).top to label(it).top }
        val distinctIcons = geometry.map { it.first }.distinct()
        val distinctLabels = geometry.map { it.second }.distinct()
        assertEquals(
            "the selected item's icon sits at a different y than the unselected ones " +
                "(tops, px: $distinctIcons) — the bar twitches when you tap it",
            1, distinctIcons.size)
        assertEquals(
            "the selected item's label sits at a different y than the unselected ones " +
                "(tops, px: $distinctLabels)",
            1, distinctLabels.size)
    }

    @Test
    fun theEdgePillsShareTheIslandsEndCurvature() {
        // The island and the pill are both stadiums on the SAME radius token,
        // so each end is a perfect semicircle. Concentric end arcs need
        // exactly one thing: the horizontal gap between the pill's end and
        // the island's end must equal the pill's vertical inset — the radii
        // then differ by that gap and the centres coincide. The island's edge
        // is the bar's edge (the island wraps the bar with no padding), so
        // that gap IS the bar's own start/end padding. 16dp of end inset
        // against 6dp of pill inset put the arcs 10dp off-centre — the
        // leftmost/rightmost misalignment reported 2026-09-19.
        for (idx in intArrayOf(0, nav.menu.size() - 1)) {
            val id = nav.menu.getItem(idx).itemId
            nav.selectedItemId = id
            drawNav()
            val cell = cells.first { it.id == id }
            val pill = pill(cell)
            val sidePad = if (idx == 0) nav.paddingLeft else nav.paddingRight
            println("#498 edge '${title(cell)}': pill=$pill cell=${cell.width}x${cell.height} " +
                "sidePad=${dp(sidePad)} pillInset=${dp(pillInset)} bar=${dp(nav.height)}")
            assertEquals("item '${title(cell)}': the pill stops ${dp(pill.left)} short of its " +
                "cell's start edge — a horizontal pill inset breaks the end ring", 0, pill.left)
            assertEquals("item '${title(cell)}': the pill stops ${dp(cell.width - pill.right)} " +
                "short of its cell's end edge", cell.width, pill.right)
            assertEquals(
                "the bar's end padding is ${dp(sidePad)} but the pill's vertical inset is " +
                    "${dp(pillInset)} — the pill's end arc and the island's end arc are not " +
                    "concentric (the ring differs horizontally vs vertically)",
                pillInset, sidePad)
            assertEquals(
                "stadium centres differ: (bar ${dp(nav.height)} − pill ${dp(pill.height())})/2 " +
                    "≠ end gap ${dp(sidePad)}",
                sidePad, (nav.height - pill.height()) / 2)
        }
    }

    // ── measurement helpers: nothing here reads a file ────────────────────

    /** The icon: the one ImageView Material builds into every item. */
    private fun icon(cell: ViewGroup): Rect =
        rectIn(cell, cell.descendants().filterIsInstance<ImageView>().first())

    /** The visible caption: small label when unselected, large when selected. */
    private fun label(cell: ViewGroup): Rect =
        rectIn(cell, cell.descendants().filterIsInstance<TextView>()
            .first { it.visibility == View.VISIBLE })

    /** What the user actually sees painted in the cell: glyph plus caption. */
    private fun ink(cell: ViewGroup): Rect = Rect(icon(cell)).also { it.union(label(cell)) }

    /** Backgrounds get their bounds when a view DRAWS, not when it lays out. */
    private fun drawNav(): Bitmap {
        assertTrue("nav was laid out with no size", nav.width > 0 && nav.height > 0)
        return Bitmap.createBitmap(nav.width, nav.height, Bitmap.Config.ARGB_8888)
            .also { nav.draw(Canvas(it)) }
    }

    /** The painted capsule — the checked state's inset shape, in cell coords. */
    private fun pill(cell: ViewGroup): Rect {
        val current = cell.background?.current
        assertTrue("the selected item's checked-state background is ${current?.javaClass?.name}, " +
            "not the inset pill — nothing is painted behind the selection", current is InsetDrawable)
        return Rect((current as InsetDrawable).drawable!!.bounds)
    }

    private fun title(cell: ViewGroup): CharSequence =
        cell.descendants().filterIsInstance<TextView>().firstOrNull()?.text ?: "?"

    private fun rectIn(ancestor: ViewGroup, v: View): Rect =
        Rect(0, 0, v.width, v.height).also { ancestor.offsetDescendantRectToMyCoords(v, it) }

    private fun View.descendants(): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return out
    }

    private fun dp(px: Int): String = "%.2fdp".format(px / density)

    /**
     * The evidence, printed whether the run is red or green: a bare
     * "1 test failed" is what let four tickets close on this widget.
     */
    private fun dump() {
        println("#498 density=$density bar=${nav.width}x${nav.height}px " +
            "(${dp(nav.width)} x ${dp(nav.height)}) padding L/T/R/B=" +
            "${nav.paddingLeft}/${nav.paddingTop}/${nav.paddingRight}/${nav.paddingBottom}px")
        println("#498 declared px: pad=$pad pillInset=$pillInset gap=$iconLabelGap " +
            "endInset=$endInset iconSize=$iconSize")
        println("#498 effective px: itemPaddingTop=${nav.itemPaddingTop} " +
            "itemPaddingBottom=${nav.itemPaddingBottom} " +
            "activeIndicatorLabelPadding=${nav.activeIndicatorLabelPadding} " +
            "minimumHeight=${nav.minimumHeight}")
        for (cell in cells) {
            val i = icon(cell)
            val l = label(cell)
            val k = ink(cell)
            println("#498 item '${title(cell)}'${if (cell.id == selected) " [SELECTED]" else ""} " +
                "padT=${cell.paddingTop} padB=${cell.paddingBottom} ")
            println("#498   " +
                "cell=${cell.width}x${cell.height} icon=$i label=$l ink=$k " +
                "above=${k.top}px gap=${l.top - i.bottom}px below=${cell.height - k.bottom}px")
            for (t in cell.descendants().filterIsInstance<TextView>()) {
                println("#498   label text='${t.text}' vis=${t.visibility} " +
                    "size=${t.textSize}px includeFontPadding=${t.includeFontPadding} " +
                    "ascent=${t.paint.fontMetricsInt.ascent} descent=${t.paint.fontMetricsInt.descent} " +
                    "baseline=${t.baseline} box=${t.width}x${t.height} " +
                    "parentPadB=${(t.parent as View).paddingBottom}")
            }
            for (v in cell.descendants()) {
                if (v is ViewGroup && v !== cell) {
                    println("#498   group ${v.javaClass.simpleName} rect=${rectIn(cell, v)} " +
                        "padB=${v.paddingBottom} children=${v.childCount}")
                }
            }
            val bg = cell.background?.current
            if (bg is InsetDrawable) println("#498   pill=${bg.drawable!!.bounds}")
        }
    }
}
