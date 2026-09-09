package app.sterna.ui.search

/**
 * How much of [delta] the panel can actually take, given where it currently sits.
 */
fun searchPanelTakes(offsetPx: Float, panelHeightPx: Float, delta: Float): Float =
    (offsetPx + delta).coerceIn(0f, panelHeightPx) - offsetPx

/**
 * A released drag lands the advanced-filter panel on one side or the other: this is that decision,
 */
fun searchPanelSettlesOpen(offsetPx: Float, panelHeightPx: Float, velocity: Float): Boolean = when {
    // A flick decides first: honouring the halfway line over it would make a deliberate throw stop
    // dead at 40% of the travel.
    velocity > SEARCH_PANEL_FLING_VELOCITY -> true
    velocity < -SEARCH_PANEL_FLING_VELOCITY -> false
    // Otherwise the nearest side, so a stray micro-drag lands where it started rather than toggling.
    else -> offsetPx > panelHeightPx / 2f
}

/** Above this release speed (px/s) the throw decides, below it the halfway line does. */
private const val SEARCH_PANEL_FLING_VELOCITY = 800f
