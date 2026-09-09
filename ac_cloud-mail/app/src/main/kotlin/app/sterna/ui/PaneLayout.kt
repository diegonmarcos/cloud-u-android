package app.sterna.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * How many panes the inbox host shows, decided on the window WIDTH alone — never on the
 */
enum class PaneLayout { Narrow, Wide, Desk }

/** M3 "Medium": list + reader. */
const val WIDE_MIN_WIDTH_DP = 600

/** M3 "Large": list + reader + a permanent drawer (the drawer itself is a later slice, V6). */
const val DESK_MIN_WIDTH_DP = 1200

/**
 * The width of the drawer sheet (`ModalDrawerSheet` / `PermanentDrawerSheet` in InboxScreen) — and
 * what a permanent drawer takes out of [paneSplit].
 *
 * 360, not the 300 this used to be, because 300 made folder names WRAP onto a second line: at the
 * row chrome below that left 116dp for the label, and 14 of the 28 labels a real account draws
 * (the synced folders measured in test/drawer-folder-labels.json plus the localised built-ins)
 * are wider than that. 360dp is Material 3's own `DrawerDefaults.MaximumDrawerWidth`, i.e. the
 * most horizontal room a modal drawer may take before it stops leaving a phone any scrim to tap
 * back through — so it is the ceiling, not a preference.
 */
const val DRAWER_SHEET_WIDTH_DP = 360

/**
 * Point size of a folder row's label, the second lever against wrapping.
 *
 * Material 3 draws a `NavigationDrawerItem` label at labelLarge (14sp); 12sp is a sixth off, which
 * buys ~14 % more characters per line and stays at the floor this app already uses for secondary
 * text elsewhere. It is a size, not a truncation: nothing here ellipsises, because a folder whose
 * name the user cannot read is worse than one that wraps.
 */
const val FOLDER_LABEL_TEXT_SIZE_SP = 12

/**
 * Everything on a folder row that is NOT the label, in dp — the budget the label does not get.
 *
 *   12  drawer-item horizontal padding (left)      Modifier.padding(horizontal = 12.dp)
 *   16  M3 NavigationDrawerItem content start
 *   48  the icon slot: 24 chevron-or-indent + 24 folder icon
 *   12  M3 icon-to-label gap
 *   12  M3 label-to-badge gap
 *   48  the badge slot: the folder-options IconButton's touch target
 *   24  M3 NavigationDrawerItem content end
 *   12  drawer-item horizontal padding (right)
 *
 * Kept here rather than inline in the row so the wrapping test can compute the same budget the
 * screen does; a number that lived only in InboxScreen could drift from the one being asserted.
 */
const val DRAWER_FOLDER_ROW_CHROME_DP = 184

/** How much width a folder LABEL actually gets at [DRAWER_SHEET_WIDTH_DP]. */
fun folderLabelBudgetDp(drawerWidthDp: Int = DRAWER_SHEET_WIDTH_DP): Int =
    drawerWidthDp - DRAWER_FOLDER_ROW_CHROME_DP

/**
 * What Material 3 makes a `NavigationDrawerItem` cost in height if nothing caps it:
 * `NavigationDrawerTokens.ActiveIndicatorHeight`, applied as `heightIn(min = …)`.
 *
 * Recorded here because it is the number this app is deliberately spending less than, and a rule
 * that says "denser than Material" needs Material's figure to compare against.
 */
const val MATERIAL_DRAWER_ROW_HEIGHT_DP = 56

/**
 * The smallest a control carrying a tap target may be drawn: Material's own
 * `minimumInteractiveComponentSize`, which every `IconButton` reserves.
 *
 * The floor under [DRAWER_ROW_HEIGHT_DP] and not a style choice — the folder-options `IconButton`
 * measures itself at this height and ignores the constraints handed to it, so a shorter row does
 * not shrink the button, it draws it outside the row.
 */
const val MIN_TOUCH_TARGET_DP = 48

/**
 * How tall one drawer row is allowed to be, at `fontScale 1`.
 *
 * 48 and not Material's [MATERIAL_DRAWER_ROW_HEIGHT_DP], because at 56 a row holds one 20dp line
 * box of label and 36dp of nothing — and the sidebar draws 28 folders, so that "nothing" is the
 * difference between reading the folder list and scrolling for it. The gap was always there;
 * what made it show was folder labels ceasing to wrap onto the second line that used to fill it.
 *
 * It cannot go below [MIN_TOUCH_TARGET_DP]: the folder-options button owns that height whatever
 * the row says. And it does not need to go above, because a label that still wraps draws two
 * label-large line boxes — 40dp — which 48 holds without clipping, so the wrapping that was
 * deliberately kept readable stays readable.
 */
const val DRAWER_ROW_HEIGHT_DP = 48

/**
 * The row height at THIS font scale — the cap actually handed to a drawer row.
 *
 * Capping is what beats Material's `heightIn(min = 56.dp)` at all: that modifier enforces the
 * constraints coming in, so only a maximum can bring a row under the token. A maximum fixed in dp
 * would then CLIP a user who has scaled their text up, which the token's minimum never did — so
 * the cap grows with the text, exactly as [app.sterna.ui.compose.minimumSuggestionRow] does.
 */
fun drawerRowHeight(density: Density): Dp = DRAWER_ROW_HEIGHT_DP.dp * max(1f, density.fontScale)

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
