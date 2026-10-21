// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import helium314.keyboard.latin.utils.ToolbarKey
import kotlin.math.abs

/**
 * The toolbar row holds more icons than fit on screen. Free scrolling let a drag stop anywhere,
 * so the row came to rest mid-icon and the user had no way to tell which part of the toolbar they
 * were looking at. The row is therefore paged: every gesture settles on a page boundary, so the
 * toolbar always shows a whole, aligned set of icons.
 *
 * A page is one viewport worth of whole icons, measured live rather than configured, because the
 * viewport depends on screen width, keyboard height and the user's icon size, and the icon set is
 * data-driven (the row is rebuilt whenever the toolbar keys change). Nothing is cached here: every
 * value below is computed from the current measurements at the moment it is needed.
 */
object ToolbarPaging {
    /** The icon scale the toolbar follows (the system font scale): the bounds of what the owner's scale control offers. */
    const val MIN_SCALE = 0.85f
    const val MAX_SCALE = 1.3f

    /** One icon's cell in px: the declared cell times the scale, never below one px. */
    @JvmStatic
    fun cell(baseCell: Int, scale: Float): Int = Math.round(baseCell * scale.coerceIn(MIN_SCALE, MAX_SCALE)).coerceAtLeast(1)

    /** As many whole cells as the viewport shows. Never zero, so a cell wider than the viewport still pages one at a time. */
    @JvmStatic
    fun iconsPerPage(viewport: Int, icon: Int): Int {
        if (icon <= 0) return 1
        return (viewport / icon).coerceAtLeast(1)
    }

    /**
     * A page IS one screen: exactly the viewport's width. The icons of a page are stretched to share it, so no
     * sliver of the next page shows at the edge and one swipe turns exactly one screen.
     */
    @JvmStatic
    fun pageWidth(viewport: Int): Int = viewport.coerceAtLeast(1)

    /** Left edge of cell [slot], in px. The cells of a page are whole pixels that add up to exactly [page]. */
    @JvmStatic
    fun slotStart(slot: Int, perPage: Int, page: Int): Int =
        (slot / perPage) * page + ((slot % perPage).toLong() * page / perPage).toInt()

    /** Cells to leave empty in front of a key so it starts the next page when it wants a page of its own. */
    @JvmStatic
    fun gapSlotsForOwnPage(slot: Int, perPage: Int): Int = (perPage - slot % perPage) % perPage

    /**
     * The gap in cells in front of every key. A key in [ownPage] gets the cells that are left of the page it would
     * otherwise land in the middle of. Computed from the measured [perPage], never from a count.
     */
    @JvmStatic
    fun gaps(keys: List<ToolbarKey>, ownPage: Set<ToolbarKey>, perPage: Int): IntArray {
        val gaps = IntArray(keys.size)
        var slot = 0
        keys.forEachIndexed { i, key ->
            if (key in ownPage && perPage > 0) gaps[i] = gapSlotsForOwnPage(slot, perPage)
            slot += gaps[i] + 1
        }
        return gaps
    }

    /** Where every key of the row sits, in px. [keyStart] and [keyWidth] are per key, [gapBefore] the empty px in front of it. */
    class Layout(
        val perPage: Int, val page: Int, val cell: Int,
        val keyStart: IntArray, val keyWidth: IntArray, val gapBefore: IntArray, val trailing: Int,
    ) {
        val pages: Int get() = if (keyStart.isEmpty()) 1 else (keyStart.last() + keyWidth.last() + trailing) / page
    }

    /**
     * The whole toolbar layout from the available width in px: icons per page = floor(viewport / cell), where the cell is
     * the declared one times the [scale]; each page is exactly [viewport] wide (cells share it), the row ends on a page
     * boundary, and a key in [ownPage] starts a page of its own.
     */
    @JvmStatic
    fun layout(keys: List<ToolbarKey>, ownPage: Set<ToolbarKey>, viewport: Int, baseCell: Int, scale: Float): Layout {
        val cell = cell(baseCell, scale)
        val perPage = iconsPerPage(viewport, cell)
        val page = pageWidth(viewport)
        val gapSlots = gaps(keys, ownPage, perPage)
        val start = IntArray(keys.size)
        val width = IntArray(keys.size)
        val gapPx = IntArray(keys.size)
        var slot = 0
        keys.indices.forEach { i ->
            gapPx[i] = slotStart(slot + gapSlots[i], perPage, page) - slotStart(slot, perPage, page)
            slot += gapSlots[i]
            start[i] = slotStart(slot, perPage, page)
            width[i] = slotStart(slot + 1, perPage, page) - start[i]
            slot++
        }
        val end = if (keys.isEmpty()) 0 else start.last() + width.last()
        return Layout(perPage, page, cell, start, width, gapPx, (page - end % page) % page)
    }

    /**
     * The scroll position one page towards [direction] (positive is towards the end of the row), or
     * [scroll] when there is nowhere to go. The last page is short whenever the icons do not divide
     * evenly into pages: clamping to [limit] scrolls by exactly that remainder, so the row lands
     * flush against the end of the content instead of overshooting into empty space, and the
     * remainder stays reachable rather than being a sliver past the last full page.
     */
    @JvmStatic
    fun turnPageTarget(scroll: Int, direction: Int, page: Int, limit: Int): Int {
        if (limit <= 0) return scroll
        // The page grid is anchored at the start of the row, so both edges land on an icon boundary.
        val target = if (direction > 0) (scroll / page + 1) * page
        else (scroll - 1).coerceAtLeast(0) / page * page
        return target.coerceIn(0, limit)
    }

    /** Settles a drag that was too slow to fling onto whichever page boundary it ended up nearest. */
    @JvmStatic
    fun nearestPageTarget(scroll: Int, page: Int, limit: Int): Int {
        if (limit <= 0) return scroll
        val before = (scroll / page * page).coerceAtMost(limit)
        val after = (before + page).coerceAtMost(limit)
        return if (scroll - before <= after - scroll) before else after
    }
}

/**
 * The paged row's fling: a fling turns exactly one page, and a drag that ended too slowly to fling
 * settles on the nearest page boundary. The free-scrolling fling is what this replaces, so it does
 * not call a default behaviour.
 */
internal class PagedToolbarFling(
    private val state: ScrollState,
    private val pageWidth: () -> Int,
    private val minFlingVelocity: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val limit = state.maxValue
        if (limit <= 0) return 0f // the icons already fit: paging is inert
        val from = state.value
        val page = pageWidth()
        val target = if (abs(initialVelocity) >= minFlingVelocity)
            ToolbarPaging.turnPageTarget(from, if (initialVelocity > 0) 1 else -1, page, limit)
        else ToolbarPaging.nearestPageTarget(from, page, limit)
        if (target != from) {
            var last = from.toFloat()
            animate(last, target.toFloat(), animationSpec = tween(PAGE_TURN_MILLIS)) { value, _ ->
                scrollBy(value - last)
                last = value
            }
        }
        return 0f
    }

    private companion object {
        const val PAGE_TURN_MILLIS = 250
    }
}
