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
 * The LINE BOX a folder label draws in, which is not the same lever as [FOLDER_LABEL_TEXT_SIZE_SP]
 * and is the one that was missed.
 *
 * `labelLarge` is 14sp of glyph inside a 20sp line box. Shrinking only `fontSize` to 12sp left that
 * 20sp box untouched — every row went on paying Material's leading for 14sp text while drawing
 * 12sp, and because the cap has to hold two boxes for the names that still wrap, that 4sp of dead
 * leading set the floor under the whole sidebar at 40dp. Stating the line height is what lets
 * [DRAWER_ROW_HEIGHT_DP] come down at all.
 *
 * 16 keeps labelLarge's proportion almost exactly (14/20 is 1.43; 12/16 is 1.33, marginally
 * tighter, which is the request). It is not smaller than the glyphs need: at 12sp an accented
 * capital runs about 14dp from Ñ's tilde to a descender, so 16dp clears the diacritics a Spanish
 * folder list is full of rather than clipping them.
 */
const val FOLDER_LABEL_LINE_HEIGHT_SP = 16

/**
 * Everything on a folder row that is NOT the label, in dp — the budget the label does not get.
 *
 *   12  drawer-item horizontal padding (left)      Modifier.padding(horizontal = 12.dp)
 *   16  M3 NavigationDrawerItem content start
 *   48  the icon slot: 24 chevron-or-indent + 24 folder icon
 *   12  M3 icon-to-label gap
 *   12  M3 label-to-badge gap
 *   32  the badge slot: [DRAWER_FOLDER_MENU_TAP_SIZE_DP]
 *   24  M3 NavigationDrawerItem content end
 *   12  drawer-item horizontal padding (right)
 *
 * The badge slot was 48 while the folder-options control was an `IconButton`. Narrowing it to
 * [DRAWER_FOLDER_MENU_TAP_SIZE_DP] hands the label the 16dp back, which is why only one real folder
 * name still wraps where three did — see test/drawer-folder-labels.json::known_wrapping.
 *
 * Kept here rather than inline in the row so the wrapping test can compute the same budget the
 * screen does; a number that lived only in InboxScreen could drift from the one being asserted.
 */
const val DRAWER_FOLDER_ROW_CHROME_DP = 168

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
 * What Material reserves under anything tappable: `minimumInteractiveComponentSize`, which every
 * `IconButton` applies unconditionally and which IGNORES the constraints handed to it.
 *
 * This used to be the floor under [DRAWER_ROW_HEIGHT_DP], and that is precisely why two rounds of
 * making the sidebar denser could not reach it — the number recorded here is what the row was
 * charged, so lowering the cap to it and stopping looked like the end of the road. It is kept as
 * the figure the row is now measured AGAINST: the folder-options control is no longer an
 * `IconButton`, so the reservation is gone and the row is allowed under it. A change that puts an
 * `IconButton` back in a folder row silently restores every dp of it, which is what
 * DrawerRowDensityLintTest watches for.
 */
const val MIN_TOUCH_TARGET_DP = 48

/**
 * The tappable box around the folder-options icon — the one control on a folder row.
 *
 * Deliberately under Material's [MIN_TOUCH_TARGET_DP], and the reason the row can be 32dp at all.
 * An `IconButton` here reserved 48dp to draw a 24dp glyph, so 24 of the 48dp row was blank held for
 * a tap, under a label whose line box is [FOLDER_LABEL_LINE_HEIGHT_SP]. The same structural waste
 * was removed from the reading view's tag strip for the same reason (a01045f34).
 *
 * 32 rather than the chevron's bare 24dp because this one opens a destructive menu (rename, delete)
 * and deserves the larger target of the two. The glyph stays 24dp and its contentDescription is
 * unchanged, so nothing about the screen reader's view of this row moves.
 *
 * ponytail: a 32dp target is a deliberate trade against Material's 48dp guidance, taken because the
 * owner has asked for this sidebar to be denser three times. If it proves hard to hit, the upgrade
 * is a long-press on the whole row — a target far bigger than 48dp — not a taller row.
 */
const val DRAWER_FOLDER_MENU_TAP_SIZE_DP = 32

/**
 * How tall one drawer row is allowed to be, at `fontScale 1`.
 *
 * 32, down from 48, and the number is now set by CONTENT rather than by a tap target. Two things
 * had to move before it could: the label's line box, which was still Material's 20sp leading for
 * 12sp text (see [FOLDER_LABEL_LINE_HEIGHT_SP]), and the folder-options `IconButton`'s 48dp
 * reservation (see [DRAWER_FOLDER_MENU_TAP_SIZE_DP]). Lowering this constant alone — the obvious
 * move, and the one already made twice — could not go under 48 while either was in place.
 *
 * What holds it at 32 is the wrapping that was deliberately kept readable: a folder name too long
 * for the sheet draws two [FOLDER_LABEL_LINE_HEIGHT_SP] line boxes, 32dp, which this holds exactly.
 * Going lower would slice the second line off the one real folder name that still wraps.
 *
 * For the 28-folder account in test/drawer-folder-labels.json this is ~450dp of height returned to
 * the list — more than a phone screen of scrolling.
 */
const val DRAWER_ROW_HEIGHT_DP = 32

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
