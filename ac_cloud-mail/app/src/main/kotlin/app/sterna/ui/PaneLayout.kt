package app.sterna.ui

/**
 * How many panes the inbox host shows, decided on the window WIDTH alone — never on the
 */
enum class PaneLayout { Narrow, Wide, Desk }

/** M3 "Medium": list + reader. */
const val WIDE_MIN_WIDTH_DP = 600

/** M3 "Large": list + reader + a permanent drawer (the drawer itself is a later slice, V6). */
const val DESK_MIN_WIDTH_DP = 1200

/** The width of the drawer sheet (`ModalDrawerSheet` in InboxScreen) — what a permanent drawer takes. */
const val DRAWER_SHEET_WIDTH_DP = 300

/** Below this the list truncates sender and time on every row. */
const val LIST_PANE_MIN_WIDTH_DP = 280

/** The list's share of what is left once the drawer, if permanent, has taken its width. */
const val LIST_PANE_SHARE_PERCENT = 40

/** < 600 → [PaneLayout.Narrow] ; 600..1199 → [PaneLayout.Wide] ; ≥ 1200 → [PaneLayout.Desk]. */
fun paneLayout(widthDp: Int): PaneLayout = when {
    widthDp < WIDE_MIN_WIDTH_DP -> PaneLayout.Narrow
    widthDp < DESK_MIN_WIDTH_DP -> PaneLayout.Wide
    else -> PaneLayout.Desk
}

/** The decision the host carries to the screen: which layout, and how wide the list pane is. */
data class PaneSplit(val layout: PaneLayout, val listWidthDp: Int)

/**
 * `null` on a narrow window (one pane, nothing to split). Otherwise the list takes
 */
fun paneSplit(widthDp: Int): PaneSplit? {
    val layout = paneLayout(widthDp)
    val available = when (layout) {
        PaneLayout.Narrow -> return null
        PaneLayout.Wide -> widthDp
        PaneLayout.Desk -> widthDp - DRAWER_SHEET_WIDTH_DP
    }
    val list = maxOf(LIST_PANE_MIN_WIDTH_DP, available * LIST_PANE_SHARE_PERCENT / 100)
    return PaneSplit(layout, list)
}
