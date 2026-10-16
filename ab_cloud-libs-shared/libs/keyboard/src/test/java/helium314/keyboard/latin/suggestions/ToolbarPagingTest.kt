// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultToolbarPref
import helium314.keyboard.latin.utils.toolbarKeysOnOwnPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The toolbar pages by a whole screen of icons and the page size is measured, never declared. This is
 * the arithmetic of that row (it used to be a HorizontalScrollView's), on the real default row.
 */
class ToolbarPagingTest {
    private val keyDp = 36 // config_suggestions_strip_edge_key_width

    /** The default first row, in order: the enabled entries of the stored default. */
    private fun defaultRow(): List<ToolbarKey> = defaultToolbarPref.split(Separators.ENTRY).mapNotNull {
        val (name, enabled) = it.split(Separators.KV)
        if (enabled == "true") ToolbarKey.valueOf(name) else null
    }

    @Test fun aPageIsAsManyWholeIconsAsTheViewportShows() {
        assertEquals(11 * keyDp, ToolbarPaging.pageWidth(411, keyDp)) // 411 / 36 = 11.4 -> 11 icons
        assertEquals(10 * keyDp, ToolbarPaging.pageWidth(380, keyDp))
        assertEquals(keyDp, ToolbarPaging.pageWidth(20, keyDp)) // an icon wider than the viewport still pages by one icon
        assertEquals(411, ToolbarPaging.pageWidth(411, 0)) // nothing measured yet: by a viewport
        assertEquals(1, ToolbarPaging.pageWidth(0, 0))
    }

    @Test fun theDefaultRowSplitsElevenTenAndTheOwnPageKeyAlone() {
        val keys = defaultRow()
        val page = ToolbarPaging.pageWidth(411, keyDp)
        val gaps = ToolbarPaging.gaps(keys, toolbarKeysOnOwnPage, keyDp, page)
        var x = 0
        val perPage = sortedMapOf<Int, MutableList<ToolbarKey>>()
        keys.forEachIndexed { i, key ->
            x += gaps[i]
            perPage.getOrPut(x / page) { mutableListOf() }.add(key)
            x += keyDp
        }
        assertEquals(listOf(11, 10, 1), perPage.values.map { it.size })
        assertEquals(toolbarKeysOnOwnPage, perPage.values.last().toSet())
    }

    @Test fun onlyAKeyThatWantsItsOwnPageGetsAGap() {
        val keys = listOf(ToolbarKey.UNDO, ToolbarKey.REDO, ToolbarKey.VAULT)
        val gaps = ToolbarPaging.gaps(keys, toolbarKeysOnOwnPage, keyDp, 4 * keyDp)
        assertEquals(0, gaps[0])
        assertEquals(0, gaps[1])
        assertEquals(2 * keyDp, gaps[2]) // the 2 icons that remain of the first page
    }

    @Test fun aKeyAlreadyOnAPageBoundaryNeedsNoGap() {
        val keys = listOf(ToolbarKey.UNDO, ToolbarKey.REDO, ToolbarKey.COPY, ToolbarKey.CUT, ToolbarKey.VAULT)
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 0), ToolbarPaging.gaps(keys, toolbarKeysOnOwnPage, keyDp, 4 * keyDp))
    }

    @Test fun theGapsDoNotMoveWhenTheRowIsLaidOutAgain() {
        val keys = defaultRow()
        val page = ToolbarPaging.pageWidth(411, keyDp)
        assertArrayEquals(ToolbarPaging.gaps(keys, toolbarKeysOnOwnPage, keyDp, page), ToolbarPaging.gaps(keys, toolbarKeysOnOwnPage, keyDp, page))
    }

    @Test fun aFlingTurnsOnePageAndLandsFlushAtTheEnd() {
        val page = 396 // 11 icons
        val limit = 700
        assertEquals(396, ToolbarPaging.turnPageTarget(0, 1, page, limit))
        assertEquals(limit, ToolbarPaging.turnPageTarget(396, 1, page, limit)) // the short last page: clamped to the end
        assertEquals(0, ToolbarPaging.turnPageTarget(396, -1, page, limit))
        assertEquals(396, ToolbarPaging.turnPageTarget(700, -1, page, limit)) // back from the end to the page before it
        assertEquals(0, ToolbarPaging.turnPageTarget(0, -1, page, limit)) // nowhere to go
    }

    @Test fun aSlowDragSettlesOnTheNearestPage() {
        val page = 396
        val limit = 700
        assertEquals(0, ToolbarPaging.nearestPageTarget(150, page, limit))
        assertEquals(396, ToolbarPaging.nearestPageTarget(250, page, limit))
        assertEquals(limit, ToolbarPaging.nearestPageTarget(690, page, limit))
    }

    @Test fun whenTheIconsFitPagingIsInert() {
        assertEquals(0, ToolbarPaging.turnPageTarget(0, 1, 396, 0))
        assertEquals(0, ToolbarPaging.nearestPageTarget(0, 396, 0))
        assertTrue(ToolbarPaging.gapForOwnPage(0, 396) == 0)
    }
}
