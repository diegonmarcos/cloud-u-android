package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.system.ModePrefs
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.os.bundleOf

/**
 * Drawer's section pane — site-map style navigation for ONE section.
 * Same list as [HomeDrawerFragment] but scoped to the currently-open section: every page is shown,
 * and pages with sub_pages render as expandable parents (Mail Settings → 11 tabs, Mail More → 8
 * overflow items, etc.).
 *
 * Tapping any item bubbles up to the host activity via
 * [HomeDrawerFragment.NavigationListener] so the same dispatcher
 * resolves the destination.
 *
 * Compose since #773 (was a NavigationView filled programmatically): [rows] is the menu as data,
 * [DrawerList] draws it.
 */
class SectionMenuFragment : KitComposeFragment() {

    private val sectionId: String get() = requireArguments().getString(ARG_SECTION_ID).orEmpty()

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val rows = androidx.compose.runtime.remember(sectionId) { rows(ctx, sectionId) }
        DrawerList(rows, ::onItemPicked, Modifier.fillMaxSize())
    }

    private fun onItemPicked(t: DrawerTarget) {
        val listener = activity as? HomeDrawerFragment.NavigationListener
        when (t) {
            is DrawerTarget.Section -> listener?.onDrawerSectionSelected(t.id, t.label)
            is DrawerTarget.Page    -> listener?.onDrawerPageSelected(t.sectionId, t.pageId, t.label)
            is DrawerTarget.Action  -> listener?.onDrawerActionSelected(t.actionType)
            is DrawerTarget.Tile    -> {
                // Aggregator tile in the drawer — route through the same
                // dispatcher tile clicks in the body use. Activity closes
                // the drawer in its onTileClicked path.
                (activity as? TileGridFragment.TileClickListener)?.onTileClicked(t.target)
            }
        }
    }

    companion object {
        private const val ARG_SECTION_ID = "section_id"

        /** The menu of [sectionId] as rows: the section header, then its pages / panels / defaults. */
        internal fun rows(ctx: Context, sectionId: String): List<DrawerRow> {
            val section = Sections.byId(sectionId) ?: return emptyList()
            val out = mutableListOf<DrawerRow>()
            fun icon(name: String?) = name?.let { Sections.iconResFor(ctx, it) } ?: 0

            // Section header — taps go straight to the section index.
            out += DrawerRow(section.label.uppercase(), DrawerRow.Kind.ITEM, icon(section.iconName),
                DrawerTarget.Section(section.id, section.label))

            when {
                section.pages.isNotEmpty() -> {
                    // Declared headings (#649) become disabled rows, the same way a
                    // tile group's title does below — see [Sections.headingOf].
                    var shownGroup = ""; var shownSub = ""
                    for (page in section.pages) {
                        val (g, s) = Sections.headingOf(section, page)
                        if (g.isNotEmpty() && g != shownGroup) {
                            out += DrawerRow(g.uppercase(), DrawerRow.Kind.HEADING)
                            shownGroup = g; shownSub = ""
                        }
                        if (s.isNotEmpty() && s != shownSub) {
                            out += DrawerRow("  $s", DrawerRow.Kind.HEADING)
                            shownSub = s
                        }
                        out += DrawerRow(page.label, DrawerRow.Kind.ITEM, icon(page.iconName),
                            DrawerTarget.Page(section.id, page.id, page.label))
                        for (sp in page.subPages) {
                            out += DrawerRow("    ↳  ${sp.label}", DrawerRow.Kind.ITEM, icon(sp.iconName),
                                DrawerTarget.Page(section.id, "${page.id}/${sp.id}", sp.label))
                        }
                    }
                }
                section.isAggregator -> {
                    // Aggregator section drawer pane shows ONLY the stack
                    // content (the structured panels). Flat aggregatorTiles
                    // are NOT rendered here — they used to surface
                    // c3-health / vms / workflows / drive-connections as
                    // orphan rows with no parent header, which read as
                    // legacy noise. Those shortcuts live in the body's
                    // tile grid; the drawer mirrors the body's PANEL
                    // hierarchy instead.
                    val mode = ModePrefs(ctx).mode
                    val panels = Sections.aggregatorStackFor(section, mode)
                    if (panels.isEmpty() && section.tileGroups.isNotEmpty()) {
                        // Tile-group aggregators (Suite is the canonical case — declares tile_groups
                        // but no stack_apps / stack_admin). Each group's title becomes a disabled
                        // section header; each tile becomes a tappable row that routes through the
                        // same Tile dispatch the body grid uses.
                        for (group in section.tileGroups) {
                            if (group.title.isNotBlank()) out += DrawerRow(group.title.uppercase(), DrawerRow.Kind.HEADING)
                            // `destinations`, not `tiles`: this menu lists things
                            // that can be opened, and a folder icon standing in
                            // front of three bots is not one of them.
                            for (tile in group.destinations) {
                                out += DrawerRow("    ↳  ${tile.label}", DrawerRow.Kind.ITEM, icon(tile.iconName),
                                    DrawerTarget.Tile(tile.target))
                            }
                        }
                        return out
                    }
                    for (panel in panels) {
                        // linktree_slide panels load their actual content
                        // from data/linktree.json — the StackPanel itself
                        // only carries slideId. Resolve it here so we
                        // render the slide's columns + links inside the
                        // drawer the same way the body renders them.
                        val resolvedColumns: List<Sections.LinkColumn> = when {
                            panel.kind == "linktree_slide" ->
                                Sections.linktreeSlide(panel.slideId)?.columns ?: emptyList()
                            panel.columns.isNotEmpty() -> panel.columns
                            else                       -> emptyList()
                        }
                        when {
                            resolvedColumns.isNotEmpty() -> {
                                // Multi-column linktree slide: each column header as a section
                                // sub-header (greyed) + indented links beneath.
                                for (col in resolvedColumns) {
                                    if (col.header.isNotBlank()) out += DrawerRow(col.header.uppercase(), DrawerRow.Kind.HEADING)
                                    for (link in col.links) {
                                        out += DrawerRow("    ↳  ${link.label}", DrawerRow.Kind.ITEM, icon(link.icon),
                                            DrawerTarget.Tile(link.url))
                                    }
                                }
                            }
                            panel.links.isNotEmpty() -> {
                                // Flattened linktree column (single panel.links list) or inline link_grid.
                                if (panel.title.isNotBlank()) out += DrawerRow(panel.title.uppercase(), DrawerRow.Kind.HEADING)
                                for (link in panel.links) {
                                    out += DrawerRow("    ↳  ${link.label}", DrawerRow.Kind.ITEM, icon(link.icon),
                                        DrawerTarget.Tile(link.url))
                                }
                            }
                            panel.title.isNotBlank() -> {
                                // Non-link panel (c3_public, notification_center, rss, …).
                                // Surface title; tap re-opens body.
                                out += DrawerRow(panel.title, DrawerRow.Kind.ITEM, icon(panel.iconName),
                                    DrawerTarget.Section(section.id, section.label))
                            }
                        }
                    }
                }
                else -> {
                    // Fallback: drawer_default_children (no pages declared yet).
                    for ((idx, label) in section.defaultChildren.withIndex()) {
                        out += DrawerRow(label, DrawerRow.Kind.ITEM, 0, DrawerTarget.Page(section.id, "child-$idx", label))
                    }
                }
            }
            return out
        }

        fun newInstance(sectionId: String) = SectionMenuFragment().apply {
            arguments = bundleOf(ARG_SECTION_ID to sectionId)
        }
    }
}
