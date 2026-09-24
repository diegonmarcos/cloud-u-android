package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.launcher.AppLongPressMenu
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.settings.LauncherProfile
import com.diegonmarcos.superapp.settings.LauncherProfiles
import com.diegonmarcos.superapp.settings.LauncherProfilePrefs

import android.app.Dialog
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone tab of the Home Apps swipe-up sheet — Android-launcher-style
 * grouping of every installed (launchable) app into the folder
 * taxonomy declared in `build.json::ui.phone_folders`. Smart classifier
 * lives in [PhoneAppClassifier]; folder rendering follows the iOS-
 * glass-on-Samsung-grid spec the user pinned in
 * Screenshot_20260608_001142_One UI Home.jpg.
 *
 * Visual contract:
 *   • Grid of folder cards, 6 columns (from `phone_grid_columns`).
 *   • Each folder card: 2×2 mini-icon preview of its top apps inside a
 *     rounded glass-bg square, label beneath.
 *   • Tap a folder → modal dialog showing every app inside, 5 columns
 *     of full-size icons. Tap an app → launches via [LauncherApps].
 */
class PhoneAppsFragment : Fragment() {

    private lateinit var launcherApps: LauncherApps

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val rootCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 8); setPadding(pad, pad, pad, dp(ctx, 96))
        }
        scroll.addView(rootCol)

        // Content first, then a small centered refresh affordance at the
        // VERY BOTTOM — invalidates the app cache (picks up new installs
        // / uninstalls) and rebuilds the folder + smart-folder content
        // in place.
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        rootCol.addView(content)
        buildContent(ctx, content)
        rootCol.addView(refreshBar(ctx) {
            invalidateCache()
            content.removeAllViews()
            buildContent(ctx, content)
        })
        return scroll
    }

    /** true = A–Z folders (alphabetic), false = category + smart folders.
     *  In-memory only — resets to Categories each time the sheet opens. */
    private var alphaMode = false

    /** Dispatcher: segmented "Categories | Alphabetic" toggle under the
     *  host search bar, then the chosen layout. Re-invoked in place when
     *  the toggle or refresh fires (caller clears [rootCol] first). */
    private fun buildContent(ctx: Context, rootCol: LinearLayout) {
        rootCol.addView(modeToggle(ctx, rootCol))
        if (alphaMode) buildAlphabetic(ctx, rootCol) else buildCategories(ctx, rootCol)
    }

    /** Right-aligned minimalist icon toggle: folder (categories) vs
     *  sort-alpha. Active icon is bright, inactive dim. Tap flips mode
     *  and rebuilds [content] in place (same clear+rebuild shape as the
     *  refresh affordance). */
    private fun modeToggle(ctx: Context, content: LinearLayout): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(ctx, 2); bottomMargin = dp(ctx, 2) }
        }
        fun rebuild() { content.removeAllViews(); buildContent(ctx, content) }
        fun iconBtn(res: Int, active: Boolean, desc: String, onPick: () -> Unit) = ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (active) 0xFFE9D8FD.toInt() else 0x55FFFFFF)
            val sz = dp(ctx, 20); val p = dp(ctx, 6)
            layoutParams = LinearLayout.LayoutParams(sz + 2 * p, sz + 2 * p)
            setPadding(p, p, p, p)
            contentDescription = desc
            isClickable = true; isFocusable = true
            val outVal = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            if (outVal.resourceId != 0) setBackgroundResource(outVal.resourceId)
            setOnClickListener {
                Haptics.tap(it)
                if (active) return@setOnClickListener
                onPick(); rebuild()
            }
        }
        bar.addView(iconBtn(R.drawable.ic_p_folder, !alphaMode, "Categories") { alphaMode = false })
        bar.addView(iconBtn(R.drawable.ic_sort_alpha, alphaMode, "Alphabetic") { alphaMode = true })
        return bar
    }

    /** A–Z (plus a trailing "#" bucket for non-letter labels) folders of
     *  every launchable app, 6 per row. All 26 letter folders are always
     *  shown — empty ones render as empty glass squares, per spec. */
    private fun buildAlphabetic(ctx: Context, rootCol: LinearLayout) {
        val apps    = sCachedApps ?: collectLaunchableApps(ctx).also { sCachedApps = it }
        val columns = BuildConfig.UI_PHONE_GRID_COLUMNS
        val byLetter = LinkedHashMap<String, MutableList<PhoneApp>>()
        for (c in 'A'..'Z') byLetter[c.toString()] = mutableListOf()
        val hash = mutableListOf<PhoneApp>()
        for (app in apps) {
            val ch = app.label.trim().firstOrNull()?.uppercaseChar()
            if (ch != null && ch in 'A'..'Z') byLetter.getValue(ch.toString()).add(app) else hash.add(app)
        }
        val folders = mutableListOf<PhoneFolders.Folder>()
        val grouped = HashMap<String, List<PhoneApp>>()
        for ((letter, list) in byLetter) {
            folders.add(PhoneFolders.Folder("alpha:$letter", letter, letter, emptyList()))
            grouped["alpha:$letter"] = list.sortedBy { it.label.lowercase() }
        }
        if (hash.isNotEmpty()) {
            folders.add(PhoneFolders.Folder("alpha:#", "zz", "#", emptyList()))
            grouped["alpha:#"] = hash.sortedBy { it.label.lowercase() }
        }
        renderFolderGrid(rootCol, ctx, folders, grouped, columns)
    }

    /** Build (or rebuild) the folder grid + smart folders into [rootCol].
     *  Delegates to the companion builders so this exact rendering can
     *  also be embedded inline by SuitePhoneAppsFragment's merged
     *  Suite→Phone page. */
    private fun buildCategories(ctx: Context, rootCol: LinearLayout) {
        renderAllApps(ctx, rootCol)
        renderSmartFolders(ctx, rootCol)
    }

    /** Instance-side wrapper — delegates to the companion helper so
     *  warm-up + first-open can share the exact same enumeration code
     *  path. Kept as a fragment method so existing callers (none in
     *  Push 1, but kept symmetric) don't have to thread `ctx` twice. */
    private fun collectLaunchableApps(ctx: Context): List<PhoneApp> =
        collectLaunchableAppsStatic(ctx)

    companion object {
        fun newInstance() = PhoneAppsFragment()

        /** Centered "refresh app list" icon, pinned at the very bottom of a
         *  page. Lives in the companion because BOTH app surfaces need it:
         *  this fragment's Phone tab and SuitePhoneAppsFragment's merged
         *  Suite→Phone page, which only ever embedded the grid renderers and
         *  so silently lost the refresh affordance. */
        fun refreshBar(ctx: Context, onRefresh: () -> Unit): View {
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(ctx, 8) }
            }
            bar.addView(android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_refresh)
                imageTintList = android.content.res.ColorStateList.valueOf(0xCCFFFFFF.toInt())
                val sz = dp(ctx, 22); val p = dp(ctx, 8)
                layoutParams = LinearLayout.LayoutParams(sz + 2 * p, sz + 2 * p)
                setPadding(p, p, p, p)
                isClickable = true; isFocusable = true
                contentDescription = "Refresh app list"
                val outVal = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outVal, true)
                if (outVal.resourceId != 0) setBackgroundResource(outVal.resourceId)
                setOnClickListener { Haptics.tap(it); onRefresh() }
            })
            return bar
        }

        /** "All Apps by purpose" — category folder grid, bucketed by
         *  label prefix (System _, Services -, Tools A ., Tools B >),
         *  each under its own subhead, plus a trailing "Other" bucket.
         *  Shared between the standalone Phone tab ([buildCategories])
         *  and SuitePhoneAppsFragment's merged Suite→Phone page, which
         *  embeds this exact rendering inline instead of navigating to
         *  a separate "more" screen.
         *
         *  [only] is the mirror of [exclude] (#563): Phone ▸ Apps draws every
         *  app EXCEPT ours, Cloud ▸ Apps draws ONLY ours, through this same
         *  grid and the same taxonomy, so the two All Apps cannot disagree
         *  about where an app belongs. */
        fun renderAllApps(
            ctx: Context,
            rootCol: LinearLayout,
            exclude: Set<String> = emptySet(),
            only: Set<String>? = null,
        ) {
            // Process-level cache populated by [warmUp] at app launch. By
            // the time the user navigates here it's usually already
            // primed → render is instant. If the user got here BEFORE the
            // warm-up thread finished (rare cold path), we fall back to a
            // synchronous load on the main thread — same blocking shape
            // as v1 of the Phone tab. The render path is otherwise
            // identical for hot + cold.
            val folders  = sCachedFolders ?: PhoneFolders.loadFromBuildConfig().also { sCachedFolders = it }
            val all      = sCachedApps    ?: collectLaunchableAppsStatic(ctx).also { sCachedApps = it }
            val apps     = all.filter { it.packageName !in exclude && (only == null || it.packageName in only) }
            // The grouped cache is only reusable for the UNFILTERED set — and
            // must not be WRITTEN from a filtered one either, or the next
            // caller that wants every app silently gets somebody else's
            // subset. A caller that filters pays one regroup.
            val grouped  = if (exclude.isEmpty() && only == null) {
                sCachedGrouped ?: PhoneAppClassifier.groupByFolder(apps, folders).also { sCachedGrouped = it }
            } else {
                PhoneAppClassifier.groupByFolder(apps, folders)
            }
            val columns  = BuildConfig.UI_PHONE_GRID_COLUMNS

            // Skip empty folders entirely — One UI hides them too, and an
            // empty 2×2 placeholder reads as broken to the user. EXCEPT
            // folders marked `pin: true` in build.json::ui.phone_folders
            // (today: _Misc) — those stay visible as deliberate placeholders
            // so the user can drop apps into them via build.json keyword
            // edits.
            val visible = folders.filter { it.pinned || (grouped[it.id]?.size ?: 0) > 0 }

            // ── Bucket folders into top-level sections by label prefix
            //    (System _, Services -, Tools A ., Tools B >). Each section
            //    gets a subhead + its own grid below. Folders whose label
            //    starts with no known prefix fall through to "Other" so they
            //    don't disappear silently.
            val sections = PhoneSections.loadFromBuildConfig()
            val byPrefix = LinkedHashMap<String, MutableList<PhoneFolders.Folder>>()
            for (sec in sections) byPrefix[sec.title] = mutableListOf()
            val otherBucket = mutableListOf<PhoneFolders.Folder>()
            for (folder in visible) {
                val sec = sections.firstOrNull { folder.label.startsWith(it.prefix) }
                if (sec != null) byPrefix.getValue(sec.title).add(folder) else otherBucket.add(folder)
            }
            for (sec in sections) {
                val list = byPrefix[sec.title].orEmpty()
                if (list.isEmpty()) continue
                rootCol.addView(subhead(ctx, sec.title))
                renderFolderGrid(rootCol, ctx, list, grouped, columns)
            }
            if (otherBucket.isNotEmpty()) {
                rootCol.addView(subhead(ctx, "Other"))
                renderFolderGrid(rootCol, ctx, otherBucket, grouped, columns)
            }
        }

        /** Smart Folders — dynamic filters (Samsung, Google, Alternative
         *  Stores, …). Same UX as a real folder card: tap opens the same
         *  dialog with the filtered apps. Rules operate over the SAME
         *  master `apps` list so contents track the source enumeration in
         *  lockstep. Shared the same way as [renderAllApps].
         *
         *  SYNCHRONOUS, which is only acceptable where the caller has already
         *  drawn its page and nothing is waiting on the frame. What that
         *  actually costs is counted in [renderSmartFoldersAsync] — read it
         *  before adding a third caller of this one. */
        fun renderSmartFolders(
            ctx: Context,
            rootCol: LinearLayout,
            exclude: Set<String> = emptySet(),
        ) {
            val rendered = smartFoldersCached(ctx, exclude)
            if (rendered.isEmpty()) return
            rootCol.addView(subhead(ctx, "Smart Folders"))
            renderSmartFolderBody(ctx, rootCol, rendered)
        }

        /**
         * The same Smart Folders, computed OFF the main thread and then
         * rendered into [body] back on it.
         *
         * WHAT IS ACTUALLY SLOW HERE, because it is not the view inflation the
         * word "render" suggests. build.json declares fourteen smart folders
         * and four of them — google_play, fdroid, uptodown, direct — carry an
         * install_source rule. [PhoneSmartFolders.Rule.matches] answers those
         * by calling PackageManager.getInstallSourceInfo ONCE PER APP, and the
         * install_source_not one calls getApplicationInfo once per app on top
         * of that; every one is a synchronous binder round trip to the package
         * manager. On a phone with two hundred launchable apps that is on the
         * order of a thousand IPCs before a single View is created. Five more
         * folders rank apps through UsageStatsManager, NetworkStatsManager and
         * the battery estimator, each walking its own multi-day history. None
         * of that work touches a View and all of it used to happen on the main
         * thread — which is the delay the owner reported as this page taking
         * forever to open.
         *
         * [onDone] reports which of three different things happened, because a
         * caller that cannot tell them apart has no way to stop showing a
         * spinner:
         *   true  — folders were rendered into [body]
         *   false — the rules matched nothing on this device
         *   null  — the computation itself threw
         *
         * [owner] MUST be the caller's viewLifecycleOwner, not the fragment.
         * It is the whole cancellation story: a fragment outlives its view, so
         * scoping to the fragment would leave the probe painting into a view
         * tree that onDestroyView already took down.
         */
        fun renderSmartFoldersAsync(
            owner: LifecycleOwner,
            ctx: Context,
            body: LinearLayout,
            exclude: Set<String> = emptySet(),
            onDone: (Boolean?) -> Unit,
        ) {
            // Already computed once in this process: render on the spot and
            // answer synchronously. THIS is what stops a rotation or a
            // re-entry paying the thousand IPCs again — the second visit never
            // starts a probe at all.
            sCachedSmart[exclude]?.let { cached ->
                renderSmartFolderBody(ctx, body, cached)
                onDone(cached.isNotEmpty())
                return
            }
            // applicationContext for the background half. The probe can outlive
            // the fragment that started it (see the cancellation note below),
            // and holding that fragment's Activity for the duration is a leak
            // worth not having.
            val appContext = ctx.applicationContext
            // Captured BEFORE the probe starts, checked before it publishes —
            // see [sCacheGeneration].
            val generation = sCacheGeneration
            // STRUCTURAL CANCELLATION, THE SAME SHAPE AS Configs ▸ About.
            //
            // This used to be a bare `Thread { … }` whose result came back
            // through body.post, with the comment "this app does not depend on
            // kotlinx-coroutines". That was not true: DevControlFragment — the
            // About page whose per-section lazy loader is #286/#333 — already
            // runs every one of its probes on viewLifecycleOwner.lifecycleScope
            // with Dispatchers.IO, in this same module. So the Thread was a
            // SECOND private lazy-load mechanism sitting beside the first, which
            // is the #228 defect, and it bought a weaker guarantee: a raw Thread
            // is never cancelled, so leaving the page left ~1000 package-manager
            // IPCs still running with nobody to receive them.
            //
            // The scope here is the CALLER'S VIEW lifecycle, so onDestroyView
            // cancels this coroutine and nothing downstream of the probe runs.
            // That is the #194 crash class — a fragment painting after detach —
            // removed rather than merely caught.
            owner.lifecycleScope.launch {
                val computed = withContext(Dispatchers.IO) {
                    val result = runCatching { computeSmartFolders(appContext, exclude) }.getOrNull()
                    // Published from INSIDE the IO block on purpose. Cancellation
                    // stops the resumption, not the block already running, so
                    // writing the cache here keeps a page the user walked away
                    // from mid-fetch warming the cache for the next visit —
                    // which the old Thread did and a naive port would lose.
                    if (result != null && generation == sCacheGeneration) {
                        sCachedSmart = sCachedSmart + (exclude to result)
                    }
                    result
                }
                // Cancellation has normally already returned for us. It has not
                // when the view was torn down between the probe finishing and
                // this line resuming, and writing into a detached hierarchy
                // then is the crash this check exists for.
                if (!body.isAttachedToWindow) return@launch
                // body.context, not the captured one: by here the only Context
                // proven still alive is the view's own.
                if (computed != null) renderSmartFolderBody(body.context, body, computed)
                onDone(computed?.isNotEmpty())
            }
        }

        /** Selection only — creates no Views, so it is safe on a background
         *  thread. Every expensive call named in [renderSmartFoldersAsync]'s
         *  documentation happens inside here. */
        private fun computeSmartFolders(
            ctx: Context,
            exclude: Set<String>,
        ): List<SmartRendered> {
            val all  = sCachedApps ?: collectLaunchableAppsStatic(ctx).also { sCachedApps = it }
            // The master exclusion is applied PER FOLDER, not to the shared
            // master list up front. Computing it once here is not the same
            // as pre-filtering `all`: the filtered list below is the DEFAULT
            // every folder filters over, but a folder that opts out of the
            // exclusion (include_constellation) selects from the FULL list
            // instead, because its entire content IS the constellation's own
            // packages — Cloud Apps and Cloud Libs. `exclude` still keys the
            // cache (see [smartFoldersCached]), so nothing about the cache
            // contract changes; it is only the per-folder decision that moved
            // inside this computation.
            val excluded = if (exclude.isEmpty()) all else all.filter { it.packageName !in exclude }
            // Installed constellation LIBRARY packages. A lib APK ships no
            // launcher activity, so it is absent from `all`/`excluded` (both
            // derive from the launchable enumeration) — a folder whose rule
            // selects libs must therefore source from this set, not the
            // launchable list, or it always comes up empty (#474).
            val libSlots by lazy { PhoneSmartFolders.installedLibSlots(ctx) }
            return PhoneSmartFolders.loadFromBuildConfig().mapNotNull { sf ->
                val source = when {
                    // A fleet_kind=lib folder IS the installed libs — its
                    // membership comes from the fleet manifest ∩ installed
                    // packages, never from the launchable enumeration.
                    sf.selectsInstalledLibs -> libSlots
                    // The opt-in folder (Cloud Apps) selects over the FULL
                    // enumeration; every other folder keeps the excluded one.
                    sf.includeConstellation -> all
                    else -> excluded
                }
                val matches = sf.select(ctx, source)
                if (matches.isEmpty()) null else SmartRendered(sf, matches)
            }
        }

        /** [computeSmartFolders] through the process-level cache.
         *
         *  KEYED ON `exclude`, because the two callers do not pass the same
         *  set — the standalone Phone tab excludes nothing, the merged
         *  Suite→Phone page excludes the constellation's own packages. Storing
         *  one under the other's key is the subset leak sCachedGrouped already
         *  documents above; a key costs one line and cannot make that mistake. */
        private fun smartFoldersCached(
            ctx: Context,
            exclude: Set<String>,
        ): List<SmartRendered> = sCachedSmart[exclude] ?: computeSmartFolders(ctx, exclude)
            .also { sCachedSmart = sCachedSmart + (exclude to it) }

        /** The view half: one subhead per `group` plus its grid. Deliberately
         *  does NOT draw the "Smart Folders" heading itself — the lazy caller
         *  owns that, because for it the heading is a CONTROL that collapses
         *  the section and so has to exist before there is anything under it. */
        private fun renderSmartFolderBody(
            ctx: Context,
            rootCol: LinearLayout,
            rendered: List<SmartRendered>,
        ) {
            val columns = BuildConfig.UI_PHONE_GRID_COLUMNS
            // Smaller "subtile" cells for Smart Folders — denser than the
            // A-Z/category grid above, and grouped under sub-labels
            // (Usage/Stores/Dev/Rank/…) per build.json's `group` field.
            val subtileCell = dp(ctx, 44)
            val subtileColumns = columns + 1
            rendered.groupBy { it.spec.group ?: "Other" }.forEach { (group, inGroup) ->
                rootCol.addView(subhead(ctx, group))
                // Synthesize a Folder per Smart Folder so the existing
                // renderFolderGrid + makeFolderCard helpers light up
                // unchanged. id prefixed with "smart:" so it can't collide
                // with a real folder id from build.json.
                val syntheticFolders = inGroup.map { vs ->
                    PhoneFolders.Folder(
                        id            = "smart:${vs.spec.id}",
                        order         = "zz",
                        label         = vs.spec.title,
                        matchKeywords = emptyList(),
                    )
                }
                val smartGrouped = inGroup.associate { vs -> "smart:${vs.spec.id}" to vs.apps }
                renderFolderGrid(rootCol, ctx, syntheticFolders, smartGrouped, subtileColumns, subtileCell)
            }
        }

        private data class SmartRendered(
            val spec: PhoneSmartFolders.SmartFolder,
            val apps: List<PhoneApp>,
        )

        private fun subhead(ctx: Context, title: String) = TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setPadding(dp(ctx, 4), dp(ctx, 12), 0, dp(ctx, 4))
        }

        /** Lay out [folders] as a fixed-column grid of folder cards. Uses
         *  nested LinearLayouts instead of GridLayout to avoid GridLayout's
         *  cell-size measure cycles when child widths vary. */
        private fun renderFolderGrid(
            parent: LinearLayout,
            ctx: Context,
            folders: List<PhoneFolders.Folder>,
            grouped: Map<String, List<PhoneApp>>,
            columns: Int,
            cellSize: Int = dp(ctx, 60),
        ) {
            folders.chunked(columns).forEach { rowFolders ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                for (folder in rowFolders) {
                    val apps = grouped[folder.id].orEmpty()
                    row.addView(makeFolderCard(ctx, folder, apps, cellSize))
                }
                // Pad the row with empty weighted spacers if it has fewer
                // than `columns` folders, so the last row stays left-aligned
                // (Samsung does the same — folders flow left to right, no
                // re-centring of the trailing row).
                val short = columns - rowFolders.size
                repeat(short) {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
                parent.addView(row)
            }
        }

        /** A single folder tile: glass-bg rounded square with a 2×2 mini-
         *  icon preview, label underneath. */
        private fun makeFolderCard(
            ctx: Context,
            folder: PhoneFolders.Folder,
            apps: List<PhoneApp>,
            cellSize: Int = dp(ctx, 60),
        ): View {
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val side = dp(ctx, 2); val top = dp(ctx, 6)
                setPadding(side, top, side, top)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    Haptics.tap(it)
                    showFolderDialog(ctx, folder, apps)
                }
            }
            // Glass square holding the 2x2 mini-preview.
            val square = FrameLayout(ctx).apply {
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass)
                layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
            }
            val mini = GridLayout(ctx).apply {
                rowCount = 2; columnCount = 2
                val p = dp(ctx, 6); setPadding(p, p, p, p)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            val miniSize = (cellSize - dp(ctx, 18)) / 2  // 2 minis, 6dp pad ×3
            for (slot in 0 until 4) {
                val iv = ImageView(ctx).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = miniSize; height = miniSize
                        setMargins(dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                apps.getOrNull(slot)?.icon?.let { iv.setImageDrawable(it) }
                mini.addView(iv)
            }
            square.addView(mini)
            column.addView(square)
            column.addView(TextView(ctx).apply {
                text = folder.label
                setTextColor(0xFFE9D8FD.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                val mt = dp(ctx, 4)
                layoutParams = LinearLayout.LayoutParams(
                    cellSize, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = mt }
            })
            return column
        }

        /** Open the folder in a modal dialog — full app list with launchable
         *  icons. 5 columns, like One UI's expanded folder view. */
        private fun showFolderDialog(
            ctx: Context,
            folder: PhoneFolders.Folder,
            apps: List<PhoneApp>,
        ) {
            val launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val dialog = launcherFolderDialog(ctx)
            dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
            val sheet = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // Background is applied by setFolderContent — the card corners
                // have to be part of the same drawable, so setting a flat
                // colour here would just paint square edges over them.
                val pad = dp(ctx, 16); setPadding(pad, pad, pad, pad)
            }
            sheet.addView(TextView(ctx).apply {
                text = folder.label
                setTextColor(0xFFFFFFFFL.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Headline)
                setPadding(0, dp(ctx, 16), 0, dp(ctx, 16))
            })
            val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(ctx).apply {
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f,
                )
            }
            scroll.addView(grid)
            // Match the folder-grid column count (build.json::ui.phone_grid_columns,
            // default 6) instead of a hardcoded 5.
            val expandedCols = BuildConfig.UI_PHONE_GRID_COLUMNS
            apps.chunked(expandedCols).forEach { rowApps ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                for (app in rowApps) row.addView(makeExpandedAppTile(ctx, app, dialog, launcherApps))
                val short = expandedCols - rowApps.size
                repeat(short) {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
                grid.addView(row)
            }
            sheet.addView(scroll)
            // Dismissing on a tap anywhere in the sheet was a workaround for the
            // sheet being the whole screen. setFolderContent gives the folder a
            // real outside, so the card must now swallow its own taps instead.
            dialog.setFolderContent(ctx, sheet)
            dialog.show()
        }

        /** A single full-size launchable app tile inside the expanded
         *  folder dialog. Tap → launch via [LauncherApps.startMainActivity]
         *  + dismiss the dialog (Samsung's UX). */
        private fun makeExpandedAppTile(
            ctx: Context,
            app: PhoneApp,
            dialog: Dialog,
            launcherApps: LauncherApps,
        ): View {
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val p = dp(ctx, 8); setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    Haptics.tap(it)
                    // A launchable app fires normally. A LIBRARY has no
                    // launcher component (activityComponent == null) and so
                    // cannot be launched — firing the intent would no-op at
                    // best and leave the app's safety model broken (#156).
                    // Its tap opens that lib's entry in the in-app
                    // Constellation AppStore instead (#474).
                    val comp = app.activityComponent
                    if (comp != null) {
                        runCatching {
                            launcherApps.startMainActivity(comp, app.user, null, null)
                        }
                    } else {
                        openStore(ctx)
                    }
                    dialog.dismiss()
                }
                // Long-press → stock Android-style options menu (shortcuts +
                // App info + Uninstall). Mirrors what Pixel Launcher / One UI
                // do on a long-press. Dismiss the folder dialog so the menu
                // sits cleanly on its own backdrop.
                setOnLongClickListener {
                    Haptics.tap(it)
                    dialog.dismiss()
                    AppLongPressMenu.show(ctx, app.packageName)
                    true
                }
            }
            tile.addView(ImageView(ctx).apply {
                app.icon?.let { setImageDrawable(it) }
                val sz = dp(ctx, 48)
                layoutParams = LinearLayout.LayoutParams(sz, sz)
            })
            tile.addView(TextView(ctx).apply {
                text = app.label
                setTextColor(0xFFE9D8FD.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                val mt = dp(ctx, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = mt }
            })
            return tile
        }

        /** In-app open of Store ▸ Cloud Constellation — where every lib's
         *  entry lives. Carefully NOT a launch and NOT an external intent:
         *  a library has no launcher activity to fire, and #156 forbids
         *  showing it as "opening" while actually leaving the app or doing
         *  nothing. `store-cloud` is a tab of Configs ▸ Store, and
         *  openSectionPage resolves a tab id to its owner page on that tab.
         *  The Phone tab lives inside the launcher ([ShellActivity]), so its
         *  nav controller is the single in-app route to the store. */
        private fun openStore(ctx: Context) {
            (ctx as? android.app.Activity)?.let { act ->
                (act as? ShellActivity)?.nav?.openSectionPage("config", "store-cloud", null)
            }
        }

        private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

        /** Process-level write-ahead buffer for the Phone tab data.
         *  Populated by [warmUp] from MainActivity.onCreate so the
         *  list + folder classification is ready BEFORE the user ever
         *  swipes up to Home Apps. If the user races the warm-up
         *  thread, the synchronous fallback in [onCreateView] still
         *  works — just incurs the ~600ms load on the main thread the
         *  first time. @Volatile so the warm-up writer + the Fragment
         *  reader on different threads see consistent reference
         *  publication. */
        @Volatile private var sCachedFolders: List<PhoneFolders.Folder>? = null
        @Volatile private var sCachedApps:    List<PhoneApp>? = null
        @Volatile private var sCachedGrouped: Map<String, List<PhoneApp>>? = null

        /** Smart-folder SELECTION results, keyed by the caller's `exclude`
         *  set — see [smartFoldersCached]. Separate from [sCachedGrouped]
         *  because it is a different and far more expensive computation: the
         *  grouped cache is a pure classification over data already in memory,
         *  this one is the thousand package-manager round trips
         *  [renderSmartFoldersAsync] describes. Replaced wholesale rather than
         *  mutated, so the @Volatile publication covers the whole map; a race
         *  between two callers can only cost one recomputation. */
        @Volatile private var sCachedSmart: Map<Set<String>, List<SmartRendered>> = emptyMap()

        /** Bumped by [invalidateCache]. A background selection captures this
         *  before it starts and publishes only if it still matches.
         *
         *  WITHOUT IT THE REFRESH BUTTON HAS A HOLE. A thread that started
         *  BEFORE the user refreshed is still running afterwards, holding a
         *  result computed from the app list that was just thrown away. When it
         *  finished it wrote that result into the cache — after the clear — and
         *  the next render could serve it. The section would then show the app
         *  set from before the refresh, which is the exact staleness the clear
         *  exists to prevent, and it would survive until something invalidated
         *  again. Narrow, because the thread the refresh starts has to lose a
         *  race it begins with a head start in; real, because nothing made it
         *  impossible.
         *
         *  A plain Int is enough: every [invalidateCache] caller is on the main
         *  thread (the PACKAGE_ADDED receiver and the two refresh taps), so the
         *  increment cannot interleave with itself. @Volatile is what makes the
         *  new value visible to the background threads that read it. */
        @Volatile private var sCacheGeneration = 0

        /** Invalidate every cache slot.
         *
         *  NO LONGER UNUSED. This said "Currently unused; killing + reopening
         *  the app rebuilds the cache organically", which stopped being true
         *  on 2026-09-10: SuitePhoneAppsFragment registers a
         *  PACKAGE_ADDED/PACKAGE_REMOVED receiver and calls this before
         *  rebuilding, so a Quickmark placeholder the user just installed
         *  turns into the real app in place. Without dropping the cache the
         *  rebuild would redraw the same warm snapshot and the new app would
         *  still be missing — which looks exactly like the install failing. */
        fun invalidateCache() {
            sCachedFolders = null
            sCachedApps = null
            sCachedGrouped = null
            // The smart-folder selection is derived from sCachedApps, so it is
            // stale the instant that is. Leaving it behind would make the
            // refresh button redraw yesterday's Stores and Rank folders over a
            // freshly enumerated All Apps — the two halves of one page
            // disagreeing, which reads as the refresh not having worked.
            sCachedSmart = emptyMap()
            sCacheGeneration++
        }

        /** Warm-up: kick a background Thread that enumerates installed
         *  apps + loads folder taxonomy + runs the classifier, then
         *  publishes the results to the @Volatile cache slots. Safe to
         *  call multiple times — the no-op fast path returns immediately
         *  if everything's already cached. MainActivity.onCreate fires
         *  this so the Phone tab is hot the moment the user reaches it.
         *
         *  NOT a coroutine — keeping it on a plain low-priority Thread
         *  avoids hauling in kotlinx-coroutines as an app/ dep just for
         *  one fire-and-forget warm-up. */
        fun warmUp(ctx: Context) {
            if (sCachedFolders != null && sCachedApps != null && sCachedGrouped != null) return
            Thread {
                runCatching {
                    val folders = sCachedFolders
                        ?: PhoneFolders.loadFromBuildConfig().also { sCachedFolders = it }
                    val apps = sCachedApps
                        ?: collectLaunchableAppsStatic(ctx.applicationContext)
                            .also { sCachedApps = it }
                    if (sCachedGrouped == null) {
                        sCachedGrouped = PhoneAppClassifier.groupByFolder(apps, folders)
                    }
                }
            }.apply {
                name = "PhoneAppsFragment.warmUp"
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }

        /** Public snapshot for callers OUTSIDE this fragment that need
         *  the launchable-apps list — currently SuperappSearchIndex's
         *  "Phone (apps)" scope. Returns the warm-up cache when
         *  present (zero work), otherwise falls through to a fresh
         *  enumeration. Same profile-filter pipeline applies; the
         *  search scope therefore honours Guest-mode whitelisting
         *  automatically. */
        fun snapshot(ctx: Context): List<PhoneApp> =
            sCachedApps ?: collectLaunchableAppsStatic(ctx.applicationContext).also { sCachedApps = it }

        /** Static enumeration — derives LauncherApps from [ctx] each
         *  call so warm-up (no Fragment instance) + onCreateView (has
         *  one) share the exact same code path.
         *
         *  Profile filter applied last: when LauncherProfile = Guest,
         *  the behavior map (build.json::ui.launcher_profiles[guest]
         *  .behavior) declares app_filter=whitelist + whitelist=
         *  [browser], and [LauncherProfiles.allowedPackagesFor]
         *  resolves "browser" to every installed browser. The Phone
         *  tab in Guest mode therefore lists exactly the device's
         *  browsers — system browser, Chrome, Firefox, etc. — and
         *  nothing else. Personal / Work pass through (filter
         *  returns null = "no filter"). */
        private fun collectLaunchableAppsStatic(ctx: Context): List<PhoneApp> {
            val me = Process.myUserHandle()
            val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return emptyList()
            val allowed = LauncherProfiles.allowedPackagesFor(ctx, LauncherProfilePrefs(ctx).profile)
            return runCatching {
                launcher.getActivityList(null, me).mapNotNull { info ->
                    val pkg = info.applicationInfo.packageName
                    if (pkg == ctx.packageName) return@mapNotNull null
                    if (allowed != null && pkg !in allowed) return@mapNotNull null
                    PhoneApp(
                        packageName       = pkg,
                        activityComponent = info.componentName,
                        label             = info.label?.toString() ?: pkg,
                        icon              = runCatching { info.getBadgedIcon(0) }.getOrNull(),
                        user              = me,
                        firstInstallTime  = runCatching {
                            ctx.packageManager.getPackageInfo(pkg, 0).firstInstallTime
                        }.getOrDefault(0L),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}
