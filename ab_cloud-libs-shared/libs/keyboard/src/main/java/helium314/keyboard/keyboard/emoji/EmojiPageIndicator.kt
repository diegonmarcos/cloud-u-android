/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.emoji

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.math.min

/**
 * The thin bar under the emoji pages that shows where in the category's pages the list is: one
 * unit per page, the bar sitting on the page the list is at, sliding as it scrolls. Only a
 * category of more than one page has one. The numbers are decided here and drawn by [EmojiPageIndicator].
 */
object EmojiPageIndicatorMath {
    private const val BOTTOM_MARGIN_RATIO = 1.0f

    /** Where the list is: the page index and how far (0..1) into it. */
    class Position(val pageId: Int, val offset: Float)

    /**
     * The position from how far the list has scrolled: [offset] of [range] px with [extent] px
     * showing. Nothing to scroll (the pages fit) reads as the top.
     */
    @JvmStatic
    fun position(offset: Int, range: Int, extent: Int, pageCount: Int): Position {
        val scrollable = range - extent
        val percentage = if (scrollable > 0) (offset / scrollable.toFloat()).coerceIn(0f, 1f) else 0f
        val a = (percentage * pageCount).toInt()
        val b = percentage * pageCount - a
        return Position(a, b)
    }

    /** The bar's left and right edge in a [width] px wide indicator, or null when there is nothing to draw. */
    @JvmStatic
    fun bar(pageCount: Int, pageId: Int, offset: Float, width: Float): Pair<Float, Float>? {
        // If the category is not set yet or contains only one page, there is no indicator.
        if (pageCount <= 1) return null
        val unitWidth = width / pageCount
        val left = min(unitWidth * pageId + offset * unitWidth, width - unitWidth)
        val right = min(left + unitWidth, width)
        return left to right
    }

    /** How tall the bar is in an indicator [height] px high. */
    @JvmStatic
    fun barBottom(height: Float): Float = height * BOTTOM_MARGIN_RATIO
}

/** The indicator: [background] over the whole width, the bar inside the side paddings. */
@Composable
internal fun EmojiPageIndicator(
    pageCount: Int,
    position: () -> EmojiPageIndicatorMath.Position,
    foreground: Color,
    background: Color,
    paddingStart: Float,
    paddingEnd: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(background)
        val pos = position()
        val bar = EmojiPageIndicatorMath.bar(pageCount, pos.pageId, pos.offset, size.width - paddingStart - paddingEnd) ?: return@Canvas
        drawRect(foreground, Offset(paddingStart + bar.first, 0f), Size(bar.second - bar.first, EmojiPageIndicatorMath.barBottom(size.height)))
    }
}
