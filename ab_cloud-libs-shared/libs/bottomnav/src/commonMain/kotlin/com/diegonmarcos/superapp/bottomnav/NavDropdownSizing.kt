package com.diegonmarcos.superapp.bottomnav

/**
 * THE height rule of every page-list dropdown of the nav bars (the first row's title menu in the one- and two-row modes, a
 * group's index menu): the dropdown is as tall as its content, capped at the room the window leaves below its anchor (window
 * height, minus the top system bar or the anchor's bottom, whichever is lower, minus the bottom system bar, minus
 * [NavTokens.dropdownScreenMargin]); it scrolls only when the content is taller than that cap. A short list leaves no empty
 * space, a long one is never cut at a fixed height. All values are window pixels, re-read on every layout, so a rotation
 * or a split-screen resize re-caps it. Pure: JVM-tested (NavDropdownSizingTest) and mirrored on the web (navDropdown.ts).
 */
public object NavDropdownSizing {
    /** The cap: the room below the anchor, never under [minPx] (unless the whole window is smaller), never negative. */
    public fun maxHeightPx(windowHeightPx: Int, anchorBottomPx: Int, barTopPx: Int, barBottomPx: Int, marginPx: Int, minPx: Int): Int {
        val whole = (windowHeightPx - barTopPx - barBottomPx - marginPx).coerceAtLeast(0)
        val below = windowHeightPx - maxOf(anchorBottomPx, barTopPx) - barBottomPx - marginPx
        return below.coerceIn(minOf(minPx, whole), whole)
    }

    /** The drawn height: the content's own, up to [capPx]. */
    public fun heightPx(contentPx: Int, capPx: Int): Int = minOf(contentPx, capPx)

    /** The dropdown scrolls only when its content is taller than [capPx]. */
    public fun scrolls(contentPx: Int, capPx: Int): Boolean = contentPx > capPx

    /** The popup's top: under the anchor, lifted when a [popupPx]-tall popup would cross the bottom bar and margin. */
    public fun topPx(anchorBottomPx: Int, popupPx: Int, windowHeightPx: Int, barBottomPx: Int, marginPx: Int): Int =
        anchorBottomPx.coerceAtMost(windowHeightPx - barBottomPx - marginPx - popupPx).coerceAtLeast(0)
}
