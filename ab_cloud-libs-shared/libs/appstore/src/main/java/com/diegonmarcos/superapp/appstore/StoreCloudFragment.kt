package com.diegonmarcos.superapp.appstore

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.FleetIdentity
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Store ▸ Cloud Constellation (#563) — the page that used to BE the store
 * ("Constellation AppStore"), now the first of Store's two subpages. superapp
 * is the fleet manager: install / update / uninstall / open every
 * constellation APK. Its sibling [StorePhoneFragment] covers every APK that is
 * NOT in the fleet.
 *
 * IDENTITY vs TITLE. "Constellation" survives here as a display word only —
 * the subpage's label, which build.json declares beside its id. Nothing that
 * ROUTES to this page (class, page id, target) says constellation; the
 * fleet's own names (constellation-fleet.json, CONSTELLATION_DATA,
 * [ConstellationWorker]) are the fleet's identity, not the store's, and
 * stay. test-store-identity.sh holds that line.
 *
 * Each app's status is fetched on its OWN thread (concurrently), so one slow or
 * unreachable image never blocks the others — the previous single-thread loop
 * was why Dialer showed no status and unpublished Chat looked "stuck". Fleet
 * list is data-driven from BuildConfig.CONSTELLATION_FLEET_B64.
 */
class StoreCloudFragment : Fragment() {

    private val fleet by lazy { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }

    /** One tab: a group the fleet data declares, and every row drawn in it. */
    private class Tab(val id: String, val label: String, val blurb: String, val rows: List<Fleet.App>)

