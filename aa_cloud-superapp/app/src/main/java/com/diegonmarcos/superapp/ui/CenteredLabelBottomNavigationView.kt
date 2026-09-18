package com.diegonmarcos.superapp.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.diegonmarcos.superapp.R
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * #498 defect 2 — the selected item's icon+label stack sat visibly HIGH in
 * its box even after the pill/content boxes were unified (see #498's other
 * half in bg_bottom_nav_item_checked.xml / dimens.xml). Cause: a TextView's
 * line box reserves ascent/descent space that is NOT symmetric around the
 * visible ink, so with equal top/bottom padding the drawn glyphs still sit
 * high and the leftover gap reads larger below.
 *
 * The fix is android:includeFontPadding="false" — but the nav LABEL is
 * Material's own internal TextView (design_bottom_navigation_item.xml),
 * unreachable from any layout XML. The only other lever, itemTextAppearance,
 * is a no-op for this: Android 14's TextView.readTextAppearance() reads a
 * fixed list of ~23 TextAppearance attrs and includeFontPadding is not one
 * of them, so a style/TextAppearance placement is silently ignored (pinned
 * by test-bottom-nav-selected-pill.sh T6). The attribute only takes effect
 * set directly on the TextView instance, so this subclass walks its own
 * tree — once, right after Material's own constructor has built the item
 * views from app:menu — and sets it there.
 *
 * #512 — defStyleAttr MUST default to Material's own bottomNavigationStyle.
 * As first written it defaulted to 0, which to a View constructor means "no
 * default style attribute": Material's two-arg constructor supplies
 * R.attr.bottomNavigationStyle itself, but a subclass that calls the
 * three-arg one with 0 opts out of it, and the whole themed style
 * (Widget.CloudSuperApp.BottomNavigationView — itemBackground, i.e. the
 * selected-item pill, the Material3 parent, the 80dp height) was silently
 * never applied. The launcher shipped with no selection UI at all while
 * every XML tester stayed green. Proven on the inflated view by
 * app/src/test/.../BottomNavSelectedPillTest.kt.
 *
 * #498 (third attempt) — this class now also owns the bar's VERTICAL
 * geometry, because two of its three terms are only knowable from the font
 * and Material measures the third from the baseline, not from the box.
 * Measured on the laid-out bar (run 35402703581), with every distance in the
 * cell's own coordinates:
 *
 *   above the icon  = pillInset + itemPaddingTop
 *   below the label = pillInset + max(labelDescent, itemPaddingBottom) - labelDescent
 *
 * The pillInset term is there because the selected-state pill is an
 * InsetDrawable and View.setBackground() folds a background's getPadding()
 * into the view's own padding — so the capsule's 6dp inset is ALSO 6dp of
 * item padding, on every item, selected or not (a DrawableContainer hands out
 * the max padding of all its states).
 *
 * The asymmetry is the second term: BaselineLayout, which holds the two
 * labels, measures its bottom padding from the BASELINE
 * (maxChildDescent = max(descent, paddingBottom)), so the first `descent`
 * worth of itemPaddingBottom buys no space at all — it is already spent on
 * the glyph's own descender. Equal itemPaddingTop and itemPaddingBottom
 * therefore leave the stack sitting `descent` LOW, which is precisely what
 * #477's "one token read twice = centred by construction" could never
 * deliver, and what shipped: 12.00dp above the icon against 9.00dp below the
 * label. So the bottom pad is the top pad plus the measured descent, taken
 * from the real Paint on the real label.
 *
 * The height is the third term. Material's minHeight is not a floor: in
 * BottomNavigationView.makeMinHeightSpec it is a CAP — min(available,
 * minHeight), forced to EXACTLY — and BottomNavigationMenuView then takes
 * whatever height it is offered, EXACTLY. So zeroing minHeight does not make
 * the bar hug its content; it removes the only clamp and the bar fills its
 * parent (measured 788.00dp on 2026-09-18). The height has to be stated, and
 * the only honest statement of it is the sum of the parts, which includes the
 * label's ink height — a font number. So it is computed here, once, from the
 * declared tokens plus the real font metrics, and BottomNavGeometryTest
 * measures every term of it back off the laid-out view.
 */
class CenteredLabelBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomNavigationStyle,
) : BottomNavigationView(context, attrs, defStyleAttr) {

    init {
        val labels = ArrayList<TextView>()
        collectTextViews(this, labels)
        for (label in labels) label.includeFontPadding = false
        applyVerticalGeometry(labels)
    }

    private fun collectTextViews(view: View, into: MutableList<TextView>) {
        if (view is TextView) into.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTextViews(view.getChildAt(i), into)
        }
    }

    /**
     * Derive the two distances XML cannot state: the bottom item pad (which
     * must carry the label's descender before it buys any space) and the
     * bar's height (which is the sum of the parts, one of which is the font's
     * ink height). Everything else is a declared token, read here and nowhere
     * else — no literal dp appears in this file.
     */
    private fun applyVerticalGeometry(labels: List<TextView>) {
        if (labels.isEmpty()) return
        var descent = 0
        var inkHeight = 0
        for (label in labels) {
            val fm = label.paint.fontMetricsInt
            // includeFontPadding is off above, so a one-line TextView is
            // exactly ascent..descent — the same two numbers Material's
            // BaselineLayout measures the label group with.
            descent = maxOf(descent, fm.descent)
            inkHeight = maxOf(inkHeight, fm.descent - fm.ascent)
        }
        val pad = resources.getDimensionPixelSize(R.dimen.bottom_nav_item_vertical_pad)
        val pillInset = resources.getDimensionPixelSize(R.dimen.bottom_nav_pill_inset)
        val gap = resources.getDimensionPixelSize(R.dimen.bottom_nav_icon_label_gap)
        val iconSize = resources.getDimensionPixelSize(R.dimen.bottom_nav_icon_size)

        itemPaddingTop = pad
        itemPaddingBottom = pad + descent
        minimumHeight = 2 * (pillInset + pad) + iconSize + gap + inkHeight
    }
}
