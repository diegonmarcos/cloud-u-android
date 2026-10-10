package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.ui.Haptics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.apps.PhoneApp
import com.diegonmarcos.superapp.apps.PhoneAppClassifier
import com.diegonmarcos.superapp.apps.PhoneAppsFragment
import com.diegonmarcos.superapp.apps.PhoneFolders
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.kitComposeView

/**
 * Aggregator section that renders themed sub-groups stacked vertically.
 * Used when [Sections.Section.tileGroups] is non-empty (Suite currently).
 *
 * Layout:
 *   ┌─ Group title ────────────────
 *   │  [tile] [tile] [tile] [tile] [tile]
 *   └──────────────────────────────
 *   ┌─ Next group title ───────────
 *   │  [tile] [tile]
 *   └──────────────────────────────
 *
 * Tile clicks bubble up through the same TileGridFragment.TileClickListener
 * the activity already implements, so deep-link grammar (section: / page: /
 * action: / http(s):) routes identically to the flat aggregator path.
 */
class GroupedTilesFragment : Fragment(), BackHandler {

    // ── Search (Cloud ▸ Apps only) ────────────────────────────────────────
    /** One searchable thing on the page: a declared tile, or an installed fleet app. */
    private sealed class Entry {
        data class Tile(val tile: Sections.AggTile) : Entry()
        data class App(val app: PhoneApp) : Entry()
    }

