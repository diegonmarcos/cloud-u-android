package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.settings.GridColumns
import com.diegonmarcos.superapp.settings.redrawOnGridColumns

import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.ModePrefs
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Grouped Home master view.
 *
 * Each group contributes (header + N tile rows) of [GridColumns.cloud] columns; a fixed row height
 * keeps icon + label legible at every group size, and the page scrolls when the content exceeds the
 * visible area (the earlier "auto-fit to one screen" weight layout crammed rows into too-short
 * heights and clipped tile labels).
 *
 * Data source: [Sections.homeGroups] from build.json::ui.home_groups, plus
 * a trailing "Actions" group from build.json::ui.home_actions.
 *
 * Tile clicks fan out via [TileGridFragment.TileClickListener] using the
 * same id grammar the rest of the app already understands:
 *   "section:<id>"      switch to that section
 *   "page:<sec>/<page>" deep-link (handled in MainActivity.onTileClicked)
 *   "action:<type>"     app-level action (dispatched in MainActivity)
 *
 * Compose since #773 (was fragment_home_grouped.xml + item_tile.xml + item_home_group_header.xml): the
 * buckets are [buckets], pure over Sections, so a test pins the groups and their order.
 */
class HomeGroupedFragment : KitComposeFragment() {

    /** One group as drawn: its heading and its tiles (id, label, icon name). */
    internal class Bucket(val title: String, val tiles: List<Triple<String, String, String>>, val scroll: String? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        redrawOnGridColumns(GridColumns.Kind.CLOUD)
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        // build.json::ui.tile_columns is the default; the Controls stepper overrides it.
        val cols = GridColumns.cloud(ctx)
        val groups = remember { buckets(ctx) }
        val click = { id: String -> (activity as? TileGridFragment.TileClickListener)?.onTileClicked(id); Unit }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(6.dp)) {
            for (bucket in groups) {
                TileGroupHeaderPlain(bucket.title)
                // All groups render as a full multi-row grid (COLS per row, wrapping to as many rows as
                // needed) — every icon visible, row per row. The build.json `scroll:"horizontal"` flag is
                // intentionally ignored now: the old single-row HorizontalScrollView strip hid apps
                // behind a left/right scroll.
                for (row in bucket.tiles.chunked(cols)) {
                    Row(Modifier.fillMaxWidth()) {
                        for ((id, label, iconName) in row) {
                            LauncherTile(
                                tileId = id, label = label,
                                iconRes = Sections.iconResFor(ctx, iconName).takeIf { it != 0 } ?: R.drawable.ic_settings,
                                cols = cols, pageDp = 8, chromeDp = 16,
                                onClick = { click(id) },
                                // Tightened from 96dp to 76dp to match GroupedTilesFragment's compact density
                                // now that tile_columns is 6 (more tiles per line → each is narrower).
                                height = 76.dp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        for (k in row.size until cols) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    @Composable
    private fun TileGroupHeaderPlain(title: String) {
        androidx.compose.material3.Text(title.uppercase(), color = com.diegonmarcos.superapp.uikit.LocalKitPalette.current.accent,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 12.dp, end = 10.dp, bottom = 4.dp).testTag(headerTag(title)))
    }

    companion object {
        fun newInstance() = HomeGroupedFragment()

        fun headerTag(title: String) = "homegrouped:group:$title"

        /**
         * The groups of the grouped "All Apps" grid (build.json::ui.home_groups + ui.home_actions).
         *
         * Per-mode icon selection: Section.iconForMode(mode) is the SOURCE OF TRUTH — when a tile points
         * at `section:X`, we look the section up and resolve its mode-aware icon there, so the home_groups
         * tile + the bottom-nav item + the drawer entry all render the SAME glyph for the same mode. For
         * `page:S/P` tiles and free-form tiles the tile's own iconName is used.
         */
        internal fun buckets(ctx: Context): List<Bucket> {
            val mode = ModePrefs(ctx).mode
            fun resolveTileIcon(tile: Sections.HomeTile): String {
                if (tile.id.startsWith("section:")) {
                    val sid = tile.id.removePrefix("section:")
                    Sections.byId(sid)?.let { return it.iconForMode(mode) }
                }
                return tile.iconForMode(mode)
            }
            val out = mutableListOf<Bucket>()
            Sections.homeGroups().forEach { g ->
                out += Bucket(
                    title  = g.title,
                    tiles  = g.tiles.map { Triple(it.id, it.label, resolveTileIcon(it)) },
                    scroll = g.scroll,
                )
            }
            Sections.homeActions().takeIf { it.isNotEmpty() }?.let { acts ->
                out += Bucket(
                    ctx.getString(R.string.home_group_actions),
                    acts.map { Triple("action:${it.actionType}", it.label, it.iconName) },
                )
            }
            return out
        }
    }
}
