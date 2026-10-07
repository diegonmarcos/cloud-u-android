package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * THE Store's top tab strip - one builder for every Store page that has tabs.
 *
 * It is what StoreCloudFragment.tabBar / tabButton / paintTabs were, lifted out so Phone
 * (Installed | Declared) and Feed (Commits | CI-CD) draw the SAME strip as Cloud instead of
 * a second copy (#896). A strip is a list of lines, each a list of [StoreControls.Control]s
 * wearing the style their declaration names; a line whose controls all `stretch` fills the
 * width (a segmented control), any other wraps and scrolls (a set of pages). The running
 * index across the lines is the page's one tab ordering, which is what [paint] and the
 * caller's `onSelect` speak.
 *
 * Touch height: every button is at least [StoreDensity.MIN_TAP_DP] tall whatever the density.
 */
object StoreTabs {

    /** The strip: [lines] as one column, each line one row. [buttons] is filled in tab order. */
    fun bar(ctx: Context, lines: List<List<StoreControls.Control>>,
            buttons: MutableList<TextView>, onSelect: (Int) -> Unit): View {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, StoreDensity.dp(ctx, StoreDensity.S8)); layoutParams = lp
        }
        buttons.clear()
        for (line in lines) {
            if (line.isEmpty()) continue
            // Only BETWEEN lines, so a page with one line draws no stray rule.
            if (column.childCount > 0) column.addView(divider(ctx))
            val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (control in line) {
                val index = buttons.size
                val t = button(ctx, control) { onSelect(index) }
                buttons.add(t); strip.addView(t)
            }
            // A wrapping line scrolls rather than clipping its last chip on a
            // narrow phone; a stretched line fills the width by definition.
            column.addView(if (line.all { it.style.stretch }) strip
                else HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
        paint(buttons, 0)
        return column
    }

    /** The hairline plus the real space that makes a second line a second table. */
    fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(0xFF2A2A33.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, StoreDensity.dp(ctx, StoreDensity.S1))
            .apply { setMargins(0, StoreDensity.dp(ctx, StoreDensity.S12), 0, StoreDensity.dp(ctx, StoreDensity.S8)) }
    }

    /**
     * THE ONE tab-button builder. [control]'s style is carried as the view's tag so [paint]
     * stays one pass over one list. A stretching style (weight 1f) is a partition filling its
     * bar; a wrapping one sits left at its own width, because a set of pages is not a partition.
     * The icon leads and the chevron trails only when the style declares them.
     */
    fun button(ctx: Context, control: StoreControls.Control, onClick: () -> Unit) = TextView(ctx).apply {
        val style = control.style
        text = listOf(control.icon, control.label, style.chevron).filter { it.isNotEmpty() }.joinToString("  ")
        maxLines = 1
        tag = style
        isClickable = true
        setOnClickListener { onClick() }
        typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        minHeight = StoreDensity.minTap(ctx)
        textSize = StoreDensity.T_BODY
        if (style.stretch) {
            gravity = Gravity.CENTER
            setPadding(StoreDensity.dp(ctx, StoreDensity.S4), StoreDensity.dp(ctx, StoreDensity.S6), StoreDensity.dp(ctx, StoreDensity.S4), StoreDensity.dp(ctx, StoreDensity.S6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(StoreDensity.dp(ctx, StoreDensity.S12), StoreDensity.dp(ctx, StoreDensity.S6), StoreDensity.dp(ctx, StoreDensity.S12), StoreDensity.dp(ctx, StoreDensity.S6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, StoreDensity.dp(ctx, StoreDensity.S8), 0) }
        }
    }

    /** Selection reads per style: each button carries its declared style as its tag, so this is a
     *  single pass over one list and the active look is whatever that style's `_active` fields say. */
    fun paint(buttons: List<TextView>, selected: Int) = buttons.forEachIndexed { i, t ->
        val on = i == selected
        val style = t.tag as StoreControls.Style
        t.background = StoreControls.background(t.context, style, on)
        t.setTextColor(if (on) style.textActive else style.text)
    }
}
