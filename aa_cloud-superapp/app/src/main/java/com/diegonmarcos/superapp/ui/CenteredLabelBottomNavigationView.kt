package com.diegonmarcos.superapp.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
 */
class CenteredLabelBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.bottomNavigationStyle,
) : BottomNavigationView(context, attrs, defStyleAttr) {

    init {
        disableFontPaddingRecursively(this)
    }

    private fun disableFontPaddingRecursively(view: View) {
        if (view is TextView) {
            view.includeFontPadding = false
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                disableFontPaddingRecursively(view.getChildAt(i))
            }
        }
    }
}
