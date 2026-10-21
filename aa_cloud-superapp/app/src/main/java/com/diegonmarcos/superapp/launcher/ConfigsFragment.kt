package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.search.SearchEntry
import com.diegonmarcos.superapp.search.SearchFrame
import com.diegonmarcos.superapp.search.SearchSession
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.os.bundleOf

/**
 * Configs (Cloud ▸ Configs and the Configs section) as a sibling of Cloud ▸ Apps (#1075): the same list, the same
 * [GroupTitle] headings and the same [TileStrip] / [TileCell] tiles at the same [TILE_CELL_WIDTH], the same page padding
 * ([TILE_PAGE_PADDING]), under the same inline search bar (#1076) — wrapped onto further lines instead of scrolling sideways.
 * It scrolls only inside its own content area, which the shell hosts below the top row like every other page.
 * What it lists is the Configs grid's tiles, arranged by their declared headings ([groups]).
 */
class ConfigsFragment : KitComposeFragment(), BackHandler {

    private var search: SearchSession? = null

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val a = requireArguments()
        val tiles = groups(a.getStringArray(ARG_IDS) ?: emptyArray(), a.getStringArray(ARG_LABELS) ?: emptyArray(),
            a.getIntArray(ARG_ICONS) ?: IntArray(0), a.getStringArray(ARG_GROUPS) ?: emptyArray(), a.getStringArray(ARG_SUBS) ?: emptyArray())
        val click = { id: String -> (activity as? TileGridFragment.TileClickListener)?.onTileClicked(id); Unit }
        val session = remember { SearchSession(this, SearchEntry.CONFIGS_PAGE).also { search = it } }
        SearchFrame(session, "Search Configs", Modifier.fillMaxSize()) { ConfigsPage(tiles, click) }
    }

    /** Back with a query clears it before Back leaves the page. */
    override fun tryHandleBack(): Boolean = search?.handleBack() == true

    override fun onDestroyView() {
        super.onDestroyView()
        search = null
    }

    /** One run of tiles under its headings: [headings] are the group / subgroup words that open the run (blank = none). */
    internal class Run(val headings: List<String>, val tiles: List<Sections.AggTile>)

    companion object {
        private const val ARG_IDS = "ids"
        private const val ARG_LABELS = "labels"
        private const val ARG_ICONS = "icons"
        private const val ARG_GROUPS = "groups"
        private const val ARG_SUBS = "subgroups"
        const val TAG_LIST = "configs:list"

        /** The declared headings (group, then subgroup) wherever they change; the tiles between them are one [Run]. */
        internal fun groups(ids: Array<String>, labels: Array<String>, icons: IntArray, groups: Array<String>, subs: Array<String>): List<Run> {
            val runs = mutableListOf<Run>()
            var shownGroup: String? = null
            var shownSub: String? = null
            var heads = mutableListOf<String>()
            var tiles = mutableListOf<Sections.AggTile>()
            fun flush() { if (tiles.isNotEmpty()) runs += Run(heads, tiles); heads = mutableListOf(); tiles = mutableListOf() }
            for (i in ids.indices) {
                val g = groups.getOrNull(i).orEmpty()
                val s = subs.getOrNull(i).orEmpty()
                if ((g.isNotEmpty() && g != shownGroup) || (s.isNotEmpty() && s != shownSub)) flush()
                if (g.isNotEmpty() && g != shownGroup) { heads += g; shownGroup = g; shownSub = null }
                if (s.isNotEmpty() && s != shownSub) { heads += s; shownSub = s }
                tiles += Sections.AggTile(ids[i], labels.getOrElse(i) { "" }, "", ids[i],
                    separator = ids[i].startsWith(TileGridFragment.SEPARATOR_PREFIX),
                    iconRes = icons.getOrNull(i)?.takeIf { it != 0 } ?: R.drawable.ic_settings)
            }
            flush()
            return runs
        }

        fun newInstance(tiles: List<TileGridFragment.Tile>): ConfigsFragment = ConfigsFragment().apply {
            arguments = bundleOf(
                ARG_IDS to tiles.map { it.id }.toTypedArray(),
                ARG_LABELS to tiles.map { it.label }.toTypedArray(),
                ARG_ICONS to tiles.map { it.iconRes }.toIntArray(),
                ARG_GROUPS to tiles.map { it.group }.toTypedArray(),
                ARG_SUBS to tiles.map { it.subgroup }.toTypedArray(),
            )
        }
    }
}

@Composable
internal fun ConfigsPage(runs: List<ConfigsFragment.Run>, click: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag(ConfigsFragment.TAG_LIST), contentPadding = PaddingValues(TILE_PAGE_PADDING)) {
        runs.forEachIndexed { ri, run ->
            item(key = "r:$ri") {
                Column {
                    for (h in run.headings) GroupTitle(h)
                    TileStrip(run.tiles, click, { click(it.target) }, wrap = true)
                }
            }
        }
    }
}
