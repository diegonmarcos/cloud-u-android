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

    /**
     * As many whole icons as the viewport can show. Never zero, so an icon wider than the viewport
     * still pages one icon at a time, and an unmeasured row still pages by a viewport.
     */
    @JvmStatic
    fun pageWidth(viewport: Int, icon: Int): Int {
        if (icon <= 0) return viewport.coerceAtLeast(1)
        return (viewport / icon).coerceAtLeast(1) * icon
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

    /** The left gap that puts a key starting at [startWithoutGap] on the start of the next page. */
    @JvmStatic
    fun gapForOwnPage(startWithoutGap: Int, page: Int): Int = (page - startWithoutGap % page) % page

    /**
     * The gap in front of every key of the row, in px. A key in [ownPage] gets a gap sized to
     * whatever is left of the page it would otherwise land in the middle of, so it starts a page of
     * its own. The same gap is what pushes the row past its last full page and so makes that page
     * reachable at all.
     *
     * The gap comes from the measured [page], never from a key count: how many icons fit on a page
     * depends on screen width, icon size and rotation, so filler keys could not do this. Each gap is
     * computed from the position EXCLUDING the gap it is about to set, so there is one answer and it
     * does not move when the row is laid out again.
     */
    @JvmStatic
    fun gaps(keys: List<ToolbarKey>, ownPage: Set<ToolbarKey>, icon: Int, page: Int): IntArray {
        val gaps = IntArray(keys.size)
        var x = 0
        keys.forEachIndexed { i, key ->
            if (key in ownPage && page > 0) gaps[i] = gapForOwnPage(x, page)
            x += gaps[i] + icon
        }
        return gaps
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
