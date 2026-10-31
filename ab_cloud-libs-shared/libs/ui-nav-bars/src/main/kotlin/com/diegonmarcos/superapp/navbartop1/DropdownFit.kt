package com.diegonmarcos.superapp.navbartop1

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.diegonmarcos.superapp.bottomnav.LocalNavTokens
import com.diegonmarcos.superapp.bottomnav.NavDropdownSizing
import com.diegonmarcos.superapp.bottomnav.NavTokens
import kotlin.math.roundToInt

/** Where a page-list dropdown's anchor sits, re-read on every layout (a rotation or a split-screen resize relayouts it). */
@Stable
internal class DropdownAnchor {
    var bottomPx by mutableIntStateOf(0)
    var windowHeightPx by mutableIntStateOf(0)
}

@Composable
internal fun rememberDropdownAnchor(): DropdownAnchor = remember { DropdownAnchor() }

/** Records the anchor's window bottom and the window's height. */
@Composable
internal fun Modifier.dropdownAnchor(anchor: DropdownAnchor): Modifier {
    val root = LocalView.current.rootView
    return onGloballyPositioned { c ->
        anchor.bottomPx = c.boundsInWindow().bottom.roundToInt()
        anchor.windowHeightPx = root.height
    }
}

/** The dropdown's height cap ([NavDropdownSizing.maxHeightPx]) for [anchor], from the nav tokens and the live system bars. */
@Composable
internal fun dropdownMaxHeight(anchor: DropdownAnchor): Dp {
    val d = LocalDensity.current
    val t = LocalNavTokens.current ?: NavTokens()
    val bars = WindowInsets.systemBars
    val px = NavDropdownSizing.maxHeightPx(
        windowHeightPx = anchor.windowHeightPx,
        anchorBottomPx = anchor.bottomPx,
        barTopPx = bars.getTop(d),
        barBottomPx = bars.getBottom(d),
        marginPx = with(d) { t.dropdownScreenMargin.roundToPx() },
        minPx = with(d) { t.dropdownMinHeight.roundToPx() },
    )
    return with(d) { px.toDp() }
}

/** The bottom room a popup keeps clear (bottom system bar + margin), in px, for [BelowAnchorFit]. */
@Composable
internal fun dropdownBottomReservePx(): Int {
    val d = LocalDensity.current
    val t = LocalNavTokens.current ?: NavTokens()
    return WindowInsets.systemBars.getBottom(d) + with(d) { t.dropdownScreenMargin.roundToPx() }
}

/** Under the anchor, kept inside the window horizontally, and lifted so its bottom never crosses the bottom bar + margin. */
internal class BelowAnchorFit(private val reserveBottomPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset(
            anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            NavDropdownSizing.topPx(anchorBounds.bottom, popupContentSize.height, windowSize.height, reserveBottomPx, 0),
        )
}
