package app.sterna.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A lightweight scroll position indicator drawn on the right edge of a scrolling
 */
fun Modifier.verticalScrollbar(
    state: LazyListState,
    itemCount: Int,
    width: Dp = 4.dp,
): Modifier = composed {
    val alpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) 1f else 0f,
        animationSpec = tween(durationMillis = if (state.isScrollInProgress) 120 else 600),
        label = "scrollbarAlpha",
    )
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant
    val widthPx = with(LocalDensity.current) { width.toPx() }

    drawWithContent {
        drawContent()
        val visible = state.layoutInfo.visibleItemsInfo
        if (alpha <= 0f || itemCount <= 0 || visible.isEmpty()) return@drawWithContent

        val visibleCount = visible.size
        // Only show the bar once the list overflows the viewport.
        if (visibleCount >= itemCount) return@drawWithContent

        val track = size.height
        val thumbFraction = (visibleCount.toFloat() / itemCount).coerceIn(0.05f, 1f)
        val thumbHeight = track * thumbFraction
        val scrollableItems = (itemCount - visibleCount).coerceAtLeast(1)
        val scrollFraction = (state.firstVisibleItemIndex.toFloat() / scrollableItems).coerceIn(0f, 1f)
        val thumbTop = (track - thumbHeight) * scrollFraction

        drawRoundRect(
            color = thumbColor.copy(alpha = 0.5f * alpha),
            topLeft = Offset(size.width - widthPx, thumbTop),
            size = Size(widthPx, thumbHeight),
            cornerRadius = CornerRadius(widthPx / 2, widthPx / 2),
        )
    }
}