    // The same baked JSON Fleet.parse reads, for the three things that are this
    // page's business and not the updater's: the declared groups, the members
    // of each, and the reference catalogue. Read here rather than added to
    // Fleet.App, so no updater, worker or grant path ever sees a reference row.
    private val fleetJson by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.CONSTELLATION_FLEET_B64, Base64.DEFAULT))) }
            .getOrElse { JSONObject() }
    }
    private fun fleetObjects(key: String): List<JSONObject> =
        fleetJson.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.orEmpty()

    private val catalogue by lazy { fleetObjects("catalogue").associateBy { it.getString("id") } }
    // Third-party reference rows: no APK, package or asset. Built BLOCKED unless
    // the data says installable, so every row, chip and batch path treats them the
    // way it already treats an unpublished app - and Fleet.installAllLocked drops
    // a blocked entry before it checks status, so no batch ever fetches one.
    private val references by lazy {
        catalogue.values.map { entry ->
            Fleet.App(id = entry.getString("id"), label = entry.optString("label", entry.getString("id")),
                pkg = "", altId = null, registry = "", namespace = "", image = "", tag = "",
                asset = "", assets = emptyMap(), releaseUrl = "", repoUrl = "", ghcrPage = "",
                blocked = !entry.optBoolean("installable", false), kind = "",
                // A catalogue row's `version` IS our version of it: the content
                // address regen.sh computed from the tree (#618/#624). Carried
                // so the one identity pattern has a real version to print here
                // too, instead of the marker for a row that does declare one.
                declaredVersionName = entry.optString("version").takeIf { it.isNotEmpty() })
        }
    }
    // #405: an ML lib states its application in its own id -
    // lib-ml-{t|l}-{domain}-{name}, where domain is voice, text, image, sensor
    // or tabular. The ML tabs group by that, so the heading a row is drawn under
    // is READ OFF THE ROW and there is no second list of applications to keep in
    // step with the names. A row that is not an ML lib has no application; every
    // such row sits in one unnamed run, which is every tab but the two ML ones.
    private val mlApplication = Regex("^lib-ml-[tl]-([a-z0-9]+)-")
    private fun applicationOf(id: String): String? =
        mlApplication.find(id)?.groupValues?.get(1)

    // #563: every other row is grouped by the host's CENTRAL classification -
    // the section and folder the launcher's All Apps files the same package
    // under (cloud-drive -> "Tools · Data Apps / Storage"). Asked once, as one
    // batch, of the host; this page holds no taxonomy of its own, because
    // #170/#102/#405 each deleted a second copy of exactly that.
    private val shelves by lazy {
        AppStoreHost.classify(requireContext(),
            fleet.filter { it.pkg.isNotEmpty() }.associate { it.pkg to it.label })
    }

    // #642: THE LIBS TAB'S TABLES, and not a second grouping mechanism.
    //
    // An APP is filed by [shelves] above — the launcher's central
    // classification — which is why Cloud ▸ Apps has always drawn headed
    // tables. A LIB has no launcher entry, so that classifier returns nothing
    // for every one of them and all 46 rows fell into ONE unnamed run: the
    // flat list. So the fleet data declares each lib's category as the SAME
    // (heading, order) pair, and [shelfOf] hands it to the code that already
    // draws the Apps tables. Nothing below this line knows a lib from an app.
    //
    // DATA, both directions: the categories, their order and their membership
    // are `lib_apks.categories` in ab_cloud-libs-shared/lib-apks/build.json,
    // carried here by regen.sh in the same {id,label,members} shape as the
    // groups. Adding a lib or a whole category is that edit and nothing else —
    // no label, id or count is written here. A lib no category lists has no
    // shelf and heads "Other", exactly like an app the launcher does not file.
    private val libShelves: Map<String, AppStoreHost.Shelf> by lazy {
        val filed = HashMap<String, AppStoreHost.Shelf>()
        fleetObjects("lib_categories").forEachIndexed { index, category ->
            // The ORDER key is the declaration's own index, zero-padded so it
            // still sorts as a string beside the host's folder orders.
            val shelf = AppStoreHost.Shelf(
                category.optString("label", category.getString("id")), "%03d".format(index))
            val members = category.optJSONArray("members")
            for (i in 0 until (members?.length() ?: 0))
                members?.optString(i)?.takeIf { it.isNotEmpty() }?.let { filed[it] = shelf }
        }
        filed
    }

    /** The shelf a row is filed on — its declared lib category, else the host's
     *  classification of its package. ONE lookup, so the heading drawn over a
     *  run and the key that orders it cannot read different answers. */
    private fun shelfOf(app: Fleet.App): AppStoreHost.Shelf? =
        libShelves[app.id] ?: shelves[app.pkg]

    /** The heading a row is drawn under: its ML application (#405) when its
     *  name declares one, else its shelf, else none. */
    private fun headingOf(app: Fleet.App): String? =
        applicationOf(app.id)?.replaceFirstChar { it.uppercase() } ?: shelfOf(app)?.heading

    // Tabs are a VIEW over the fleet: one per group data/regen.sh declares, in
    // its declared order, holding the members it lists and skipping a group with
    // no rows - never a hardcoded list here. Sorted at this single point rather
    // than at each call site: the rows, the detail pane and the copy dump all
    // read these lists, so ordering here orders the whole page. Heading first
    // (ML application, else the shelf's declared order; unshelved rows last),
    // then display name (#334), so each heading is one contiguous run and
    // renderList can head it with a single pass and no regrouping.
    private val tabs by lazy {
        val everyRow = (fleet + references).associateBy { it.id }
        fleetObjects("groups").map { group ->
            val members = group.optJSONArray("members")
            val rows = (0 until (members?.length() ?: 0))
                .mapNotNull { index -> members?.optString(index)?.let(everyRow::get) }
            Tab(group.getString("id"), group.optString("label", group.getString("id")), group.optString("blurb"),
                rows.sortedWith(compareBy(
                    { applicationOf(it.id) ?: shelfOf(it)?.order ?: UNSHELVED },
                    { it.label.lowercase() })))
        }.filter { it.rows.isNotEmpty() }
    }

    // #732 how every tab, page entry and action button looks: assets/appstore-controls.json.
    private val controls by lazy { StoreControls.load(requireContext()) }

    private val statusViews = HashMap<String, TextView>()
    // The collapsed row shows a one-line summary; the full status line lives in
    // the detail pane, so both need painting from the same state.
    private val fullStatusViews = HashMap<String, TextView>()
    private val dots = HashMap<String, TextView>()
    /** #831 each row's own error area (full reason + DNS button), under the row. */
    private val errBoxes = HashMap<String, LinearLayout>()
    private val quickBtns = HashMap<String, TextView>()
    // Last known state per app, kept so the filter chips can re-slice the list
    // WITHOUT re-hitting the network - re-checking 24 libs to hide 21 of them
    // would make filtering slower than scrolling.
    private val states = HashMap<String, Fleet.State>()
    private val expanded = HashSet<String>()
    private var filter = 0
    private lateinit var summaryView: TextView
    private lateinit var listHost: LinearLayout

    // ── live download / install progress ─────────────────────────────────────
    // Sits under the buttons that start the work and above the summary line that
    // describes the result: what the fleet is doing RIGHT NOW. Before this, the
    // page went quiet the moment you pressed Check all / Update all, and the only
    // place with an answer was the shell's overlay — which is not this screen, and
    // is not there at all when a satellite app hosts the page.
    private var progressRow: LinearLayout? = null
    private var progressIcon: ImageView? = null
    private var progressLabel: TextView? = null
    private var progressBar: ProgressBar? = null
    /** #894 the one writer of the bar's state: monotonic per item (see [ProgressBarModel]). */
    private val barModel = ProgressBarModel()
    private var progressCancel: TextView? = null

    /**
     * Attached with [UpdateProgress.addObserver], never setListener: that slot is
     * the shell overlay's, and taking it would turn the overlay off for as long as
     * this page is open. The pipeline posts from its worker thread, so hop to the
     * view's looper before touching anything — but describe it HERE, on the
     * thread that published it, so the line pairs this state with the job that
     * was running when it was published, not whatever job runs by the time the
     * looper gets to it.
     */
    private val progressObserver: (UpdateProgress.State) -> Unit = { state ->
        val p = StoreStages.progress(state)
        progressRow?.post { renderProgress(state, p) }
    }
    private val filterChips = ArrayList<TextView>()
    private val actionRows = HashMap<String, LinearLayout>()
    // #774 the Download / Install / Clear buttons per app, and the stage that
    // decides which of them are live. StoreStages owns the logic; this only draws.
    private val stageBtns = HashMap<String, MutableMap<String, TextView>>()
    private val stages = HashMap<String, StoreStages.Stage>()
    private lateinit var headerControls: LinearLayout
    private lateinit var body: LinearLayout
    private val tabBtns = ArrayList<TextView>()
    private var tab = 0

    // amber, green, grey, red, orange, blue
    private val cUp = 0xFF48BB78.toInt(); private val cUpd = 0xFFED8936.toInt()
    private val cMiss = 0xFF63B3ED.toInt(); private val cBlk = 0xFFF56565.toInt()
    private val cErr = 0xFFECC94B.toInt(); private val cDim = 0x99FFFFFF.toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, StoreDensity.S12); setPadding(p, p, p, p)
        }
        scroll.addView(col)

        // No title line: the host's toolbar names the page ("Store") and its
        // tab strip names this subpage, both from their one declaration in
        // build.json. A third copy here is how the old "Constellation
        // AppStore" heading outlived the rename it described.
        col.addView(caption(ctx,
            (tabs.map { "${it.rows.size} ${it.label}" } + "superapp is the fleet manager").joinToString(" · ")))

        col.addView(tabBar(ctx))
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)
        renderTab(ctx)
        // THE ONLY PLACE Fleet.downgradePolicy IS EVER ASSIGNED.
        //
        // #496: Fleet.commit refuses a downgrade unconditionally by default —
        // every caller that never touches this var keeps that exact
        // behaviour (background workers, other constellation-hosting apps
        // that never open this page). Assigning it here, scoped to exactly
        // while this page is visible, is deliberate: a policy that shows a
        // dialog needs a live Activity to show it on, and one that outlived
        // this fragment would fire from a LATER background pass with no
        // screen to draw on — see confirmDowngrade's own fail-closed note.
        Fleet.downgradePolicy = Fleet.DowngradePolicy { app, candidateCode, installedCode ->
            confirmDowngrade(app, candidateCode, installedCode)
        }
        return scroll
    }

    /** The observer holds a view; leaving it attached would outlive the view tree. */
    /** #812 Opening the Store is reading the badge: it clears. A later chain
     *  that still finds updates pending posts it again. */
    override fun onResume() {
        super.onResume()
        if (::headerControls.isInitialized) StoreBar.onResume(headerControls)
        context?.let { c -> runCatching { StoreAuto.onPending(c.applicationContext, 0) } }
        // #858 back in front: resolve handed-over installs and show a prompt
        // that is still pending again, then repaint the rows.
        context?.applicationContext?.let { c ->
            thread(name = "store-install-watch") {
                // Repaint only when a handover was resolved: the first resume
                // must not jump ahead of #857's updates-first refresh.
                if (runCatching { StoreInstallWatch.onForeground(c, fleet) }.getOrDefault(false))
                    view?.post { if (isAdded && ::body.isInitialized) checkAll(c, current()) }
            }
        }
    }

    override fun onDestroyView() {
        UpdateProgress.removeObserver(progressObserver)
        progressRow = null; progressIcon = null; progressLabel = null; progressBar = null; progressCancel = null
        // Restore the unconditional-refuse default the moment this page is
        // no longer visible. Any downgrade a background pass hits after this
        // must be refused, not asked — there is nothing left to ask it on.
        Fleet.downgradePolicy = Fleet.DowngradePolicy { _, _, _ -> false }
        super.onDestroyView()
    }

    /**
     * THE DOWNGRADE GATE'S UI, and the only place that shows one.
     *
     * Called on [Fleet.commit]'s caller thread — always a background
     * `fleet-install-*`/`fleet-update-all` thread here, never main. Posts the
     * confirmation to the main thread and BLOCKS this one on a latch until
     * the human answers, because [Fleet.DowngradePolicy.allowDowngrade] is a
     * synchronous question: [Fleet.commit] cannot proceed past it either way
     * without an answer.
     *
     * FAIL CLOSED. If the activity is gone (the app was backgrounded or
     * killed mid-install) there is nothing to show and nobody to ask, so this
     * refuses rather than guessing — the same "no answer means no" the
     * unconditional default already enforces for every other caller.
     */
    private fun confirmDowngrade(app: Fleet.App, candidateCode: Long, installedCode: Long): Boolean {
        val act = activity ?: return false
        if (!isAdded) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        // No @Volatile needed: CountDownLatch's await()/countDown() pair
        // already establishes happens-before, same as any other latch-guarded
        // handoff between threads.
        var proceed = false
        act.runOnUiThread {
            if (!isAdded) { latch.countDown(); return@runOnUiThread }
            AlertDialog.Builder(act)
                .setTitle("Downgrade — ${app.label}")
                .setMessage(
                    "This moves the device BACKWARDS:\n\n" +
                    "  installed   versionCode $installedCode\n" +
                    "  available   versionCode $candidateCode\n\n" +
                    "The available build is OLDER than what is on this device. Proceeding " +
                    "installs it anyway. Refusing is the safe choice and is what happens by " +
                    "default — only continue if you mean to roll back.")
                .setCancelable(false)
                .setNegativeButton("Cancel (recommended)") { d, _ -> proceed = false; d.dismiss(); latch.countDown() }
                .setPositiveButton("Install anyway") { d, _ -> proceed = true; d.dismiss(); latch.countDown() }
                .show()
        }
        latch.await()
        return proceed
    }

    // ── tabs: one per declared group ──────────────────────────────────────────
    /**
     * A declared group is a TYPE OF APK: Apps, Libs, Lite-ML, Tiny-ML partition the fleet, and
     * picking one narrows what the page is showing you, so they are the segmented `tab` style (#671/#732).
     * The Commits and CI-CD feeds, Perms and Apps Mesh used to be a second line of page chips; they
     * are bottom-nav pages now (#896 Feed, #896.3 Access), so this page is one line.
     *
     * The strip itself is [StoreTabs.bar], the same builder Phone draws with. WHICH TABS EXIST is
     * not written here: it is the declared groups.
     */
    private fun tabBar(ctx: Context): View = StoreTabs.bar(ctx, listOf(
        tabs.map { StoreControls.Control(it.label, "", controls.groupTab) }), tabBtns) { index ->
        if (tab != index) { tab = index; filter = 0; paintTabs(); renderTab(ctx) }
    }

    private fun paintTabs() = StoreTabs.paint(tabBtns, tab)

    private fun renderTab(ctx: Context) {
        body.removeAllViews()
        statusViews.clear(); actionRows.clear(); stageBtns.clear()
        fullStatusViews.clear(); dots.clear(); errBoxes.clear(); quickBtns.clear(); filterChips.clear()
        // Each blurb is data beside its group, so the caption naming the out-of-process engines moves
        // with the engines. (#896.3: Apps Mesh left this page for Access, so every tab is a group.)
        val shown = tabs.getOrNull(tab) ?: return
        renderFleet(ctx, shown.rows, shown.blurb, shown.id == libConsumers.optString("group"))
    }

    /** A fleet app's detail (from the progress row): its group's tab, the row expanded and
     *  scrolled into view. An app no tab holds has no row to open. */
    private fun openDetail(ctx: Context, app: Fleet.App) {
        val target = tabs.indexOfFirst { t -> t.rows.any { it.id == app.id } }
        if (target < 0) return
        tab = target; filter = 0; expanded.add(app.id)
        paintTabs(); renderTab(ctx)
        val sv = view as? ScrollView ?: return
        val row = dots[app.id] ?: return
        sv.post {
            var y = 0; var v: View? = row
            while (v != null && v !== sv) { y += v.top; v = v.parent as? View }
            sv.smoothScrollTo(0, y)
        }
    }

    private fun renderFleet(ctx: Context, list: List<Fleet.App>, blurb: String, hasLibSections: Boolean = false) {
        body.addView(caption(ctx, blurb))
        headerControls = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(headerControls)
        renderHeader(ctx)
        body.addView(progressPanel(ctx))
        if (list.isEmpty()) { body.addView(caption(ctx, "Nothing here yet.")); return }
        summaryView = TextView(ctx).apply {
            textSize = StoreDensity.T_META; setTextColor(cDim); setPadding(0, dp(ctx, StoreDensity.S2), 0, dp(ctx, StoreDensity.S6))
        }
        // The Libs tab has two sections: the installable engines (this list, with
        // who binds each) and, below it, the libs that only compile in (info rows).
        libSplit = hasLibSections
        if (hasLibSections) body.addView(sectionHeading(ctx, "Runtime engines — installable APKs"))
        body.addView(summaryView)
        body.addView(filterBar(ctx, list))
        listHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listHost)
        renderList(ctx, list)
        if (hasLibSections) renderBuildTimeLibs(ctx)
        // #857/#861 Wi-Fi + Auto-update: pending updates download on their own
        // max-priority queue (appstore-priority.json), fully async. The check
        // never waits on it: it runs now, always, and always settles.
        StorePriority.startUpdatesAsync(ctx, fleet)
        checkAll(ctx, list)
    }

    /** Filter chips. With two dozen libs the answer to "too much scrolling" is
     *  to stop scrolling: pick the slice you came for. Counts come from the
     *  cached [states], so a chip is instant and never re-checks. */
    private fun filterBar(ctx: Context, list: List<Fleet.App>): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(ctx, StoreDensity.S6)) }
        }
        filterChips.clear()
        // #793 the shared chip (StoreBar.chip), the same one Apps Mesh filters with.
        listOf("All", "⬆ Updates", "◯ Missing", "✓ Installed").forEachIndexed { i, label ->
            val c = StoreBar.chip(ctx, controls.filter, label, i == 0) {
                if (filter != i) { filter = i; paintFilter(); renderList(ctx, list) }
            }
            filterChips.add(c); bar.addView(c)
        }
        paintFilter()
        return bar
    }

    private fun paintFilter() = filterChips.forEachIndexed { i, c -> StoreBar.paint(c, i == filter) }

    /** True when [app] belongs in the current filter. An app whose state has not
     *  landed yet only shows under "All" - guessing would flicker it in and out. */
    private fun inFilter(app: Fleet.App): Boolean = when (filter) {
        1 -> states[app.id] is Fleet.State.UpdateAvailable
        2 -> states[app.id] is Fleet.State.Missing
        3 -> states[app.id] is Fleet.State.Installed
        else -> true
    }

    /** True on the page that carries the two lib sections: ML rows are then drawn apart from the rest. */
    private var libSplit = false

    private fun renderList(ctx: Context, list: List<Fleet.App>) {
        listHost.removeAllViews()
        statusViews.clear(); actionRows.clear(); stageBtns.clear()
        fullStatusViews.clear(); dots.clear(); errBoxes.clear(); quickBtns.clear()
        val shown = list.filter { inFilter(it) }
        if (shown.isEmpty()) { listHost.addView(caption(ctx, "Nothing in this filter.")); return }
        // One heading per run of rows sharing a heading. The list is already
        // sorted by heading, so a change of heading is the only place one can
        // belong. Unshelved rows sort last; once a list has had headings they
        // get their own "Other" so they do not read as part of the run above.
        var heading: String? = null
        // Don't mix ML libs with the others: on the Libs page the ML engines (the ml- rule
        // applicationOf already reads off the id) form their own run under their own header.
        val (ml, plain) = if (libSplit) shown.partition { applicationOf(it.id) != null } else emptyList<Fleet.App>() to shown
        for (app in plain + ml) {
            if (ml.isNotEmpty() && app === ml.first()) {
                listHost.addView(sectionHeading(ctx, "Machine-learning engines"))
                heading = null
            }
            val here = headingOf(app) ?: if (heading != null) OTHER else null
            if (here != null && here != heading) listHost.addView(applicationHeading(ctx, here))
            heading = here
            listHost.addView(fleetRow(ctx, app))
        }
        // Repaint from cache so a filtered rebuild shows real state immediately
        // instead of 24 rows saying "checking..." for a list already checked.
        for (app in shown) states[app.id]?.let { paint(app.id, it, stages[app.id]) }
        updateSummary(list)
    }

    private fun updateSummary(list: List<Fleet.App>) {
        if (!::summaryView.isInitialized) return
        val known = list.mapNotNull { states[it.id] }
        val upd = known.count { it is Fleet.State.UpdateAvailable }
        val miss = known.count { it is Fleet.State.Missing }
        val bytes = known.sumOf { it.bytes }
        summaryView.text = buildString {
            append("${list.size} total")
            if (known.size < list.size) append("  ·  ${list.size - known.size} checking")
            if (upd > 0) append("  ·  $upd update${if (upd == 1) "" else "s"}")
            if (miss > 0) append("  ·  $miss missing")
            if (bytes > 0) append("  ·  ${human(bytes)}")
        }
    }

    // ── live progress, under the buttons ─────────────────────────────────────

    /** The row itself. Built once per render pass and hidden until there is work. */
    private fun progressPanel(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, StoreDensity.S6), 0, dp(ctx, StoreDensity.S4))
            visibility = View.GONE
            tag = StoreBar.PROGRESS_TAG
        }
        // #785 WHICH app: its launcher icon (when it is on the device — the
        // PackageManager already has it, so it costs one lookup) beside the line.
        val icon = ImageView(ctx).apply { visibility = View.GONE }
        val label = TextView(ctx).apply { textSize = StoreDensity.T_META; setTextColor(cUpd) }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon, LinearLayout.LayoutParams(dp(ctx, StoreDensity.GLYPH), dp(ctx, StoreDensity.GLYPH)).apply { marginEnd = dp(ctx, StoreDensity.S6) })
        head.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Horizontal style = a real determinate bar; the default is the spinner,
        // which cannot show a percentage.
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        // Cancel. The machinery was already here and already correct —
        // UpdateProgress.cancelRequested is polled by both download loops in
        // ApkSource and by both phases of Fleet.installAll, and the shell
        // overlay has driven it through Updater.cancelNow all along. This row
        // simply never offered the button, so a download started from the
        // table could only be waited out.
        val cancel = btn(ctx, "Cancel", 0xFF4A4A55.toInt()) {
            Updater.cancelNow(requireContext())
        }.apply { visibility = View.GONE }

        row.addView(head)
        row.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, StoreDensity.S6)).apply {
            topMargin = dp(ctx, StoreDensity.S4)
        })
        row.addView(cancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, StoreDensity.S6)
            gravity = android.view.Gravity.END
        })
        progressRow = row; progressIcon = icon; progressLabel = label; progressBar = bar
        progressCancel = cancel
        // Re-attaching on every render would stack observers, so drop the old one
        // first — the field is the same lambda instance for the fragment's life.
        UpdateProgress.removeObserver(progressObserver)
        UpdateProgress.addObserver(progressObserver)
        return row
    }

    /**
     * #785 Draws [StoreStages.progress] — the SAME line `/api/store/progress`
     * returns, so the screen and the API cannot disagree. It names the app, its
     * version and the stage (downloading / verifying / installing / clearing),
     * bytes and %, and in a batch the position and what is next; a failure names
     * app + stage + reason. A tap jumps to that app's row.
     *
     * The states keep their old rules: an unknown size is an indeterminate bar
     * and never a hard 0% (identical, to the person watching, to a stalled
     * transfer); a failure is never hidden (silence about work that did not
     * happen is hiding, not quietness); a Cancel the user asked for is not a
     * failure to report, so the row goes; and the Done/Idle dip between two apps
     * of a batch keeps the row up instead of flickering it out per app.
     */
    /** The only place the bar is written. Touches a property only when it changes: swapping the
     *  indeterminate and determinate drawables is itself what shows as a flash. */
    private fun drawBar(bar: ProgressBar, d: ProgressBarModel.Draw) {
        if (bar.isIndeterminate != d.indeterminate) bar.isIndeterminate = d.indeterminate
        if (!d.indeterminate && bar.progress != d.percent) bar.progress = d.percent
    }

    private fun renderProgress(state: UpdateProgress.State, p: StoreStages.Progress?) {
        val row = progressRow ?: return
        val label = progressLabel ?: return
        val bar = progressBar ?: return
        if (state is UpdateProgress.State.Cancelled) {
            barModel.reset()
            UpdateProgress.reset()
            progressCancel?.visibility = View.GONE
            row.visibility = View.GONE
            return
        }
        if (p == null) { barModel.reset(); row.visibility = View.GONE; return }
        // #831 a failure's reason is long and this line is one slot above the
        // list: name the app and point at its row, where the reason is whole.
        label.text = if (p.failed) StoreRowError.banner(p.app) else p.text
        label.setTextColor(if (p.failed) cBlk else cUpd)
        drawBar(bar, barModel.step(p.appId.ifEmpty { p.pkg }, p.bytes, p.percent, p.failed))
        val icon = p.pkg.takeIf { it.isNotEmpty() }?.let { pkg ->
            runCatching { row.context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        }
        progressIcon?.apply { setImageDrawable(icon); visibility = if (icon != null) View.VISIBLE else View.GONE }
        val target = fleet.firstOrNull { it.id == p.appId || (p.pkg.isNotEmpty() && it.pkg == p.pkg) }
        row.setOnClickListener { target?.let { openDetail(row.context, it) } }
        row.isClickable = target != null
        // Offer Cancel only while something is actually cancellable. Failed
        // has already stopped, and the batch-gap between apps is not a job of
        // its own.
        progressCancel?.visibility = when (state) {
            is UpdateProgress.State.Downloading,
            is UpdateProgress.State.CheckingManifest,
            is UpdateProgress.State.Installing -> View.VISIBLE
            else -> View.GONE
        }
        row.visibility = View.VISIBLE
    }

    // ── header: batch actions + configs, two rows ────────────────────────────
    /** The Store's one top bar (#565), shared with Phone Apps through
     *  [StoreBar]; only the three batch verbs are this page's. Update all is
     *  `fleet`, which Fleet.parse built from `apps` alone: reference rows are
     *  not in it, so the pass cannot fetch or fail on one. */
    private fun renderHeader(ctx: Context) = StoreBar.render(this, headerControls, StoreBar.Verbs(
        checkAll = { checkAll(ctx) },
        installAll = { installMissing(ctx) },
        updateAll = { updateAll(ctx, "the whole fleet", fleet) },
        downloadAll = { downloadAll(ctx, fleet) },
    ))

    // ── one COLLAPSED row per app; the full card is one tap away ─────────────
    // Seven lines per entry x 24 libs was twelve screens of scrolling. The row
    // keeps what you scan by (state, name, version, size) and defers what you
    // only ever inspect (package, image, sha, links, permissions) into a detail
    // pane. Nothing is dropped - it moves behind the chevron.
    private fun fleetRow(ctx: Context, app: Fleet.App): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1C1C24.toInt())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(ctx, StoreDensity.S2), 0, dp(ctx, StoreDensity.S2)); layoutParams = lp
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S8))
            isClickable = true
        }
        val dot = TextView(ctx).apply {
            text = "·"; textSize = StoreDensity.T_BODY; setTextColor(cDim); setPadding(0, 0, dp(ctx, StoreDensity.S8), 0)
        }
        val name = TextView(ctx).apply {
            text = app.label; textSize = StoreDensity.T_TITLE; setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val meta = TextView(ctx).apply { text = "checking…"; textSize = StoreDensity.T_CAPTION; setTextColor(cDim); maxLines = 1 }
        // The per-app action, on the collapsed row on purpose: updating ONE app
        // is the common case, and making it expand-then-tap would cost two taps
        // for the thing people do most. Hidden when the app is up to date, so
        // the column only ever shows actionable rows.
        val quick = TextView(ctx).apply {
            textSize = StoreDensity.T_BODY; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S4), dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S4))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(ctx, StoreDensity.S8), 0, 0, 0) }
            setOnClickListener { next(ctx, app) }
        }
        val chev = TextView(ctx).apply {
            text = if (expanded.contains(app.id)) "⌄" else "›"
            textSize = StoreDensity.T_TITLE; setTextColor(cDim); setPadding(dp(ctx, StoreDensity.S8), 0, 0, 0)
        }
        head.addView(dot); head.addView(name); head.addView(meta); head.addView(quick); head.addView(chev)

        val detail = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, StoreDensity.S12), 0, dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S8))
            visibility = if (expanded.contains(app.id)) View.VISIBLE else View.GONE
        }
        detailBody(ctx, app, detail)
        head.setOnClickListener {
            val open = !expanded.contains(app.id)
            if (open) expanded.add(app.id) else expanded.remove(app.id)
            detail.visibility = if (open) View.VISIBLE else View.GONE
            chev.text = if (open) "⌄" else "›"
        }

        dots[app.id] = dot; statusViews[app.id] = meta; quickBtns[app.id] = quick
        errBoxes[app.id] = errorArea(ctx, app)
        card.addView(head); card.addView(errBoxes[app.id]); card.addView(detail)
        return card
    }

    /** #831 The row's error area: the WHOLE reason (folded when long, tap to
     *  expand) and the ways out — Retry (the quick button's own next stage, which
     *  paint() hides on a failed row), APK↗ (the direct download in the browser,
     *  otherwise only behind the chevron) and, for a DNS failure, the DNS page.
     *  Hidden until a failure is painted into it by [showError]. */
    private fun errorArea(ctx: Context, app: Fleet.App) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, StoreDensity.S12), 0, dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S8))
        visibility = View.GONE
        val text = TextView(ctx).apply {
            textSize = StoreDensity.T_META; setTextColor(cErr); setTextIsSelectable(false)
            maxLines = StoreRowError.FOLDED_LINES; ellipsize = TextUtils.TruncateAt.END
            setOnClickListener {
                maxLines = if (maxLines == StoreRowError.FOLDED_LINES) Int.MAX_VALUE else StoreRowError.FOLDED_LINES
            }
        }
        addView(text)
        val retry = btn(ctx, StoreRowError.RETRY_BUTTON, cUpd) { next(ctx, app) }
        val apk = btn(ctx, StoreRowError.APK_BUTTON, 0xFF4A4A55.toInt()) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.abiReleaseUrl.ifEmpty { app.releaseUrl }))) }
        }.apply { visibility = if (app.releaseUrl.isNotEmpty()) View.VISIBLE else View.GONE }
        val dns = btn(ctx, StoreRowError.DNS_BUTTON, 0xFF4A4A55.toInt()) { openDnsPage(ctx) }.apply { visibility = View.GONE }
        addView(buttonRow(ctx, retry, apk, dns), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, StoreDensity.S4) })
    }

    /** Paint [look] into [appId]'s row, or clear it (null). */
    private fun showError(appId: String, look: StoreRowError.Look?) {
        val box = errBoxes[appId] ?: return
        if (look == null) { box.visibility = View.GONE; return }
        dots[appId]?.let { it.text = look.glyph; it.setTextColor(cErr) }
        statusViews[appId]?.let { it.text = look.meta; it.setTextColor(cErr) }
        (box.getChildAt(0) as TextView).apply {
            text = look.error + if (StoreRowError.foldable(look.error)) "  (tap to expand)" else ""
            maxLines = StoreRowError.FOLDED_LINES
        }
        // the button row: Retry, APK↗, DNS — DNS only for a DNS failure with a page to open.
        (box.getChildAt(1) as LinearLayout).getChildAt(2).visibility =
            if (look.dnsButton && dnsPageIntent(box.context) != null) View.VISIBLE else View.GONE
        box.visibility = View.VISIBLE
    }

    /** The Intent that opens the host's DNS page, or null when there is none to
     *  open: [AppStoreHost.dnsPagePackage]'s launcher when the host names one
     *  (Cloud Store → SuperApp) and it is installed, else [AppStoreHost.launchActivity]. */
    private fun dnsPageIntent(ctx: Context): Intent? {
        if (AppStoreHost.dnsPageExtras.isEmpty()) return null
        val pkg = AppStoreHost.dnsPagePackage
        val base = if (pkg != null) ctx.packageManager.getLaunchIntentForPackage(pkg)
                   else AppStoreHost.launchActivity?.let { Intent(ctx, it) }
        return base?.apply {
            AppStoreHost.dnsPageExtras.forEach { (k, v) -> putExtra(k, v) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private fun openDnsPage(ctx: Context) {
        val intent = dnsPageIntent(ctx) ?: return
        runCatching { startActivity(intent) }
    }

    /** Everything the old always-visible card carried, now behind the chevron. */
    private fun detailBody(ctx: Context, app: Fleet.App, into: LinearLayout) {
        fun linkChip(label: String, url: String) = TextView(ctx).apply {
            text = label; textSize = StoreDensity.T_CAPTION; setTextColor(cMiss)
            setPadding(0, dp(ctx, StoreDensity.S2), dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S2)); isClickable = true
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        }
        val links = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        // The chip must offer the SAME bytes the updater would install, so it
        // uses the per-ABI asset too — handing an x86_64 user the arm64 APK is
        // the manual version of the bug Fleet.App.abiReleaseUrl fixes.
        if (app.releaseUrl.isNotEmpty()) links.addView(linkChip("APK↗", app.abiReleaseUrl))
        if (app.repoUrl.isNotEmpty())    links.addView(linkChip("GH↗",  app.repoUrl))
        if (app.ghcrPage.isNotEmpty())   links.addView(linkChip("PKG↗", app.ghcrPage))
        if (links.childCount > 0) into.addView(links)

        // A reference row has no package or image to show. What it has is what
        // the library is for, and nothing on it can be opened or removed.
        val reference = catalogue[app.id]
        if (reference != null) into.addView(caption(ctx, reference.optString("description")))
        else into.addView(mono(ctx, app.pkg + "  ·  " + app.image))
        runtimeCallers(app.id)?.let { into.addView(caption(ctx, "Called by ${it.size} at runtime (bound over IPC): ${it.joinToString(", ")}")) }

        val status = TextView(ctx).apply {
            textSize = StoreDensity.T_META; setTextColor(cDim); text = "checking…"
            setPadding(0, dp(ctx, StoreDensity.S2), 0, dp(ctx, StoreDensity.S4))
        }
        fullStatusViews[app.id] = status
        into.addView(status)
        if (reference != null) return

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actionRows[app.id] = actions
        // Order and captions are appstore-fleet-actions.json; this only says
        // what each declared id DOES. Direct install lives on the Details
        // sheet now (still one tap from here), and Stop sits before Uninstall.
        for (a in FleetActions.row(ctx)) rowAction(ctx, app, a)?.let { actions.addView(it) }
        into.addView(actions)
    }
    // #878 who uses which lib: data/lib-consumers.json (its `group` names the tab that carries the
    // two sections), generated by data/regen.sh from every app's build.json and manifests
    // (cloud-android-lib-consumers.py). Nothing here knows a lib or an app by name.
    private val libConsumers by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.LIB_CONSUMERS_B64, Base64.DEFAULT))) }
            .getOrElse { JSONObject() }
    }
    private fun appNames(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map {
        a!!.getString(it).substringAfterLast('/').replace(Regex("^a[a-z]_"), "")
    }
    /** The apps that bind engine row [id] over IPC, or null when the data names none. */
    private fun runtimeCallers(id: String): List<String>? =
        appNames(libConsumers.optJSONObject("runtime")?.optJSONArray(id)).takeIf { it.isNotEmpty() }

    /** Section 2 of the Libs tab: every shared lib that is not an engine. Not installable,
     *  so an info row each: name, what it is, the apps that compile it in. */
    private fun renderBuildTimeLibs(ctx: Context) {
        val libs = libConsumers.optJSONArray("build_time") ?: return
        body.addView(sectionHeading(ctx, "Build-time shared libs — compiled in, nothing to install"))
        body.addView(caption(ctx, "${libs.length()} libs. Each is compiled into the apps listed, so it ships inside their APKs."))
        // Don't mix ML libs with the others: the generator flags each lib (`ml`, the ml- id rule)
        // and the two kinds are drawn as separate runs under their own headers.
        val all = (0 until libs.length()).map { libs.getJSONObject(it) }
        for ((title, group) in listOf("Shared libs" to all.filter { !it.optBoolean("ml") },
                                      "Machine-learning libs" to all.filter { it.optBoolean("ml") })) {
            if (group.isEmpty()) continue
            val groupBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            body.addView(groupHeader(ctx, "lib_group_$title", title, group.size, groupBox))
            body.addView(groupBox)
            for (lib in group) {
            val compiledBy = appNames(lib.optJSONArray("compiled_by"))
            val engines = lib.optJSONArray("engines")?.let { e -> (0 until e.length()).map { e.getString(it) } }.orEmpty()
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF1C1C24.toInt())
                setPadding(dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S8), dp(ctx, StoreDensity.S12), dp(ctx, StoreDensity.S8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(ctx, StoreDensity.S2), 0, dp(ctx, StoreDensity.S2)) }
            }
            card.addView(TextView(ctx).apply {
                text = lib.getString("id"); textSize = StoreDensity.T_TITLE; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(caption(ctx, lib.optString("description")))
            val callers = TextView(ctx).apply {
                text = buildString {
                    append(if (compiledBy.isEmpty()) "Compiled into no app directly" else "Compiled into ${compiledBy.size}: ${compiledBy.joinToString(", ")}")
                    if (engines.isNotEmpty()) append("  ·  inside ${if (engines.size > 3) "${engines.size} engine APKs" else engines.joinToString(", ")}")
                    lib.optString("primary").takeIf { it.isNotEmpty() }
                        ?.let { append("  ·  primary: ${appNames(JSONArray().put(it)).first()}") }
                }
                textSize = StoreDensity.T_CAPTION; setTextColor(cMiss); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { maxLines = if (maxLines == 2) Int.MAX_VALUE else 2 }
            }
            card.addView(callers)
            groupBox.addView(card)
            }
        }
    }

    /** A section or group separator: larger than a row title, with room above and below, so the
     *  headers stay prominent while the rows under them stay compact. */
    private fun sectionHeading(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = StoreDensity.T_TITLE * 1.4f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(ctx, StoreDensity.S12 * 2), 0, dp(ctx, StoreDensity.S8))
    }

    /** Each lib group's collapsed state, per user, in this app's prefs (keyed by the group's title). */
    private val groupPrefs by lazy { requireContext().getSharedPreferences("store_lib_groups", Context.MODE_PRIVATE) }
    private fun isCollapsed(key: String) = runCatching { groupPrefs.getBoolean(key, false) }.getOrDefault(false)
    private fun setCollapsed(key: String, v: Boolean) { runCatching { groupPrefs.edit().putBoolean(key, v).apply() } }

    /** A collapsible group header: chevron, title and count. Tapping toggles [content] and remembers it. */
    private fun groupHeader(ctx: Context, key: String, title: String, count: Int, content: View) = TextView(ctx).apply {
        fun paintIt() {
            val closed = content.visibility == View.GONE
            text = "${if (closed) "›" else "⌄"}  $title ($count)"
        }
        textSize = StoreDensity.T_TITLE * 1.25f; setTextColor(cUpd); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(ctx, StoreDensity.S12 * 2), 0, dp(ctx, StoreDensity.S8)); isClickable = true
        content.visibility = if (isCollapsed(key)) View.GONE else View.VISIBLE
        paintIt()
        setOnClickListener {
            content.visibility = if (content.visibility == View.GONE) View.VISIBLE else View.GONE
            setCollapsed(key, content.visibility == View.GONE); paintIt()
        }
    }

    /** The rows of the visible tab. Perms draws no batch actions, so it has none. */
    private fun current(): List<Fleet.App> = tabs.getOrNull(tab)?.rows.orEmpty()

    // ── concurrent status — one thread per app, independent + non-blocking ───
    private fun checkAll(ctx: Context, list: List<Fleet.App> = current()) {
        // #804 a Store refresh is an auto-chain trigger: what it is about to
        // show as pending is what the chain downloads and installs (gated by
        // the toggle and Wi-Fi only in the worker, like every other trigger).
        ConstellationWorker.kick(ctx, StoreAuto.TRIGGER_STORE_REFRESH)
        for (app in list) {
            statusViews[app.id]?.let { tv -> tv.post { tv.text = "checking…"; tv.setTextColor(cDim) } }
            thread(name = "fleet-check-${app.id}") {
                val st = Fleet.status(ctx, app)
                val stg = StoreStages.stage(ctx, app, st)
                // Post on `body`, not on the row: a filtered-out app still has a
                // state worth recording, and it has no row to post to.
                body.post { paint(app.id, st, stg); updateSummary(list) }
            }
        }
    }

    /** One state -> dot, collapsed meta line, quick button, full status line.
     *  Tolerates absent views: the app may be filtered out of the visible list
     *  while its check thread is still in flight. */
    private fun paint(appId: String, s: Fleet.State, stage: StoreStages.Stage? = null) {
        states[appId] = s
        val (glyph, color) = when (s) {
            is Fleet.State.Installed       -> "✓" to cUp
            is Fleet.State.UpdateAvailable -> "⬆" to cUpd
            is Fleet.State.Missing         -> "◯" to cMiss
            is Fleet.State.Blocked         -> "⛔" to cBlk
            is Fleet.State.Error           -> "⚠" to cErr
        }
        dots[appId]?.let { it.text = glyph; it.setTextColor(color) }

        // #631: ONE identity pattern, and neither line here composes it. Both
        // ask [FleetIdentity] — the collapsed row for the fields you scan by,
        // the expanded row for all three — so the version, the sha and the size
        // cannot come out in two shapes, and neither line can invent a field
        // this device could not source.
        val app = (fleet + references).firstOrNull { it.id == appId }
        fun identity(fields: Set<FleetIdentity.Field>) =
            app?.let { FleetIdentity.of(it, s, fields) } ?: ""

        statusViews[appId]?.let { tv ->
            tv.setTextColor(color)
            tv.text = listOf(when (s) {
                is Fleet.State.Installed       -> "installed"
                is Fleet.State.UpdateAvailable -> "update"
                is Fleet.State.Missing         -> "not installed"
                is Fleet.State.Blocked         -> if (appId in catalogue) "reference · not installable" else "not published"
                is Fleet.State.Error           -> s.message
            }, if (s is Fleet.State.Blocked || s is Fleet.State.Error) "" else identity(FleetIdentity.SCAN))
                .filter { it.isNotEmpty() }.joinToString(FleetIdentity.SEP)
        }

        fullStatusViews[appId]?.let { tv ->
            tv.setTextColor(color)
            val id = identity(FleetIdentity.FULL)
            val head = when (s) {
                is Fleet.State.Installed       -> "✓ up to date"
                is Fleet.State.UpdateAvailable -> "⬆ update available"
                is Fleet.State.Missing         -> "◯ not installed  ·  tap Install"
                is Fleet.State.Blocked         -> if (appId in catalogue) "⛔ reference row — a third-party library with nothing to install" else "⛔ not published yet"
                is Fleet.State.Error           -> "⚠ ${s.message}"
            }
            tv.text = if (s is Fleet.State.Blocked || s is Fleet.State.Error || id.isEmpty()) head
                      else head + FleetIdentity.SEP + id
        }

        quickBtns[appId]?.let { b ->
            when (s) {
                is Fleet.State.UpdateAvailable -> { b.visibility = View.VISIBLE; b.text = "⬆"; b.setBackgroundColor(cUpd) }
                is Fleet.State.Missing         -> { b.visibility = View.VISIBLE; b.text = "⬇"; b.setBackgroundColor(0xFF2B6CB0.toInt()) }
                else                           -> b.visibility = View.GONE
            }
        }

        showError(appId, StoreRowError.of(null, (s as? Fleet.State.Error)?.message))
        stage?.let { paintStage(appId, it) }
    }

    /**
     * #774 The cache stages over the network state: a row with an APK in the
     * cache, a verb running, or a stage that stopped says THAT — "cached (ready
     * to install)", "downloading 41%", "install did not finish: cancelled" — and
     * only the verbs [StoreStages.Stage.actions] allows are live. The quick
     * button is always the NEXT stage, so one tap takes over from exactly where
     * a stage (or the auto chain) stopped.
     */
    private fun paintStage(appId: String, stg: StoreStages.Stage) {
        stages[appId] = stg
        val busy = stg.id == "downloading" || stg.id == "installing"
        if (busy || stg.cached != null || stg.failedAt != null) {
            val color = if (stg.failedAt != null) cErr else cUpd
            statusViews[appId]?.let { it.text = stg.text; it.setTextColor(color) }
            fullStatusViews[appId]?.let { it.text = stg.text; it.setTextColor(color) }
        }
        // #831 a stopped stage: ⚠ mark, short meta, the whole reason in the row's error area.
        if (stg.failedAt != null) showError(appId, StoreRowError.of(stg.text, null))
        quickBtns[appId]?.let { b ->
            val verb = stg.actions.firstOrNull()
            when {
                verb == null -> b.visibility = View.GONE
                // Install from a cached APK, or Clear: say which. Install with
                // nothing cached is the whole chain (#784) — paint() already
                // drew ⬆ / ⬇ for it, and the tap runs the chain.
                stg.cached != null || verb == StoreStages.CLEAR -> {
                    b.visibility = View.VISIBLE
                    b.text = if (verb == StoreStages.INSTALL && stg.failedAt == StoreStages.INSTALL)
                        StoreInstallWatch.load(b.context).retryLabel else FleetActions.label(b.context, verb)
                    b.setBackgroundColor(if (verb == StoreStages.INSTALL) 0xFF7C3AED.toInt() else 0xFF4A4A55.toInt())
                }
            }
        }
        stageBtns[appId]?.forEach { (verb, b) ->
            val live = !busy && verb in stg.actions
            b.isClickable = live; b.alpha = if (live) 1f else 0.35f
        }
    }

    /** The one size format, owned by [FleetIdentity] (#631) so the summary
     *  line and the progress line cannot drift from the row's own size. */
    private fun human(b: Long): String = FleetIdentity.human(b)

    /** The quick button: whatever stage comes next for this row. */
    private fun next(ctx: Context, app: Fleet.App) =
        runStage(ctx, app, stages[app.id]?.actions?.firstOrNull() ?: StoreStages.DOWNLOAD)

    /**
     * Run one #774 stage verb off the main thread, repainting the row from
     * [StoreStages.stage] twice a second while it runs (download %), then once
     * from the network when it ends. Download only fetches; Install is the whole
     * chain (#784) — Download if nothing installable is cached, install, Clear.
     */
    private fun runStage(ctx: Context, app: Fleet.App, verb: String) {
        thread(name = "fleet-$verb-${app.id}") {
            val ticker = thread(name = "fleet-$verb-tick-${app.id}") {
                try {
                    while (true) {
                        Thread.sleep(500)
                        val s = StoreStages.stage(ctx, app)
                        body.post { paintStage(app.id, s) }
                    }
                } catch (e: InterruptedException) { }
            }
            val done = when (verb) {
                StoreStages.DOWNLOAD -> StoreStages.download(ctx, app)
                StoreStages.INSTALL -> StoreStages.install(ctx, app, states[app.id])
                else -> StoreStages.clear(ctx, app)
            }
            ticker.interrupt()
            if (done.failedAt != null)
                view?.post { Toast.makeText(ctx, "${app.label}: ${done.text}", Toast.LENGTH_LONG).show() }
            val st = Fleet.status(ctx, app)
            val stg = StoreStages.stage(ctx, app, st)
            body.post { paint(app.id, st, stg); updateSummary(current()) }
        }
    }

    /** One declared row button. Null only for Install on an unpublished app,
     *  which has nothing to install. An id with no handler is still drawn and
     *  says so on tap — never a button that does nothing. */
    private fun rowAction(ctx: Context, app: Fleet.App, a: FleetActions.Action): View? = when (a.id) {
        // #496: installed vs. available, side by side, plus Direct install and
        // App settings — see ApkDetailSheet.
        "details" -> btn(ctx, a.label, a.color) { ApkDetailSheet.show(requireActivity(), app, states[app.id]) }
        "open" -> btn(ctx, a.label, a.color) { openApp(ctx, Fleet.installedId(ctx, app) ?: app.pkg) }
        // #774 three stages, three buttons; which are live is StoreStages' call.
        "download" -> if (app.blocked) null else stageBtn(ctx, app, a, StoreStages.DOWNLOAD)
        "install" -> if (app.blocked) null
                     else stageBtn(ctx, app, a, StoreStages.INSTALL)
        "clear" -> if (app.blocked) null else stageBtn(ctx, app, a, StoreStages.CLEAR)
        "stop" -> btn(ctx, a.label, a.color) { stop(ctx, app) }
        "uninstall" -> btn(ctx, a.label, a.color) {
            runCatching { Fleet.uninstall(ctx, Fleet.installedId(ctx, app) ?: app.pkg) }
                .onFailure { Toast.makeText(ctx, "Uninstall: ${it.message}", Toast.LENGTH_LONG).show() }
        }
        else -> btn(ctx, a.label, 0xFF4A4A55.toInt()) {
            Toast.makeText(ctx, "'${a.id}' is declared in ${FleetActions.ASSET} but this build has no handler for it",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun stageBtn(ctx: Context, app: Fleet.App, a: FleetActions.Action, verb: String): TextView =
        btn(ctx, a.label, a.color) { runStage(ctx, app, verb) }
            .also { stageBtns.getOrPut(app.id) { HashMap() }[verb] = it }

    /**
     * Force-stop through the shell channel ladder — the same door Phone Apps'
     * Stop uses ([PhoneAppActions.forceStop]). With no channel armed it says
     * so and offers App settings, where Android's own Force stop lives.
     */
    private fun stop(ctx: Context, app: Fleet.App) {
        val pkg = Fleet.installedId(ctx, app)
            ?: return Toast.makeText(ctx, "${app.label}: not installed — nothing to stop", Toast.LENGTH_SHORT).show()
        if (SelfStop.isSelf(ctx, pkg)) return SelfStop.stop(ctx, activity)
        thread(name = "fleet-stop-${app.id}") {
            val out = PhoneAppActions.forceStop(ctx, pkg)
            view?.post {
                if (!isAdded) return@post
                when {
                    out == null -> AlertDialog.Builder(requireActivity())
                        .setTitle(FleetActions.label(ctx, "stop") + " — " + app.label)
                        .setMessage(getString(R.string.store_fleet_stop_no_channel))
                        .setPositiveButton(FleetActions.label(ctx, "app_settings")) { _, _ ->
                            runCatching { startActivity(PhoneAppActions.appInfo(pkg)) }
                                .onFailure { Toast.makeText(ctx, "${app.label}: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                        .show()
                    out.contains("OK") -> Toast.makeText(ctx,
                        getString(R.string.store_phone_stopped, app.label), Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(ctx,
                        getString(R.string.store_phone_failed, app.label, out.trim()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // "Update" — only entries ALREADY installed that have a newer image.
    // [targets] is passed in rather than read from current(), so one button can
    // stay tab-scoped while another spans apps + libs.
    private fun updateAll(ctx: Context, what: String, targets: List<Fleet.App>) {
        // Update the HOST first, through libs:updater's own channel, which does
        // not read the fleet at all. That is what makes this button a repair
        // tool rather than one more thing that breaks along with the list: when
        // the baked fleet is empty — as it shipped after libs:appstore's fleet
        // path broke in the module move — every fleet-driven action is a no-op,
        // so the store cannot pull the very build that would repopulate it.
        // checkNow does not care whether the fleet parsed.
        Toast.makeText(ctx, "Updating $what (+ SuperApp)…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-update-all") {
            // #784 StoreStages.updateAll: every cached build newer than what is
            // installed goes in with NO network; online, the rest get the same
            // Download → Install → Clear chain a row's Install runs. Missing
            // libs are taken, missing apps are Install all's (Mode.AUTO's rule).
            // The report is per app — installed / skipped / needs download /
            // failed at <stage> — because a count of zero has meant "everything
            // is current" and "everything failed" alike before.
            val batch = StoreStages.updateAll(ctx, targets)
            view?.post { report(ctx, "Update all", batch) }
            // The HOST update goes LAST. It still runs unconditionally, so the
            // button stays the repair tool it was built to be when the baked
            // fleet is empty — an empty fleet just makes the batch above a
            // fast no-op. But running it FIRST meant a successful self-install
            // replaced the APK and Android killed the process, taking the
            // whole fleet batch with it. UpdateWorker already orders it this
            // way for exactly that reason.
            com.diegonmarcos.superapp.updater.Updater.checkNow(ctx)
            checkAll(ctx)
        }
    }

    /**
     * #784 "Download all": every update (or missing entry) into the cache,
     * nothing installed — pre-fetch on Wi-Fi, Update all later, offline if need
     * be. What does not fit in the REAL free storage (less the declared
     * reserve, #812) is skipped rather than evicting the rest. #812 no dialog:
     * progress and the outcome are drawn in the bar under the buttons.
     */
    private fun downloadAll(ctx: Context, targets: List<Fleet.App>) {
        thread(name = "fleet-download-all") {
            val done = StoreStages.downloadAll(ctx, targets)
            view?.post { report(ctx, "Download all", done) }
            checkAll(ctx)
        }
    }

    /** #812 A batch's outcome, IN the page's progress bar — never a dialog.
     *  A failure already drawn there ([StoreStages.progress]) keeps the bar. */
    private fun report(ctx: Context, title: String, b: StoreStages.Batch) {
        if (!isAdded) return
        val row = progressRow ?: return
        if (StoreStages.progress() != null) return
        progressLabel?.text = "$title — ${b.summary.ifEmpty { "nothing to do" }}" + if (b.online) "" else " (offline)"
        progressLabel?.setTextColor(cUpd)
        progressBar?.let { drawBar(it, barModel.complete()) }
        progressCancel?.visibility = View.GONE
        progressIcon?.visibility = View.GONE
        row.setOnClickListener(null)
        row.visibility = View.VISIBLE
    }

    // "Install all" — only apps not yet on the device.
    private fun installMissing(ctx: Context) {
        Toast.makeText(ctx, "Installing missing apps…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-install-all") {
            val n = Fleet.installAll(ctx, current(), Fleet.Mode.MISSING)
            view?.post {
                Toast.makeText(ctx, if (n == 0) "All apps already installed" else "$n install(s) queued",
                    Toast.LENGTH_LONG).show()
            }
            checkAll(ctx)
        }
    }

    private fun openApp(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else Toast.makeText(ctx, "Not installed", Toast.LENGTH_SHORT).show()
    }

    /** Developer options — where the OS keeps Wireless Debugging. No public
     *  action targets the sub-screen itself, so this is the closest honest
     *  landing; falls back to top-level Settings if an OEM locks it away. */


    // ── view helpers ─────────────────────────────────────────────────────────
    private fun dp(ctx: Context, v: Int) = StoreDensity.dp(ctx, v)
    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = StoreDensity.T_META; setTextColor(cDim); setPadding(0, 0, 0, dp(ctx, StoreDensity.S8))
    }

    /** A run's heading - an ML row's application (Voice, Text, ...) or the
     *  central classification's "Section / Folder" - drawn over its run. Both
     *  words come off the row, so a new application or folder gets its
     *  heading with no edit here. */
    private fun applicationHeading(ctx: Context, heading: String) = TextView(ctx).apply {
        text = heading
        textSize = StoreDensity.T_META; setTextColor(cUpd)
        setPadding(0, dp(ctx, StoreDensity.S8), 0, dp(ctx, StoreDensity.S4))
    }

    private companion object {
        /** Sorts after every folder order and every application name. */
        const val UNSHELVED = "￿"
        const val OTHER = "Other"


    }
    private fun mono(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = StoreDensity.T_CAPTION; setTextColor(cDim); typeface = Typeface.MONOSPACE
    }
    /** #732 the `action` style in the verb's own colour — #793 drawn by the
     *  shared [StoreBar.button], the same component the Apps Mesh page uses. */
    private fun btn(ctx: Context, label: String, bg: Int, onClick: () -> Unit): TextView {
        val style = controls.action
        return StoreBar.button(ctx, style, label, bg, onClick)
    }
    private fun buttonRow(ctx: Context, vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; for (v in views) addView(v)
    }
}
