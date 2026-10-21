package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.ModePrefs
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Grouped Home master view.
 *
 * Each group is Cloud ▸ Apps' [GroupTitle] over a wrapped [TileStrip] of [TileCell]s (#1075); the page scrolls when the content exceeds the
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

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val groups = remember { buckets(ctx) }
        val click = { id: String -> (activity as? TileGridFragment.TileClickListener)?.onTileClicked(id); Unit }
        // Cloud ▸ Apps' page (#1075): the same GroupTitle + TileStrip/TileCell at the same tokens, cells wrapped onto lines
        // so every icon is visible (the build.json `scroll:"horizontal"` flag stays ignored: no hidden sideways strips).
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(TILE_PAGE_PADDING)) {
            groups.forEachIndexed { i, bucket ->
                item(key = "g:$i") {
                    Column {
                        GroupTitle(bucket.title)
                        TileStrip(bucket.tiles.map { (id, label, icon) -> Sections.AggTile(id, label, icon, id) }, click,
                            { click(it.target) }, wrap = true)
                    }
                }
            }
        }
    }

    companion object {
        fun newInstance() = HomeGroupedFragment()

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