    /** The query lives with the fragment's view and nowhere else: never saved, so a relaunch
     *  (or a re-entry to the page) starts on the full grid. */
    private val query = mutableStateOf("")
    private var grid: View? = null
    private var results: LinearLayout? = null
    /** Grid + results, one layer: painted the opaque theme surface while results show. */
    private var searchLayer: LinearLayout? = null
    private var searchBar: View? = null
    private var index: List<AppsSearch.Group<Entry>>? = null
    private var lastResult: AppsSearch.Result<Entry>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val sectionId = arguments?.getString(ARG_SECTION_ID).orEmpty()
        val section = Sections.byId(sectionId)
        val groups  = section?.tileGroups.orEmpty()
        val search  = arguments?.getBoolean(ARG_SEARCH) == true && sectionId == "cloud"

        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFillViewport = true
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12); setPadding(pad, pad, pad, pad)
        }
        if (search) {
            // The grid and the search results share the one scroller; exactly one is visible.
            val out = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(12); setPadding(pad, 0, pad, pad)
                visibility = View.GONE
            }
            val layer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(col); addView(out)
            }
            scroll.addView(layer)
            grid = col; results = out; searchLayer = layer
        } else {
            scroll.addView(col)
        }

        // Suite is the only section merging in the generic Home-tab "All
        // Apps" grid + a "Recently Used" smart folder below its Quickmarks
        // — other GroupedTilesFragment users (Home, Labs, Configs, …) keep
        // their plain grouped-tiles rendering unchanged.
        if (sectionId == "cloud") col.addView(groupHeader(ctx, "Quickmarks"))

        for (group in groups) {
            col.addView(groupHeader(ctx, group.title))
            col.addView(tileRow(ctx, group.tiles))
        }

        // ── Actions, after the declared groups ────────────────────────────
        // The SAME list the all-apps star draws on its inner ring
        // (build.json::onehand.circular_menu.actions), read from that block
        // rather than copied into tile_groups. Copying is what let the Configs
        // grid and the Configs star disagree about how many actions exist; one
        // list cannot disagree with itself, and adding a sixth action means
        // editing one place and seeing it in both.
        //
        // Suite only, like the Quickmarks header above: these are the global
        // actions, so repeating them in Home, Labs and Configs would be the
        // same five tiles four times over.
        if (sectionId == "cloud") {
            val actions = starActions()
            if (actions.isNotEmpty()) {
                col.addView(groupHeader(ctx, "Actions"))
                col.addView(tileRow(ctx, actions))
            }
        }

        if (sectionId == "cloud") {
            // ── Below the fold: built AFTER the first frame ──────────────
            //
            // Everything above is the Quickmarks + Actions the user opens this
            // page for, and it is ~30 tiles. Smart Folders follows, each tile a
            // separate inflate(R.layout.item_tile) — inline, that work sat
            // between the tap and the first frame, so the tab stayed on the
            // previous page until all of it existed.
            //
            // Deferring it costs nothing visually: it is appended below the
            // fold, so it lands off-screen while the user is still reading
            // Quickmarks.
            //
            // The whole-of-Home "All Apps" grid used to sit here too, via
            // HomeGroupedFragment.buildInto. It was the Home tab rendered a
            // second time inside a Cloud tab — the same ~30 groups the user
            // had just navigated away from — so this page is Quickmarks,
            // Actions and Smart Folders now. Home still renders it; the
            // buildInto entry point stays for Home's own use.
            col.post {
                if (!isAdded) return@post
                // ── All Apps (#563) — the same section Phone ▸ Apps has (#249),
                //    mirrored: Phone draws every installed app EXCEPT the
                //    fleet's, this draws ONLY the fleet's, through the one
                //    renderer and the one taxonomy. Distinct from the removed
                //    Home grid below: that was ui.home_groups tiles repeated,
                //    this is installed apps grouped by purpose.
                col.addView(sectionDivider(ctx))
                col.addView(groupHeader(ctx, "All Apps"))
                com.diegonmarcos.superapp.apps.PhoneAppsFragment.renderAllApps(ctx, col,
                    only = Sections.constellationPackages(ctx.packageName))
                // ── Smart Folders → Recently Used. Cloud tiles have no
                //    existing pin/favorite/most-used signal the way Phone
                //    apps do — this is the one real per-tile signal
                //    available (RecentCloudTiles, recorded centrally from
                //    MainActivity.onTileClicked), so it's the sole Smart
                //    Folders subsection for now. Always shown (with an
                //    empty-state line) so the section doesn't disappear
                //    entirely before the user has opened any Cloud tiles.
                col.addView(sectionDivider(ctx))
                val allTiles = Sections.all().flatMap { it.tileGroups }.flatMap { it.destinations }
                    .associateBy { it.target }
                val recent = RecentCloudTiles.recent(ctx).mapNotNull { allTiles[it] }
                col.addView(groupHeader(ctx, "Smart Folders"))
                col.addView(groupHeader(ctx, "Recently Used"))
                if (recent.isNotEmpty()) {
                    col.addView(tileRow(ctx, recent))
                } else {
                    col.addView(TextView(ctx).apply {
                        text = "Open a few Cloud tiles and they'll show up here"
                        setTextColor(0x99FFFFFF.toInt())
                        setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                        setPadding(dp(4), 0, dp(4), dp(8))
                    })
                }
            }
        }
        return if (search) withSearchBar(ctx, scroll) else scroll
    }

    private fun starActions(): List<Sections.AggTile> =
        com.diegonmarcos.superapp.onehand.CircularMenu.config().actions
            .map { Sections.AggTile(it.target, it.label, it.iconName, it.target) }

    /**
     * The page with the search bar pinned under it — the bottom of the page, so just above the
     * bottom island the content host already clears, where the thumb is. The bar is the kit's
     * [KitSearchBar]; typing filters every group live ([AppsSearch]), Go launches the top match,
     * and with no match the one row left offers the query to Cloud Search.
     */
    private fun withSearchBar(ctx: android.content.Context, scroll: ScrollView): View {
        val bar = ctx.kitComposeView(LauncherPalette.kit(ctx)) {
            KitSearchBar(
                query = query.value,
                onQueryChange = ::setQuery,
                onSubmit = ::submit,
                placeholder = "Search apps",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        searchBar = bar
        // The keyboard hides when the user scrolls the page — a DRAG, not any scroll change:
        // the results swapping in under a typed letter clamp the scroll position too, and that
        // must not close the keyboard mid-word. The listener sees an event only once the
        // scroller itself handles the gesture (a drag that began on a tile reaches it as MOVEs
        // after it intercepts), so any event short of the release means the finger is dragging.
        var dragging = false
        scroll.setOnTouchListener { _, ev ->
            dragging = when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> false
                else -> true
            }
            false
        }
        scroll.setOnScrollChangeListener { _, _, y, _, oldY -> if (dragging && y != oldY) hideKeyboard() }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun setQuery(q: String) {
        query.value = q
        val out = results ?: return
        val full = grid ?: return
        if (!AppsSearch.isActive(q)) {
            lastResult = null
            out.removeAllViews()
            out.visibility = View.GONE
            full.visibility = View.VISIBLE
            searchLayer?.background = null
            return
        }
        val ctx = out.context
        val r = AppsSearch.filter(searchIndex(ctx), q)
        lastResult = r
        full.visibility = View.GONE
        out.visibility = View.VISIBLE
        // Results are read over nothing see-through: the theme surface at 100% alpha.
        searchLayer?.setBackgroundColor(com.diegonmarcos.superapp.ui.LauncherPalette.opaqueSurface(ctx))
        out.removeAllViews()
        // Hidden groups are simply not drawn: an empty result is the Cloud Search row alone.
        if (r.isEmpty) {
            out.addView(cloudSearchRow(ctx, q.trim()))
            return
        }
        for (g in r.groups) {
            out.addView(groupHeader(ctx, g.title))
            out.addView(resultRow(ctx, g.items.map { it.value }))
        }
    }

    /** Go: the top match, else Cloud Search. A folder's Go opens the folder, like its tap. */
    private fun submit() {
        val q = query.value
        if (!AppsSearch.isActive(q)) return
        val top = lastResult?.top?.value
        when (top) {
            null -> searchInCloudSearch(q.trim())
            is Entry.App -> launch("app:${top.app.packageName}")
            is Entry.Tile -> if (top.tile.children.isNotEmpty()) openFolder(top.tile) else launch(top.tile.target)
        }
    }

    /** Leaving through search resets it: coming back shows the full grid, not a stale filter. */
    private fun launch(target: String) {
        setQuery("")
        hideKeyboard()
        (activity as? TileGridFragment.TileClickListener)?.onTileClicked(target)
    }

    /** #937 the query goes to Cloud Search ([CloudSearchHandoff]); a Cloud Search that cannot take
     *  it (not installed, or too old) is opened through its tile target, as before. */
    private fun searchInCloudSearch(q: String) {
        val ctx = context ?: return
        val app = Sections.externalApp(CLOUD_SEARCH_TARGET.removePrefix("extapp:"))
        val intent = CloudSearchHandoff.intent(ctx.packageManager, CloudSearchHandoff.packages(app), q)
        if (intent == null || runCatching { startActivity(intent) }.isFailure) {
            launch(CLOUD_SEARCH_TARGET)
            return
        }
        setQuery("")
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val bar = searchBar ?: return
        val imm = bar.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(bar.windowToken, 0)
        bar.clearFocus()
    }

    /** Back with a query clears it (the full grid returns) before Back leaves the page. */
    override fun tryHandleBack(): Boolean {
        if (!AppsSearch.isActive(query.value)) return false
        setQuery("")
        hideKeyboard()
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        grid = null; results = null; searchLayer = null; searchBar = null; index = null; lastResult = null
        query.value = ""
    }

    /**
     * Everything the page shows, as search groups: the declared rows (a folder AND the tiles in
     * it, separators dropped — a "|" is punctuation, not a destination), the star's Actions, then
     * the installed fleet apps by the same folders All Apps draws them in. Built on the first
     * letter typed, not with the page.
     */
    private fun searchIndex(ctx: android.content.Context): List<AppsSearch.Group<Entry>> {
        index?.let { return it }
        val out = mutableListOf<AppsSearch.Group<Entry>>()
        for (g in Sections.byId("cloud")?.tileGroups.orEmpty()) {
            val tiles = g.tiles.filterNot { it.separator }
                .flatMap { t -> listOf(t) + t.children.filterNot { it.separator } }
            out += AppsSearch.Group(g.title, tiles.map { AppsSearch.Item(it.label, Entry.Tile(it)) })
        }
        out += AppsSearch.Group("Actions", starActions().map { AppsSearch.Item(it.label, Entry.Tile(it)) })
        runCatching {
            val ours = Sections.constellationPackages(ctx.packageName)
            val apps = PhoneAppsFragment.snapshot(ctx)
                .filter { it.packageName in ours && it.activityComponent != null }
            val folders = PhoneFolders.loadFromBuildConfig()
            val byFolder = PhoneAppClassifier.groupByFolder(apps, folders)
            for (f in folders) {
                val inIt = byFolder[f.id].orEmpty()
                if (inIt.isNotEmpty()) out += AppsSearch.Group(f.label, inIt.map { AppsSearch.Item(it.label, Entry.App(it)) })
            }
        }
        return out.also { index = it }
    }

    private fun resultRow(ctx: android.content.Context, entries: List<Entry>): View {
        val scroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (e in entries) row.addView(when (e) {
            is Entry.Tile -> tileCell(ctx, e.tile, fromSearch = true)
            is Entry.App -> appCell(ctx, e.app)
        })
        scroll.addView(row)
        return scroll
    }

    /** An installed fleet app in the results: its own icon, launched as `app:<package>`. */
    private fun appCell(ctx: android.content.Context, app: PhoneApp): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = dp(6); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true; isFocusable = true
            setOnClickListener { launch("app:${app.packageName}") }
            addView(android.widget.ImageView(ctx).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            })
            addView(TextView(ctx).apply {
                text = app.label
                setTextColor(0xCCFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                gravity = android.view.Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }

    /** The one row an empty result leaves: hand the query to Cloud Search, which opens on its
     *  Search page and runs it (#937, [searchInCloudSearch]); without a Cloud Search that takes
     *  it, the row opens the app (extapp:cloud-search, the AGI tile's own target). */
    private fun cloudSearchRow(ctx: android.content.Context, q: String): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isClickable = true; isFocusable = true
            setOnClickListener { searchInCloudSearch(q) }
            val iconRes = Sections.iconResFor(ctx, "ic_cloud_search")
            if (iconRes != 0) addView(android.widget.ImageView(ctx).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
            })
            addView(TextView(ctx).apply {
                text = "Search \u201C$q\u201D in Cloud Search"
                setTextColor(0xCCFFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

    private fun openFolder(tile: Sections.AggTile) {
        val ctx = context ?: return
        TileFolderDialog.open(ctx, tile) { child ->
            (activity as? TileGridFragment.TileClickListener)?.onTileClicked(child.target)
        }
    }

    private fun groupHeader(ctx: android.content.Context, title: String): View =
        TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setPadding(dp(4), dp(12), 0, dp(4))
        }

    /** Thin separator line between the Quickmarks / All Apps / Smart
     *  Folders sections on the merged page. */
    private fun sectionDivider(ctx: android.content.Context): View =
        View(ctx).apply {
            setBackgroundColor(0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
            ).apply { topMargin = dp(16); bottomMargin = dp(4) }
        }

    /** One horizontally-scrollable strip per group — tiles stay on a
     *  single line regardless of count; the user scrolls right for
     *  overflow. Replaces the previous 5-column wrap so groups like
     *  Suite/Data Apps (6 tiles now) don't break onto a second row. */
    private fun tileRow(ctx: android.content.Context, tiles: List<Sections.AggTile>): View {
        val scroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (tile in tiles) row.addView(tileCell(ctx, tile))
        scroll.addView(row)
        return scroll
    }

    /**
     * The "|" between two runs of tiles in one group. A glyph, not a control:
     * a cell that looks like the tiles around it but does nothing when pressed
     * is the defect this app already fixed once in the tab strips, so this one
     * is explicitly not clickable, not focusable, and hidden from
     * accessibility — TalkBack should walk from the tile before it to the tile
     * after it and never stop on a vertical bar.
     *
     * Set in the tiles' own caption type at the icon's height so it reads as
     * punctuation between the rows rather than as a piece of chrome sitting on
     * top of them, and it stays aligned when the labels wrap to two lines.
     */
    private fun separatorCell(ctx: android.content.Context, tile: Sections.AggTile): View =
        TextView(ctx).apply {
            text = tile.label
            setTextColor(0x66FFFFFF)
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            gravity = android.view.Gravity.CENTER
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }

    /** [fromSearch]: the cell is a search result, so a launch from it resets the search. */
    private fun tileCell(ctx: android.content.Context, tile: Sections.AggTile, fromSearch: Boolean = false): View {
        if (tile.separator) return separatorCell(ctx, tile)
        // Fixed-width cells so the horizontal scroll row shows ~6
        // tiles at a time on a typical phone width (matches Home Apps'
        // tile_columns = 6) and the rest stay reachable by swiping.
        val cellWidth = dp(60)
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = dp(6); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(cellWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true; isFocusable = true
            // No cell background — matches TileGridFragment's bare tile
            // look. The previous list_selector_background was the default
            // amber/yellow highlight from the platform list theme, which
            // showed up only on Suite (the sole GroupedTilesFragment user
            // today).
            setOnClickListener {
                // A folder HOLDS destinations instead of being one, so it opens
                // the popup and the entries inside dispatch. Falling through to
                // `target` as well would make the first tap ambiguous, which is
                // why a folder in build.json carries no target at all.
                //
                // A folder opens a POPUP, which is not a tile click and reaches
                // no dispatcher, so it buzzes for itself. A destination does
                // not: onTileClicked IS the per-tap bookkeeping — log, haptic,
                // drawer close, Recently-Used and App-Tabs — and #195 put it
                // all in one place precisely so one tap could not pay for it
                // twice. This row was the place that still did. Every Data Apps
                // tap, Drive's included, fired Haptics.tap here and again at
                // ShellActivity.onTileClicked: two real Vibrator pulses (they
                // are direct `fire()` calls, not view feedback that could
                // coalesce) for one finger. test-tile-click-dispatched-once.sh
                // read only ShellActivity.kt, so it stayed green over it.
                if (tile.children.isNotEmpty()) {
                    Haptics.tap(it)
                    TileFolderDialog.open(ctx, tile) { child ->
                        (activity as? TileGridFragment.TileClickListener)
                            ?.onTileClicked(child.target)
                    }
                } else if (fromSearch) {
                    launch(tile.target)
                } else {
                    (activity as? TileGridFragment.TileClickListener)?.onTileClicked(tile.target)
                }
            }
        }
        // #571: an extapp tile whose app is not on the phone draws the #249
        // placeholder Phone ▸ Apps uses — same glyph, same strings — instead
        // of an icon that promises an app. The tap is unchanged: it still
        // reaches launchExternalApp, which installs from the Store's asset.
        val missing = Sections.extappMissing(ctx, tile.target)
        val palette = com.diegonmarcos.superapp.ui.LauncherPalette.of(ctx)
        if (missing) {
            cell.contentDescription = ctx.getString(
                com.diegonmarcos.superapp.R.string.phone_app_not_installed_tile_hint, tile.label)
        }
        val iconRes = if (missing) com.diegonmarcos.superapp.R.drawable.ic_app_not_installed
                      else Sections.iconResFor(ctx, tile.iconName)
        if (iconRes != 0) {
            cell.addView(android.widget.ImageView(ctx).apply {
                setImageResource(iconRes)
                // A tile that declares tint:false keeps its glyph's own colours;
                // the not-installed placeholder is always tinted.
                if (missing || tile.tint) imageTintList = android.content.res.ColorStateList.valueOf(
                    if (missing) palette.textSecondary else 0xFFFFFFFF.toInt())
                val sz = dp(32)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
            })
        }
        cell.addView(TextView(ctx).apply {
            text = tile.label
            setTextColor(if (missing) palette.textSecondary else 0xCCFFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            gravity = android.view.Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        if (missing) {
            cell.addView(TextView(ctx).apply {
                setText(com.diegonmarcos.superapp.R.string.phone_app_not_installed)
                setTextColor(palette.accent)
                textSize = 9f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
            })
        }
        return cell
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_SEARCH = "search"

        /** Where an empty search sends its query: the Cloud Search app (ac_cloud-search). */
        const val CLOUD_SEARCH_TARGET = "extapp:cloud-search"

        /** [search] pins the search bar under the page — Cloud ▸ Apps asks for it; the Home
         *  sheet's Cloud tab does not (that sheet has its own search island on top). */
        fun newInstance(sectionId: String, search: Boolean = false): GroupedTilesFragment = GroupedTilesFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SECTION_ID, sectionId)
                putBoolean(ARG_SEARCH, search)
            }
        }
    }
}
