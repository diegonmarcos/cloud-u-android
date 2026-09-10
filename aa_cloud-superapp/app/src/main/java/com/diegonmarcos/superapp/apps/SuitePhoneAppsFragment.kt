package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.launcher.AppInstall
import com.diegonmarcos.superapp.launcher.AppLongPressMenu
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.ui.snack
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.datamanager.AppUsageProvider

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Base64
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
import org.json.JSONArray

/**
 * Suite section's Phone tab — curated phone apps grouped under the
 * SAME titled sections used by Suite/Cloud (AI · Data Primary · Data
 * Apps · Tools Primary · Tools Dashboards · Configs). Source of truth
 * = build.json::sections[id=suite].phone_app_groups, baked into
 * BuildConfig.UI_SUITE_PHONE_GROUPS_B64 by app/build.gradle.
 *
 * Render shape mirrors GroupedTilesFragment (Cloud-side):
 *   • One subhead per group title.
 *   • UI_PHONE_GRID_COLUMNS-col grid of icon tiles below it.
 *   • A group with no declared entries at all is skipped, so the
 *     visual layout never has dead headers. A group whose apps merely
 *     are not INSTALLED still renders — see below.
 *
 * A CURATED ENTRY FOR A PACKAGE THAT IS NOT INSTALLED RENDERS AS A
 * PLACEHOLDER, and that is the opposite of what this comment used to
 * say. It read "Missing packages (uninstalled, or wrong package guess)
 * silently skipped", and it was accurate: resolve() returned null and
 * mapNotNull dropped the entry, so a tile the owner had deliberately
 * put in build.json was simply not on the page and nothing explained
 * its absence. Since 2026-09-10 a curated entry always draws a tile —
 * the real app when installed, otherwise an outlined glyph with a
 * "Not installed" caption whose tap starts the install (AppInstall).
 *
 * THE INVERSION IS SCOPED TO THE CURATED LIST. Everything else on this
 * page — All Apps, Smart Folders, Active Apps, Last Apps — is
 * ENUMERATED from what the device actually has, and a not-installed
 * placeholder in a list built by enumerating installed apps would be
 * incoherent. Those paths still go through resolve() and still show
 * only installed apps. The difference is not cosmetic: it is the
 * difference between a list somebody wrote and a list the device
 * reported.
 */
class SuitePhoneAppsFragment : Fragment() {

