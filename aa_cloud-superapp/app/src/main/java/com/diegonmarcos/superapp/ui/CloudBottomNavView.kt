package com.diegonmarcos.superapp.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.R

/**
 * The bottom nav, rebuilt without Material — the 2026-09-19 reorganization.
 *
 * WHY NO MATERIAL. The previous stack was BottomNavigationView + a themed
 * style + an ActiveIndicator override + a subclass carrying three
 * generations of counter-patches (font-padding walk, computed minimumHeight,
 * indicator kill switch) — and it STILL rendered an icon-only halo on the
 * device while every Robolectric assertion passed, because Material's
 * NavigationBarItemView takes hidden branches (refreshItemBackground,
 * menu-view height claims) that differ between the device and the JVM
 * harness. Four tickets closed green on a widget that looked wrong. A bar
 * of five buttons does not need a framework: this file IS the whole
 * mechanism, and what it says is what renders — everywhere.
 *
 * GEOMETRY, all from the ONE-GEOMETRY tokens in values/dimens.xml:
 *   - item = vertical stack: pad, icon (bottom_nav_icon_size), gap
 *     (bottom_nav_icon_label_gap), label, pad — so the bar's wrap_content
 *     height is honestly the content height, no minHeight games.
 *   - the selected capsule is the ITEM's background
 *     (bg_bottom_nav_item_checked): inset top+bottom by
 *     bottom_nav_pill_inset, full width of the cell. Equal insets off the
 *     same token the content pads read = symmetric by construction.
 *   - the bar's paddingStart/End (bottom_nav_end_inset, an alias of the
 *     pill inset) keeps the edge capsules' end arcs concentric with the
 *     island's — see the dimens.xml comment.
 *
 * API kept from the old widget, exactly what the app used: [menu],
 * [selectedItemId] (setting it fires the listeners, same as Material),
 * [setOnItemSelectedListener] (return false to refuse the switch),
 * [setOnItemReselectedListener], and item views carry the menu item's id so
 * findViewById(navId) still answers. Items are DIRECT children — there is
 * no inner "menu view". After mutating [menu] icons/titles (the build.json
 * mode refresh), call [refresh].
 */
class CloudBottomNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** A real framework Menu (PopupMenu-backed) inflated from the structural
     *  anchor res/menu/bottom_nav.xml — ids + order; titles and icons are
     *  overwritten from build.json::ui.sections at runtime, as before. */
    val menu: Menu = PopupMenu(context, this).menu.also {
        MenuInflater(context).inflate(R.menu.bottom_nav, it)
    }

    private var onSelected: ((MenuItem) -> Boolean)? = null
    private var onReselected: ((MenuItem) -> Unit)? = null
    private var selectedId: Int = View.NO_ID

    var selectedItemId: Int
        get() = selectedId
        set(value) = select(value, fire = true)

    init {
        orientation = HORIZONTAL
        rebuild()
        menu.getItem(0)?.let { select(it.itemId, fire = false) }
    }

    fun setOnItemSelectedListener(listener: ((MenuItem) -> Boolean)?) {
        onSelected = listener
    }

    fun setOnItemReselectedListener(listener: ((MenuItem) -> Unit)?) {
        onReselected = listener
    }

    /** Re-render after menu items were mutated (icons/titles from build.json). */
    fun refresh() {
        rebuild()
        for (i in 0 until childCount) getChildAt(i).isSelected = getChildAt(i).id == selectedId
    }

    private fun select(id: Int, fire: Boolean) {
        val item = menu.findItem(id) ?: return
        if (selectedId == id) {
            if (fire) onReselected?.invoke(item)
            return
        }
        if (fire && onSelected?.invoke(item) == false) return
        selectedId = id
        for (i in 0 until childCount) getChildAt(i).isSelected = getChildAt(i).id == id
    }

    private fun rebuild() {
        removeAllViews()
        val res = resources
        val pad = res.getDimensionPixelSize(R.dimen.bottom_nav_item_vertical_pad)
        val pillInset = res.getDimensionPixelSize(R.dimen.bottom_nav_pill_inset)
        val gap = res.getDimensionPixelSize(R.dimen.bottom_nav_icon_label_gap)
        val iconSize = res.getDimensionPixelSize(R.dimen.bottom_nav_icon_size)
        val content = ContextCompat.getColorStateList(context, R.color.bottom_nav_content)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            val itemView = LinearLayout(context).apply {
                id = item.itemId
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_bottom_nav_item_checked)
                // AFTER the background, so the explicit value wins over the
                // InsetDrawable's folded padding. The capsule sits pillInset
                // inside the cell; the ink sits pad inside the capsule —
                // total pillInset + pad each side, the measured contract of
                // BottomNavGeometryTest M1/M3.
                setPadding(0, pillInset + pad, 0, pillInset + pad)
                addView(ImageView(context).apply {
                    isDuplicateParentStateEnabled = true
                    setImageDrawable(item.icon)
                    imageTintList = content
                }, LayoutParams(iconSize, iconSize))
                addView(TextView(context).apply {
                    isDuplicateParentStateEnabled = true
                    text = item.title
                    includeFontPadding = false
                    maxLines = 1
                    setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        res.getDimension(R.dimen.bottom_nav_label_text_size))
                    setTextColor(content)
                }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = gap })
                setOnClickListener { select(item.itemId, fire = true) }
            }
            addView(itemView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
