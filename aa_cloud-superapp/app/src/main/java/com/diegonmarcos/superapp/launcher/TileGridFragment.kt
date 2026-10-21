package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.settings.GridColumns
import com.diegonmarcos.superapp.settings.redrawOnGridColumns
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf

/**
 * Drive-style tile grid. Each tile is a glass card with a glyph fitted to its column and a label
 * below ([LauncherTile]); the click ripple and a quick "press" scale give the grid its tactile feel.
 * Compose since #773 (was fragment_tile_grid.xml + item_tile.xml): [plan] lays the tiles out in rows
 * and headings as data, so a test pins the row shapes.
 *
 * Generic API: see [Tile]. The Activity dispatches the tile's id string
 * to the matching nav action via [TileClickListener].
 */
class TileGridFragment : KitComposeFragment() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        redrawOnGridColumns(GridColumns.Kind.CLOUD)
    }

    fun interface TileClickListener {
        fun onTileClicked(tileId: String)
    }

    /** [group] = optional heading this tile sits under, [subgroup] an optional
     *  second heading below it — the two levels Configs declares (#649:
     *  Launcher; Setup ▸ Apps / Observability). Blank means ungrouped,
     *  which is how every section but Configs builds.
     *
     *  Both are the DECLARED words from build.json (`group` / `subgroup` on a
     *  page, see [Sections.Page.group]) and are printed verbatim — a heading is
     *  never a literal here, the same rule a tile [label] already keeps.
     *
     *  [rowBreak] = start a fresh row AT this tile. A group boundary already
     *  breaks a row, but it also prints a heading; this is the break without
     *  the heading, for a section that wants a particular row shape out of one
     *  unlabelled list. Declared per page in build.json (`row_break`), never
     *  computed from the index — the shape has to survive inserting a tile. */
    data class Tile(
        val id: String,
        val label: String,
        @DrawableRes val iconRes: Int,
        val group: String = "",
        val subgroup: String = "",
        val rowBreak: Boolean = false,
    )

    /** One thing in the grid's column: a heading, or a row of tile indices (separators included). */
    internal sealed class GridItem {
        class Header(val text: String) : GridItem()
        class SubHeader(val text: String) : GridItem()
        /** [first] is the index of the row's first tile, [span] how many items (tiles and separators) it holds. */
        class TileRow(val first: Int, val span: Int, val slots: Int) : GridItem()
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val args  = requireArguments()
        val title = args.getString(ARG_TITLE).orEmpty()
        val ids    = args.getStringArray(ARG_TILE_IDS)    ?: emptyArray()
        val labels = args.getStringArray(ARG_TILE_LABELS) ?: emptyArray()
        val icons  = args.getIntArray(ARG_TILE_ICONS)     ?: IntArray(0)
        val groups = args.getStringArray(ARG_TILE_GROUPS) ?: emptyArray()
        val subs   = args.getStringArray(ARG_TILE_SUBS)   ?: emptyArray()
        val breaks = args.getBooleanArray(ARG_TILE_BREAKS) ?: BooleanArray(0)
        val p = LocalKitPalette.current

        // Every grid (Configs fit or not) is the same chip grid: CHIP_COLUMNS columns of 32 dp chips, 4 dp gaps,
        // small headings. The column scrolls only if a section is ever too long for the viewport.
        val cols = CHIP_COLUMNS
        val click = { id: String -> (activity as? TileClickListener)?.onTileClicked(id); Unit }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
            Text(title.uppercase(), color = p.accent, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 0.dp).testTag(TAG_TITLE))
            if (ids.isEmpty()) {
                Text(stringResource(R.string.tile_grid_empty), color = p.textPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp).testTag(TAG_EMPTY),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                return@Column
            }
            for (item in plan(ids, groups, subs, breaks, cols)) {
                when (item) {
                    is GridItem.Header -> TileGroupHeader(item.text, Modifier.testTag(headerTag(item.text)), compact = true)
                    is GridItem.SubHeader -> TileSubgroupHeader(item.text, Modifier.testTag(subHeaderTag(item.text)), compact = true)
                    is GridItem.TileRow -> Row(Modifier.fillMaxWidth().padding(vertical = CHIP_GAP / 2),
                        horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                        for (k in 0 until item.span) {
                            val at = item.first + k
                            if (ids[at].startsWith(SEPARATOR_PREFIX)) { TileSeparator(labels[at]); continue }
                            CompactTile(
                                tileId = ids[at], label = labels[at],
                                iconRes = icons.getOrNull(at)?.takeIf { it != 0 } ?: R.drawable.ic_settings,
                                onClick = { click(ids[at]) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        for (k in item.slots until cols) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_TITLE       = "title"
        private const val ARG_TILE_IDS    = "tile_ids"
        private const val ARG_TILE_LABELS = "tile_labels"
        private const val ARG_TILE_ICONS  = "tile_icons"
        private const val ARG_TILE_GROUPS = "tile_groups"
        private const val ARG_TILE_SUBS   = "tile_subgroups"
        /** Tile id prefix of a `separator` page: drawn as the faint character, never clickable. */
        const val SEPARATOR_PREFIX = "separator:"
        private const val ARG_COLS        = "tile_columns"
        private const val ARG_TILE_BREAKS = "tile_breaks"
        private const val ARG_FIT         = "tile_fit"

        /** Columns of every tile grid. */
        const val CHIP_COLUMNS = 5
        /** Tallest a tile may be: plate + one label line. */
        const val FIT_ROW_MAX_DP = 35
        /** Narrowest a fitted tile may be before the grid drops a column. */
        const val FIT_MIN_CELL_DP = 72f

        /** Columns for the fitted grid: as many [FIT_MIN_CELL_DP] cells as the width holds, 4 at least, never over [declared]. */
        internal fun fitColumns(widthDp: Float, declared: Int): Int =
            ((widthDp - 8f) / FIT_MIN_CELL_DP).toInt().coerceIn(4, maxOf(4, declared))

        const val TAG_TITLE = "tilegrid:title"
        const val TAG_EMPTY = "tilegrid:empty"
        fun headerTag(text: String) = "tilegrid:group:$text"
        fun subHeaderTag(text: String) = "tilegrid:subgroup:$text"

        /**
         * Lay [ids] out for [cols] columns: headings where the declared group / subgroup changes, tile
         * rows between them.
         */
        internal fun plan(ids: Array<String>, groups: Array<String>, subs: Array<String>,
                          breaks: BooleanArray, cols: Int): List<GridItem> {
            val items = mutableListOf<GridItem>()
            var i = 0
            var shownGroup: String? = null
            var shownSub:   String? = null
            while (i < ids.size) {
                // Group header, then the subgroup header under it. Both are the
                // words build.json declared. Blank = ungrouped, which is every
                // other section — they render exactly as before.
                //
                // A new group RESETS shownSub: "Setup" under Watchdog and a "Setup"
                // that arrived again under a later group are two headings, and
                // remembering the old one would silently swallow the second.
                val g = groups.getOrNull(i).orEmpty()
                val s = subs.getOrNull(i).orEmpty()
                if (g.isNotEmpty() && g != shownGroup) {
                    items += GridItem.Header(g); shownGroup = g; shownSub = null
                }
                if (s.isNotEmpty() && s != shownSub) { items += GridItem.SubHeader(s); shownSub = s }
                // A row never straddles a group OR subgroup boundary: count how many
                // of the next `cols` tiles still belong to this exact pair, and start
                // the next one on a fresh row.
                // A declared row_break stops the run too — `span == 0 ||` so the
                // tile carrying the break still starts a row instead of a row of
                // zero tiles that would loop forever.
                // Separator pages ride INLINE: they take only their own text width and never
                // a column slot, so `span` counts items while `slots` counts the tiles that
                // fill the `cols` columns.
                var span = 0
                var slots = 0
                while (i + span < ids.size && groups.getOrNull(i + span).orEmpty() == g &&
                    subs.getOrNull(i + span).orEmpty() == s &&
                    (span == 0 || !breaks.getOrElse(i + span) { false })) {
                    val sep = ids[i + span].startsWith(SEPARATOR_PREFIX)
                    if (!sep && slots == cols) break
                    if (!sep) slots++
                    span++
                }
                items += GridItem.TileRow(i, span, slots)
                i += span
            }
            return items
        }

        fun newInstance(title: String, tiles: List<Tile>, columns: Int = 0, fit: Boolean = false) = TileGridFragment().apply {
            arguments = bundleOf(
                ARG_FIT         to fit,
                ARG_COLS        to columns,
                ARG_TITLE       to title,
                ARG_TILE_IDS    to tiles.map { it.id }.toTypedArray(),
                ARG_TILE_LABELS to tiles.map { it.label }.toTypedArray(),
                ARG_TILE_ICONS  to tiles.map { it.iconRes }.toIntArray(),
                ARG_TILE_GROUPS to tiles.map { it.group }.toTypedArray(),
                ARG_TILE_SUBS   to tiles.map { it.subgroup }.toTypedArray(),
                ARG_TILE_BREAKS to tiles.map { it.rowBreak }.toBooleanArray(),
            )
        }
    }
}
