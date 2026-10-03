package com.diegonmarcos.superapp.appstore

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
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

    // Declared in libs:core's manifest at protectionLevel="signature" and merged
    // into every constellation app. Kept as one constant so the UI and any future
    // ContentProvider guard name the same string.
    private val CONSTELLATION_PERM = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    private val fleet by lazy { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }

    /** One tab: a group the fleet data declares, and every row drawn in it. */
    private class Tab(val label: String, val blurb: String, val rows: List<Fleet.App>)

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
            Tab(group.optString("label", group.getString("id")), group.optString("blurb"),
                rows.sortedWith(compareBy(
                    { applicationOf(it.id) ?: shelfOf(it)?.order ?: UNSHELVED },
                    { it.label.lowercase() })))
        }.filter { it.rows.isNotEmpty() }
    }

    // #642 the read-only feed tabs, one per entry in libs:appstore's own
    // assets/appstore-feeds.json, sitting between the fleet groups and Perms.
    // An unreadable declaration is NO feed tabs at all, so this page is exactly
    // what it was before the feeds existed rather than a tab that cannot load.
    private val feeds by lazy { FeedViewer.feeds(requireContext()) }

    // #732 how every tab, page entry and action button looks: assets/appstore-controls.json.
    private val controls by lazy { StoreControls.load(requireContext()) }

    private val statusViews = HashMap<String, TextView>()
    // The collapsed row shows a one-line summary; the full status line lives in
    // the detail pane, so both need painting from the same state.
    private val fullStatusViews = HashMap<String, TextView>()
    private val dots = HashMap<String, TextView>()
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
    // Which per-app permission pane is open, keyed by package (0 = Android, 1 = Cloud).
    private val permTab = HashMap<String, Int>()

    // amber, green, grey, red, orange, blue
    private val cUp = 0xFF48BB78.toInt(); private val cUpd = 0xFFED8936.toInt()
    private val cMiss = 0xFF63B3ED.toInt(); private val cBlk = 0xFFF56565.toInt()
    private val cErr = 0xFFECC94B.toInt(); private val cDim = 0x99FFFFFF.toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(ctx, 14); setPadding(p, p, p, p)
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

    // ── tabs: one per declared group, then Perms ─────────────────────────────
    /**
     * #660 — TWO LINES, because these are two different kinds of tab.
     *
     * One line per KIND OF TAB, not one line for all of them. A declared group
     * is a TYPE OF APK: Apps, Libs, Lite-ML, Tiny-ML partition the fleet, and
     * picking one narrows what the page is showing you. Commits, CI-CD and
     * Perms narrow nothing — the feeds are repo-wide (their declared endpoints
     * carry no type at all) and Perms walks the WHOLE fleet. Sitting them in
     * the per-type row states that they are peers of Apps and Libs, which is
     * the one thing they are not.
     *
     * It was also simply out of room. Seven weight-1 tabs across one
     * MATCH_PARENT row at 13sp with maxLines=1 ellipsize into stubs; the feeds
     * took it from five to seven, which is what made a latent crowding problem
     * a visible one.
     *
     * WHICH LINE A TAB SITS ON IS NOT WRITTEN HERE. It is which declaration the
     * tab came from: `constellation.groups` is the per-type line,
     * libs:appstore's own feed declaration plus [PERMS] is the line that is not
     * a type. So a new group appears on the first line and a new feed on the
     * second with no edit to this file — the same rule the tables follow.
     *
     * A line with NO tabs draws NO strip. An empty row that reserves height for
     * controls that are not there is the affordance-that-cannot-act shape: it
     * says "something goes here" about nothing.
     */
    private fun tabBar(ctx: Context): View {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, dp(ctx, 8)); layoutParams = lp
        }
        tabBtns.clear()
        // #671 A DIFFERENT CLASS OF CONTROL GETS A DIFFERENT CONTROL LANGUAGE.
        //
        // Two rows was necessary and not sufficient: identical pills on both
        // rows still said "seven tabs that happened to wrap". The rows do not
        // do the same KIND of thing, and that is measurable rather than a
        // matter of taste. A line-1 tab SELECTS A SUBSET — each declared group
        // filters the rows below it. A line-2 entry filters nothing: both feeds
        // are repo-wide (their declared endpoints carry no type at all) and the
        // Perms page walks the whole fleet. A filter and a destination drawn
        // identically claim to be the same control, so:
        //
        //   TAB         line 1 — pills that stretch to fill the width, bold,
        //               filled when active. A segmented control: pick exactly
        //               one of a partition.
        //   PAGE        line 2 — wrap-content chips, left-aligned. #671 drew
        //               them as bare text, which read as captions; #732 made
        //               them outlined chips with an icon and a chevron, the
        //               look of something that opens a page (see below).
        //
        // Separated by real space and a hairline, so the eye sees two tables
        // rather than one block. The rule is not "add margin" — the two lines
        // read as different kinds of thing because they ARE.
        //
        // STILL ONE BUILDER. [tabButton] takes the style as an argument, so
        // there is no second renderer and the two appearances cannot drift into
        // two code paths. Line membership is still purely which declaration the
        // tab came from, and tabBtns.size is still the running tab index, which
        // is what keeps renderTab's group/feed/Perms mapping correct.
        //
        // #732 THE LOOK IS DATA NOW. Line 2's entries open pages, and drawn as
        // bare text they read as captions, not as things to tap. Each entry
        // wears the style its own declaration names in assets/appstore-controls
        // .json (a `page` there: outlined chip, icon, chevron) and line 1 wears
        // `group_tab_style`, so a third look (or a fourth line) is a data edit.
        // `action` is the third style, worn by btn(); the three are kept
        // visibly different by test-store-controls.sh.
        val lines = listOf(
            tabs.map { StoreControls.Control(it.label, "", controls.groupTab) },
            feeds.map { controls.page(it.id, it.label) } + controls.page(MESH) + controls.page(PERMS))
        for (line in lines) {
            if (line.isEmpty()) continue
            // Only BETWEEN lines, so a page with one line draws no stray rule.
            if (column.childCount > 0) column.addView(lineDivider(ctx))
            val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (control in line) {
                val t = tabButton(ctx, tabBtns.size, control)
                tabBtns.add(t); strip.addView(t)
            }
            // A wrapping line scrolls rather than clipping its last chip on a
            // narrow phone; a stretched line fills the width by definition.
            column.addView(if (line.all { it.style.stretch }) strip
                else HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
        paintTabs()
        return column
    }

    /** The hairline plus the real space that makes line 2 a second table rather
     *  than a continuation of the first. */
    private fun lineDivider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(0xFF2A2A33.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1))
            .apply { setMargins(0, dp(ctx, 12), 0, dp(ctx, 8)) }
    }

    /**
     * THE ONE tab-button builder, for both lines. [index] is its position in the
     * page's single tab ordering, captured here so a button in the second strip
     * still selects itself; [control]'s style is carried as the view's tag so
     * [paintTabs] stays one pass over one list.
     *
     * A stretching style (weight 1f) is a partition filling its bar; a wrapping
     * one sits left at its own width, because a set of pages is not a partition.
     * The icon leads and the chevron trails only when the style declares them.
     */
    private fun tabButton(ctx: Context, index: Int, control: StoreControls.Control) = TextView(ctx).apply {
        val style = control.style
        text = listOf(control.icon, control.label, style.chevron).filter { it.isNotEmpty() }.joinToString("  ")
        maxLines = 1
        tag = style
        isClickable = true
        setOnClickListener { if (tab != index) { tab = index; filter = 0; paintTabs(); renderTab(ctx) } }
        typeface = if (style.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        if (style.stretch) {
            gravity = Gravity.CENTER; textSize = 13f
            setPadding(dp(ctx, 4), dp(ctx, 9), dp(ctx, 4), dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            gravity = Gravity.CENTER_VERTICAL; textSize = 13f
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, dp(ctx, 8), 0) }
        }
    }

    /** Selection reads per style too (#671): each button carries its declared
     *  style as its tag, so this stays a single pass over one list and the
     *  active look is whatever that style's `_active` fields say. */
    private fun paintTabs() = tabBtns.forEachIndexed { i, t ->
        val on = i == tab
        val style = t.tag as StoreControls.Style
        t.background = StoreControls.background(t.context, style, on)
        t.setTextColor(if (on) style.textActive else style.text)
    }

    private fun renderTab(ctx: Context) {
        body.removeAllViews()
        statusViews.clear(); actionRows.clear(); stageBtns.clear()
        fullStatusViews.clear(); dots.clear(); quickBtns.clear(); filterChips.clear()
        // Past the last group are the declared feeds (#642), then Perms. Each
        // blurb is data beside its group or its feed, so the caption naming the
        // out-of-process engines moves with the engines.
        val shown = tabs.getOrNull(tab)
        val feed = feeds.getOrNull(tab - tabs.size)
        when {
            shown != null -> renderFleet(ctx, shown.rows, shown.blurb)
            feed != null -> renderFeed(ctx, feed)
            tab == tabs.size + feeds.size -> renderMesh(ctx)
            else -> renderPerms(ctx)
        }
    }

    /**
     * #642 — one declared read-only feed: the repo's commits, or its CI-CD runs.
     *
     * Deliberately NOT given the header bar, the filter chips or the progress
     * row the fleet tabs carry. Those act on a selection of installable rows and
     * there are none here: a feed row's only action is to open the link the feed
     * itself supplied. A Check all button on a page with nothing to check is the
     * control that lies about what the screen can do.
     */
    private fun renderFeed(ctx: Context, feed: FeedViewer.Feed) {
        val host = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(host)
        FeedViewer.render(ctx, host, feed, FeedViewer.opener(ctx))
    }

    /**
     * #728/#733 Store ▸ Apps Mesh — [AppsMesh.page], the one page Configs ▸
     * Mesh ▸ Apps Mesh also draws (AppsMeshFragment). This host adds the one
     * thing only it has: a member's Store row ([openDetail]). The page probes
     * off the main thread and redraws only while its view is still attached,
     * so leaving the tab drops a late probe rather than drawing into it.
     */
    private fun renderMesh(ctx: Context) {
        val host = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(host)
        AppsMesh.page(this, host) { openDetail(ctx, it) }
    }

    /** A mesh node's Store detail: its group's tab, the row expanded and
     *  scrolled into view. A member no tab holds has no row to open. */
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

    private fun renderFleet(ctx: Context, list: List<Fleet.App>, blurb: String) {
        body.addView(caption(ctx, blurb))
        headerControls = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(headerControls)
        renderHeader(ctx)
        body.addView(progressPanel(ctx))
        if (list.isEmpty()) { body.addView(caption(ctx, "Nothing here yet.")); return }
        summaryView = TextView(ctx).apply {
            textSize = 12f; setTextColor(cDim); setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
        }
        body.addView(summaryView)
        body.addView(filterBar(ctx, list))
        listHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listHost)
        renderList(ctx, list)
        checkAll(ctx, list)
    }

    /** Filter chips. With two dozen libs the answer to "too much scrolling" is
     *  to stop scrolling: pick the slice you came for. Counts come from the
     *  cached [states], so a chip is instant and never re-checks. */
    private fun filterBar(ctx: Context, list: List<Fleet.App>): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(ctx, 6)) }
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

    private fun renderList(ctx: Context, list: List<Fleet.App>) {
        listHost.removeAllViews()
        statusViews.clear(); actionRows.clear(); stageBtns.clear()
        fullStatusViews.clear(); dots.clear(); quickBtns.clear()
        val shown = list.filter { inFilter(it) }
        if (shown.isEmpty()) { listHost.addView(caption(ctx, "Nothing in this filter.")); return }
        // One heading per run of rows sharing a heading. The list is already
        // sorted by heading, so a change of heading is the only place one can
        // belong. Unshelved rows sort last; once a list has had headings they
        // get their own "Other" so they do not read as part of the run above.
        var heading: String? = null
        for (app in shown) {
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
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
            visibility = View.GONE
            tag = StoreBar.PROGRESS_TAG
        }
        // #785 WHICH app: its launcher icon (when it is on the device — the
        // PackageManager already has it, so it costs one lookup) beside the line.
        val icon = ImageView(ctx).apply { visibility = View.GONE }
        val label = TextView(ctx).apply { textSize = 12f; setTextColor(cUpd) }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(icon, LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18)).apply { marginEnd = dp(ctx, 6) })
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
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 6)).apply {
            topMargin = dp(ctx, 4)
        })
        row.addView(cancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(ctx, 6)
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
    private fun renderProgress(state: UpdateProgress.State, p: StoreStages.Progress?) {
        val row = progressRow ?: return
        val label = progressLabel ?: return
        val bar = progressBar ?: return
        if (state is UpdateProgress.State.Cancelled) {
            UpdateProgress.reset()
            progressCancel?.visibility = View.GONE
            row.visibility = View.GONE
            return
        }
        if (p == null) { row.visibility = View.GONE; return }
        label.text = p.text
        label.setTextColor(if (p.failed) cBlk else cUpd)
        bar.isIndeterminate = !p.failed && p.percent < 0
        bar.progress = if (p.failed) 0 else p.percent.coerceAtLeast(0)
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
            lp.setMargins(0, dp(ctx, 2), 0, dp(ctx, 2)); layoutParams = lp
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
            isClickable = true
        }
        val dot = TextView(ctx).apply {
            text = "·"; textSize = 13f; setTextColor(cDim); setPadding(0, 0, dp(ctx, 8), 0)
        }
        val name = TextView(ctx).apply {
            text = app.label; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val meta = TextView(ctx).apply { text = "checking…"; textSize = 11f; setTextColor(cDim); maxLines = 1 }
        // The per-app action, on the collapsed row on purpose: updating ONE app
        // is the common case, and making it expand-then-tap would cost two taps
        // for the thing people do most. Hidden when the app is up to date, so
        // the column only ever shows actionable rows.
        val quick = TextView(ctx).apply {
            textSize = 13f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(ctx, 11), dp(ctx, 4), dp(ctx, 11), dp(ctx, 4))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(ctx, 8), 0, 0, 0) }
            setOnClickListener { next(ctx, app) }
        }
        val chev = TextView(ctx).apply {
            text = if (expanded.contains(app.id)) "⌄" else "›"
            textSize = 15f; setTextColor(cDim); setPadding(dp(ctx, 10), 0, 0, 0)
        }
        head.addView(dot); head.addView(name); head.addView(meta); head.addView(quick); head.addView(chev)

        val detail = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), dp(ctx, 10))
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
        card.addView(head); card.addView(detail)
        return card
    }

    /** Everything the old always-visible card carried, now behind the chevron. */
    private fun detailBody(ctx: Context, app: Fleet.App, into: LinearLayout) {
        fun linkChip(label: String, url: String) = TextView(ctx).apply {
            text = label; textSize = 11f; setTextColor(cMiss)
            setPadding(0, dp(ctx, 2), dp(ctx, 10), dp(ctx, 2)); isClickable = true
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

        val status = TextView(ctx).apply {
            textSize = 12f; setTextColor(cDim); text = "checking…"
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 4))
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
    /** The rows of the visible tab. Perms draws no batch actions, so it has none. */
    private fun current(): List<Fleet.App> = tabs.getOrNull(tab)?.rows.orEmpty()

    // ── concurrent status — one thread per app, independent + non-blocking ───
    private fun checkAll(ctx: Context, list: List<Fleet.App> = current()) {
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
        quickBtns[appId]?.let { b ->
            val verb = stg.actions.firstOrNull()
            when {
                verb == null -> b.visibility = View.GONE
                // Install from a cached APK, or Clear: say which. Install with
                // nothing cached is the whole chain (#784) — paint() already
                // drew ⬆ / ⬇ for it, and the tap runs the chain.
                stg.cached != null || verb == StoreStages.CLEAR -> {
                    b.visibility = View.VISIBLE
                    b.text = FleetActions.label(b.context, verb)
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
        if (pkg == ctx.packageName)
            return Toast.makeText(ctx, getString(R.string.store_phone_why_self), Toast.LENGTH_LONG).show()
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
     * be. A dry run sizes it first; when it will not fit in the free space (or
     * the cache's own bound) the owner is told BEFORE anything is written, and
     * what does not fit is skipped rather than evicting the rest.
     */
    private fun downloadAll(ctx: Context, targets: List<Fleet.App>) {
        Toast.makeText(ctx, "Checking what to download…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-download-all-plan") {
            val plan = StoreStages.downloadAll(ctx, targets, dryRun = true)
            view?.post {
                if (!isAdded) return@post
                val n = plan.count(StoreStages.DOWNLOAD)
                if (n == 0) return@post report(ctx, "Download all", plan)
                val fits = plan.needBytes <= plan.roomBytes
                AlertDialog.Builder(requireActivity())
                    .setTitle("Download all — $n app(s), ${human(plan.needBytes)}")
                    .setMessage((if (fits) "" else "⚠ Only ${human(plan.roomBytes)} free for the cache: " +
                        "what does not fit is skipped, never squeezed in.\n\n") +
                        lines(plan))
                    .setPositiveButton(FleetActions.label(ctx, "download")) { _, _ ->
                        thread(name = "fleet-download-all") {
                            val done = StoreStages.downloadAll(ctx, targets)
                            view?.post { report(ctx, "Download all", done) }
                            checkAll(ctx)
                        }
                    }
                    .setNegativeButton(R.string.store_close, null)
                    .show()
            }
        }
    }

    /** The per-app lines of a batch, skipped ones folded into a count. */
    private fun lines(b: StoreStages.Batch): String {
        val shown = b.outcomes.filter { it.result != StoreStages.SKIPPED }
        val skipped = b.outcomes.size - shown.size
        return (shown.map { o ->
            "${o.app.label}: ${o.result.replace('_', ' ')}" +
                (o.failedAt?.let { " at $it" } ?: "") + " — ${o.text}"
        } + listOfNotNull(if (skipped > 0) "$skipped skipped (already current)" else null)).joinToString("\n")
    }

    private fun report(ctx: Context, title: String, b: StoreStages.Batch) {
        if (!isAdded) return
        AlertDialog.Builder(requireActivity())
            .setTitle("$title — ${b.summary.ifEmpty { "nothing to do" }}" + if (b.online) "" else " (offline)")
            .setMessage(lines(b).ifEmpty { "Nothing to do." })
            .setPositiveButton(R.string.store_close, null)
            .show()
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

    // ── Perms tab ────────────────────────────────────────────────────────────
    // The constellation is a trusted environment because every APK is signed
    // with the SAME key. That is what makes signature-level permissions usable
    // between our apps: an app exposes a ContentProvider guarded by a
    // `signature` permission, and only same-key packages can bind/read it.
    //
    // Each app card carries two panes:
    //
    //   Android Perms — the platform's own runtime grants (camera, location,
    //     contacts…). Listed read-only, because only the system UI may change
    //     them; "System settings ↗" hands off to exactly that screen.
    //
    //   Cloud Perms — CONSTELLATION_DATA, declared in libs:core (shared by
    //     reference into every app, so it merges into all their manifests) at
    //     protectionLevel="signature". Android grants it at install to every
    //     APK carrying our key and refuses it to everyone else, so "all apps
    //     talk freely to each other" is the DEFAULT, enforced by the OS.
    //
    // Neither pane renders a toggle, and that is the point: the Android grants
    // aren't ours to flip, and the Cloud grant is already on by construction.
    // A switch here could only misreport state it doesn't control.
    private fun renderPerms(ctx: Context) {
        body.addView(caption(ctx,
            "One signing key across the constellation = signature-level trust. Each app " +
            "opens on two panes: Android Perms (the OS's own runtime grants — read-only " +
            "here, the system screen owns them) and Cloud Perms (our constellation " +
            "permission, granted automatically to every app carrying the Cloud key, so " +
            "they talk freely to each other by default)."))

        val me = ctx.packageName
        for (app in fleet) {
            if (app.pkg == me) continue
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF1C1C24.toInt())
                val ph = dp(ctx, 12); val pv = dp(ctx, 8)
                setPadding(ph, pv, ph, pv)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, dp(ctx, 4), 0, dp(ctx, 4)); layoutParams = lp
            }
            val pkg = Fleet.installedId(ctx, app)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(ctx).apply {
                text = app.label + (if (app.kind == "lib") "  ·  lib" else "")
                textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            card.addView(row)
            card.addView(mono(ctx, pkg ?: app.pkg))

            val trust = TextView(ctx).apply {
                textSize = 12f; setPadding(0, dp(ctx, 3), 0, dp(ctx, 2))
            }
            when {
                pkg == null -> { trust.setTextColor(cMiss); trust.text = "◯ not installed" }
                sameSignature(ctx, pkg) -> { trust.setTextColor(cUp); trust.text = "🔑 same key  ·  eligible for signature-level data access" }
                else -> { trust.setTextColor(cBlk); trust.text = "⚠ different signature  ·  NOT eligible — reinstall from our release" }
            }
            card.addView(trust)

            if (pkg != null) {
                // Per-app sub-tabs: these are two genuinely different systems, so
                // they get separate panes instead of one mixed list. Android Perms
                // = the OS's own runtime grants, which only the system UI can
                // change. Cloud Perms = our constellation permission, which needs
                // no control at all because it is granted by signature. Keyed by
                // package so each card remembers which pane was open.
                val sub = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val tabs = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
                }
                val chips = ArrayList<TextView>()
                fun paint() {
                    val sel = permTab[pkg] ?: 0
                    chips.forEachIndexed { i, c ->
                        c.setBackgroundColor(if (i == sel) 0xFF7C3AED.toInt() else 0xFF2A2A33.toInt())
                    }
                    sub.removeAllViews()
                    if (sel == 0) renderAndroidPerms(ctx, sub, pkg) else renderCloudPerms(ctx, sub, pkg)
                }
                listOf("Android Perms", "Cloud Perms").forEachIndexed { i, label ->
                    val c = TextView(ctx).apply {
                        text = label
                        textSize = 12f; gravity = Gravity.CENTER
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { setMargins(if (i == 0) 0 else dp(ctx, 4), 0, 0, 0) }
                        setOnClickListener { permTab[pkg] = i; paint() }
                    }
                    chips.add(c); tabs.addView(c)
                }
                card.addView(tabs)
                card.addView(sub)
                paint()
            }
            body.addView(card)
        }
    }

    /** Android's own runtime permissions for [pkg]. Read-only by design: only
     *  the system UI may change these, so we list what the package requests and
     *  whether it currently holds it, then hand off to the system screen. */
    private fun renderAndroidPerms(ctx: Context, into: LinearLayout, pkg: String) {
        val pm = ctx.packageManager
        val requested = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList()
        }.getOrNull().orEmpty()
            // Our constellation permission lives in the other pane; here we show
            // the platform's own, which is what the system screen can act on.
            .filter { it.startsWith("android.permission.") }
            .sorted()

        if (requested.isEmpty()) {
            into.addView(caption(ctx, "Requests no Android permissions."))
        } else {
            for (p in requested) {
                val granted = pm.checkPermission(p, pkg) == PackageManager.PERMISSION_GRANTED
                into.addView(TextView(ctx).apply {
                    text = (if (granted) "✓  " else "·  ") + p.removePrefix("android.permission.")
                    textSize = 11f
                    setTextColor(if (granted) cUp else cMiss)
                    setPadding(0, dp(ctx, 1), 0, dp(ctx, 1))
                })
            }
        }
        into.addView(buttonRow(ctx, btn(ctx, "System settings ↗", 0xFF2A2A33.toInt()) {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", pkg, null)))
            }.onFailure { Toast.makeText(ctx, "No settings screen", Toast.LENGTH_SHORT).show() }
        }))
    }

    /** The constellation's own permission. There is deliberately no switch here:
     *  CONSTELLATION_DATA is protectionLevel="signature", so Android grants it at
     *  install time to every APK carrying our signing key and refuses it to every
     *  other APK. "All apps talk freely to each other" is therefore the DEFAULT
     *  state, enforced by the OS itself — a toggle could only lie about it.
     *  Declared once in libs:core, which every app now shares by reference, so it
     *  manifest-merges into all of them. */
    private fun renderCloudPerms(ctx: Context, into: LinearLayout, pkg: String) {
        val pm = ctx.packageManager
        val holds = pm.checkPermission(CONSTELLATION_PERM, pkg) == PackageManager.PERMISSION_GRANTED
        val weHold = pm.checkPermission(CONSTELLATION_PERM, ctx.packageName) == PackageManager.PERMISSION_GRANTED

        into.addView(TextView(ctx).apply {
            text = if (holds) "✓  Cloud data access — granted"
                   else "✕  Cloud data access — not granted"
            textSize = 13f
            setTextColor(if (holds) cUp else cBlk)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        })
        into.addView(mono(ctx, CONSTELLATION_PERM))
        into.addView(caption(ctx, when {
            holds && weHold ->
                "Two-way: this app and SuperApp can each read the other's constellation data. " +
                "Granted automatically at install because both carry the Cloud signing key — " +
                "no prompt, and no outside APK can obtain it."
            holds ->
                "This app holds it but SuperApp does not — reinstall SuperApp from our release."
            sameSignature(ctx, pkg) ->
                "Same signing key, but this build predates the constellation permission. " +
                "Update it from the Apps tab; the grant lands on reinstall."
            else ->
                "Signed with a different key, so Android refuses this permission. " +
                "Reinstall from our release to bring it into the constellation."
        }))
    }

    /** True when [pkg] is signed with the same key as us — the whole basis of
     *  `signature`-level permissions inside the constellation. */
    @Suppress("DEPRECATION")
    private fun sameSignature(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.checkSignatures(ctx.packageName, pkg) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    private fun openApp(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else Toast.makeText(ctx, "Not installed", Toast.LENGTH_SHORT).show()
    }

    /** Developer options — where the OS keeps Wireless Debugging. No public
     *  action targets the sub-screen itself, so this is the closest honest
     *  landing; falls back to top-level Settings if an OEM locks it away. */


    // ── view helpers ─────────────────────────────────────────────────────────
    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun caption(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 12f; setTextColor(cDim); setPadding(0, 0, 0, dp(ctx, 8))
    }

    /** A run's heading - an ML row's application (Voice, Text, ...) or the
     *  central classification's "Section / Folder" - drawn over its run. Both
     *  words come off the row, so a new application or folder gets its
     *  heading with no edit here. */
    private fun applicationHeading(ctx: Context, heading: String) = TextView(ctx).apply {
        text = heading
        textSize = 12f; setTextColor(cUpd)
        setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
    }

    private companion object {
        /** Sorts after every folder order and every application name. */
        const val UNSHELVED = "￿"
        const val OTHER = "Other"

        /** The two line-2 pages this fragment owns rather than reads from a
         *  feed. These are their ids in assets/appstore-controls.json, which
         *  also holds their captions and icons (#732) — no caption is written here. */
        const val PERMS = "perms"

        /** #728 the mesh view — #733 the shared Apps Mesh page (see [renderMesh]). */
        const val MESH = "mesh"
    }
    private fun mono(ctx: Context, t: String) = TextView(ctx).apply {
        text = t; textSize = 11f; setTextColor(cDim); typeface = Typeface.MONOSPACE
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
