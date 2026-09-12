/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.core.view.children
import androidx.core.view.isVisible
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.toolbarKeysOnOwnPage

/**
 * The toolbar row holds more icons than fit on screen. Free scrolling let a drag stop anywhere,
 * so the row came to rest mid-icon and the user had no way to tell which part of the toolbar they
 * were looking at. This scroll view turns the row into pages instead: every gesture settles on a
 * page boundary, so the toolbar always shows a whole, aligned set of icons.
 *
 * A page is one viewport worth of whole icons, measured live rather than configured, because the
 * viewport depends on screen width, keyboard height and the user's icon size, and the icon set is
 * data-driven (the row is rebuilt whenever the toolbar keys change). Anything cached here would
 * survive a layout it no longer describes, so nothing is cached — every value below is read from
 * the current measurements at the moment a gesture ends.
 */
class PagedToolbarScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    /** A fling already turned a page, so the touch that ended it must not also snap. */
    private var turnedPageOnFling = false

    private val viewportWidth get() = width - paddingLeft - paddingRight

    /**
     * All toolbar keys share one [android.widget.LinearLayout.LayoutParams], so the first laid-out
     * one speaks for the rest. Gone keys are skipped: they take up no width in the row either.
     */
    private val iconWidth
        get() = (getChildAt(0) as? ViewGroup)?.children?.firstOrNull { it.isVisible && it.width > 0 }?.width ?: 0

    /** How far the row can travel; zero when the icons already fit, which is what makes paging inert. */
    private val maxScrollX get() = ((getChildAt(0)?.width ?: 0) - viewportWidth).coerceAtLeast(0)

    /**
     * As many whole icons as the viewport can show. Never zero, so an icon wider than the viewport
     * still pages one icon at a time, and an unmeasured row still pages by a viewport.
     */
    private val pageWidth: Int
        get() {
            val icon = iconWidth
            if (icon <= 0) return viewportWidth.coerceAtLeast(1)
            return (viewportWidth / icon).coerceAtLeast(1) * icon
        }

    /**
     * Turns one page towards [direction] (positive is towards the end of the row). The last page is
     * short whenever the icons do not divide evenly into pages: clamping to [maxScrollX] scrolls by
     * exactly that remainder, so the row lands flush against the end of the content instead of
     * overshooting into empty space, and the remainder stays reachable rather than being a sliver
     * past the last full page.
     */
    private fun turnPage(direction: Int) {
        val limit = maxScrollX
        if (limit <= 0) return
        val page = pageWidth
        // The page grid is anchored at the start of the row, so both edges land on an icon boundary.
        val target = if (direction > 0) (scrollX / page + 1) * page
        else (scrollX - 1).coerceAtLeast(0) / page * page
        smoothScrollToPage(target.coerceIn(0, limit))
    }

    /** Settles a drag that was too slow to fling onto whichever page boundary it ended up nearest. */
    private fun snapToNearestPage() {
        val limit = maxScrollX
        if (limit <= 0) return
        val page = pageWidth
        val before = (scrollX / page * page).coerceAtMost(limit)
        val after = (before + page).coerceAtMost(limit)
        smoothScrollToPage(if (scrollX - before <= after - scrollX) before else after)
    }

    /** Animated so a page turn reads as a page turn, and silent when there is nowhere to go. */
    private fun smoothScrollToPage(target: Int) {
        if (target != scrollX) smoothScrollTo(target, 0)
    }

    /**
     * The row has no arrows of its own (the expand key is hidden in the two-row strip), so the
     * horizontal fling that used to scroll freely is the gesture that turns a page. Deliberately
     * does not call super: the free-scrolling fling is what we are replacing.
     */
    override fun fling(velocityX: Int) {
        turnedPageOnFling = true
        turnPage(if (velocityX > 0) 1 else -1)
    }

    /**
     * Gives every key in [toolbarKeysOnOwnPage] a left margin sized to whatever is left of the page
     * it would otherwise land in the middle of, so it starts a page of its own. The same margin is
     * what pushes the row past its last full page and so makes that page reachable at all — without
     * it [maxScrollX] stops at the content width and there is nothing to scroll to.
     *
     * The gap comes from the measured [pageWidth], never from a key count: how many icons fit on a
     * page depends on screen width, icon size and rotation, so filler keys could not do this.
     *
     * Returns true when a margin changed, which needs one more layout pass to take effect. That
     * pass recomputes each gap from the position EXCLUDING the margin it just set, so it arrives at
     * the same answer and stops.
     */
    private fun alignKeysThatWantTheirOwnPage(): Boolean {
        val row = getChildAt(0) as? ViewGroup ?: return false
        val page = pageWidth
        var changed = false
        for (child in row.children) {
            if (!child.isVisible) continue
            val key = child.tag as? ToolbarKey ?: continue
            if (key !in toolbarKeysOnOwnPage) continue
            val params = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val startWithoutGap = child.left - params.leftMargin
            val gap = (page - startWithoutGap % page) % page
            if (params.leftMargin != gap) {
                params.leftMargin = gap
                changed = true
            }
        }
        return changed
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Widths and positions are only real after layout, so the page alignment is computed here
        // and asks the row to lay itself out once more with the margins it just got.
        if (alignKeysThatWantTheirOwnPage()) getChildAt(0)?.requestLayout()
    }

    @SuppressLint("ClickableViewAccessibility") // super handles the touch; we only settle it afterwards
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) turnedPageOnFling = false
        val handled = super.onTouchEvent(event)
        val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        // super.onTouchEvent calls fling() while handling the release, so the flag is already current.
        if (ended && !turnedPageOnFling) snapToNearestPage()
        return handled
    }
}
