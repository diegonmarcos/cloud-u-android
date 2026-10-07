package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.cache.ApkCache
import kotlin.concurrent.thread

/**
 * Store ▸ Phone Apps (#563, #564, #565, #571) — every app that BELONGS on this
 * phone, grouped by the host's central classification exactly as
 * [StoreCloudFragment] groups the fleet: what is installed, PLUS every fleet
 * app the constellation manifest declares, PLUS every external app the
 * install-source map declares. On a fresh phone the list is therefore full,
 * with every row saying "not installed" and how it installs.
 *
 * Each row carries a state line — installed version, update available, not
 * installed, or the 'needs Play' badge — and its buttons: [PhoneAppActions.of]
 * for an installed app (Update, Open, Stop, Remove, App info, origin) and
 * [PhoneAppActions.forMissing] for one that is not (Install, origin). Install
 * and Update route through the ONE path per kind: fleet → [FleetInstall],
 * external → [ExternalInstall] over its declared ladder, Play-only → the Play
 * page, never a download.
 *
 * Above the rows: the Cloud tab's own top bar ([StoreBar]). Check all probes
 * every row (fleet: [Fleet.status]; external: [SourceResolver.check]); Install
 * all and Update all walk the rows that can be served without any other store,
 * and say how many were skipped because they need Play. Then Export / Import
 * of the app inventory ([AppInventory]); an import shows its plan
 * ([StoreImport]) before anything acts.
 */
class StorePhoneFragment : Fragment() {

    private class Row(val pkg: String, val label: String, val shelf: AppStoreHost.Shelf?,
                      val fleetApp: Fleet.App?, val external: SourceResolver.External?,
                      val installed: Boolean, val actions: List<PhoneAppActions.Action>) {
        /** This store can put it on the phone by itself. */
        val direct: Boolean get() = (fleetApp != null && !fleetApp.blocked) || (external?.needsPlay == false)
    }

    private val controls by lazy { StoreControls.load(requireContext()) }
    private val cDim = 0x99FFFFFF.toInt()
    private val cHead = 0xFFED8936.toInt()
    private val cUp = 0xFF48BB78.toInt()
    private val cUpd = 0xFFF6AD55.toInt()
    private val cMiss = 0xFF9F7AEA.toInt()
    private val cBadge = 0xFFE53E3E.toInt()
    private var list: LinearLayout? = null
    private var rows: List<Row> = emptyList()
    // #619 the row filter. false = Declared (the full set rows() builds:
    // installed ∪ fleet ∪ external) — the default, and the mode Profile ▸ Store
    // deep-links into; true = Installed (only rows already on the device). No
    // second list: it filters Row.installed, the state rows() already resolved
    // through PackageManager. redraw() re-renders the SAME rows, so flipping the
    // toggle never re-probes or re-enumerates.
    private var installedOnly = false
    // #627 which declared store source the page is showing, or null for All.
    // COMPOSES with [installedOnly] rather than replacing it: the tab chooses the
    // store, the pill chooses declared-vs-installed, and render() applies both to
    // the SAME rows() output — so every view is a subset of Declared, by
    // construction, exactly as #619 required.
    private var sourceTab: SourceResolver.Kind? = null
    // #896 Installed | Declared are the page's top tabs now (the Cloud page's strip, [StoreTabs]); the
    // bottom bar holds every other control, and the store-source strip is drawn into [sourceHost].
    private val pageButtons = ArrayList<TextView>()
    private var sourceHost: LinearLayout? = null
    private var stopObserving: (() -> Unit)? = null
    private var cfg: SourceResolver.Config? = null
    private val states = HashMap<String, SourceResolver.Check>()
    private val stateViews = HashMap<String, TextView>()
    // #625 what the APK cache holds, per package, as of the last reload: the
    // row's own answer to "is the download still here, and is it the thing I
    // have installed?". Read from ApkCache's download records — no second
    // bookkeeping, and no hashing on the UI thread.
    private val cached = HashMap<String, ApkCache.Entry>()
    // #666 the clear-cache button, kept so its label can carry the REAL count and
    // megabytes it would free, recomputed on every reload rather than described.
    private var cacheBtn: TextView? = null
    // Set when a tap has already taken the provably-installed bytes and the only
    // thing left is unproven: the next tap discards the only copy, and the toast
    // has already said so in those words. It stays armed across reloads on
    // purpose — leaving the tab does not un-warn the user — and is cleared by the
    // discard itself, or as soon as a later install proves an entry redundant.
    private var armedToDiscard = false