    /** One curated entry as build.json declares it: the package, plus the
     *  name to show when that package is NOT installed. `label` is a
     *  fallback and nothing else — an installed app always draws its own
     *  PackageManager label, so the two cannot disagree on a real device.
     *  It exists because a placeholder has no launcher label to borrow. */
    private data class Entry(val pkg: String, val label: String)
    private data class Folder(val label: String, val entries: List<Entry>)
    private data class Group(
        val title: String,
        val entries: List<Entry>,
        val folders: List<Folder>,
    )
    /** Resolved app payload — populated after LauncherApps lookup so
     *  both the plain-grid tile and the folder-dialog tile can launch
     *  via the same `pkg` + `label` + `icon` tuple. */
    private data class AppInfo(
        val pkg: String,
        val label: String,
        /** Null ONLY when [installed] is false. A package the device does not
         *  have has no launcher icon to draw, which is the entire reason
         *  R.drawable.ic_app_not_installed exists. */
        val icon: Drawable?,
        val installed: Boolean = true,
    )

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(ctx, 8); setPadding(pad, pad, pad, dp(ctx, 96))
        }
        scroll.addView(root)
        pageRoot = root
        buildPage(ctx, root)
        return scroll
    }

    /** The page container, kept so [packageChanged] can rebuild it. */
    private var pageRoot: LinearLayout? = null

    /**
     * WHAT HAPPENS AFTER THE INSTALL FINISHES. Without this the placeholder
     * stayed a placeholder until the user left the tab and came back, or
     * restarted the launcher — the install worked and the page disagreed,
     * which reads exactly like the install having failed.
     *
     * PACKAGE_ADDED / PACKAGE_REMOVED arrive from the platform when any
     * package appears or goes away. Both are handled: an install turns a
     * placeholder into the real app, and an uninstall turns a real app back
     * into a placeholder, so the page tells the truth in both directions.
     *
     * PhoneAppsFragment.invalidateCache() FIRST, and that ordering is the
     * whole trick. The tiles are drawn from a warm snapshot taken at launch;
     * rebuilding without dropping it would redraw the same stale list and the
     * newly installed app would still be missing, which would look like this
     * receiver not firing at all.
     */
    private val packageChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val root = pageRoot ?: return
            val ctx = context ?: return
            PhoneAppsFragment.invalidateCache()
            root.removeAllViews()
            buildPage(ctx, root)
        }
    }

    override fun onResume() {
        super.onResume()
        // Registered only while the tab is on screen: a page nobody is looking
        // at does not need to rebuild itself, and onCreateView already drew
        // the current truth for the next time it is.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        // NOT_EXPORTED because these are protected system broadcasts — nothing
        // else may deliver them, and from API 34 a runtime receiver has to say
        // which it is or the registration throws.
        ContextCompat.registerReceiver(
            requireContext(), packageChanged, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onPause() {
        super.onPause()
        runCatching { requireContext().unregisterReceiver(packageChanged) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pageRoot = null
    }

    /** The whole page, so the bottom refresh button can clear [root] and call
     *  this again — the same clear-and-rebuild shape PhoneAppsFragment uses.
     *  Everything below used to sit inline in onCreateView, which is why this
     *  page had no way to rebuild itself and therefore no refresh button. */
    private fun buildPage(ctx: Context, root: LinearLayout) {
        // ── Quickmarks — the curated groups/folders/active-apps content
        //    that used to be this fragment's entire page. Now the first
        //    of three stacked sections on one scrollable page.
        root.addView(subhead(ctx, "Quickmarks"))

        val groups = parseGroups()
        // READ THE WARM CACHE, DO NOT RE-ENUMERATE.
        //
        // This used to call LauncherApps.getActivityList(null, me) itself and
        // then info.getIcon(densityDpi) once per Quickmark — a full launcher
        // enumeration plus one icon DECODE per package, on the main thread, in
        // onCreateView. PhoneAppsFragment.warmUp already did exactly that work
        // on a background thread at launch (MainActivity.onCreate) and holds the
        // result, icons included, so every millisecond of it was being paid
        // twice: once early where nobody was waiting, and again here where the
        // user is staring at a frozen tab.
        //
        // snapshot() returns that cache, or enumerates once if the user somehow
        // beat the warm-up thread here — the same fall-through renderAllApps
        // below already relies on.
        val byPkg = PhoneAppsFragment.snapshot(ctx).associateBy { it.packageName }
        val columns = BuildConfig.UI_PHONE_GRID_COLUMNS

        // Phone is the THIRD-PARTY half of the launcher. Everything the
        // constellation already offers a way into lives one tab over in
        // Cloud ▸ Apps, and listing it here too put the same icon in both —
        // so the ENUMERATED sections below drop it.
        //
        // Only the enumerated ones. Quickmarks is a curated list
        // (build.json::sections[id=phone].phone_app_groups) and is left alone
        // on purpose: filtering a hand-written list leaves entries that are
        // declared and never render, which is the "declared but not behaving"
        // trap. Our packages came out of that list in build.json instead,
        // where you can see them go.
        val ourApps = Sections.constellationPackages(ctx.packageName)

        fun resolve(pkg: String): AppInfo? {
            byPkg[pkg]?.let { app ->
                // icon is nullable on PhoneApp (getBadgedIcon can throw).
                val icon = app.icon
                    ?: runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()
                if (icon != null) return AppInfo(pkg = pkg, label = app.label, icon = icon)
            }
            // The cache is a LOOKUP TABLE here, not the guest list. snapshot()
            // drops this app's own package and applies the launcher-profile
            // whitelist, and a Quickmark legitimately names both — one of the
            // declared groups points at com.diegonmarcos.superapp itself. The
            // old code enumerated raw LauncherApps and so showed them; falling
            // back to a direct PackageManager resolve keeps that behaviour
            // exactly, and costs one lookup for the handful of packages the
            // cache filters out instead of a full enumeration for all of them.
            val pm = ctx.packageManager
            return runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                AppInfo(pkg = pkg, label = pm.getApplicationLabel(ai).toString(), icon = pm.getApplicationIcon(ai))
            }.getOrNull()
        }

        // THE ONE PLACE THE MISSING-PACKAGE FILTER IS LIFTED, and it is lifted
        // for curated entries only. resolve() above answers "is this
        // installed" and every enumerated section below still asks it. Here
        // the question is different: the owner wrote this list, so the answer
        // to "not installed" is a placeholder offering to fix that, not
        // silence. Note this never calls mapNotNull — curated() cannot return
        // null, which is what makes the guarantee structural rather than a
        // convention somebody has to remember.
        fun curated(entry: Entry): AppInfo =
            resolve(entry.pkg)
                ?: AppInfo(entry.pkg, entry.label, icon = null, installed = false)

        var anyRendered = false
        for (group in groups) {
            val packageTiles = group.entries.map(::curated)
            // A folder is skipped only when it DECLARES nothing. It used to be
            // skipped when nothing in it was installed, which hid the folder
            // for the same reason it hid the tiles; its contents can render as
            // placeholders now, so there is nothing left to hide.
            val folderTiles = group.folders.map { folder ->
                folder to folder.entries.map(::curated)
            }.filter { (_, contents) -> contents.isNotEmpty() }
            if (packageTiles.isEmpty() && folderTiles.isEmpty()) continue
            anyRendered = true
            root.addView(subhead(ctx, group.title))

            // Render packages first, then folder tiles, in one continuous
            // grid — so the folder cards flow naturally as the next
            // column after the last app. Tile builders are heterogenous
            // (View, not the same type) so we use a List<View> and
            // chunk over it.
            val tiles = mutableListOf<View>()
            for (a in packageTiles) tiles.add(makeAppTile(ctx, a, root))
            for ((folder, contents) in folderTiles) tiles.add(makeFolderTile(ctx, folder.label, contents, root))

            for (rowChunk in tiles.chunked(columns)) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                for (tile in rowChunk) row.addView(tile)
                // Pad short trailing row with weighted spacers so the last
                // row stays left-aligned within its group.
                repeat(columns - rowChunk.size) {
                    row.addView(View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
                root.addView(row)
            }
        }
        if (!anyRendered) {
            root.addView(TextView(ctx).apply {
                // Reachable only when the curated data declares NOTHING —
                // no group with a single entry in it. "Not installed" no
                // longer lands here, because a not-installed entry renders.
                text = "No curated apps declared — add a group to build.json::sections[id=phone].phone_app_groups."
                setTextColor(0x99FFFFFF.toInt())
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
            })
        }
        // ── Active Apps — apps that are STILL RUNNING: holding a foreground
        //    service open, or sitting in the foreground. Not "recently
        //    used": ranking by recency returned the same head of the list as
        //    Last Apps below, so the two sections rendered as twins.
        //    Data-driven: build.json::sections[id=phone].active_apps →
        //    AppUsageProvider.activeNow via libs:datamanager. Hidden when
        //    the usage-access grant is missing (activeNow → empty) or
        //    nothing launchable matches. Constellation packages filtered out
        //    — the superapp is always hot, and the rest belong to Cloud ▸
        //    Apps.
        if (BuildConfig.UI_SUITE_ACTIVE_APPS_ENABLED) {
            usageSection(
                ctx, root, columns,
                title = BuildConfig.UI_SUITE_ACTIVE_APPS_TITLE,
                apps = AppUsageProvider.activeNow(ctx)
                    .asSequence()
                    .filter { it !in ourApps }
                    .mapNotNull { resolve(it) }
                    .take(BuildConfig.UI_SUITE_ACTIVE_APPS_LIMIT.coerceAtLeast(1))
                    .toList(),
            )
        }

        // ── Last Apps — the apps most recently OPENED, most recent first.
        //    Sits directly under Active Apps and looks identical, but the
        //    ranking differs on purpose: Active Apps reads lastTimeUsed,
        //    which the system also bumps for background work, so an app that
        //    merely synced can outrank one you actually launched. This list
        //    counts MOVE_TO_FOREGROUND events only — apps you brought to the
        //    front. Same knobs, same hidden-when-empty behaviour.
        if (BuildConfig.UI_SUITE_LAST_APPS_ENABLED) {
            usageSection(
                ctx, root, columns,
                title = BuildConfig.UI_SUITE_LAST_APPS_TITLE,
                apps = AppUsageProvider.lastOpened(ctx)
                    .asSequence()
                    .filter { it !in ourApps }
                    .mapNotNull { resolve(it) }
                    .take(BuildConfig.UI_SUITE_LAST_APPS_LIMIT.coerceAtLeast(1))
                    .toList(),
            )
        }

        // ── All Apps — every installed app grouped by purpose. Same
        //    rendering PhoneAppsFragment uses standalone (the Phone tab
        //    of the swipe-up app drawer); embedded inline here instead
        //    of navigating to a separate "more" screen.
        root.addView(sectionDivider(ctx))
        // ── Below the fold: built AFTER the first frame ──────────────────
        //
        // All Apps renders a tile per app across ~31 folders and Smart Folders
        // renders several more — hundreds of ImageView+TextView pairs, none of
        // them recycled (this is a ScrollView, not a RecyclerView). Doing that
        // inline meant onCreateView did not return until every one existed, so
        // the tab stayed frozen on the PREVIOUS page for the whole build and
        // the page appeared already-scrolled-to-top and complete — the "opens
        // extremely slowly" symptom.
        //
        // Quickmarks is what the user actually looks at first and it is small,
        // so it stays inline: the page now appears as soon as it is built. The
        // two heavy sections are appended on later frames, one section per
        // frame so neither one blocks the other, and the scroll position does
        // not move because they are added BELOW the visible content.
        //
        // ponytail: one section per frame, not one row per frame. If All Apps
        // alone still drops frames on the slowest device, chunk its rows the
        // same way rather than reaching for a RecyclerView rewrite.
        root.post {
            if (!isAdded) return@post
            root.addView(subhead(ctx, "All Apps"))
            PhoneAppsFragment.renderAllApps(ctx, root, ourApps)

            // ── Smart Folders — dynamic folders (Samsung, Google, Recent 7,
            //    …), same shared renderer as PhoneAppsFragment. Self-headed.
            root.post {
                if (!isAdded) return@post
                root.addView(sectionDivider(ctx))
                PhoneAppsFragment.renderSmartFolders(ctx, root, ourApps)

                // Refresh belongs at the VERY BOTTOM, so it has to be added
                // from inside the last deferred section — added in buildPage's
                // own frame it would land above the two posted sections.
                root.addView(PhoneAppsFragment.refreshBar(ctx) {
                    PhoneAppsFragment.invalidateCache()
                    root.removeAllViews()
                    buildPage(ctx, root)
                })
            }
        }
    }

    private fun parseGroups(): List<Group> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_SUITE_PHONE_GROUPS_B64, Base64.DEFAULT))
        val arr = JSONArray(json)
        // Plain mutable list + for-loop because `continue` inside an
        // inline-lambda body (buildList { ... }) is an experimental
        // Kotlin feature gated behind a compiler flag — using a
        // regular for-loop sidesteps the gate entirely.
        val out = mutableListOf<Group>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title")
            if (title.isBlank()) continue
            val entryList = parseEntries(o.optJSONArray("packages"))
            // Optional `folders` array — One-UI-style nested folders
            // inside the group. Each folder = { label, packages[] }.
            // Absent or empty → group renders as a flat grid (legacy
            // behaviour, no schema break).
            val folderList = mutableListOf<Folder>()
            val foldersArr = o.optJSONArray("folders")
            if (foldersArr != null) {
                for (k in 0 until foldersArr.length()) {
                    val fo = foldersArr.optJSONObject(k) ?: continue
                    val fLabel = fo.optString("label")
                    if (fLabel.isBlank()) continue
                    val fEntries = parseEntries(fo.optJSONArray("packages"))
                    if (fEntries.isNotEmpty()) folderList.add(Folder(fLabel, fEntries))
                }
            }
            out.add(Group(title, entryList, folderList))
        }
        out
    }.getOrDefault(emptyList())

    /** One `packages` array → [Entry] list. An element is either a bare
     *  package string (the shape every entry had before 2026-09-10) or an
     *  object {"pkg", "label"}. Both forms stay valid on purpose: the label
     *  is only needed where a placeholder might be drawn, so requiring it
     *  everywhere would be churn for no behaviour. */
    private fun parseEntries(arr: JSONArray?): List<Entry> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            val pkg = if (obj != null) obj.optString("pkg").trim() else arr.optString(i).trim()
            if (pkg.isBlank()) continue
            val declared = if (obj != null) obj.optString("label").trim() else ""
            out.add(Entry(pkg, if (declared.isNotBlank()) declared else derivedLabel(pkg)))
        }
        return out
    }

    /** Label of LAST RESORT for a placeholder whose entry declared none:
     *  the final segment of the package id, capitalised
     *  ("com.foxdebug.acode" → "Acode").
     *
     *  This is deliberately dumb and it is not a naming scheme. It gets
     *  "ch.protonmail.android" wrong ("Android"), and the fix for that is to
     *  declare `label` on the entry, not to grow a table of segments to
     *  ignore here — the names live in build.json, which is where the rest of
     *  this page's data lives. Every entry shipped on 2026-09-10 declares
     *  one; this only catches an entry somebody adds later without. */
    private fun derivedLabel(pkg: String): String {
        val tail = pkg.substringAfterLast('.')
        if (tail.isEmpty()) return pkg
        return tail.replaceFirstChar { it.uppercase() }
    }

    /** Thin separator line between the Quickmarks / All Apps / Smart
     *  Folders sections on the merged page. */
    private fun sectionDivider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1),
        ).apply { topMargin = dp(ctx, 16); bottomMargin = dp(ctx, 4) }
    }

    /** A subheading plus a grid of app tiles — the shape both Quickmarks
     *  usage sections (Active Apps, Last Apps) render. Draws nothing at all
     *  when [apps] is empty, so a missing usage-access grant leaves no
     *  orphan heading behind. */
    private fun usageSection(
        ctx: Context,
        root: LinearLayout,
        columns: Int,
        title: String,
        apps: List<AppInfo>,
    ) {
        if (apps.isEmpty()) return
        root.addView(subhead(ctx, title))
        for (rowChunk in apps.chunked(columns)) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            // Enumerated, not curated: `apps` comes from AppUsageProvider, so
            // every entry is installed by construction and none of them can be
            // a placeholder. Passing `root` as the Snackbar anchor costs
            // nothing and keeps one tile builder for the whole page.
            for (a in rowChunk) row.addView(makeAppTile(ctx, a, root))
            // Pad a short last row so its tiles keep column alignment
            // instead of stretching across the full width.
            repeat(columns - rowChunk.size) {
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            root.addView(row)
        }
    }

    private fun subhead(ctx: Context, t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xFFE9D8FD.toInt())
        setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        setPadding(dp(ctx, 4), dp(ctx, 12), 0, dp(ctx, 4))
    }

    /**
     * One icon tile. [anchor] is only the Snackbar host for the
     * not-installed tap — it is never mutated.
     *
     * NOT-INSTALLED TREATMENT, and why it is drawn this way. The tile keeps
     * its exact cell geometry so a placeholder does not reflow the grid
     * around it; what changes is the ink. The launcher icon is replaced by
     * R.drawable.ic_app_not_installed (a dashed outline holding a download
     * arrow — an empty slot, not a broken icon), the app name drops to the
     * theme's secondary text colour, and a third line spells out "Not
     * installed" in the accent colour so the state is readable without
     * decoding the glyph.
     *
     * EVERY COLOUR COMES FROM LauncherPalette. The launcher ships a
     * Samsung-black Power Saving theme and a Minimalistic Black one, and a
     * literal picked against the default gradient is legible on one and
     * invisible on the other. The literals already in this file predate the
     * palette and are left alone — this file is not in
     * build.json::ui.launcher_theme_surfaces, and adding it there means
     * converting all of them, which is a separate job.
     */
    private fun makeAppTile(
        ctx: Context,
        app: AppInfo,
        anchor: View,
    ) = LinearLayout(ctx).apply {
        val palette = LauncherPalette.of(ctx)
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        val pad = dp(ctx, 6); setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        isClickable = true
        isFocusable = true
        val outVal = android.util.TypedValue()
        ctx.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, outVal, true)
        if (outVal.resourceId != 0) setBackgroundResource(outVal.resourceId)

        // A screen reader gets the state, not just the name — the glyph and
        // the colour shift are both invisible to it.
        contentDescription =
            if (app.installed) app.label
            else ctx.getString(R.string.phone_app_not_installed_tile_hint, app.label)

        // EXACTLY ONE LISTENER ON EXACTLY ONE VIEW IN THIS CELL. A tile in
        // this launcher has already shipped firing twice per tap (#195, 149
        // duplicate deliveries in a single trace) because two views in the
        // same cell both dispatched. The ImageView and the TextViews below
        // are deliberately left non-clickable, so the cell has one dispatch
        // point and a second delivery has nowhere to come from.
        setOnClickListener {
            if (app.installed) {
                runCatching {
                    val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
                    if (intent != null) ctx.startActivity(intent)
                }
            } else {
                // AppInstall always returns a message, started or refused, and
                // it is always shown. A TAP THAT DOES NOTHING IS WORSE THAN A
                // TILE THAT WAS NOT THERE — that is the whole reason this
                // branch cannot fall through in silence.
                anchor.snack(AppInstall.start(ctx, app.pkg, app.label).message)
            }
        }
        // Long-press → stock Android-style options menu (shortcuts +
        // App info + Uninstall). Mirrors what Pixel Launcher / One UI
        // do; same helper PhoneAppsFragment uses so behaviour is
        // identical across both Phone surfaces.
        setOnLongClickListener {
            // A package that is not installed has no App-info screen and
            // nothing to uninstall, so the stock menu would open onto its own
            // empty state. Returning false leaves the gesture unconsumed
            // instead of showing that.
            if (!app.installed) return@setOnLongClickListener false
            AppLongPressMenu.show(ctx, app.pkg)
            true
        }
        addView(ImageView(ctx).apply {
            if (app.installed) {
                setImageDrawable(app.icon)
            } else {
                setImageResource(R.drawable.ic_app_not_installed)
                imageTintList = ColorStateList.valueOf(palette.textSecondary)
            }
            val sz = dp(ctx, 52)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        })
        addView(TextView(ctx).apply {
            text = app.label
            setTextColor(if (app.installed) 0xFFFFFFFFL.toInt() else palette.textSecondary)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(ctx, 4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        if (!app.installed) {
            addView(TextView(ctx).apply {
                setText(R.string.phone_app_not_installed)
                setTextColor(palette.accent)
                textSize = 9f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
        }
    }

    /** Folder tile rendered in-line with the package grid — same cell
     *  width as a single-app tile so it slots seamlessly. Shows a 2×2
     *  mini-icon preview of the first four installed apps inside; tap
     *  opens a fullscreen modal dialog with every app in the folder
     *  (5-column grid, same chrome as PhoneAppsFragment's expanded
     *  folder view). Mirrors One UI's app-folder UX. */
    private fun makeFolderTile(
        ctx: Context,
        label: String,
        contents: List<AppInfo>,
        anchor: View,
    ): View {
        val cellSize = dp(ctx, 52)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(ctx, 6); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            val outVal = android.util.TypedValue()
            ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, outVal, true)
            if (outVal.resourceId != 0) setBackgroundResource(outVal.resourceId)
            setOnClickListener { showFolderDialog(ctx, label, contents, anchor) }
        }
        // Glass-bg square holding the 2×2 mini-preview — visually
        // distinguishes folder tiles from plain-icon tiles.
        val square = FrameLayout(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass)
            layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
        }
        val mini = GridLayout(ctx).apply {
            rowCount = 2; columnCount = 2
            val p = dp(ctx, 5); setPadding(p, p, p, p)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val miniSize = (cellSize - dp(ctx, 16)) / 2
        for (slot in 0 until 4) {
            val iv = ImageView(ctx).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = miniSize; height = miniSize
                    setMargins(dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            // The 2×2 preview shows the placeholder glyph for a
            // not-installed member rather than leaving the slot blank — an
            // empty slot reads as "this folder has fewer than four apps",
            // which would be a different and untrue statement.
            val member = contents.getOrNull(slot)
            if (member != null) {
                if (member.installed) {
                    iv.setImageDrawable(member.icon)
                } else {
                    iv.setImageResource(R.drawable.ic_app_not_installed)
                    iv.imageTintList =
                        ColorStateList.valueOf(LauncherPalette.of(ctx).textSecondary)
                }
            }
            mini.addView(iv)
        }
        square.addView(mini)
        column.addView(square)
        column.addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFFL.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(ctx, 4), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        })
        return column
    }

    /** Modal dialog showing every app inside a folder, 5-col grid.
     *  Tap an app → launch + dismiss. Tap the dim outside area to
     *  dismiss without launching. */
    private fun showFolderDialog(
        ctx: Context,
        label: String,
        contents: List<AppInfo>,
        anchor: View,
    ) {
        val dialog = launcherFolderDialog(ctx)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // Background is applied by setFolderContent — the card corners have
            // to be part of the same drawable, so setting a flat colour here
            // would just paint square edges over them.
            val pad = dp(ctx, 16); setPadding(pad, pad, pad, pad)
            // Dismissing on a tap anywhere in the sheet was a workaround for the
            // sheet being the whole screen. The folder now has a real outside,
            // so the card swallows its own taps instead.
            isClickable = true
        }
        sheet.addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFFL.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setPadding(0, dp(ctx, 16), 0, dp(ctx, 16))
        })
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        scroll.addView(grid)
        // Match the phone grid column count (default 6), not a hardcoded 5.
        val cols = BuildConfig.UI_PHONE_GRID_COLUMNS
        for (rowChunk in contents.chunked(cols)) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            for (a in rowChunk) row.addView(makeExpandedAppTile(ctx, a, dialog, anchor))
            repeat(cols - rowChunk.size) {
                row.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            grid.addView(row)
        }
        sheet.addView(scroll)
        dialog.setFolderContent(ctx, sheet)
        dialog.show()
    }

    /** Full-size app tile inside the expanded folder dialog. Tap
     *  launches via PackageManager + dismisses the dialog. */
    private fun makeExpandedAppTile(
        ctx: Context,
        app: AppInfo,
        dialog: Dialog,
        anchor: View,
    ): View {
        val palette = LauncherPalette.of(ctx)
        val tile = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(ctx, 8); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true; isFocusable = true
            contentDescription =
                if (app.installed) app.label
                else ctx.getString(R.string.phone_app_not_installed_tile_hint, app.label)
            // One listener, one view — same single-dispatch rule as
            // makeAppTile. The dialog is dismissed BEFORE the Snackbar is
            // raised, because a Snackbar behind a modal dialog is a message
            // nobody reads, which is the silent-tap failure wearing a hat.
            setOnClickListener {
                if (app.installed) {
                    runCatching {
                        val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
                        if (intent != null) ctx.startActivity(intent)
                    }
                    dialog.dismiss()
                } else {
                    dialog.dismiss()
                    anchor.snack(AppInstall.start(ctx, app.pkg, app.label).message)
                }
            }
            // Long-press inside folder modal — dismiss the folder
            // dialog first so the long-press menu sits cleanly on
            // its own dim backdrop, matching the PhoneAppsFragment
            // folder-tile behaviour.
            setOnLongClickListener {
                if (!app.installed) return@setOnLongClickListener false
                dialog.dismiss()
                AppLongPressMenu.show(ctx, app.pkg)
                true
            }
        }
        tile.addView(ImageView(ctx).apply {
            if (app.installed) {
                setImageDrawable(app.icon)
            } else {
                setImageResource(R.drawable.ic_app_not_installed)
                imageTintList = ColorStateList.valueOf(palette.textSecondary)
            }
            val sz = dp(ctx, 48)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        })
        tile.addView(TextView(ctx).apply {
            text =
                if (app.installed) app.label
                else ctx.getString(
                    R.string.phone_app_not_installed_tile_hint, app.label,
                )
            setTextColor(if (app.installed) 0xFFE9D8FD.toInt() else palette.textSecondary)
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

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    companion object { fun newInstance() = SuitePhoneAppsFragment() }
}
