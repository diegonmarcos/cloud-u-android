package com.diegonmarcos.superapp.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.R
import com.google.android.material.color.MaterialColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #512 — the selected bottom-nav pill, proven on the RESOLVED view.
 *
 * #462/#473/#477/#498 each shipped CI-green on this widget while it was wrong,
 * and after #498 the pill did not render AT ALL: the subclass #498 introduced
 * handed defStyleAttr=0 to Material's constructor, which means "no default
 * style attribute", so ?attr/bottomNavigationStyle — the style that carries
 * itemBackground, i.e. the pill — was never applied. Every tester stayed green
 * because every tester read XML text, and the XML was fine.
 *
 * So nothing here reads a file. It inflates the real activity_main under the
 * real app theme, resolves the nav by its id (whatever class the layout tag
 * names), and asserts what the widget ended up with: the resolved
 * itemBackground id, the pill's measured bounds against the measured icon and
 * label, and the pixels the nav actually paints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomNavSelectedPillTest {

    private lateinit var nav: CloudBottomNavView
    private val selectedId = R.id.nav_home
    private val unselectedId = R.id.nav_cloud

    @Before
    fun inflateRealLayout() {
        val ctx = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_Superapp)
        val root = LayoutInflater.from(ctx).inflate(R.layout.activity_main, null)
        nav = root.findViewById(R.id.bottom_nav)
        nav.selectedItemId = selectedId
        val dm = ctx.resources.displayMetrics
        root.measure(
            MeasureSpec.makeMeasureSpec(dm.widthPixels, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dm.heightPixels, MeasureSpec.EXACTLY))
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
    }

    @Test
    fun everyCellCarriesTheCapsuleSelectorAndOnlySelectedShowsIt() {
        // Rebuilt world: no Material style chain to "reach" the view — the
        // capsule selector is applied per cell by CloudBottomNavView itself.
        println("nav class=${nav.javaClass.name} size=${nav.width}x${nav.height}px")
        drawNav()
        for (i in 0 until nav.childCount) {
            val cell = nav.getChildAt(i)
            assertNotNull("cell $i has no background — the capsule selector is not applied", cell.background)
            // Platform getCurrent() may be null while no selector state matches.
            val current = cell.background?.let { bg -> runCatching { bg.current }.getOrNull() }
            if (cell.id == selectedId) {
                assertTrue(
                    "the SELECTED cell's background state is ${current?.javaClass?.name}, " +
                        "not the inset capsule — no selection UI is painted",
                    current is InsetDrawable)
            } else {
                assertTrue(
                    "an UNSELECTED cell paints the capsule too",
                    current !is InsetDrawable)
            }
        }
    }

    @Test
    fun pillWrapsIconAndLabelAndIsCentredInTheItemCell() {
        val bitmap = drawNav()
        val item = nav.findViewById<ViewGroup>(selectedId)
        val pill = pillBounds(item)
        // Rebuilt cells: no Material internal ids — find children by ROLE.
        val icon = rectIn(item, (0 until item.childCount).map { item.getChildAt(it) }
            .first { it is android.widget.ImageView })
        val label = rectIn(item, (0 until item.childCount).map { item.getChildAt(it) }
            .first { it is android.widget.TextView && it.visibility == View.VISIBLE })
        println("#512 measured (px, item coords): cell=${item.width}x${item.height} " +
            "pill=$pill icon=$icon label=$label")
        println("#512 measured: pill centre=(${pill.exactCenterX()}, ${pill.exactCenterY()}) " +
            "cell centre=(${item.width / 2f}, ${item.height / 2f})")

        assertTrue("pill has no area: $pill", pill.width() > 0 && pill.height() > 0)
        assertTrue("icon has no area: $icon", icon.width() > 0 && icon.height() > 0)
        assertTrue("label has no area: $label", label.width() > 0 && label.height() > 0)
        assertTrue("pill $pill does not contain the icon $icon", pill.contains(icon))
        assertTrue("pill $pill does not contain the label $label", pill.contains(label))
        assertEquals("pill is off-centre horizontally in its cell",
            item.width / 2f, pill.exactCenterX(), 0.5f)
        assertEquals("pill is off-centre vertically in its cell",
            item.height / 2f, pill.exactCenterY(), 0.5f)
        bitmap.recycle()
    }

    @Test
    fun onlyTheSelectedItemPaintsThePill() {
        val bitmap = drawNav()
        // Revolut capsule (2026-09-19): the LIGHT inverse surface pair.
        val expected = MaterialColors.getColor(
            nav, com.google.android.material.R.attr.colorSurfaceInverse)
        val selected = nav.findViewById<ViewGroup>(selectedId)
        val unselected = nav.findViewById<ViewGroup>(unselectedId)
        // Probe 1dp inside the pill's top edge on the cell's centre line: above
        // the icon (which starts lower, centred in its container), so nothing
        // but the pill itself can have painted it.
        val dy = pillBounds(selected).top + nav.resources.displayMetrics.density.toInt()
        val on = probe(bitmap, selected, dy)
        val off = probe(bitmap, unselected, dy)
        println("#512 painted: selected=#%08X unselected=#%08X expected pill=#%08X"
            .format(on, off, expected))
        // ponytail: ±2 per channel absorbs premultiplied-alpha rounding, nothing more.
        val drift = listOf(Color.alpha(on) - Color.alpha(expected), Color.red(on) - Color.red(expected),
            Color.green(on) - Color.green(expected), Color.blue(on) - Color.blue(expected))
            .maxOf { kotlin.math.abs(it) }
        assertTrue("the selected item paints #%08X, not the pill colour #%08X".format(on, expected),
            Color.alpha(on) > 0 && drift <= 2)
        assertEquals("an UNSELECTED item paints a background too",
            0, Color.alpha(off))
        bitmap.recycle()
    }

    // REMOVED (#498, third attempt): barHugsItsContentNoM3MinHeightInflation.
    // It asserted menu.height == nav.height with zero dead band above and
    // below, to prove the bar was not inflated by Material's 80dp minHeight.
    // Those three assertions can never fail: BottomNavigationMenuView.onMeasure
    // rebuilds its height spec as MeasureSpec.EXACTLY of whatever size it is
    // offered, so the menu block IS the bar, at every height. It passed on CI
    // 35402703581 printing "nav=2364px menu=2364px above=0px below=0px" — a
    // 788dp bottom bar, the very inflation it was written to catch. The bar's
    // height is asserted against the DECLARED geometry instead, in
    // BottomNavGeometryTest (barIsExactlyAsTallAsTheDeclaredItemGeometryNeeds
    // and theBarIsNotAllowedToFillItsParent), where the expected value comes
    // from the dimens and the font rather than from the view itself.

    /** Backgrounds get their bounds when a view DRAWS, not when it lays out. */
    private fun drawNav(): Bitmap {
        assertTrue("nav was laid out with no size", nav.width > 0 && nav.height > 0)
        return Bitmap.createBitmap(nav.width, nav.height, Bitmap.Config.ARGB_8888)
            .also { nav.draw(Canvas(it)) }
    }

    /** The painted capsule: the checked state's inset shape, in item coords. */
    private fun pillBounds(item: View): Rect {
        val current = item.background?.current
        assertTrue(
            "the selected item's background in its checked state is " +
                "${current?.javaClass?.name}, not the inset pill — no selection UI is painted",
            current is InsetDrawable)
        return Rect((current as InsetDrawable).drawable!!.bounds)
    }

    private fun rectIn(ancestor: ViewGroup, v: View): Rect =
        Rect(0, 0, v.width, v.height).also { ancestor.offsetDescendantRectToMyCoords(v, it) }

    private fun probe(bitmap: Bitmap, item: ViewGroup, dy: Int): Int {
        val r = rectIn(nav, item)
        return bitmap.getPixel(r.centerX(), r.top + dy)
    }
}