    // #565 export / import. Registered at construction, as the Activity Result
    // API requires; the system picker owns where the file lives.
    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }
    private val importDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        // The page's TOP TABS: Installed | Declared, in build.json::ui phone.pages order. Drawn here by
        // the Cloud page's own strip builder unless the host draws them (StorePages.hostDrawsStrip).
        val strip = if (StorePages.hostDrawsStrip) null else StoreTabs.bar(ctx, listOf(listOf(
            StoreControls.Control(ctx.getString(R.string.store_phone_filter_installed), "", controls.groupTab),
            StoreControls.Control(ctx.getString(R.string.store_phone_filter_declared), "", controls.groupTab))),
            pageButtons) { StorePages.select(SECTION, if (it == 0) PAGE_INSTALLED else PAGE_DECLARED) }
        syncPage()
        stopObserving = StorePages.observe(SECTION) { view?.post { syncPage(); redraw() } }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, StoreDensity.S12); setPadding(p, p, p, p)
        }
        col.addView(caption(ctx, ctx.getString(R.string.store_phone_caption)))
        val rowsView = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list = rowsView
        col.addView(rowsView)

        // The BOTTOM ACTION BAR: the SAME bar the Cloud tab draws. #571: Install all and Update all are
        // real verbs here now - they walk every row this store can serve itself (fleet path, vendor
        // APK, F-Droid) and report the rows that need Play. Then export / import / clear cache, and
        // the store-source strip (which store the rows come from).
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        bar.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            StoreBar.render(this@StorePhoneFragment, this, StoreBar.Verbs(
                checkAll = { checkAll() }, installAll = { installAll() }, updateAll = { updateAll() }))
        })
        bar.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(fileBtn(ctx, ctx.getString(R.string.store_export)) { exportDoc.launch(EXPORT_NAME) })
            addView(fileBtn(ctx, ctx.getString(R.string.store_import)) { importDoc.launch(IMPORT_TYPES) })
            // #625 the manual door onto the cache. The app owns eviction now, so
            // the user needs a way to say "drop it all" that does not mean
            // Settings > Clear cache - which no longer reaches these bytes, on
            // purpose, because the OS doing that silently WAS the bug.
            // #666 the label states the measured count and megabytes; reload()
            // rewrites it from ApkCache.plan, so it is never an estimate.
            addView(fileBtn(ctx, ctx.getString(R.string.store_cache_clear)) { clearCache() }
                .also { cacheBtn = it })
        })
        sourceHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        bar.addView(sourceHost)
        return StorePage.frame(ctx, strip, ScrollView(ctx).apply { addView(col) }, bar)
    }

    /** [installedOnly] is the selected page, and the strip paints it. */
    private fun syncPage() {
        installedOnly = StorePages.page(SECTION, PAGE_DECLARED) == PAGE_INSTALLED
        StoreTabs.paint(pageButtons, if (installedOnly) 0 else 1)
    }

    // Reloaded on every return: Remove and App info leave for a system
    // screen, and what they changed must not be drawn stale.
    override fun onResume() {
        super.onResume()
        reload()
        // #570 Account ▸ Fleet ▸ Apps ▸ Apply list to Store: the declared inventory, same path as a picked file.
        StoreImport.takePending()?.let { importText(it) }
    }

    private fun reload(then: (() -> Unit)? = null) {
        val ctx = requireContext()
        val into = list ?: return
        into.removeAllViews()
        into.addView(caption(ctx, ctx.getString(R.string.store_phone_loading)))
        // Enumerating, classifying and probing a few hundred packages is
        // PackageManager IPC — off the main thread, the #261 lesson.
        val app = ctx.applicationContext
        thread(name = "store-phone-apps") {
            val built = runCatching { rows(app) }
            into.post {
                if (!isAdded) return@post
                into.removeAllViews()
                built.onSuccess { rows = it; render(ctx, into, it); refreshCacheLabel(); then?.invoke() }
                    .onFailure { into.addView(caption(ctx, ctx.getString(R.string.store_phone_list_failed, it.message))) }
            }
        }
    }

    override fun onDestroyView() {
        stopObserving?.invoke(); stopObserving = null; pageButtons.clear(); sourceHost = null
        list = null; cacheBtn = null; stateViews.clear(); super.onDestroyView()
    }

    /** Every launchable app, fleet included, as [AppInventory] JSON. */
    private fun exportTo(uri: Uri) {
        val app = requireContext().applicationContext
        thread(name = "store-export") {
            val result = runCatching {
                val entries = AppInventory.entriesFor(app, AppInventory.launchable(app))
                app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(AppInventory.toJson(entries).toByteArray()) }
                entries.size
            }
            toastLater(app, result.fold({ app.getString(R.string.store_export_done, it) },
                { app.getString(R.string.store_file_failed, it.message) }))
        }
    }

    /** Read a file, diff it against this phone, show the plan. Acts on nothing. */
    private fun importFrom(uri: Uri) {
        val app = requireContext().applicationContext
        thread(name = "store-import") {
            val text = runCatching { app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
            view?.post { if (isAdded) text.onSuccess { importText(it) }
                .onFailure { Toast.makeText(app, app.getString(R.string.store_file_failed, it.message), Toast.LENGTH_LONG).show() } }
        }
    }

    /** Diff an inventory (file or Account hand-off) against this phone, show the plan. Acts on nothing. */
    private fun importText(text: String) {
        val app = requireContext().applicationContext
        thread(name = "store-import") {
            val result = runCatching {
                val wanted = AppInventory.parse(text)
                val pm = app.packageManager
                val installed = wanted.map { it.pkg }.filter { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }.toSet()
                AppInventory.plan(wanted, installed, AppInventory.fleetPackages(), PhoneAppActions.sources(app))
            }
            view?.post {
                if (!isAdded) return@post
                result.onSuccess { StoreImport.show(this, it) }
                    .onFailure { Toast.makeText(app, app.getString(R.string.store_file_failed, it.message), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /**
     * The DECLARED list: installed launchable apps ∪ fleet apps (kind app) ∪
     * the resolver's external apps, each with its actions and its local state.
     * Sorted: shelf order, then label; unshelved last.
     */
    private fun rows(ctx: Context): List<Row> {
        val pm = ctx.packageManager
        val fleetList = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
        val fleet = PhoneAppActions.fleetByPackage(fleetList)
        val sources = PhoneAppActions.sources(ctx)
        val resolver = PhoneAppActions.resolver(sources).also { cfg = it }
        val shellReady = ShellChannels.active(ctx) != null
        val declared = LinkedHashMap<String, String>()
        declared.putAll(AppInventory.launchable(ctx))
        fleetList.filter { it.kind == "app" }.forEach { declared.putIfAbsent(it.pkg, it.label) }
        resolver.apps.values.forEach { declared.putIfAbsent(it.pkg, it.label) }
        val shelves = AppStoreHost.classify(ctx, declared)
        states.clear()
        cached.clear()
        ApkCache.entries(ctx).forEach { e ->
            val r = e.record ?: return@forEach
            // Newest cached build per package wins the row's line; the older one
            // is what eviction takes first.
            val had = cached[r.pkg]?.record
            if (had == null || r.versionCode > had.versionCode) cached[r.pkg] = e
        }
        return declared.map { (pkg, label) ->
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            val fa = fleet[pkg]
            val ext = if (fa == null) SourceResolver.resolve(resolver, pkg) else null
            val actions = if (installed) PhoneAppActions.of(ctx, pkg, fa, shellReady, sources)
                          else PhoneAppActions.forMissing(ctx, ext ?: SourceResolver.resolve(resolver, pkg), fa, sources, resolver)
            states[pkg] = when {
                ext != null -> SourceResolver.local(ctx, ext)
                installed -> SourceResolver.installed(ctx, pkg)?.let { SourceResolver.Check.Installed(it.first, it.second, null, SourceResolver.Note.NONE) }
                    ?: SourceResolver.Check.Unknown(null, "")
                else -> SourceResolver.Check.NotInstalled(SourceResolver.VIA_FLEET, needsPlay = false)
            }
            Row(pkg, label, shelves[pkg], fa, ext, installed, actions)
        }.sortedWith(compareBy({ it.shelf?.order ?: UNSHELVED }, { it.label.lowercase() }))
    }

    private fun render(ctx: Context, into: LinearLayout, rows: List<Row>) {
        stateViews.clear()
        // #619 Declared shows every row rows() built; Installed keeps only the
        // ones on the device. The declared set is never rebuilt here — it is
        // filtered, so Installed is by construction a subset of Declared.
        // #627 the tab strip is drawn from the DECLARED kinds, rebuilt on every
        // render so a change in the asset is a change on the screen with nothing
        // in between. Rows with no external ladder (fleet members) belong to no
        // store tab — they come from the constellation, not from a store, and
        // pretending otherwise would put them under whichever tab was listed
        // first.
        val kinds = cfg?.kinds.orEmpty()
        sourceHost?.let { host ->
            host.removeAllViews()
            if (kinds.isNotEmpty()) host.addView(
                StoreSourceTabs.render(ctx, kinds, sourceTab) { k ->
                    if (sourceTab?.id != k?.id) { sourceTab = k; redraw() }
                })
        }
        val tab = sourceTab
        val shown = rows
            .filter { tab == null || it.external?.inTab(tab) == true }
            .filter { !installedOnly || it.installed }
        val missing = shown.count { !it.installed }
        val play = shown.count { !it.installed && !it.direct }
        into.addView(caption(ctx, ctx.getString(R.string.store_phone_count, shown.size) + "  ·  " +
            ctx.getString(R.string.store_phone_count_missing, missing, play)))
        var heading: String? = null
        for (r in shown) {
            val here = r.shelf?.heading ?: if (heading != null) ctx.getString(R.string.store_phone_other) else null
            if (here != null && here != heading) into.addView(TextView(ctx).apply {
                text = here; textSize = StoreDensity.T_META; setTextColor(cHead)
                setPadding(0, dp(ctx, StoreDensity.S8), 0, dp(ctx, StoreDensity.S4))
            })
            heading = here
            into.addView(row(ctx, r))
        }
    }

    /** Re-render the current rows under the current filter — no enumerate, no probe. */
    private fun redraw() {
        val ctx = context ?: return
        val into = list ?: return
        into.removeAllViews()
        render(ctx, into, rows)
    }

    private fun row(ctx: Context, r: Row) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF1C1C24.toInt())
        setPadding(dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S6))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(ctx, StoreDensity.S2), 0, dp(ctx, StoreDensity.S2)) }
        addView(TextView(ctx).apply {
            text = r.label; textSize = StoreDensity.T_TITLE; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        addView(TextView(ctx).apply {
            text = r.pkg; textSize = StoreDensity.T_CAPTION; typeface = Typeface.MONOSPACE; setTextColor(cDim)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        // The state line: version / update / not installed / needs Play. Tagged
        // with the package so a test reads the rendered verdict, not this source.
        addView(TextView(ctx).apply {
            tag = STATE_TAG_PREFIX + r.pkg; textSize = StoreDensity.T_CAPTION
            stateViews[r.pkg] = this
            paint(ctx, this, states[r.pkg] ?: SourceResolver.Check.Unknown(null, ""), r)
        })
        // #625 cached-vs-installed, per row. "cached, matches what is
        // installed" is the state in which the bytes are about to be reaped;
        // "cached, NOT installed" is a download waiting for its install — the
        // thing that used to disappear.
        cached[r.pkg]?.let { e ->
            val rec = e.record ?: return@let
            val here = SourceResolver.installed(ctx, r.pkg)?.second
            addView(TextView(ctx).apply {
                tag = CACHE_TAG_PREFIX + r.pkg; textSize = StoreDensity.T_CAPTION
                val mb = (e.bytes / 1_000_000).coerceAtLeast(1L)
                text = if (here == rec.versionCode)
                    ctx.getString(R.string.store_cache_matches, rec.versionCode, mb)
                else ctx.getString(R.string.store_cache_waiting, rec.versionCode, mb, here?.toString() ?: "—")
                setTextColor(if (here == rec.versionCode) cUp else cUpd)
            })
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, StoreDensity.S4), 0, 0)
        }
        r.actions.forEach { a -> buttons.addView(btn(ctx, a) { act(ctx, r, a) }) }
        addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(buttons) })
    }

    /** One [SourceResolver.Check] → the row's state line and colour. */
    private fun paint(ctx: Context, tv: TextView, s: SourceResolver.Check, r: Row) {
        // #627 A KIND'S NAME COMES FROM THE DECLARATION, ONCE. This was a
        // `when` over three KIND_* constants mapping to three string resources —
        // a second list of the stores that exist, three lines long, and adding
        // Samsung or Aurora to the asset would have shown them on the row's
        // ladder as the empty string. The fleet is not a declared store kind, so
        // it keeps its own label; everything else is looked up.
        fun via(k: String?) = when (k) {
            null -> ""
            SourceResolver.VIA_FLEET -> ctx.getString(R.string.store_phone_source_fleet)
            else -> cfg?.kinds?.firstOrNull { it.id == k }?.label ?: k
        }
        val ladder = r.external?.sources?.joinToString(" → ") { via(it.kind) } ?: via(SourceResolver.VIA_FLEET)
        val (text, colour) = when (s) {
            is SourceResolver.Check.NotInstalled -> {
                // #627 NAME THE STORE THAT ACTUALLY HAS IT. "needs Play" is the
                // right sentence for a Play hand-off and the wrong one for an app
                // only the Galaxy Store publishes, so the store comes from the
                // app's own first hand-off rung rather than from a constant.
                val handoff = r.external?.handoff
                val text = when {
                    !s.needsPlay -> ctx.getString(R.string.store_phone_state_not_installed, ladder)
                    handoff is SourceResolver.Source.Store ->
                        ctx.getString(R.string.store_phone_state_needs_store, via(handoff.kind))
                    else -> ctx.getString(R.string.store_phone_state_needs_play,
                        ctx.getString(R.string.store_phone_badge_needs_play))
                }
                text to (if (s.needsPlay) cBadge else cMiss)
            }
            is SourceResolver.Check.Installed -> {
                val note = when (s.note) {
                    SourceResolver.Note.NONE -> if (s.via == null) "" else "  ·  " + via(s.via)
                    SourceResolver.Note.NO_FEED -> "  ·  " + ctx.getString(R.string.store_phone_note_no_feed)
                    SourceResolver.Note.PLAY_MANAGES -> "  ·  " + ctx.getString(R.string.store_phone_note_play)
                    // #627 the same honesty for every other hand-off store: it
                    // owns the update and we have no feed, said rather than
                    // rounded to "up to date".
                    SourceResolver.Note.STORE_MANAGES -> "  ·  " +
                        ctx.getString(R.string.store_phone_note_store, via(s.via))
                    SourceResolver.Note.UNDECLARED -> "  ·  " + ctx.getString(R.string.store_phone_note_undeclared)
                    SourceResolver.Note.NOT_COMPARABLE -> "  ·  " + ctx.getString(R.string.store_phone_note_not_comparable)
                }
                (ctx.getString(R.string.store_phone_state_installed, s.versionName, s.versionCode) + note) to cUp
            }
            is SourceResolver.Check.UpdateAvailable ->
                ctx.getString(R.string.store_phone_state_update, s.versionName, s.remote, via(s.via)) to cUpd
            is SourceResolver.Check.Unknown ->
                ctx.getString(R.string.store_phone_state_unknown, s.versionName ?: "—", s.reason) to cDim
        }
        tv.text = text; tv.setTextColor(colour)
    }

    /** Check all: rebuild the rows, then probe every one off the main thread. */
    private fun checkAll() = reload {
        val ctx = requireContext(); val app = ctx.applicationContext
        val resolver = cfg ?: return@reload
        for (r in rows) {
            stateViews[r.pkg]?.let { tv -> tv.text = ctx.getString(R.string.store_phone_checking); tv.setTextColor(cDim) }
            thread(name = "store-phone-check-${r.pkg}") {
                val s = runCatching {
                    r.fleetApp?.let { SourceResolver.ofFleet(Fleet.status(app, it)) }
                        ?: SourceResolver.check(app, resolver, r.external ?: SourceResolver.resolve(resolver, r.pkg))
                }.getOrElse { SourceResolver.Check.Unknown(null, it.message ?: it.javaClass.simpleName) }
                view?.post { if (isAdded) { states[r.pkg] = s; stateViews[r.pkg]?.let { paint(ctx, it, s, r) } } }
            }
        }
    }

    /** Install all: every declared app not on the phone that this store can serve itself. */
    private fun installAll() {
        val ctx = requireContext()
        val targets = rows.filter { !it.installed && it.direct }
        val play = rows.count { !it.installed && !it.direct }
        if (targets.isEmpty()) { Toast.makeText(ctx, ctx.getString(R.string.store_phone_batch_none), Toast.LENGTH_LONG).show(); return }
        Toast.makeText(ctx, ctx.getString(R.string.store_phone_install_all_start, targets.size, play), Toast.LENGTH_LONG).show()
        batch(ctx, targets)
    }

    /** Update all: every row Check all found an update for. Never runs a probe itself. */
    private fun updateAll() {
        val ctx = requireContext()
        val targets = rows.filter { states[it.pkg] is SourceResolver.Check.UpdateAvailable && it.direct }
        if (targets.isEmpty()) { Toast.makeText(ctx, ctx.getString(R.string.store_phone_update_none), Toast.LENGTH_LONG).show(); return }
        Toast.makeText(ctx, ctx.getString(R.string.store_phone_batch_start, targets.size), Toast.LENGTH_SHORT).show()
        batch(ctx, targets)
    }

    /**
     * #625 DOWNLOAD THEM ALL, THEN INSTALL THEM ONE BY ONE.
     *
     * This used to be one loop calling [installOne] per row — resolve,
     * download, install, next — so every confirmation dialog was followed by a
     * wait on the next app's network fetch, and a batch interrupted half way
     * had installed some apps and not even downloaded the rest. [BatchInstall]
     * is the ONE engine that owns the ordering (the same two-phase shape
     * [Fleet.installAllLocked] already used for the constellation pass), so
     * this fragment cannot hold a different opinion about it.
     *
     * Installs stay strictly sequential inside phase 2 — each one may raise the
     * system confirm sheet — and a download failure costs only its own app.
     */
    private fun batch(ctx: Context, targets: List<Row>) {
        val app = ctx.applicationContext
        val resolver = cfg ?: PhoneAppActions.resolver(PhoneAppActions.sources(app))
        thread(name = "store-phone-batch") {
            val outcomes = BatchInstall.run(
                app,
                targets.map { BatchInstall.Target(it.pkg, it.label, it.fleetApp, it.external) },
                BatchInstall.engine(resolver)
            ) { phase, t, i, n ->
                UpdateProgress.beginBatch(
                    (if (phase == BatchInstall.Phase.DOWNLOAD) "\u2193 " else "") + t.label, i, n)
            }
            UpdateProgress.endBatch()
            // Per-app, never a bare count: a failure you cannot name is a
            // failure nobody can act on. The cached bytes of a failed install
            // are still on disk, and the row's cache line now says so.
            val failed = outcomes.filter { !it.installed }
            failed.forEach { o ->
                toastLater(app, app.getString(R.string.store_phone_failed, o.target.label,
                    o.message ?: app.getString(R.string.store_cache_kept)))
            }
            toastLater(app, app.getString(R.string.store_phone_batch_done,
                outcomes.size - failed.size, failed.size))
            view?.post { if (isAdded) reload() }
        }
    }

    /**
     * #666 the user's own "clear cache", in two tiers so that a tap can never be
     * the thing that loses a download.
     *
     * Tier one takes only the bytes [ApkCache.clearRedundant] can PROVE are
     * redundant — the installed package hashes to them. Whatever is left is the
     * only copy of something whose install is not proven (a failed install, a
     * download interrupted mid-update), so it is kept and the toast says, in
     * real counts and megabytes, that a second tap discards it. Tier two runs
     * only on that second tap. #625's data loss must not come back through this
     * button.
     */
    private fun clearCache() {
        val app = requireContext().applicationContext
        val discard = armedToDiscard
        thread(name = "store-cache-clear") {
            val e = if (discard) ApkCache.clear(app) else ApkCache.clearRedundant(app)
            val left = if (discard) ApkCache.Plan(emptyList(), emptyList()) else ApkCache.plan(app)
            toastLater(app, when {
                discard -> app.getString(R.string.store_cache_discarded,
                    e.deleted.size, e.freedBytes / 1_000_000)
                left.kept.isEmpty() -> app.getString(R.string.store_cache_cleared,
                    e.deleted.size, e.freedBytes / 1_000_000)
                // #233: a surface that cannot act must say so. Name the count and
                // the megabytes, and name what a second tap would cost.
                else -> app.getString(R.string.store_cache_some_kept,
                    e.deleted.size, e.freedBytes / 1_000_000,
                    left.kept.size, left.keptBytes / 1_000_000)
            })
            view?.post {
                if (!isAdded) return@post
                armedToDiscard = left.kept.isNotEmpty()
                reload()
            }
        }
    }

    /** #666 the clear-cache label, measured. Off the main thread — deciding
     *  redundancy hashes every cached APK and the installed package beside it. */
    private fun refreshCacheLabel() {
        val app = context?.applicationContext ?: return
        val btn = cacheBtn ?: return
        val armed = armedToDiscard
        thread(name = "store-cache-plan") {
            val p = runCatching { ApkCache.plan(app) }.getOrNull() ?: return@thread
            // An install that landed since the warning turns an unproven entry
            // redundant, and then there is nothing left to warn about: disarm,
            // rather than offer to discard nothing.
            val stillArmed = armed && p.kept.isNotEmpty()
            val free = if (stillArmed) p.keptBytes else p.redundantBytes
            val count = if (stillArmed) p.kept.size else p.redundant.size
            btn.post {
                if (!isAdded) return@post
                armedToDiscard = stillArmed
                btn.text = when {
                    stillArmed -> app.getString(R.string.store_cache_clear_discard, count, free / 1_000_000)
                    count == 0 -> app.getString(R.string.store_cache_clear_empty)
                    else -> app.getString(R.string.store_cache_clear_n, count, free / 1_000_000)
                }
            }
        }
    }

    /** The ONE path per kind. Blocking. */
    private fun installOne(app: Context, r: Row): String? {
        val resolver = cfg ?: PhoneAppActions.resolver(PhoneAppActions.sources(app))
        // Not `fleetApp?.let { } ?: external`: a fleet install that SUCCEEDS
        // returns null, and that elvis would have gone on to run the external path.
        val fleetApp = r.fleetApp
        return if (fleetApp != null) FleetInstall.run(app, fleetApp)
               else ExternalInstall.run(app, resolver, r.external ?: SourceResolver.resolve(resolver, r.pkg))
    }

    private fun act(ctx: Context, r: Row, a: PhoneAppActions.Action) {
        a.disabledReason?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); return }
        when (a.kind) {
            PhoneAppActions.Kind.INSTALL, PhoneAppActions.Kind.UPDATE -> {
                Toast.makeText(ctx, ctx.getString(
                    if (a.kind == PhoneAppActions.Kind.INSTALL) R.string.store_phone_installing else R.string.store_phone_updating, r.label),
                    Toast.LENGTH_SHORT).show()
                val app = ctx.applicationContext
                thread(name = "store-phone-install-${r.pkg}") {
                    installOne(app, r)?.let { msg -> toastLater(app, app.getString(R.string.store_phone_failed, r.label, msg)) }
                    view?.post { if (isAdded) reload() }
                }
            }
            PhoneAppActions.Kind.STOP -> if (SelfStop.isSelf(ctx, r.pkg)) SelfStop.stop(ctx, activity) else thread(name = "store-phone-stop") {
                val out = PhoneAppActions.forceStop(ctx, r.pkg)
                toastLater(ctx, if (out?.contains("OK") == true) ctx.getString(R.string.store_phone_stopped, r.label)
                                else ctx.getString(R.string.store_phone_failed, r.label, out?.trim() ?: ctx.getString(R.string.store_phone_why_no_shell)))
            }
            else -> a.intent?.let { i ->
                runCatching { startActivity(i) }
                    .onFailure { Toast.makeText(ctx, ctx.getString(R.string.store_phone_failed, a.label, it.message), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun toastLater(ctx: Context, msg: String) =
        view?.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }

    private fun dp(ctx: Context, v: Int) = StoreDensity.dp(ctx, v)
    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = StoreDensity.T_META; setTextColor(cDim); setPadding(0, 0, 0, dp(ctx, StoreDensity.S8))
    }
    /** Dimmed, not hidden, when it cannot work here: the reason is one tap away. */
    private fun btn(ctx: Context, a: PhoneAppActions.Action, onClick: () -> Unit) = TextView(ctx).apply {
        text = a.label; textSize = StoreDensity.T_META; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF2A2A33.toInt())
        alpha = if (a.disabledReason == null) 1f else 0.4f
        contentDescription = a.disabledReason?.let { "${a.label}: $it" } ?: a.label
        setPadding(dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(ctx, StoreDensity.S4), 0) }
        minHeight = StoreDensity.minTap(ctx)
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun fileBtn(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = StoreDensity.T_META; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xFF2B6CB0.toInt())
        setPadding(dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6), dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S6))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(ctx, StoreDensity.S2), dp(ctx, StoreDensity.S2), dp(ctx, StoreDensity.S2), dp(ctx, StoreDensity.S4)) }
        minHeight = StoreDensity.minTap(ctx)
        isClickable = true; setOnClickListener { onClick() }
    }

    companion object {
        /** The state line of a row is tagged [STATE_TAG_PREFIX] + package. */
        const val STATE_TAG_PREFIX = "store-phone-state:"
        /** #625 the cached-vs-installed line, tagged [CACHE_TAG_PREFIX] + package.
         *  Absent when nothing for that package is in the cache — a test reads
         *  the rendered answer rather than this source. */
        const val CACHE_TAG_PREFIX = "store-phone-cache:"
        /** The build.json::ui section id this page is, and its two child pages (#896). */
        const val SECTION = "phone"
        const val PAGE_INSTALLED = "installed"
        const val PAGE_DECLARED = "declared"
        private const val UNSHELVED = "￿"
        private const val EXPORT_NAME = "cloud-sa-apps.json"
        // A .json picked from Downloads is as often octet-stream as json.
        private val IMPORT_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")
    }
}
