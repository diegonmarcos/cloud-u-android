package app.sterna.ui.compose

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * How tall the recipient suggestion menu may be, given the room left under the field once the keyboard
 */
fun suggestionMenuMaxHeight(
    windowHeightPx: Int,
    fieldBottomPx: Int,
    imeHeightPx: Int,
    density: Density,
): Dp? {
    val freePx = windowHeightPx - fieldBottomPx - imeHeightPx
    val free = with(density) { freePx.toDp() }
    if (free < minimumSuggestionRow(density)) return null
    return minOf(free, SUGGESTION_MENU_MAX_HEIGHT)
}

/**
 * The room one suggestion needs at THIS font scale.
 */
fun minimumSuggestionRow(density: Density): Dp =
    SUGGESTION_ROW_MIN_HEIGHT * max(1f, density.fontScale)

/** The cap the menu keeps whenever there is room for it: about four and a half rows. */
val SUGGESTION_MENU_MAX_HEIGHT: Dp = 256.dp

/**
 * One suggestion row at `fontScale 1`: a 40 dp avatar ([app.sterna.ui.components.ContactAvatar])
 */
val SUGGESTION_ROW_MIN_HEIGHT: Dp = 56.dp
