package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.CrashLogger
import com.diegonmarcos.superapp.notificationcenter.NotificationCenterFragment
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationListenerService
import com.diegonmarcos.superapp.rss.NtfyCatalog
import com.diegonmarcos.superapp.rss.RssFeedFragment
import com.diegonmarcos.superapp.cloud.CalendarMonthFragment
import com.diegonmarcos.superapp.cloud.CalendarAgendaFragment
import com.diegonmarcos.superapp.cloud.TasksFragment
import com.diegonmarcos.superapp.cloud.GitHubFeed
import com.diegonmarcos.superapp.cloud.GiteaFeed
import com.diegonmarcos.superapp.cloud.DaguRunsFeed
import com.diegonmarcos.superapp.cloud.DriveConnectionsFragment
import com.diegonmarcos.superapp.cloud.C3MeshFragment
import com.diegonmarcos.superapp.cloud.C3HealthFragment

import com.diegonmarcos.superapp.core.Collapsible
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.apps.InboxClasses
import com.diegonmarcos.superapp.apps.PhoneTaxonomy
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.core.NotificationStore
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stack-render variant of an aggregator section. When an aggregator
 * declares `stack_*` in build.json, MainActivity launches this fragment
 * instead of [TileGridFragment]. Each panel becomes a collapsable
 * MaterialCard whose body is dispatched by [Sections.StackPanel.kind]:
 *
 *   c3_public / c3_private   → embedded [C3HealthFragment] (scope-filtered)
 *   wg_mesh                   → embedded [C3MeshFragment]
 *   rss                       → embedded [RssFeedFragment]
 *   notification_center       → per-app grouped notification list, inline views
 *   drive_connections         → embedded [DriveConnectionsFragment]
 *   linktree_slide            → grouped link grid sourced from data/linktree.json
 *   link_grid                 → grouped link grid declared inline in build.json
 *   tile_row                  → inline mini-tile row (deep links into sections)
 *   mail_accounts             → per-account list with read/unread placeholders
 *   chat_matrix /chat_mattermost  → server list, tap → opens the chat section
 *   feed                       → config-driven repo/run feed; `source` picks
 *                                the fetcher (github_runs | github_commits |
 *                                gitea_commits | dagu_runs)
 *   open_link                 → single tappable row that opens a URL
 *   placeholder               → empty hint card
 *
 * All panels default to **expanded** (matches user spec "all uncolapsed
 * one after the other"). Tap the header chevron to collapse/expand.
 *
 * An INBOX card (mail_accounts, chat_*, and a stats card that names an app)
 * keeps its summary counts and draws that app's own notification boxes
 * underneath them: the counts say how much is in there, the boxes say what
 * arrived, and the two questions are asked in the same place.
 *
 * Every app/publisher box inside a card carries two group-level controls. The
 * double tick marks that box's notifications read; ARCHIVE moves the box
 * itself out of its card into the page's collapsed Archive section at the
 * bottom. Both are per-page state in [StackFilters], so both survive the
 * process death this launcher sees many times an hour.
 */
class AggregatorStackFragment : Fragment(),
    TileGridFragment.TileClickListener,
    Collapsible {

    private val sectionId: String get() = arguments?.getString(ARG_SECTION_ID).orEmpty()
    private val label:     String get() = arguments?.getString(ARG_LABEL).orEmpty()
    private val mode:      String get() = arguments?.getString(ARG_MODE).orEmpty()

    /** Body container + its chevron for every panel we built — used by
     *  [toggleAllCollapsed] when MainActivity re-taps the bottom nav. */
    private data class PanelRefs(val body: View, val chevron: View)
    private val panelRefs = mutableListOf<PanelRefs>()

    /** The panels this page declared. An inbox card reads it to find out
     *  whether a sibling card is about the same taxonomy folder it is. */
    private var pagePanels: List<Sections.StackPanel> = emptyList()

    /** In-page `anchor:` links for this stack. Generic — the registry is fed
     *  from the panels' own declarations, so no panel kind is special-cased
     *  here and no page is either. */
    private val anchors = StackAnchors()

    // ── filters_<page> toggle row state ────────────────────────────────
    //
    // Panels are built ONCE. The Source toggle hides and shows whole cards,
    // and the By/Show toggles rebuild only the two notification bodies. The
    // stack is never rebuilt wholesale, because that would re-run
    // [embedChild], whose fixed host ids are still claimed by the already
    // attached child fragments — the rss and news panels would come back
    // blank while looking like they had simply loaded nothing.
    private val originCards   = mutableListOf<Pair<String, View>>()
    private val bodyRefreshers = mutableListOf<() -> Unit>()

    // ── pull-down to refresh ───────────────────────────────────────────
    //
    // The gesture re-runs [bodyRefreshers] — the same re-render a filter tap
    // does — with the ntfy cards forced back to the network. It is armed only
    // where that list is non-empty, because a spinner over a page with nothing
    // to re-ask is a control reporting a fetch it never made, and this codebase
    // already treats a control that misreports state as a defect.
    private var refreshHost: SwipeRefreshLayout? = null
    /** Outstanding channel polls for the refresh in flight. The spinner stops
     *  when this reaches zero — or when the watchdog fires, because a poll
     *  that answers nothing at all still has to end somewhere and a decrement
     *  that never happens is a spinner that never stops. */
    private var refreshPending = 0
    /** Every notification id DRAWN on this page since the last render. The
     *  refresh diffs it against [refreshBefore] so the outcome line can say
     *  how much actually arrived — "refreshed" alone cannot distinguish a
     *  fetch that brought something from one that brought nothing. */
    private val pageRowIds = LinkedHashSet<String>()
    private var refreshBefore: Set<String> = emptySet()
    /** Set for the duration of a refresh render pass: [renderNtfyGroups] must
     *  go back to the network instead of repainting [ntfyCache], which is
     *  what would make the gesture answer with what it already had. */
    private var ntfyForceRepoll = false
    /** The vertical column holding the cards — where the "everything is
     *  filtered out" note is appended. */
    private var cardColumn: LinearLayout? = null
    /** The page-level Archive: ONE collapsed section at the bottom holding
     *  every app box the user archived, whichever card it was filed from.
     *  Page-level rather than per-card because the archive answers "what did I
     *  put away", a question about the page — a fold-away row per card would
     *  hide the answer in the same cards the user archived to get away from.
     *
     *  Built before the cards so a card can hand its archived boxes straight
     *  over as it renders, and added to the column after them so it still
     *  draws last. */
    private var archiveBox:    LinearLayout? = null
    private var archiveWrap:   LinearLayout? = null
    private var archiveHeader: TextView?     = null
    private var archiveOpen = false
    private var filterPage = ""
    private var sortMode   = "time"
    private var showMode   = "all"
    /** The two phone-taxonomy rows. Their values are the SECTION PREFIX
     *  characters declared in ui.phone_sections ("@", ".", "=", "-", "+"), or
     *  "all". Keeping the prefix itself as the option id is what lets a section
     *  added to build.json become filterable with no Kotlin-side list. */
    private var toolsMode    = "all"
    private var servicesMode = "all"
    /** Start of this visit's NEW window: the ts of the PREVIOUS visit.
     *  Captured before the watermark is advanced so toggling Show back and
     *  forth within one visit keeps answering the same question. This is what
     *  a group's "N new" chip counts — arrival, not attention. */
    private var visitSeenAt = 0L
    /** Ids the user has explicitly swiped read on this page. Loaded once per
     *  build and kept in step with [StackFilters] on every swipe, so a body
     *  rebuild (a filter tap) paints from memory rather than re-reading. */
    private var readIds: MutableSet<String> = HashSet()

    /** Read is EXPLICIT — the user swiped this row away. A row with no stable
     *  identity has nothing to remember, so it falls back to the arrival
     *  watermark rather than pretending to be permanently unread. */
    private fun isUnread(r: NotifRow): Boolean =
        if (r.id.isBlank()) r.ts > visitSeenAt else r.id !in readIds

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val scroll = ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFillViewport = true
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(column)
        cardColumn = column
        // The pull-down host, wrapping the ScrollView and nothing else.
        //
        // NESTED SCROLLING: SwipeRefreshLayout takes its first non-spinner
        // child as the drag target and asks it canScrollVertically(-1) before
        // it claims a drag (SwipeRefreshLayout.canChildScrollUp). ScrollView
        // answers that correctly, so a drag anywhere below the top scrolls the
        // page as it always did and only a pull from the very top arms the
        // spinner. It must stay a SINGLE child for that lookup to find the
        // ScrollView — the cards go in the column inside it, never in here.
        val host = SwipeRefreshLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(scroll)
            setColorSchemeColors(SIGNAL_OK)
            setOnRefreshListener { startRefresh() }
            // DISARMED until the panels are built and we know this page has
            // something re-queryable. Every early return below leaves it this
            // way on purpose: "section not found" and "no panels" are pages
            // with no source at all, and a gesture that spun there would be
            // claiming a fetch that cannot exist.
            isEnabled = false
        }
        refreshHost = host
        // Nothing has been drawn yet, so the previous view's row ids are not
        // this view's; keeping them would have the first pull report every
        // notification on the page as an arrival.
        pageRowIds.clear()
        refreshBefore = emptySet()
        refreshPending = 0
        // Dropped before anything can return early: these name views from the
        // PREVIOUS onCreateView, and a stale reference would have an archive
        // click filing a box into a destroyed container.
        archiveBox = null; archiveWrap = null; archiveHeader = null
        anchors.reset(scroll)

        val sec = Sections.byId(sectionId)
        if (sec == null) {
            column.addView(emptyHint(ctx, "Section not found: $sectionId"))
            return host
        }
        val panels = Sections.aggregatorStackFor(sec, mode)
        if (panels.isEmpty()) {
            column.addView(emptyHint(ctx, "No panels for ${sec.label} · $mode"))
            return host
        }
        // Kept for the whole life of the view: an inbox card decides what it is
        // allowed to speak for by looking at what its SIBLINGS on this page are
        // about — see [inboxFolderId].
        pagePanels = panels
        panelRefs.clear()
        originCards.clear()
        bodyRefreshers.clear()
        nextEmbedIdx = 0

        // Toggle row, when this page declares one. Reading the watermark
        // BEFORE advancing it is what makes Show=Unread mean "since you were
        // last here" rather than "since a moment ago", which would always be
        // empty.
        // The page id is the prefs key whether or not this page draws a toggle
        // row: C3 ▸ Observability now carries notification cards and declares
        // no filters, and its swipes must not land in a blank-keyed bucket
        // shared with every other stackless page.
        filterPage = mode
        readIds = StackFilters.readKeys(ctx, filterPage)
        // The watermark moves on EVERY visit, for the same reason readIds is
        // loaded on every visit: a page can carry notification boxes without
        // declaring a toggle row. Inboxes and C3 Observability are both that
        // page, and while this sat inside the branch below their watermark
        // stayed 0 forever — so every box on them reported its whole history
        // as "N new" on every single visit, which is the chip saying nothing.
        visitSeenAt = StackFilters.lastSeen(ctx, filterPage)
        StackFilters.markSeen(ctx, filterPage, System.currentTimeMillis())
        val filters = Sections.stackFiltersFor(sec, mode)
        if (filters.isNotEmpty()) {
            sortMode     = selection(ctx, filters, "sort", sortMode)
            showMode     = selection(ctx, filters, "show", showMode)
            toolsMode    = selection(ctx, filters, FILTER_TOOLS, "all")
            servicesMode = selection(ctx, filters, FILTER_SERVICES, "all")
            column.addView(filterRow(ctx, filters))
        }

        // Before the cards: a card files its archived boxes into this while it
        // builds. Added to the column after them, so it still renders last.
        val archive = buildArchiveSection(ctx)
        for (panel in panels) {
            val view = if (panel.kind == "section_title") sectionTitleView(ctx, panelTitle(ctx, panel))
                       else buildPanel(ctx, inflater, panel)
            if (panel.origin.isNotBlank()) originCards += panel.origin to view
            anchors.register(panel.anchor, view)
            column.addView(view)
        }
        column.addView(archive)
        syncArchiveHeader()
        if (filters.isNotEmpty()) applySource(ctx, filters)
        // ARM THE GESTURE, and only now: [bodyRefreshers] is what a refresh
        // actually re-runs, so a page that registered none has no way to fetch
        // anything and must not offer a gesture that would report otherwise.
        // Every Notify tab registers several (the phone card, the cloud card,
        // each inbox card); a link-grid or dashboard page registers none and
        // simply does not pull.
        host.isEnabled = bodyRefreshers.isNotEmpty()
        // A cross-page `page:<section>/<page>#<anchor>` link left its fragment
        // waiting for whichever stack answers to that page. Two posts deep:
        // the first waits for the first layout pass (offsets are all zero
        // before it), the second is the smooth scroll StackAnchors itself
        // defers. Undeclared ids are inert, so a stale link does nothing
        // rather than jumping the page somewhere arbitrary.
        StackAnchors.consumePending("$sectionId/$mode")?.let { id ->
            scroll.post { anchors.dispatch(StackAnchors.PREFIX + id) }
        }
        return host
    }

    /** Current choice for the filter with [id], or [fallback] if the page
     *  does not declare that filter at all. */
    private fun selection(
        ctx: android.content.Context,
        filters: List<Sections.StackFilter>,
        id: String,
        fallback: String,
    ): String = filters.firstOrNull { it.id == id }
        ?.let { StackFilters.selected(ctx, filterPage, it) } ?: fallback

    /** The declared toggles, one segmented control each.
     *
     *  These used to be plain framework Buttons at 0.45 alpha when inactive —
     *  five of those stacked read as a wall of grey slabs, and dimming is a
     *  weak "not selected" signal next to a raised button's own shadow. Now
     *  each row is a single rounded track holding equal-weight segments, with
     *  exactly one filled pill: the selected value is the only thing on the
     *  row with a background, which is what makes it legible at a glance. */
    private fun filterRow(
        ctx: android.content.Context,
        filters: List<Sections.StackFilter>,
    ): View {
        val host = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        // Every row's repainter, so one row can redraw another: picking a
        // taxonomy value resets its sibling row (see resetTaxonomySibling),
        // and that reset has to be visible, not just stored.
        val repainters = mutableListOf<() -> Unit>()
        for (filter in filters.filterNot { it.id.startsWith(SETTING_PREFIX) }) {
            host.addView(caption(ctx, filter.label))
            val track = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(FILTER_TRACK)
                }
                val p = dp(3)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) }
            }
            val segments = mutableMapOf<String, android.widget.TextView>()
            fun paint() {
                val active = StackFilters.selected(ctx, filterPage, filter)
                for ((id, segment) in segments) {
                    val on = id == active
                    segment.background = if (!on) null else
                        android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(19).toFloat()
                            setColor(FILTER_ACTIVE)
                        }
                    segment.setTextColor(if (on) FILTER_ACTIVE_TEXT else FILTER_IDLE_TEXT)
                    segment.typeface =
                        if (on) android.graphics.Typeface.DEFAULT_BOLD
                        else android.graphics.Typeface.DEFAULT
                }
            }
            repainters += { paint() }
            for (option in filter.options) {
                val segment = android.widget.TextView(ctx).apply {
                    text = option.label
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    val ph = dp(8); val pv = dp(7)
                    setPadding(ph, pv, ph, pv)
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                    )
                    setOnClickListener {
                        StackFilters.select(ctx, filterPage, filter.id, option.id)
                        resetTaxonomySibling(ctx, filters, filter.id, option.id)
                        for (repaint in repainters) repaint()
                        onFilterChanged(ctx, filters)
                    }
                }
                segments[option.id] = segment
                track.addView(segment)
            }
            paint()
            host.addView(track)
        }

        // ── Collapse ────────────────────────────────────────────────────────
        // Five of the six Notify tabs exist precisely BECAUSE their filters
        // are already chosen for them, so five rows of controls sit above
        // the content restating a decision nobody has to make. They start
        // folded away behind one line; the All tab, where the filters are
        // the point, starts open.
        //
        // The default is declared as a filter with a "__"-prefixed id, which
        // filterRow skips rendering (see the loop above): a page-level
        // setting written in exactly the same JSON shape as a visible
        // toggle. That buys per-page persistence for free — reopening the
        // row is remembered by StackFilters under this page id, like every
        // other choice on it.
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var open = selection(ctx, filters, FILTER_COLLAPSED, "no") != "yes"
        val header = caption(ctx, "").apply {
            setPadding(dp(2), dp(4), dp(2), dp(6))
            setOnClickListener {
                Haptics.tap(it)
                open = !open
                StackFilters.select(ctx, filterPage, FILTER_COLLAPSED, if (open) "no" else "yes")
                text = collapseLabel(open)
                host.isVisible = open
            }
        }
        header.text = collapseLabel(open)
        host.isVisible = open
        wrap.addView(header)
        wrap.addView(host)
        return wrap
    }

    private fun collapseLabel(open: Boolean) = if (open) "Filters  ▾" else "Filters  ▸"

    /** The two taxonomy rows are ONE choice spread over two rows: an app is a
     *  Tool or a Service, never both, so a value left set in each would AND to
     *  a permanently empty page and read as a broken filter. Narrowing one row
     *  therefore returns the other to All. */
    private fun resetTaxonomySibling(
        ctx: android.content.Context,
        filters: List<Sections.StackFilter>,
        changed: String,
        picked: String,
    ) {
        if (picked == "all") return
        val siblingId = when (changed) {
            FILTER_TOOLS    -> FILTER_SERVICES
            FILTER_SERVICES -> FILTER_TOOLS
            else            -> return
        }
        val sibling = filters.firstOrNull { it.id == siblingId } ?: return
        StackFilters.select(ctx, filterPage, sibling.id, "all")
    }

    /** Re-apply every toggle after one of them changed. */
    private fun onFilterChanged(
        ctx: android.content.Context,
        filters: List<Sections.StackFilter>,
    ) {
        sortMode     = selection(ctx, filters, "sort", sortMode)
        showMode     = selection(ctx, filters, "show", showMode)
        toolsMode    = selection(ctx, filters, FILTER_TOOLS, "all")
        servicesMode = selection(ctx, filters, FILTER_SERVICES, "all")
        rebuildBodies()
        applySource(ctx, filters)
    }

    /**
     * Re-render every registered notification body. The ONE path for it, shared
     * by a toggle tap and by the pull-down gesture, because both do exactly the
     * same thing to the page and the two rules below have to hold for both.
     *
     * Emptying the Archive first is what stops a rebuild growing a second copy
     * of every archived box, since each card re-files its own as it draws.
     * Clearing [pageRowIds] is the same rule for the refresh's arithmetic: the
     * ids are re-collected as the bodies draw, so a stale set would leave a
     * later pull comparing against notifications that are no longer on screen.
     */
    private fun rebuildBodies() {
        archiveBox?.removeAllViews()
        pageRowIds.clear()
        for (refresh in bodyRefreshers) refresh()
        syncArchiveHeader()
    }

    /** Keep a phone-stream app under the two taxonomy rows. Both default to
     *  "all" and only one can be narrowed at a time, so this is a single
     *  prefix comparison in practice.
     *
     *  [ctx] IS REQUIRED, and it is the whole reason the Inboxes tab was
     *  missing a category of apps. `ui.phone_folders` classifies by two rules:
     *  `match_keywords`, hand-written per package, and `match_metadata`, which
     *  asks Android what the package declares about itself. The metadata pass
     *  only runs when the lookup is given a Context; without one it is skipped
     *  silently, every app that no keyword happens to name falls to the
     *  `others` sink, and a sink folder carries no section prefix — which the
     *  comparison below turns into "shown on no tab at all". @Chat reaches
     *  most messengers through `intent:…APP_MESSAGING` and not through a
     *  keyword, so context-less meant the entire chat category was invisible.
     *  Appending packages to the keyword list would have hidden that one app
     *  at a time instead of fixing it. */
    private fun taxonomyKeeps(
        ctx: android.content.Context, packageName: String, label: String,
    ): Boolean {
        val want = when {
            toolsMode    != "all" -> toolsMode
            servicesMode != "all" -> servicesMode
            else                  -> return true
        }
        // An option id is a SET of section prefixes, not one prefix: the
        // Services option is "-+" because Services Buro ("-") and Services
        // Others ("+") are two build.json sections but one idea to the
        // person reading the filter. Membership also keeps single-character
        // ids working unchanged, so adding a section stays a build.json-only
        // change.
        val prefix = PhoneTaxonomy.sectionPrefixOf(packageName, label, ctx)
        return prefix.isNotEmpty() && want.contains(prefix)
    }

    /** The same narrowing for a CLOUD group, whose key is an ntfy topic or an
     *  in-app producer name rather than a package. Nothing can be classified
     *  from those, so the section prefix is declared per publisher in
     *  `ui.ntfy.taxon`; an undeclared publisher shows on the All tab only,
     *  rather than on every tab as the SuperApp updater did. */
    private fun cloudTaxonomyKeeps(groupKey: String): Boolean {
        val want = when {
            toolsMode    != "all" -> toolsMode
            servicesMode != "all" -> servicesMode
            else                  -> return true
        }
        val prefix = NtfyCatalog.taxonOf(groupKey)
        return prefix.isNotEmpty() && want.contains(prefix)
    }

    /**
     * Cloud vs Phone. The rule is the PANEL's declared `origin`, i.e. which
     * stream the notification arrived on — not a list of phone package names,
     * which would start going stale the day it was written. Everything the
     * notification listener captures is by construction from an app installed
     * on this device; everything in the ntfy catalog, the in-app SuperApp feed
     * and the news list is by construction from off it.
     *
     * A panel with no declared origin is never hidden.
     */
    private fun applySource(
        ctx: android.content.Context,
        filters: List<Sections.StackFilter>,
    ) {
        val want = selection(ctx, filters, "source", "both")
        for ((origin, card) in originCards) card.isVisible = want == "both" || origin == want
        // Every classified card hidden means the toggle did it, not a dead
        // page — say so, because an empty screen with no reason reads as
        // broken and teaches the user to stop opening it.
        val column = cardColumn ?: return
        val allHidden = originCards.isNotEmpty() && originCards.none { it.second.isVisible }
        val existing = column.findViewWithTag<View>(SOURCE_EMPTY_TAG)
        if (existing != null) existing.isVisible = allHidden
        else if (allHidden) {
            val label = filters.firstOrNull { it.id == "source" }
                ?.options?.firstOrNull { it.id == want }?.label ?: want
            column.addView(emptyHint(
                ctx,
                "No panel on this page carries $label notifications. Switch Source back to Both.",
            ).apply { tag = SOURCE_EMPTY_TAG })
        }
    }

    /** Called by MainActivity when the user re-taps the bottom-nav slot
     *  they're already on. Collapses every panel if any is open;
     *  expands every panel if all are closed. */
    override fun toggleAllCollapsed(): Boolean {
        if (panelRefs.isEmpty()) return false
        val anyOpen = panelRefs.any { it.body.isVisible }
        val targetVisible = !anyOpen
        for (ref in panelRefs) {
            ref.body.isVisible = targetVisible
            ref.chevron.animate().rotation(if (targetVisible) 90f else 0f).setDuration(180).start()
        }
        return true
    }

    /** kind=section_title — a plain heading between cards (NOT a card),
     *  used to separate the "Containers" cards from the "Stack" list card. */
    private fun sectionTitleView(ctx: android.content.Context, text: String): View =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Headline)
            setTextColor(0xFFE9D8FD.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textSize = 20f
            setPadding(dp(4), dp(12), dp(4), dp(6))
        }

    // ── Panel card (header + collapsable body) ─────────────────────────

    private fun buildPanel(
        ctx: android.content.Context,
        inflater: LayoutInflater,
        panel: Sections.StackPanel,
    ): View {
        val card = MaterialCardView(ctx).apply {
            radius        = dp(14).toFloat()
            cardElevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            isClickable = true; isFocusable = true
        }
        val title = TextView(ctx).apply {
            text = panelTitle(ctx, panel)
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            rotation = if (panel.collapsed) 0f else 90f
            val sz = dp(20)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        header.addView(title); header.addView(chevron)

        // Optional subtitle line
        val subtitle = if (panel.subtitle.isNotBlank()) TextView(ctx).apply {
            text = panel.subtitle
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.65f
            val pad = dp(14)
            setPadding(pad, 0, pad, dp(6))
        } else null

        // Body container
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8)
            setPadding(pad * 2, pad, pad * 2, pad * 2)
            isVisible = !panel.collapsed
        }
        renderBody(ctx, inflater, body, panel)

        header.setOnClickListener {
            body.isVisible = !body.isVisible
            chevron.animate().rotation(if (body.isVisible) 90f else 0f).setDuration(180).start()
        }

        outer.addView(header)
        if (subtitle != null) outer.addView(subtitle)
        outer.addView(body)
        card.addView(outer)
        panelRefs.add(PanelRefs(body = body, chevron = chevron))
        return card
    }

    // ── Body kind dispatcher ───────────────────────────────────────────

    private fun renderBody(
        ctx: android.content.Context,
        inflater: LayoutInflater,
        body: LinearLayout,
        panel: Sections.StackPanel,
    ) = when (panel.kind) {
        "c3_public"          -> embedChild(body, C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PUBLIC))
        "c3_private"         -> embedChild(body, C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PRIVATE))
        "wg_mesh"            -> embedChild(body, C3MeshFragment.newInstance())
        "rss"                -> embedChild(body, RssFeedFragment.newInstance(panel.scopes))
        "calendar_month"     -> embedChild(body, CalendarMonthFragment.newInstance())
        "calendar_agenda"    -> embedChild(body, CalendarAgendaFragment.newInstance())
        "tasks"              -> embedChild(body, TasksFragment.newInstance())
        "drive_connections"  -> embedChild(body, DriveConnectionsFragment.newInstance())
        "linktree_slide"     -> renderLinktreeSlide(ctx, body, panel.slideId)
        "link_grid"          -> renderLinkGrid(ctx, body, panel.columns, panel.links)
        "tile_row"           -> renderTileRow(body, panel.tiles)
        // The inbox kinds: summary first, then what actually arrived. Wrapped
        // in [refreshable] like the notification centre is, because the boxes
        // underneath answer to the same Sort/Show toggles on a page that
        // declares them. Safe to re-run — every view below is a plain View,
        // nothing here goes through [embedChild].
        "mail_accounts"      -> refreshable(body) {
            renderMailAccounts(ctx, body); renderInboxNotifications(ctx, body, panel) }
        "chat_matrix"        -> refreshable(body) {
            renderChatPlaceholder(ctx, body, "Matrix", "page:chat/matrix")
            renderInboxNotifications(ctx, body, panel) }
        "chat_mattermost"    -> refreshable(body) {
            renderChatPlaceholder(ctx, body, "Mattermost", "page:chat/mattermost")
            renderInboxNotifications(ctx, body, panel) }
        "open_link"          -> renderOpenLink(ctx, body, panel)
        "notification_center" -> refreshable(body) { renderNotificationCenter(ctx, body, panel) }
        "feed"               -> renderFeed(ctx, body, panel)
        "stats"              -> refreshable(body) {
            renderStats(ctx, body, panel); renderInboxNotifications(ctx, body, panel) }
        // One card per CHANNEL CLASS, which is what this page became when the
        // owner replaced one-card-per-app. Refreshable for the same reason the
        // inbox kinds are: the boxes underneath answer to the page's own
        // Sort/Show toggles.
        "class_inbox"        -> refreshable(body) { renderClassInbox(ctx, body, panel) }
        "cloud_dashboard"    -> renderCloudDashboard(ctx, body, panel)
        else                 -> renderPlaceholder(ctx, body, panel)
    }

    // ── kind=feed ──────────────────────────────────────────────────────
    //
    // ONE config-driven kind for every repo/run integration on this page,
    // per build.json's own instruction to keep card types generic rather
    // than hand-roll a Kotlin `when` branch per data source. `source`
    // picks the fetcher; `repos` (where applicable) and `limit` are the
    // only other knobs a panel needs — adding a sixth integration is a
    // new `when` arm here plus a client object, never a new panel `kind`.

    private fun renderFeed(ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel) {
        when (panel.source) {
            "github_runs"      -> renderGithubRunsFeed(ctx, body, panel)
            "github_run_stats" -> renderRunStatsFeed(ctx, body, panel)
            "github_commits"   -> renderRepoCommitsFeed(ctx, body, panel, gitea = false)
            "gitea_commits"    -> renderRepoCommitsFeed(ctx, body, panel, gitea = true)
            "dagu_runs"        -> renderDaguRunsFeed(ctx, body, panel)
            else -> body.addView(emptyRow(ctx,
                "(kind=feed needs a \"source\": github_runs | github_run_stats | " +
                    "github_commits | gitea_commits | dagu_runs — got '${panel.source}')"))
        }
    }

    private fun loadingRow(ctx: android.content.Context, label: String): View =
        android.widget.TextView(ctx).apply {
            text = label
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(8), 0, dp(8))
        }

    /** Last commit per repo, one group per repo — used by both "GH Repos"
     *  (source=github_commits) and "Gitea Repos" (source=gitea_commits).
     *  The two only differ in which client fetches the rows; the grouping,
     *  loading state and row rendering are identical, so this is the one
     *  place that logic lives rather than two near-duplicate functions. */
    private fun renderRepoCommitsFeed(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel, gitea: Boolean,
    ) {
        if (panel.repos.isEmpty()) {
            body.addView(emptyRow(ctx, "No repos declared. Add a `repos: [{owner, repo, label}]` array to the panel."))
            return
        }
        val perRepo = panel.limit.takeIf { it > 0 } ?: 1
        val loading = loadingRow(ctx, "Loading commits…")
        body.addView(loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val results = panel.repos.mapIndexed { i, ref ->
                // Stagger the fan-out: a dozen repos hitting either API at
                // once beats up the anonymous GitHub quota just the same as
                // it would beat up the Gitea container, so both sources share
                // the same trickle.
                async {
                    kotlinx.coroutines.delay(i * GitHubFeed.STAGGER_MS)
                    val feed = if (gitea) GiteaFeed.commits(ctx, ref.owner, ref.repo, perRepo)
                               else GitHubFeed.commits(ctx, ref.owner, ref.repo, perRepo)
                    ref to feed
                }
            }.awaitAll()
            body.removeView(loading)
            for ((ref, feed) in results) {
                body.addView(repoHeader(ctx, ref.label, "${ref.owner}/${ref.repo}"))
                if (feed.items.isEmpty()) {
                    body.addView(emptyRow(ctx, feedEmptyLabel(feed.status, "commits")))
                    continue
                }
                if (feed.status != GitHubFeed.Status.OK) {
                    body.addView(emptyRow(ctx, feedStaleLabel(feed.status)))
                }
                for (c in feed.items) {
                    body.addView(githubRow(
                        ctx,
                        title    = c.message,
                        meta     = "${c.sha} · ${c.author} · ${GitHubFeed.ago(now - c.tsMillis)}",
                        url      = c.htmlUrl,
                        severity = "info",
                    ))
                }
            }
        }
    }

    /** "GHA": the last [Sections.StackPanel.limit] workflow runs across
     *  EVERY declared repo, merged into one chronological list — a feed,
     *  not the old per-repo-grouped view. Each row is labelled with its
     *  repo so the merge does not lose the context grouping used to give
     *  for free. */
    private fun renderGithubRunsFeed(ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel) {
        if (panel.repos.isEmpty()) {
            body.addView(emptyRow(ctx, "No repos declared. Add a `repos` array to the panel."))
            return
        }
        val limit = panel.limit.takeIf { it > 0 } ?: 5
        val loading = loadingRow(ctx, "Loading workflow runs…")
        body.addView(loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val results = panel.repos.mapIndexed { i, ref ->
                // Ask each repo for up to `limit` runs so the merged, sorted
                // top-`limit` cannot be short just because one repo happened
                // to be fetched last.
                async { kotlinx.coroutines.delay(i * GitHubFeed.STAGGER_MS)
                        ref to GitHubFeed.runs(ctx, ref.owner, ref.repo, limit) }
            }.awaitAll()
            body.removeView(loading)
            val flat = results
                .flatMap { (ref, feed) -> feed.items.map { ref to it } }
                .sortedByDescending { it.second.tsMillis }
                .take(limit)
            if (flat.isEmpty()) {
                val worst = results.map { it.second.status }.firstOrNull { it != GitHubFeed.Status.OK }
                    ?: GitHubFeed.Status.OK
                body.addView(emptyRow(ctx, feedEmptyLabel(worst, "workflow runs")))
                return@launch
            }
            for ((ref, r) in flat) {
                val sev = when (r.conclusion) {
                    "success" -> "info"
                    "failure", "timed_out", "cancelled" -> "error"
                    else      -> "warn"
                }
                val statusLabel = if (r.conclusion.isNotBlank()) r.conclusion else r.status
                body.addView(githubRow(
                    ctx,
                    title    = r.displayTitle.ifBlank { r.name },
                    meta     = "${ref.label} · ${r.name} · $statusLabel · ${GitHubFeed.ago(now - r.tsMillis)}",
                    url      = r.htmlUrl,
                    severity = sev,
                    // Only rows whose run identified its workflow file can
                    // be re-dispatched — GitHub addresses the dispatch
                    // endpoint by that file name.
                    action   = r.workflowFile.takeIf { it.isNotBlank() }
                        ?.let { ghaTriggerRow(ctx, ref, it) },
                ))
            }
        }
    }

    /** "Analytics": the only card on this page that COUNTS rather than lists.
     *
     *  Over the last [Sections.StackPanel.limit] runs per declared repo: how
     *  many ran, what share of the FINISHED ones went green, how many failed,
     *  and how long ago the last green was. One aggregate line per repo, plus
     *  a fleet total above them.
     *
     *  THE DENOMINATOR IS THE FINISHED RUNS, NOT ALL OF THEM. A run still in
     *  progress carries an empty `conclusion`; counting it as "not green" would
     *  make the rate sag every time the fleet is mid-build, which is precisely
     *  when this page gets opened. Queued and running are neither green nor
     *  failed — they are not yet anything. With nothing finished the card says
     *  so rather than printing 0%, because a rate over an empty denominator is
     *  not a rate, it is a division that did not happen.
     *
     *  CANCELLED IS NOT A FAILURE either. The ship workflows run under
     *  `cancel-in-progress`, so a rapid series of pushes cancels the older runs
     *  by design; counting those as failures would report a busy afternoon as
     *  an outage.
     *
     *  It reads the SAME [GitHubFeed.runs] the GHA card above reads, through
     *  the same fifteen-minute cache, so the two cards cost ONE request per
     *  repo between them on a cold page open and cannot disagree about a
     *  number. This is the glance; ac_c3-watchtower is the same subject with
     *  the per-workflow breakdown, reached by tapping the summary row
     *  (panel.url = extapp:c3-watchtower) through the ordinary
     *  [onTileClicked] dispatcher — no second launch path. */
    private fun renderRunStatsFeed(ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel) {
        if (panel.repos.isEmpty()) {
            body.addView(emptyRow(ctx, "No repos declared. Add a `repos` array to the panel."))
            return
        }
        val limit = panel.limit.takeIf { it > 0 } ?: 30
        val loading = loadingRow(ctx, "Counting workflow runs…")
        body.addView(loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val results = panel.repos.mapIndexed { i, ref ->
                async { kotlinx.coroutines.delay(i * GitHubFeed.STAGGER_MS)
                        ref to GitHubFeed.runs(ctx, ref.owner, ref.repo, limit) }
            }.awaitAll()
            body.removeView(loading)

            val all = results.flatMap { it.second.items }
            if (all.isEmpty()) {
                // The WORST status, not the first: one reachable repo with an
                // empty window must not read as a healthy fleet when the rest
                // were rate limited.
                val worst = results.map { it.second.status }.firstOrNull { it != GitHubFeed.Status.OK }
                    ?: GitHubFeed.Status.OK
                body.addView(emptyRow(ctx, feedEmptyLabel(worst, "workflow runs")))
                return@launch
            }
            if (results.any { it.second.status != GitHubFeed.Status.OK }) {
                body.addView(emptyRow(ctx, feedStaleLabel(
                    results.map { it.second.status }.first { it != GitHubFeed.Status.OK })))
            }

            // The fleet line, and the card's handoff to the full app. Severity
            // is driven by whether anything failed at all, so a single red run
            // is visible from the top of the page.
            body.addView(runStatsRow(ctx, "Fleet", all, now, panel.url))
            for ((ref, feed) in results) {
                if (feed.items.isEmpty()) {
                    body.addView(emptyRow(ctx, "${ref.label} — ${feedEmptyLabel(feed.status, "workflow runs")}"))
                    continue
                }
                body.addView(runStatsRow(ctx, ref.label, feed.items, now, ""))
            }
        }
    }

    /** One aggregate line. [url] is blank for the per-repo rows — only the
     *  fleet row is a handoff, so a tap anywhere in the card has exactly one
     *  meaning. */
    private fun runStatsRow(
        ctx: android.content.Context,
        label: String,
        runs: List<GitHubFeed.Run>,
        now: Long,
        url: String,
    ): View {
        val finished = runs.filter { it.conclusion.isNotBlank() }
        val green    = finished.filter { it.conclusion == "success" }
        val failed   = finished.count { it.conclusion == "failure" || it.conclusion == "timed_out" }
        val lastGreen = green.maxOfOrNull { it.tsMillis } ?: 0L

        val verdict = if (finished.isEmpty()) {
            "no verdicts yet (${runs.size} still running or queued)"
        } else {
            val pct = (green.size * 100) / finished.size
            val last = if (lastGreen > 0L) "last green ${GitHubFeed.ago(now - lastGreen)}"
                       else "no green run in this window"
            "${runs.size} runs · $pct% green · $failed failed · $last"
        }
        return githubRow(
            ctx,
            title    = label,
            meta     = verdict,
            url      = url,
            severity = when {
                finished.isEmpty() -> "idle"
                failed > 0         -> "error"
                else               -> "info"
            },
        )
    }

    /** "Dagu": the last [Sections.StackPanel.limit] runs across EVERY
     *  registered DAG (not one row per DAG's latest run — see
     *  [DaguRunsFeed] for how that differs from the native Dagu page's
     *  own listing). Server + bearer token come from [DaguPrefs], shared
     *  with the native page:c3/dagu list, so there is one Dagu config on
     *  the device rather than two that can disagree. */
    private fun renderDaguRunsFeed(ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel) {
        val limit = panel.limit.takeIf { it > 0 } ?: 5
        val loading = loadingRow(ctx, "Loading Dagu runs…")
        body.addView(loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = com.diegonmarcos.superapp.ops.dagu.DaguPrefs(ctx)
            val now = System.currentTimeMillis()
            val outcome = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { DaguRunsFeed.recentRuns(prefs.serverUrl, prefs.bearerToken, limit) }
            }
            body.removeView(loading)
            outcome.onFailure { e ->
                body.addView(emptyRow(ctx,
                    "(Dagu unreachable — ${e.message ?: "no response"}. " +
                        "If the token expired, sign in again on the Dagu page.)"))
            }
            outcome.onSuccess { runs ->
                if (runs.isEmpty()) {
                    body.addView(emptyRow(ctx, "(no runs recorded on ${prefs.serverUrl})"))
                    return@onSuccess
                }
                for (r in runs) {
                    val ts = if (r.finishedAtMs > 0L) r.finishedAtMs else r.startedAtMs
                    body.addView(githubRow(
                        ctx,
                        title    = r.name,
                        meta     = "${daguStatusLabel(r.status)} · ${GitHubFeed.ago(now - ts)}",
                        url      = "page:c3/dagu",
                        severity = daguSeverity(r.status),
                    ))
                }
            }
        }
    }

    /** What an EMPTY GitHub feed actually means. "no commits" is only
     *  said when GitHub answered and had nothing — a spent quota says
     *  "rate limited" in as many words, because rendering a 403 as an
     *  empty repo is the exact silent failure this panel used to have. */
    private fun feedEmptyLabel(status: GitHubFeed.Status, noun: String): String = when (status) {
        GitHubFeed.Status.RATE_LIMITED ->
            "(rate limited — GitHub's anonymous 60 requests/hour quota is spent; retry in a few minutes)"
        GitHubFeed.Status.UNREACHABLE ->
            "(unreachable — GitHub did not answer; check the network)"
        GitHubFeed.Status.OK -> "(no recent $noun)"
    }

    /** Shown ABOVE rows that came from cache because the live fetch
     *  failed, so cached data is never passed off as current. */
    private fun feedStaleLabel(status: GitHubFeed.Status): String = when (status) {
        GitHubFeed.Status.RATE_LIMITED -> "(rate limited — showing the last cached rows)"
        else                           -> "(offline — showing the last cached rows)"
    }

    /** Dagu run-status code → words. Codes are Dagu's own: 0 not started,
     *  1 running, 2 failed, 3 cancelled, 4 succeeded, 6 partially
     *  succeeded, 7 queued. 0 is spelled out as "never run" rather than
     *  left blank. */
    private fun daguStatusLabel(code: Int): String = when (code) {
        0    -> "never run"
        1    -> "running"
        2    -> "failed"
        3    -> "cancelled"
        4    -> "succeeded"
        6    -> "partially succeeded"
        7    -> "queued"
        else -> "status $code"
    }

    /** Never-run maps to "idle", NOT to the default row colour, so it is
     *  visibly its own thing next to a failure and next to a success. */
    private fun daguSeverity(code: Int): String = when (code) {
        0          -> "idle"
        2, 3       -> "error"
        1, 6, 7    -> "warn"
        else       -> "info"
    }

    private fun repoHeader(ctx: android.content.Context, title: String, sub: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        box.addView(android.widget.TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        box.addView(android.widget.TextView(ctx).apply {
            text = sub
            setTextColor(0x77FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        })
        return box
    }

    private fun emptyRow(ctx: android.content.Context, label: String): View =
        android.widget.TextView(ctx).apply {
            text = label
            setTextColor(0x77FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(dp(4), dp(2), 0, dp(2))
        }

    private fun githubRow(ctx: android.content.Context, title: String, meta: String,
                          url: String, severity: String, action: View? = null): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(8); setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
            layoutParams = lp
            setBackgroundColor(when (severity) {
                "error" -> 0x55B91C1C.toInt()
                "warn"  -> 0x55D97706.toInt()
                // "idle" = declared but never run. Deliberately a neutral
                // dim wash: not the success colour, not a failure colour,
                // and not the plain default row either, so "never ran" is
                // legible as its own state at a glance.
                "idle"  -> 0x22FFFFFF
                else    -> 0x331A0033
            })
            isClickable = true
            isFocusable = true
            setOnClickListener {
                // A workflow-run link is a plain web page, so it goes to the
                // activity dispatcher rather than straight out to ACTION_VIEW.
                // Firing the intent here handed the user to whatever external
                // browser the system picked, which ends the superapp task: the
                // Back gesture returns to the launcher, not to this run list.
                if (url.isNotBlank()) onTileClicked(url)
            }
        }
        row.addView(android.widget.TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(android.widget.TextView(ctx).apply {
            text = meta
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(2), 0, 0)
        })
        if (action != null) row.addView(action)
        return row
    }

    /**
     * "Re-run" control for one GitHub Actions workflow row.
     *
     * Dispatch goes through c3-infra-api (POST /workflows/dispatch) with the
     * Authelia bearer this device already stores, NOT with a GitHub token: a
     * PAT with actions:write shipped inside the APK would be extractable by
     * anyone who unzips it. The server holds the PAT.
     *
     * Every outcome is displayed in place — dispatched, refused by GitHub,
     * proxy not configured, or no token on the device. The control never
     * finishes without saying what happened.
     */
    private fun ghaTriggerRow(ctx: android.content.Context, ref: Sections.RepoRef,
                              workflowFile: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val status = android.widget.TextView(ctx).apply {
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = android.widget.Button(ctx).apply {
            text = "Re-run"
            isAllCaps = false
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        }
        btn.setOnClickListener {
            btn.isEnabled = false
            status.text = "Dispatching $workflowFile…"
            val bearer = runCatching {
                com.diegonmarcos.superapp.ops.dagu.DaguPrefs(ctx).bearerToken
            }.getOrDefault("")
            viewLifecycleOwner.lifecycleScope.launch {
                val outcome = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.diegonmarcos.superapp.cloud.OpsClient.dispatchWorkflow(
                        repo = "${ref.owner}/${ref.repo}",
                        workflow = workflowFile,
                        ref = "main",
                        bearer = bearer,
                    )
                }
                btn.isEnabled = true
                when (outcome) {
                    is com.diegonmarcos.superapp.cloud.OpsClient.Outcome.Ok -> {
                        status.setTextColor(0xFF7BE38B.toInt())
                        status.text = outcome.message
                    }
                    is com.diegonmarcos.superapp.cloud.OpsClient.Outcome.Failed -> {
                        status.setTextColor(0xFFFF8888.toInt())
                        status.text = "${outcome.kind}: ${outcome.message}"
                    }
                }
            }
        }
        box.addView(btn)
        box.addView(status)
        return box
    }

    // ── kind=notification_center ───────────────────────────────────────
    //
    // The Apps RSS page is now exactly this kind, twice: one card per STREAM,
    // and inside each card one group per APP.
    //
    // Every view built below is a plain inline View. Nothing here goes through
    // [embedChild], and that is load-bearing rather than incidental: embedChild
    // allocates from a FIXED pool of host ids and only commits when nothing is
    // already attached, so any body that re-runs comes back permanently blank
    // while still looking like a card that merely loaded nothing. These bodies
    // re-run on every toggle tap (they are registered through [refreshable]),
    // and a grouped notification list is the most re-rendered surface in the
    // app — so it may not own a child fragment at all. The page also no longer
    // declares any embedChild-routed panel, so [nextEmbedIdx] never advances
    // here and the fixed pool cannot be exhausted by a rebuild.
    //
    // The shade layout below — icon headers, collapsible groups, dense rows —
    // is built entirely from LinearLayout, TextView, ImageView and View, for
    // the same reason. A collapsible group is a header View that flips its
    // sibling container's visibility, NOT a hosted fragment; the collapse
    // state lives on the fragment in [collapsedGroups] so it survives the
    // body rebuild a toggle tap causes.

    /** One app / publisher and everything it has posted. [key] is the stable
     *  identity we grouped on — a package name, an ntfy topic, an in-app
     *  producer — and [label] is what the user reads. */
    private data class NotifGroup(
        val key: String,
        val label: String,
        val sub: String,
        val rows: List<NotifRow>,
        val launchPackage: String = "",
        val url: String = "",
    ) {
        val newest: Long get() = rows.maxOfOrNull { it.ts } ?: 0L
    }

    private data class NotifRow(
        val ts: Long,
        val title: String,
        val text: String,
        val severity: String = "info",
        /** Stable per-entry identity, NAMESPACED by stream ("phone:…",
         *  "app:…", "ntfy:<topic>:…") so [StackFilters.pruneRead] can retire
         *  one stream's read keys without seeing the others. Blank means this
         *  row has no identity to remember, and it falls back to the visit
         *  watermark for its unread verdict. */
        val id: String = "",
    )

    /** A group's one-line verdict. Same four-state vocabulary the C3 Obsv page
     *  uses, because the two things this page must never confuse are the same
     *  two: an app that has genuinely posted nothing, and a stream we could
     *  not read. */
    private data class GroupState(val text: String, val color: Int)

    private data class NtfyResult(val ok: Boolean, val error: String, val rows: List<NotifRow>)

    /** Per-topic poll results for this visit. Toggling Sort or Show rebuilds
     *  the body; without this every tap would re-poll every channel. */
    private val ntfyCache = mutableMapOf<String, NtfyResult>()

    /** The newest timestamp we have actually MEASURED for [topic], or
     *  [Long.MIN_VALUE] when we have not measured it at all.
     *
     *  Sort=Time orders the channel boxes on this. Unmeasured and unreachable
     *  both answer MIN_VALUE and therefore sort last, which is the honest
     *  placement: a channel we could not read has made no claim about when it
     *  last spoke, so it may not be ranked as though it had. */
    private fun newestMeasured(topic: String): Long =
        ntfyCache[topic]?.takeIf { it.ok }?.rows?.maxOfOrNull { it.ts } ?: Long.MIN_VALUE

    /** Which groups the user has folded away, keyed on the same stable
     *  identity we grouped on. Held on the fragment, not on the view, because
     *  every toggle tap rebuilds the body from scratch — a collapse state
     *  stored in the header would spring open again on the next tap. */
    private val collapsedGroups = mutableSetOf<String>()

    private fun renderNotificationCenter(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ): Unit = run {
        // A notification shade is edge-to-edge: the group headers and rows
        // supply their own structure, so the card's inner gutter is chrome
        // that only steals width and vertical space from the list.
        body.setPadding(dp(6), 0, dp(6), dp(6))
        renderStream(ctx, body, panel)
    }

    private fun renderStream(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) = when (panel.stream) {
        "phone" -> renderPhoneCenter(ctx, body)
        "cloud" -> renderCloudCenter(ctx, body, panel)
        // C3 Obsv's stream: the ntfy CHANNELS and nothing else. It is `cloud`
        // minus the in-app feed, which belongs to Apps RSS and would otherwise
        // be drawn three times here — once per scope card — and would clear the
        // framework badge as a side effect of merely opening this page.
        "channels" -> renderNtfyGroups(ctx, body, panel)
        // A card that declares no stream cannot guess one, and it must not look
        // like "you have no notifications" — so the card keeps a short state
        // line and nothing more. The build.json grammar that would fix it is
        // addressed to whoever edits the file, not to the person holding the
        // phone, so it goes to logcat and to build.json::_doc_stack_my-rss.
        else -> Unit.also {
            android.util.Log.w(TAG, "notification panel '${panel.title}' declares no " +
                "`stream`: add \"stream\": \"phone\", \"cloud\" or \"channels\" in build.json")
            body.addView(stateLine(ctx, "no stream declared", SIGNAL_UNKNOWN))
        }
    }

    /**
     * Phone stream: one group per POSTING APP, keyed on `packageName` and
     * labelled with `appLabel`.
     *
     * There is no package list in this file and there must never be one. The
     * grouping key rides on the notification itself, so an app installed
     * tomorrow groups correctly with no Kotlin and no build.json edit, and an
     * app that goes away simply stops appearing — whereas a hand-written list
     * starts rotting the day it is written.
     */
    private fun renderPhoneCenter(ctx: android.content.Context, body: LinearLayout) {
        if (!isNotificationAccessGranted(ctx)) {
            // The single most likely reason a real user sees nothing here. It is
            // a MISSING CAPABILITY, not an empty inbox, and the card says which
            // — grey, with the way to fix it attached. Rendering an empty list
            // in this state would be a failure reporting success.
            body.addView(stateLine(ctx, "unavailable · permission not granted", SIGNAL_UNKNOWN))
            body.addView(android.widget.Button(ctx).apply {
                text = "Grant Notification Access"
                setOnClickListener {
                    runCatching {
                        ctx.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            })
            return
        }
        val stored = PhoneNotificationStore.all(ctx)
        if (stored.isEmpty()) {
            // Granted and empty is a REAL state and a different one: amber, so
            // it cannot be mistaken for the grey unavailable case above.
            body.addView(stateLine(ctx, "silent · nothing captured", SIGNAL_WARN))
            return
        }
        // Classify the WHOLE feed in one pass before any of it is filtered.
        // The taxonomy lookup reads the PackageManager for packages no keyword
        // claimed, and asked per notification it would repeat those reads on
        // the thread drawing this card — see PhoneTaxonomy.prime.
        PhoneTaxonomy.prime(ctx, stored.associate { it.packageName to it.appLabel })
        val groups = stored
            .groupBy { it.packageName.ifBlank { it.appLabel } }
            .map { (key, entries) ->
                NotifGroup(
                    key           = key,
                    label         = entries.firstOrNull { it.appLabel.isNotBlank() }?.appLabel ?: key,
                    sub           = key,
                    launchPackage = entries.first().packageName,
                    // `it.key` is StatusBarNotification.key, which is what
                    // PhoneNotificationStore dedupes on too — one stored entry
                    // is exactly one read key. A notification updated in place
                    // (a music progress bar) keeps its key and stays read,
                    // matching how the shade treats a quiet update.
                    rows          = entries.map {
                        NotifRow(it.ts, it.title.ifBlank { it.appLabel }, it.text,
                            id = PHONE_NS + it.key)
                    },
                )
            }
            // Phone-taxonomy rows. Filtering here rather than hiding rendered
            // views keeps renderGroups' own count honest, so an empty result
            // still lands on filteredAwayNote instead of a bare page.
            .filter { taxonomyKeeps(ctx, it.launchPackage, it.label) }
        // The store IS the complete phone namespace, so this prune sees
        // everything it is allowed to retire.
        StackFilters.pruneRead(ctx, filterPage, PHONE_NS,
            stored.mapTo(HashSet()) { PHONE_NS + it.key })
        if (renderGroups(ctx, body, groups) == 0) {
            body.addView(caption(ctx, filteredAwayNote(stored.size)))
        }
    }

    /**
     * Cloud stream, grouped by PUBLISHER: the ntfy TOPIC for a fleet message,
     * the `source` field for an in-app one.
     *
     * Why topic and not scope. A cloud message carries no package name, and the
     * closest thing it does carry is the address it was published to. A topic
     * has exactly one publisher the way a package has exactly one app, so the
     * per-app division survives the crossing from phone to cloud. A scope
     * (advisory / user / cloud) is a ROUTING CATEGORY shared by many
     * publishers; grouping on it would collapse every unrelated channel into
     * one bucket, which is precisely the undivided list this redesign replaces.
     * Scope still decides WHICH topics this card carries — it is just not the
     * grouping key.
     */
    private fun renderCloudCenter(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        // Opening the in-app feed also clears the framework badge, as the old
        // panel did: the launcher badge and this list must not disagree. Note
        // this DESTROYS system dismissal state rather than recording it, which
        // is why no "Dismissed" filter is offered anywhere on this page — it
        // would read a field nothing writes.
        runCatching {
            (ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager)?.cancelAll()
        }

        body.addView(shadeLabel(ctx, "IN-APP FEED"))
        val stored = NotificationStore.all(ctx)
        val local = stored.groupBy { it.source.ifBlank { "SuperApp" } }
            .filterKeys { cloudTaxonomyKeeps(it) }
            .map { (source, entries) ->
            NotifGroup(
                key   = source,
                label = source,
                sub   = "in-app producer",
                rows  = entries.map {
                    NotifRow(it.ts, it.title, it.body, when (it.severity) {
                        NotificationStore.Sev.ERROR -> "error"
                        NotificationStore.Sev.WARN  -> "warn"
                        else                        -> "info"
                    }, id = APP_NS + it.id)
                },
            )
        }
        StackFilters.pruneRead(ctx, filterPage, APP_NS,
            stored.mapTo(HashSet()) { APP_NS + it.id })
        if (renderGroups(ctx, body, local) == 0) {
            if (stored.isEmpty()) {
                // The producers that write to NotificationStore are Updater (a
                // version bump on launch) and Crash (an uncaught exception).
                // That list used to be printed under the card; it is a fact
                // about this codebase, so it belongs in this comment and not on
                // the phone, where it answered a question nobody asked.
                body.addView(stateLine(ctx, "silent · no in-app events", SIGNAL_WARN))
            } else {
                body.addView(caption(ctx, filteredAwayNote(stored.size)))
            }
        }

        body.addView(shadeLabel(ctx, "CHANNELS"))
        renderNtfyGroups(ctx, body, panel)
    }

    /**
     * One group per ntfy topic in this card's declared scopes, each with a live
     * verdict. The failure states are what separate this from a plain feed:
     *
     *   N · 5m ago  — messages arrived (green).
     *   silent 7d   — the poll SUCCEEDED and the topic is empty (amber). ntfy
     *                 answers 200 for a topic nobody has ever published to, so a
     *                 dead publisher looks exactly like a quiet one; amber says
     *                 we cannot tell them apart rather than drawing an innocent
     *                 empty group.
     *   unavailable — the poll itself failed. GREY, never red and never absent:
     *                 a failed fetch is a claim about THIS PHONE'S network, not
     *                 about the fleet, and it must never be rendered as "no
     *                 notifications".
     *
     * The topic list comes from the baked `ui.ntfy` catalog, so a channel added
     * upstream lands here by data on the next build.
     *
     * ORDERING OBEYS THE SORT TOGGLE, like every other box on the page. It used
     * to be alphabetical under BOTH modes, which made this the one construction
     * site where Sort=Time did nothing — and since every Notify tab draws its
     * channels through here, the page the owner actually looked at was ordered
     * by name on all six of them while build.json said "time" on all six.
     *
     * The reason it was alphabetical is still true and is still respected: a
     * channel whose poll has not landed has no timestamp to claim, and groups
     * that reshuffle under the thumb as fetches complete are worse than groups
     * that hold still and carry their age in the chip. So the order is decided
     * ONCE, here, from the last measurement [ntfyCache] holds, and the poll
     * landing never re-sorts. A never-measured channel sorts after every
     * measured one rather than pretending to be either fresh or stale, and the
     * pull-down gesture is what promotes it once a measurement exists.
     */
    private fun renderNtfyGroups(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        val scopes = com.diegonmarcos.superapp.rss.NtfyScopes.load()
        val catalog = com.diegonmarcos.superapp.rss.NtfyScopes.fallbackChannels()
        val topics = (if (panel.scopes.isEmpty()) catalog else catalog.filter {
            com.diegonmarcos.superapp.rss.NtfyScopes.scopeOf(it, scopes).id in panel.scopes
        }).filter { cloudTaxonomyKeeps(it) }
        if (topics.isEmpty()) {
            // Nothing is being polled, which is not the same as everything being
            // quiet — so the card still says something rather than going blank.
            // WHY it is empty is a scopes-vs-ui.ntfy question only whoever edits
            // build.json can answer, so it goes to logcat with the offending
            // panel and its scopes, and is written out beside the panels in
            // build.json::_doc_scopes_my-rss.
            android.util.Log.w(TAG, "ntfy panel '${panel.title}' matched no channel: no " +
                "build.json::ui.ntfy topic falls in scopes (" +
                panel.scopes.joinToString(", ").ifBlank { "all" } + ")")
            body.addView(stateLine(ctx, "no channels in scope", SIGNAL_UNKNOWN))
            return
        }
        // Every card is drawn first, then ONE request fills them all in.
        val slots = LinkedHashMap<String, Pair<TextView, LinearLayout>>()
        // By LABEL, not by topic id: "sort by app" means the name the reader
        // can see, which is what [renderGroups] already orders its boxes by.
        val byLabel = compareBy(String.CASE_INSENSITIVE_ORDER) { t: String ->
            com.diegonmarcos.superapp.rss.NtfyCatalog.labelOf(t)
        }
        val ordered =
            if (sortMode == "app") topics.sortedWith(byLabel)
            else topics.sortedWith(
                compareByDescending<String> { newestMeasured(it) }.then(byLabel))
        for (topic in ordered) {
            val group = NotifGroup(
                key   = topic,
                label = com.diegonmarcos.superapp.rss.NtfyCatalog.labelOf(topic),
                sub   = topic,
                rows  = emptyList(),
                // The HUMAN address, deliberately the gated public one: this
                // opens in the in-app browser, which carries the Authelia
                // cookie. Only the programmatic poll needs the open route.
                url   = "${com.diegonmarcos.superapp.rss.NtfyCatalog.webBaseUrl()}/$topic",
            )
            // "checking…", never OK: an unpolled channel must not spend even its
            // first frame looking healthy.
            val block   = groupBlock(ctx, group, GroupState("checking…", SIGNAL_UNKNOWN), body)
            val state   = block.findViewWithTag<TextView>(GROUP_STATE_TAG) ?: continue
            val rowsBox = block.findViewWithTag<LinearLayout>(GROUP_ROWS_TAG) ?: continue
            placeGroup(ctx, block, topic, body)

            val cached = ntfyCache[topic]
            // Paint the last measurement first so the box does not flash empty
            // while the poll is out, but still QUEUE the poll when the user
            // asked for fresh data: repainting a cache in answer to a pull-down
            // is the spinner reporting a fetch that never happened.
            if (cached != null) paintNtfyGroup(ctx, state, rowsBox, cached, topic, panel.limit)
            if (cached == null || ntfyForceRepoll) slots[topic] = state to rowsBox
        }
        if (slots.isEmpty()) return

        // ONE REQUEST FOR ALL OF THEM. ntfy takes a comma-separated topic list
        // and stamps every envelope with its own `topic`, which is how the
        // fleet's own rss-gateway polls it. Twenty-six separate requests also
        // WORKED, right up until they did not: ntfy allows a burst of 60 with
        // one token back per 10s (`visitor-request-limit-burst` in
        // infra-obs_ntfy/src/templates/server.yml.tpl), so a page that spends
        // 26 of them per visit puts the second visit inside a minute over the
        // line and hands back HTTP 429 for a scattered handful of channels.
        // Grey cards that move around between visits are the hardest kind of
        // broken to report, and one request cannot produce them.
        // Whether THIS card's poll is one the spinner is waiting on, decided
        // now and carried into the continuation: by the time it resumes the
        // refresh may already be over, and a card drawn by an ordinary render
        // must not decrement a counter it never incremented.
        val counted = refreshHost?.isRefreshing == true
        if (counted) refreshPending++
        // THE PAINT BELONGS TO THIS VIEW, SO IT IS SCOPED TO THIS VIEW.
        //
        // This was an unscoped executor whose result hopped back to the main
        // thread with `body.post`, written on the belief that a detached view
        // drops its runnable. IT DOES NOT. A view that was ATTACHED when post()
        // was called has already handed the runnable to the ViewRootImpl's
        // main-thread Handler, and that Handler runs it whatever has become of
        // the fragment in the meantime. So leaving this page with a poll in
        // flight repainted through a fragment that no longer had a Context, and
        // the crash landed on the first `dp()` the paint reached — a Samsung
        // SM-G996B on Android 15 threw exactly that, from notifRowView.
        //
        // A guard at the paint would only have converted the crash into a
        // half-drawn card. The scope is the fix: viewLifecycleOwner's scope is
        // cancelled in onDestroyView, so the continuation cannot run at all
        // once the views it paints are gone, and the poll itself is cancelled
        // with it instead of finishing into nothing.
        viewLifecycleOwner.lifecycleScope.launch {
            val byTopic = withContext(kotlinx.coroutines.Dispatchers.IO) {
                pollTopics(slots.keys.toList())
            }
            // [pollTopics] answers for every requested topic on every path,
            // including total transport failure, so there is no separate
            // could-not-poll branch to leave a card reading "checking…".
            for ((topic, slot) in slots) {
                val result = byTopic.getValue(topic)
                ntfyCache[topic] = result
                paintNtfyGroup(ctx, slot.first, slot.second, result, topic, panel.limit)
            }
            if (counted) ntfyPollSettled()
        }
    }

    private fun paintNtfyGroup(
        ctx: android.content.Context, state: TextView, rowsBox: LinearLayout, result: NtfyResult,
        topic: String = "", limit: Int = 0,
    ) {
        rowsBox.removeAllViews()
        if (!result.ok) {
            // The verdict already says what went wrong and what to do about
            // it; prefixing "unavailable" only buried the useful half.
            state.text = result.error
            state.setTextColor(SIGNAL_UNKNOWN)
            return
        }
        // A SUCCESSFUL poll is the complete current window for this topic, and
        // only then may its read keys be retired. An unavailable one returns
        // above without pruning, so a mesh hiccup does not resurrect every row
        // the user already swiped away.
        if (topic.isNotBlank()) StackFilters.pruneRead(
            ctx, filterPage, ntfyNs(topic), result.rows.mapTo(HashSet()) { it.id })
        // `limit` caps how many of this GROUP's rows are shown (e.g. C3
        // Observability's NTFY card wants "last 5 per channel"); 0 means
        // every row the poll window returned, as Inboxes' full centre wants.
        val rows = withinGroup(result.rows).let { if (limit > 0) it.take(limit) else it }
        if (rows.isEmpty()) {
            // Empty channel and hidden-by-filter are different facts, so they get
            // different words and different colours.
            if (result.rows.isEmpty()) {
                // The window it was actually asked about, not a literal that
                // went stale the moment the declared window changed.
                state.text =
                    "silent ${com.diegonmarcos.superapp.rss.NtfyCatalog.pollWindow()} · publisher?"
                state.setTextColor(SIGNAL_WARN)
            } else {
                state.text = "nothing new"
                state.setTextColor(0x88FFFFFF.toInt())
            }
            return
        }
        state.text = "${rows.size} · ${ago(System.currentTimeMillis() - rows.first().ts)}"
        state.setTextColor(SIGNAL_OK)
        for (r in rows) {
            rowsBox.addView(notifRowView(ctx, r, ""))
            if (r.id.isNotBlank()) pageRowIds += r.id
        }
    }

    /**
     * ntfy's poll API, for EVERY topic on the card in one request. Any
     * non-200, any exception and any unparseable body is UNAVAILABLE — the
     * honest answer is that we did not measure, not that the channel is empty.
     * Every requested topic gets an entry back, so a card can never be left
     * reading "checking…".
     *
     * THE ADDRESS IS NOT THE PUBLIC HOSTNAME. This built
     * `https://rss.diegonmarcos.com/<topic>/json` itself, which is the route
     * Caddy hands to Authelia's `forward_auth`; every card on the page read
     * "unavailable · HTTP 401" for that one reason, on all twenty-six channels
     * at once. The origin now comes from [NtfyCatalog.pollUrl] — one declared
     * place, so this file cannot drift away from the advisory screen again,
     * which is exactly what it had already done.
     *
     * A topic that answers nothing is SILENT, not missing: ntfy returns 200
     * with an empty body for an address nobody has ever published to, so the
     * demultiplexed empty list is a real measurement and paints amber, while
     * a transport failure paints every card grey together because they shared
     * one request and therefore share one fate.
     */
    private fun pollTopics(topics: List<String>): Map<String, NtfyResult> {
        fun all(r: NtfyResult) = topics.associateWith { r }
        return try {
            val url = java.net.URL(
                com.diegonmarcos.superapp.rss.NtfyCatalog.pollUrl(topics.joinToString(",")))
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 4000
                // Long enough to actually finish. health_resources alone
                // replays ~851 KB in the declared window, and a 4-second read
                // turned the busiest channel on the fleet into "no answer" —
                // a timeout that only fires on the channels with the most to
                // say is worse than no timeout at all.
                readTimeout = 15_000
                requestMethod = "GET"
                // NO REDIRECTS. ntfy answers directly, so a 3xx is the SSO
                // bounce and means we are on the gated route. Followed, it
                // becomes a 200 of login HTML that parses to zero messages —
                // a channel refusing us would render as one with nothing to
                // say, which is the one thing this page must never do.
                instanceFollowRedirects = false
            }
            try {
                if (conn.responseCode != 200)
                    all(NtfyResult(false,
                        com.diegonmarcos.superapp.rss.NtfyCatalog.readVerdict(conn.responseCode),
                        emptyList()))
                else {
                    val rows = topics.associateWith { mutableListOf<NotifRow>() }
                    conn.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) runCatching {
                            val o = org.json.JSONObject(line)
                            // Each envelope names its own topic — that field is
                            // the whole reason one request can fill many cards.
                            val topic = o.optString("topic")
                            if (o.optString("event") == "message") rows[topic]?.let { bucket ->
                                val ts = o.optLong("time", 0L) * 1000L
                                bucket += NotifRow(
                                    ts    = ts,
                                    title = o.optString("title").ifBlank { topic },
                                    text  = o.optString("message"),
                                    // ntfy mints a stable per-message id.
                                    // Falling back to topic+ts keeps a row
                                    // swipeable on a server old enough not to
                                    // send one.
                                    id    = ntfyNs(topic) +
                                        o.optString("id").ifBlank { ts.toString() },
                                )
                            }
                        }
                    }
                    rows.mapValues { (_, v) -> NtfyResult(true, "", v) }
                }
            } finally { conn.disconnect() }
        } catch (_: Throwable) {
            // Nothing answered at all, so there is no status code to interpret.
            // On a phone that is almost always the mesh being down, and saying
            // so is the difference between the owner reconnecting WireGuard and
            // the owner filing another bug about the fleet.
            all(NtfyResult(false,
                com.diegonmarcos.superapp.rss.NtfyCatalog.UNREACHABLE_VERDICT, emptyList()))
        }
    }

    /**
     * Draw [groups] as a per-app list and return how many had a visible row.
     *
     * The Sort toggle chooses the ORDER OF THE GROUPS — App = alphabetical,
     * Time = most recently active app first — never whether grouping happens,
     * because the division per app IS this page. Ordering INSIDE a group is
     * always newest-first, so flipping Sort can never bury something fresh.
     */
    private fun renderGroups(
        ctx: android.content.Context, body: LinearLayout, groups: List<NotifGroup>,
    ): Int {
        val visible = groups
            .map { it.copy(rows = withinGroup(it.rows)) }
            .filter { it.rows.isNotEmpty() }
        val ordered =
            if (sortMode == "app")
                visible.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { g: NotifGroup -> g.label })
            else visible.sortedByDescending { it.newest }
        val now = System.currentTimeMillis()
        for (g in ordered) {
            // The chip carries the per-app COUNT first, because that is what a
            // shade is scanned for; when something in the group is newer than
            // the last visit the count of those leads instead, in the same
            // green the healthy verdicts use.
            // NEW, not unread: arrived since the previous visit. Kept on the
            // watermark deliberately — a chip that only fell when every row
            // was swiped would read "12 new" forever on a page you read by
            // scrolling.
            val fresh = g.rows.count { it.ts > visitSeenAt }
            val chip =
                if (fresh > 0) GroupState("$fresh new · ${g.rows.size}", SIGNAL_OK)
                else GroupState("${g.rows.size} · ${ago(now - g.newest)}", 0x99FFFFFF.toInt())
            val block = groupBlock(ctx, g, chip, body)
            val rows = block.findViewWithTag<LinearLayout>(GROUP_ROWS_TAG)
            for (r in g.rows) {
                rows?.addView(notifRowView(ctx, r, g.launchPackage))
                if (r.id.isNotBlank()) pageRowIds += r.id
            }
            placeGroup(ctx, block, g.key, body)
        }
        // Counted as rendered even when every box went to the Archive: the user
        // filed them there on purpose and the Archive line at the bottom of the
        // page says how many, so filteredAwayNote would be a wrong explanation
        // for the gap it left behind.
        return ordered.size
    }

    /** Show=Unread inside one group: everything not swiped away, newest-first.
     *  Now a real stored predicate rather than a derived one — a row leaves
     *  this list because the user said so, so the filter and the swipe cannot
     *  disagree about what "unread" means. */
    private fun withinGroup(rows: List<NotifRow>): List<NotifRow> {
        val shown = if (showMode == "unread") rows.filter { isUnread(it) } else rows
        return shown.sortedByDescending { it.ts }
    }

    /**
     * One app / publisher drawn as a notification-shade GROUP: a real header
     * bar — icon, name, count chip — with its notifications folded under it.
     *
     * Tapping the header COLLAPSES the group, so a noisy app is one tap from
     * gone. That is why launching moved onto the icon: a header that both
     * folded and launched could only ever do one of the two, and folding is
     * the thing a shade full of one app's chatter actually needs.
     *
     * Everything here is a plain View. The block is returned whole, and the
     * row container is reached back through [GROUP_ROWS_TAG] — the same
     * tag-lookup trick the verdict chip uses via [GROUP_STATE_TAG] — so a late
     * ntfy poll can fill a group in without a field per group and without a
     * child fragment.
     */
    private fun groupBlock(
        ctx: android.content.Context, g: NotifGroup, state: GroupState,
        homeBody: LinearLayout,
    ): LinearLayout {
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val rows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = GROUP_ROWS_TAG
            isVisible = g.key !in collapsedGroups
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0x1FFFFFFF)
            }
            isClickable = true; isFocusable = true
        }
        header.addView(groupAvatar(ctx, g))
        val names = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { leftMargin = dp(10) }
        }
        names.addView(TextView(ctx).apply {
            text = g.label
            setTextColor(0xFFF2E9FF.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (g.sub.isNotBlank() && !g.sub.equals(g.label, ignoreCase = true)) {
            names.addView(TextView(ctx).apply {
                text = g.sub
                setTextColor(0x66FFFFFF.toInt())
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
        }
        header.addView(names)
        // The count chip. Kept as ONE TextView holding the whole verdict,
        // because an async ntfy group repaints it with a failure sentence that
        // has no count in it at all.
        header.addView(TextView(ctx).apply {
            text = state.text
            setTextColor(state.color)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(0x22FFFFFF)
            }
            tag = GROUP_STATE_TAG
        })
        // Clear all — the group-level twin of a swipe. A chatty app arrives
        // with a dozen rows, and swiping each one away is the wrong amount of
        // work for a decision the user made once, about the app rather than
        // about any single notification. Its own click listener, so it does
        // not reach the header's collapse handler.
        header.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_check_all)
            alpha = 0.55f
            contentDescription = ctx.getString(R.string.notif_mark_group_read)
            val sz = dp(18)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { leftMargin = dp(8) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                for (r in g.rows) readIds = StackFilters.markRead(ctx, filterPage, r.id, true)
                if (showMode == "unread") {
                    // The group just emptied itself out of this view, so it
                    // leaves with its header rather than sitting there as a
                    // bar with nothing under it.
                    block.isVisible = false
                } else {
                    // Rows stay but must repaint as read. Rebuilding them
                    // against the new readIds is cheaper than threading a
                    // painter callback per row all the way out to here.
                    rows.removeAllViews()
                    for (r in g.rows) rows.addView(notifRowView(ctx, r, g.launchPackage))
                }
            }
        })
        // Archive — the tick's twin, one level up. The tick is about the
        // NOTIFICATIONS ("I have read these"); this is about the APP ("stop
        // putting this box in front of me"), so it touches no read state and
        // the box leaves whole, to the page's Archive section.
        header.addView(archivePill(ctx, g.key, block, homeBody))
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_right)
            alpha = 0.5f
            rotation = if (rows.isVisible) 90f else 0f
            val sz = dp(16)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply { leftMargin = dp(6) }
        }
        header.addView(chevron)
        header.setOnClickListener {
            val open = !rows.isVisible
            rows.isVisible = open
            if (open) collapsedGroups -= g.key else collapsedGroups += g.key
            chevron.animate().rotation(if (open) 90f else 0f).setDuration(140).start()
        }
        block.addView(header)
        block.addView(rows)
        return block
    }

    // ── archived app boxes ─────────────────────────────────────────────
    //
    // Archived state is a StackFilters SELECTION under this page id: the same
    // prefs file, the same "<page>/<id>" key shape and the same commit()
    // discipline every other choice on the page already gets. Not a second
    // store — one that would have to learn, the hard way, the same lesson
    // about apply() losing a choice to process death.
    //
    // The id carries the `__` page-setting prefix [filterRow] already skips
    // drawing a control for, exactly like [FILTER_COLLAPSED]. Unlike that one
    // it is NOT declared in build.json and cannot be: there is one key per app
    // box, and which apps exist is discovered at runtime from what posted. So
    // the yes/no option set is supplied here rather than read off a
    // declaration; everything about how it is stored is unchanged.

    private fun yesNo(id: String) = Sections.StackFilter(
        id      = id,
        label   = "",
        default = "no",
        options = listOf(Sections.FilterOption("yes", "yes"), Sections.FilterOption("no", "no")),
    )

    private fun isArchived(ctx: android.content.Context, key: String): Boolean =
        StackFilters.selected(ctx, filterPage, yesNo(ARCHIVED_PREFIX + key)) == "yes"

    private fun setArchived(ctx: android.content.Context, key: String, archived: Boolean) {
        StackFilters.select(ctx, filterPage, ARCHIVED_PREFIX + key, if (archived) "yes" else "no")
    }

    /**
     * The Archive control on an app box's header.
     *
     * A WORD, not a glyph. "File this away" has no icon in this app's set, and
     * a borrowed folder would be one more symbol to learn sitting right next to
     * a tick that already means something else. It says what it does, and says
     * "Restore" once the box is in the Archive.
     *
     * It MOVES THE BOX, immediately. Writing the preference alone would leave
     * the box exactly where it was until the next visit, which reads as a dead
     * button — the same failure an in-memory dismissal gave the home banner.
     */
    private fun archivePill(
        ctx: android.content.Context, key: String, block: View, homeBody: LinearLayout,
    ): View = TextView(ctx).apply {
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xCCFFFFFF.toInt())
        setPadding(dp(7), dp(3), dp(7), dp(3))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(0x22FFFFFF)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(8) }
        isClickable = true
        isFocusable = true
        val paint: (Boolean) -> Unit = { archived ->
            text = if (archived) ARCHIVE_RESTORE_LABEL else ARCHIVE_LABEL
            contentDescription =
                if (archived) "Restore this app out of the Archive"
                else "Archive this app"
        }
        paint(isArchived(ctx, key))
        setOnClickListener {
            Haptics.tap(it)
            val archived = !isArchived(ctx, key)
            setArchived(ctx, key, archived)
            paint(archived)
            // Falls back to the card it came from rather than to nothing: a box
            // removed from one parent and added to none is a box the user just
            // deleted by accident.
            val target = (if (archived) archiveBox else homeBody) ?: homeBody
            (block.parent as? ViewGroup)?.removeView(block)
            // A restored box lands at the END of its card rather than back in
            // its sorted slot: the order is re-derived on the next visit, and a
            // box that visibly comes back beats one that needs the page
            // reopened before it reappears.
            target.addView(block)
            syncArchiveHeader()
        }
    }

    /** File a freshly built app box where the user left it: its own card, or
     *  the page's Archive. */
    private fun placeGroup(
        ctx: android.content.Context, block: View, key: String, homeBody: LinearLayout,
    ) {
        val archive = archiveBox
        if (archive != null && isArchived(ctx, key)) {
            archive.addView(block)
            syncArchiveHeader()
        } else homeBody.addView(block)
    }

    /** The page's Archive section: one collapsed line over the boxes the user
     *  filed away, remembered per page like every other choice on it.
     *
     *  Hidden entirely while it holds nothing. A permanent "Archive 0" line on
     *  a page nobody has ever archived from is chrome, and a control that opens
     *  an empty list teaches the user to stop reading that part of the page. */
    private fun buildArchiveSection(ctx: android.content.Context): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            isVisible = false
        }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        archiveOpen = StackFilters.selected(ctx, filterPage, yesNo(FILTER_ARCHIVE_OPEN)) == "yes"
        box.isVisible = archiveOpen
        val header = caption(ctx, "").apply {
            setPadding(dp(2), dp(4), dp(2), dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Haptics.tap(it)
                archiveOpen = !archiveOpen
                StackFilters.select(
                    ctx, filterPage, FILTER_ARCHIVE_OPEN, if (archiveOpen) "yes" else "no")
                box.isVisible = archiveOpen
                syncArchiveHeader()
            }
        }
        archiveBox    = box
        archiveWrap   = wrap
        archiveHeader = header
        syncArchiveHeader()
        wrap.addView(header)
        wrap.addView(box)
        return wrap
    }

    /** Keep the Archive line honest about what is behind it — the count, the
     *  open marker, and whether the section is worth drawing at all. Called
     *  from every path that can change the count, including a late ntfy poll. */
    private fun syncArchiveHeader() {
        val box = archiveBox ?: return
        archiveWrap?.isVisible = box.childCount > 0
        archiveHeader?.text =
            "Archive  ${if (archiveOpen) "▾" else "▸"}  ${box.childCount}"
    }

    /**
     * The app's own launcher icon when there is a package to ask for one, and
     * a coloured monogram when there is not.
     *
     * An ntfy topic has no launcher icon, and a shade with a blank column
     * where every icon should be reads as broken rather than as cloud — so the
     * cloud groups get a monogram instead of nothing. Tapping opens the app or
     * the channel, which is the affordance the header gave up to collapsing.
     */
    private fun groupAvatar(ctx: android.content.Context, g: NotifGroup): View {
        val icon: android.graphics.drawable.Drawable? =
            if (g.launchPackage.isBlank()) null
            else runCatching { ctx.packageManager.getApplicationIcon(g.launchPackage) }.getOrNull()
        val view: View = if (icon != null) ImageView(ctx).apply { setImageDrawable(icon) }
            else TextView(ctx).apply {
                text = g.label.trim().take(1).uppercase()
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(monogramColor(g.key))
                }
            }
        val sz = dp(30)
        view.layoutParams = LinearLayout.LayoutParams(sz, sz)
        if (g.launchPackage.isNotBlank() || g.url.isNotBlank()) {
            view.isClickable = true
            view.setOnClickListener {
                // A group that names a package IS that app, so launching it is
                // the whole point and stays a direct intent. A group that only
                // carries a URL is a published ntfy channel — plain web content
                // this app renders itself, so it goes through the dispatcher and
                // keeps the user (and the Back gesture) inside the notification
                // stack they tapped from.
                if (g.launchPackage.isNotBlank()) {
                    runCatching {
                        ctx.packageManager.getLaunchIntentForPackage(g.launchPackage)
                            ?.let { ctx.startActivity(it) }
                    }
                } else {
                    onTileClicked(g.url)
                }
            }
        }
        return view
    }

    /** A stable colour per publisher, derived from the grouping key, so the
     *  same topic keeps the same monogram between visits and no colour table
     *  has to be maintained alongside the channel catalog. */
    private fun monogramColor(key: String): Int {
        val hues = intArrayOf(
            0xFF6D5AE6.toInt(), 0xFF1F8A70.toInt(), 0xFFB5556D.toInt(),
            0xFF2C6FB5.toInt(), 0xFF9A6A2F.toInt(), 0xFF4E7A2A.toInt(),
        )
        return hues[((key.hashCode() % hues.size) + hues.size) % hues.size]
    }

    /**
     * One notification as a shade ROW: a status stripe, then title and
     * relative time on one line with the snippet under it.
     *
     * Dense on purpose. A notification centre shows many items, so the row
     * carries no card, no margin and no elevation, and neighbours are parted
     * by a hairline rather than by empty space.
     *
     * Unread is the one thing that must survive a glance, so it is said three
     * times over: the stripe lights up, the title goes bold, and the row takes
     * a faint tint. A read row says it by staying quiet.
     *
     * READ IS A SWIPE. Dragging the row sideways past a threshold flips it,
     * either way, and the row slides out and comes back in wearing the other
     * state so the gesture is visibly answered. See [attachSwipeToRead].
     */
    private fun notifRowView(
        ctx: android.content.Context, r: NotifRow, launchPackage: String,
    ): View {
        val holder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), dp(10), dp(7))
        }
        val stripe = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(3), LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply { rightMargin = dp(9) }
        }
        row.addView(stripe)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = r.title
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        line.addView(title)
        line.addView(TextView(ctx).apply {
            text = ago(System.currentTimeMillis() - r.ts)
            setTextColor(0x77FFFFFF.toInt())
            textSize = 10f
            setPadding(dp(8), 0, 0, 0)
        })
        col.addView(line)
        val snippet = if (r.text.isBlank()) null else TextView(ctx).apply {
            text = r.text
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(1), 0, 0)
        }
        snippet?.let { col.addView(it) }
        row.addView(col)
        holder.addView(row)
        holder.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x11FFFFFF)
        })

        // All three unread cues in one place, so a repaint after a swipe
        // cannot set two of them and forget the third.
        val paint: (Boolean) -> Unit = { unread ->
            row.setBackgroundColor(when {
                r.severity == "error" -> 0x33B91C1C
                r.severity == "warn"  -> 0x33D97706
                unread                -> 0x14FFFFFF
                else                  -> 0x00000000
            })
            stripe.setBackgroundColor(when {
                r.severity == "error" -> 0xFFFF6B6B.toInt()
                r.severity == "warn"  -> 0xFFFFB020.toInt()
                unread                -> 0xFF7C5CFF.toInt()
                else                  -> 0x00000000
            })
            title.setTextColor(if (unread) 0xFFF2E9FF.toInt() else 0xBBE9D8FD.toInt())
            title.typeface = if (unread) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            snippet?.setTextColor(if (unread) 0xAAE9D8FD.toInt() else 0x77E9D8FD.toInt())
        }
        paint(isUnread(r))

        val openApp: (() -> Unit)? =
            if (launchPackage.isBlank()) null else ({
                runCatching {
                    ctx.packageManager.getLaunchIntentForPackage(launchPackage)
                        ?.let { ctx.startActivity(it) }
                }
                Unit
            })
        if (r.id.isNotBlank()) {
            attachSwipeToRead(holder, r, paint, openApp)
        } else if (openApp != null) {
            // No identity to remember, so no swipe — but the tap must survive.
            row.isClickable = true
            row.setOnClickListener { openApp() }
        }
        return holder
    }

    /**
     * Swipe a row sideways — either direction — to flip its read state.
     *
     * Hand-rolled on a touch listener because this list is plain Views in a
     * LinearLayout inside a ScrollView (no RecyclerView anywhere on this path,
     * deliberately), so ItemTouchHelper does not apply and no dependency is
     * added to get one.
     *
     * VERTICAL SCROLLING IS PRESERVED by claiming the gesture late. The listener
     * takes the stream on DOWN but decides nothing; on the first movement past
     * the touch slop it compares |dx| to |dy|, and only a clearly sideways drag
     * calls requestDisallowInterceptTouchEvent. A vertical drag is never
     * claimed, so the ScrollView intercepts it exactly as before and this row
     * receives ACTION_CANCEL and settles back.
     *
     * The gesture is always visibly answered: the row follows the finger,
     * leaves in the direction it was thrown, and returns from the other side
     * already wearing the other state. A swipe that changed nothing on screen
     * would read as a broken row.
     */
    private fun attachSwipeToRead(
        holder: View, r: NotifRow, paint: (Boolean) -> Unit, onTap: (() -> Unit)?,
    ) {
        val ctx = holder.context
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var decided = false; var horizontal = false
        // rawX/rawY, not x/y: the view is being translated under the finger,
        // so view-local coordinates drift with the animation we are driving.
        holder.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    decided = false; horizontal = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!decided && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        decided = true
                        horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                        if (horizontal) v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal) {
                        v.translationX = dx
                        v.alpha = 1f - (kotlin.math.abs(dx) / (v.width.coerceAtLeast(1) * 2f))
                            .coerceIn(0f, 0.55f)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - downX
                    val far = kotlin.math.abs(dx) >
                        kotlin.math.max(dp(64).toFloat(), v.width * 0.22f)
                    when {
                        horizontal && far -> flipRead(v, r, paint, dx > 0)
                        horizontal        -> settle(v)
                        !decided          -> onTap?.invoke()
                        else              -> settle(v)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> { settle(v); true }
                else -> false
            }
        }
    }

    /** Slide [v] out towards [toRight], persist the flipped state, then bring
     *  it back from the opposite edge already repainted. Under Show=Unread a
     *  row that just became read has nowhere to return TO, so it stays gone
     *  rather than reappearing in a list that excludes it. */
    private fun flipRead(v: View, r: NotifRow, paint: (Boolean) -> Unit, toRight: Boolean) {
        val makeRead = isUnread(r)
        val out = (if (toRight) 1f else -1f) * v.width.coerceAtLeast(1)
        v.animate().translationX(out).alpha(0f).setDuration(150).withEndAction {
            readIds = StackFilters.markRead(v.context, filterPage, r.id, makeRead)
            if (showMode == "unread" && makeRead) {
                v.visibility = View.GONE
                v.translationX = 0f
                v.alpha = 1f
            } else {
                paint(!makeRead)
                v.translationX = -out
                v.animate().translationX(0f).alpha(1f).setDuration(170).start()
            }
        }.start()
    }

    private fun settle(v: View) {
        v.animate().translationX(0f).alpha(1f).setDuration(140).start()
    }

    /** The divider between two halves of one stream — small, upper-case and
     *  quiet, the way a shade separates "Silent" from "Notifications". It is
     *  deliberately lighter than a group header: a group is a thing you can
     *  fold and tap, and this is not. */
    private fun shadeLabel(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0x66FFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textSize = 10f
            letterSpacing = 0.12f
            setPadding(dp(2), dp(14), 0, dp(2))
        }

    /** A card-level verdict line, for the states that apply to a whole stream
     *  rather than to one app. */
    private fun stateLine(ctx: android.content.Context, text: String, color: Int): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, 0)
        }

    private fun ago(ms: Long): String = when {
        ms < 60_000     -> "just now"
        ms < 3_600_000  -> "${ms / 60_000}m ago"
        ms < 86_400_000 -> "${ms / 3_600_000}h ago"
        else            -> "${ms / 86_400_000}d ago"
    }

    /** Settings.Secure.enabled_notification_listeners is a colon-
     *  separated flat string of ComponentName.flattenToString()s. We
     *  match on packageName alone — sufficient since only ONE listener
     *  per package can be enabled at a time. */
    private fun isNotificationAccessGranted(ctx: android.content.Context): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return flat.contains(ctx.packageName)
    }

    /** Round-robin pool of stable host ids. View.generateViewId() crashes
     *  on FragmentManager restore because the new id won't match the
     *  saved-state host id. Stable resource ids survive process death. */
    private val embedHostIds = intArrayOf(
        R.id.stack_embed_0, R.id.stack_embed_1, R.id.stack_embed_2, R.id.stack_embed_3,
        R.id.stack_embed_4, R.id.stack_embed_5, R.id.stack_embed_6, R.id.stack_embed_7,
    )
    private var nextEmbedIdx = 0

    private fun embedChild(body: LinearLayout, frag: Fragment) {
        val hostId = embedHostIds[nextEmbedIdx % embedHostIds.size]
        nextEmbedIdx++
        val host = FrameLayout(body.context).apply {
            id = hostId
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        body.addView(host)
        // Only commit when there's nothing already attached at that host
        // — on restore the FragmentManager re-binds the existing inner
        // fragment to the same id, so we mustn't overwrite it.
        if (childFragmentManager.findFragmentById(hostId) == null) {
            childFragmentManager.beginTransaction()
                .replace(hostId, frag)
                .commit()
        }
    }

    private fun renderLinktreeSlide(
        ctx: android.content.Context,
        body: LinearLayout,
        slideId: String,
    ) {
        val slide = Sections.linktreeSlide(slideId)
        if (slide == null) {
            body.addView(emptyHint(ctx, "linktree slide not found: $slideId"))
            return
        }
        renderLinkGrid(ctx, body, slide.columns, emptyList())
    }

    private fun renderLinkGrid(
        ctx: android.content.Context,
        body: LinearLayout,
        columns: List<Sections.LinkColumn>,
        flatLinks: List<Sections.LinkItem>,
    ) {
        // Sub-section header per column → N-icon grid of links beneath it.
        // N comes from build.json::ui.tile_columns (data-driven, no hardcode).
        val cols = BuildConfig.UI_TILE_COLUMNS.coerceAtLeast(1)
        if (columns.isNotEmpty()) {
            for (col in columns) {
                if (col.header.isNotBlank()) body.addView(colHeader(ctx, col.header, col.headerUrl))
                addIconGrid(ctx, body, col.links, cols)
            }
        }
        if (flatLinks.isNotEmpty()) addIconGrid(ctx, body, flatLinks, cols)
    }

    /** Add `links` as a wrap-flowing `cols`-column grid of icon tiles to
     *  `body`. Each row is its own horizontal LinearLayout so the grid
     *  works with any link count (padding cells fill the final row). */
    private fun addIconGrid(
        ctx: android.content.Context,
        body: LinearLayout,
        links: List<Sections.LinkItem>,
        cols: Int,
    ) {
        if (links.isEmpty()) return
        var i = 0
        while (i < links.size) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            for (c in 0 until cols) {
                if (i < links.size) row.addView(linkIconTile(ctx, links[i++]))
                else                row.addView(spacerTile(ctx))
            }
            body.addView(row)
        }
    }

    /** Icon + label tile cell (weight = 1 → 1/Nth row width). */
    private fun linkIconTile(ctx: android.content.Context, link: Sections.LinkItem): View {
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val padH = dp(4); val padV = dp(8)
            setPadding(padH, padV, padH, padV)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val iv = ImageView(ctx).apply {
            val resId = Sections.iconResFor(ctx, link.icon)
            if (resId != 0) setImageResource(resId)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE9D8FD.toInt())
            val sz = dp(28)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        val lbl = TextView(ctx).apply {
            text = link.label
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            gravity = android.view.Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        cell.addView(iv); cell.addView(lbl)
        cell.setOnClickListener { openUrlOrTarget(link.url) }
        return cell
    }

    private fun spacerTile(ctx: android.content.Context): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

    /** Mini-tile index row. The card look and the wrap-at-`ui.tile_columns`
     *  layout both live in [IndexTiles], because Configs ▸ About draws the
     *  same index and is not a stack — keeping the drawing here would have
     *  meant two copies of it. */
    private fun renderTileRow(body: LinearLayout, tiles: List<Sections.AggTile>) {
        val ctx = body.context
        body.addView(IndexTiles.grid(
            ctx,
            com.diegonmarcos.superapp.BuildConfig.UI_TILE_COLUMNS,
            tiles.map { tile ->
                IndexTiles.Cell(
                    label   = tile.label,
                    iconRes = Sections.iconResFor(ctx, tile.iconName),
                    // openUrlOrTarget, not onTileClicked directly, so `anchor:`
                    // tiles scroll this page instead of being handed to the
                    // activity's navigating dispatcher (no case for them there).
                    onClick = { openUrlOrTarget(tile.target) },
                )
            },
        ))
    }

    private fun renderMailAccounts(ctx: android.content.Context, body: LinearLayout) {
        val accounts = Sections.mailAccounts()
        if (accounts.isEmpty()) {
            body.addView(caption(ctx, "No accounts declared. Add via build.json::ui.mail_accounts or the Import flow."))
            return
        }
        for (acct in accounts) {
            val transport = when (acct.kind) {
                "jmap"      -> "JMAP"
                "imap"      -> "IMAP/STARTTLS"
                "imaps"     -> "IMAPS · SMTPS"
                "exchange"  -> "Exchange"
                else        -> acct.kind.uppercase()
            }
            val portSuffix = when {
                acct.imapPort > 0 && acct.smtpPort > 0 -> "  · ${acct.imapPort}/${acct.smtpPort}"
                acct.imapPort > 0                      -> "  · imap:${acct.imapPort}"
                acct.smtpPort > 0                      -> "  · smtp:${acct.smtpPort}"
                else                                   -> ""
            }
            body.addView(linkRow(ctx, Sections.LinkItem(
                label = "${acct.label}  ·  $transport$portSuffix",
                url   = "section:mail",
            )))
        }
        body.addView(caption(ctx, "Unread / total counts pending JMAP slice C2 + IMAP slice."))
    }

    private fun renderChatPlaceholder(
        ctx: android.content.Context, body: LinearLayout,
        kind: String, target: String,
    ) {
        body.addView(linkRow(ctx, Sections.LinkItem(
            label = "Open $kind",
            url   = target,
        )))
        body.addView(caption(ctx, "server list + unread counts pending integration"))
    }

    private fun renderOpenLink(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        body.addView(linkRow(ctx, Sections.LinkItem(
            label = panel.title.ifBlank { panel.url },
            url   = panel.url,
            icon  = panel.iconName,
        )))
    }

    private fun renderPlaceholder(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        body.addView(caption(ctx, panel.subtitle.ifBlank { "Coming soon" }))
    }

    /** kind=stats — a mock dashboard surface: one label/value line per
     *  declared row, with a "mock data" footer so it's clear the numbers
     *  are placeholders until the card's live fetch is plumbed in. */
    private fun renderStats(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        if (panel.subtitle.isNotBlank()) body.addView(caption(ctx, panel.subtitle))
        for (r in panel.rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                val p = dp(5); setPadding(0, p, 0, p)
            }
            row.addView(TextView(ctx).apply {
                text = r.label
                setTextColor(0x99FFFFFF.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(ctx).apply {
                text = r.value
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            })
            body.addView(row)
        }
        body.addView(caption(ctx, "Mock data — live fetch pending"))
    }

    // ── Inbox cards: the summary, then what actually arrived ───────────
    //
    // An Inboxes card carries that inbox's counts. Counts answer "how much is
    // in there"; they do not answer "what arrived", which is the question that
    // makes someone open the app. Both belong in the one box, so the card keeps
    // its stats and grows the posting app's own notification boxes underneath
    // them — the same boxes, with the same read swipe, tick and Archive, as the
    // Notify page draws.
    //
    // WHICH app a card is about is DATA. There is deliberately no kind→package
    // table in this file: a package list in Kotlin starts rotting the day it is
    // written, which is the same reason [renderPhoneCenter] groups on the key
    // the notification itself carries rather than on a roster of apps.

    /** The `ui.external_apps` entry this card is an inbox for, or null when the
     *  panel declares none.
     *
     *  ONE route, and it is exact: the `extapp:<id>` target the panel declares.
     *  A second route used to match the panel title against an app's declared
     *  label, and it is gone. It bound a card's notifications to a
     *  human-readable string, so relabelling a card silently moved or dropped
     *  its boxes; and because the label is not unique to one card, the Inboxes
     *  page had both a `chat_matrix` card and an Element summary card claiming
     *  the same app, which drew the same notifications twice. Nothing fuzzy
     *  either: a near-match would quietly file one app's notifications under
     *  another app's box, and a notification shown against the wrong app is
     *  worse than one not shown at all. */
    private fun inboxApp(panel: Sections.StackPanel): Sections.ExternalApp? =
        (listOf(panel.url) + panel.links.map { it.url })
            .firstOrNull { it.startsWith(EXTAPP_PREFIX) }
            ?.removePrefix(EXTAPP_PREFIX)?.substringBefore('/')
            ?.let { Sections.externalApp(it) }

    /** Every package that IS this app on a device: the hub id, the resigned
     *  stock alt, the install target and each fork. A notification can arrive
     *  under any of them — which one depends on how this phone got the app, and
     *  that is precisely what [Sections.ExternalApp.altPackage] exists for. */
    private fun inboxPackages(app: Sections.ExternalApp): Set<String> =
        (listOf(app.hubPackage, app.altPackage, app.installPackage) + app.forks.values)
            .filterNot { it.isBlank() }.toSet()

    /** The central-list FOLDER — the KIND — this card is the inbox for, or ""
     *  when it speaks only for its own app's packages.
     *
     *  DERIVED, never declared, and that is the point. The panel already names
     *  one app (`extapp:<id>`); `ui.phone_folders` already says which folder
     *  that app classifies into; so the card is the inbox for that folder, and
     *  its list is that folder's slice of the SAME feed Notify draws rather
     *  than a second, narrower restatement of it. Nothing new is written down,
     *  and a folder retuned in build.json moves this card with it.
     *
     *  Why this was worth changing: keyed on the app's own packages alone, a
     *  card could only ever show what OUR companion app posted. The mail card
     *  read one package while the mail on the phone arrived from every other
     *  mail client the user actually has, so the card reported "nothing
     *  captured" beside a feed that was full — a true sentence about the wrong
     *  question.
     *
     *  A folder TWO cards on this page would both claim is claimed by NEITHER.
     *  Chat · Matrix and Chat · Mattermost both classify into @Chat, and
     *  letting both expand would draw every chat notification twice on one
     *  page — the same double-draw the title-matching route was removed for.
     *  Those cards stay on their own roster, and the kind summary above them
     *  still reports the folder's full count, so what they do not list is
     *  disclosed rather than hidden. */
    private fun inboxFolderId(ctx: android.content.Context, panel: Sections.StackPanel): String {
        val mine = inboxApp(panel)?.let { folderOfApp(ctx, it) }.orEmpty()
        if (mine.isEmpty()) return ""
        val claimants = pagePanels.count {
            inboxApp(it)?.let { app -> folderOfApp(ctx, app) }.orEmpty() == mine
        }
        return if (claimants == 1) mine else ""
    }

    /** The one section folder every package of [app] agrees on, or "" when
     *  they disagree or land somewhere sectionless. A hub and its resigned
     *  stock alt are the same app to the user and must be the same kind; if
     *  the classification does not think so, the card is not entitled to speak
     *  for a kind at all. */
    private fun folderOfApp(ctx: android.content.Context, app: Sections.ExternalApp): String =
        inboxPackages(app)
            .map { PhoneTaxonomy.folderIdOf(it, app.label, ctx) }
            .distinct().singleOrNull()
            ?.takeIf { isSectionFolder(it) }
            .orEmpty()

    /** A folder that belongs to a section, i.e. whose label opens with a
     *  prefix character. The Misc exile and the Others sink open with a letter
     *  and are not kinds — they are where an unclassified app waits. */
    private fun isSectionFolder(folderId: String): Boolean =
        PhoneTaxonomy.folderLabelOf(folderId).firstOrNull()?.isLetterOrDigit() == false

    /** A folder label as a KIND reads on screen: "@Mail" is how the user sorts
     *  their launcher, "Mail" is what a notification is. */
    private fun kindLabel(folderId: String): String =
        PhoneTaxonomy.folderLabelOf(folderId).dropWhile { !it.isLetterOrDigit() }

    // ── Summary by KIND ────────────────────────────────────────────────
    //
    // Between a card's summary and its list: how the notifications on this page
    // divide by kind. The kinds are the CENTRAL classification's own folders —
    // ui.phone_folders, the list Notify filters by and the list every card here
    // derives its own membership from. There is deliberately no fourth taxonomy
    // behind this box: a kind it could show that no other surface knows about
    // would be the same defect this page was just fixed for.
    //
    // THE RULE THIS BOX IS BUILT AROUND. A count of zero and a question nobody
    // asked are different facts and have to look different. A confident "0"
    // beside a kind that was never queried is indistinguishable from a quiet
    // inbox, and the owner would have no way to tell which one they are looking
    // at — the same reason a control that misreports its state is treated as a
    // defect here. So a kind with no source says "no source", never "0".

    /** The value a kind shows when nothing has been asked on its behalf. */
    private val NO_SOURCE = "no source"

    private fun renderKindSummary(ctx: android.content.Context, body: LinearLayout) {
        body.addView(shadeLabel(ctx, "BY KIND"))
        if (PhoneTaxonomy.folders.isEmpty()) {
            // The classification itself did not arrive — build.json's
            // phone_folders never reached BuildConfig. Every count below would
            // be a number about a list we do not have.
            body.addView(stateLine(ctx, "$NO_SOURCE · classification unavailable", SIGNAL_UNKNOWN))
            body.addView(caption(ctx, "ui.phone_folders did not reach this build, so there are " +
                "no kinds to divide the feed into. Nothing here is empty; it is unasked."))
            return
        }
        val granted = isNotificationAccessGranted(ctx)
        val feed = if (granted) PhoneNotificationStore.all(ctx) else emptyList()
        if (granted) PhoneTaxonomy.prime(ctx, feed.associate { it.packageName to it.appLabel })
        val counted = feed
            .groupingBy { PhoneTaxonomy.folderIdOf(it.packageName, it.appLabel, ctx) }
            .eachCount()
        // The kinds this page is ABOUT stay listed even at zero: a card that
        // promises mail and has none has to say "0", or the reader cannot tell
        // the promise was kept. Every other kind is listed only when the feed
        // actually carries it — "whatever kinds exist in the data", rather than
        // all thirty-four folders of a launcher grid.
        val declared = pagePanels
            .mapNotNull { p -> inboxApp(p)?.let { folderOfApp(ctx, it) } }
            .filter { it.isNotEmpty() }
        val ids = PhoneTaxonomy.folders.map { it.id }
            .filter { isSectionFolder(it) && (counted[it] != null || it in declared) }
        for (id in ids) {
            body.addView(kindRow(ctx, kindLabel(id),
                if (granted) (counted[id] ?: 0).toString() else NO_SOURCE, granted))
        }
        // Notifications the classification could not place. Not a kind and not
        // a zero — it is the size of the gap in ui.phone_folders, which is the
        // very thing that made a whole category invisible, so it is reported
        // rather than folded into the counts above.
        val unplaced = counted.filterKeys { it.isNotEmpty() && !isSectionFolder(it) }.values.sum()
        if (unplaced > 0) body.addView(kindRow(ctx, "Unclassified", unplaced.toString(), true))
        if (!granted) {
            body.addView(caption(ctx, "Notification Access is off, so no kind above has been " +
                "queried. Those are not zeroes — grant access and they become counts."))
        } else if (ids.isEmpty() && unplaced == 0) {
            body.addView(stateLine(ctx, "silent · the phone feed is empty", SIGNAL_WARN))
        }
        body.addView(caption(ctx, "Counted from the phone notification listener. The cloud " +
            "stream is not counted here and cannot be: ui.ntfy.taxon classifies a publisher by " +
            "SECTION prefix, never by folder, so a cloud message has no kind to be counted " +
            "under. Notify ▸ Inboxes is where that stream is shown."))
    }

    /** One kind and its number. [sourced] false greys the value, because the
     *  text in it is a statement about the source and not a measurement. */
    private fun kindRow(
        ctx: android.content.Context, label: String, value: String, sourced: Boolean,
    ): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val p = dp(4); setPadding(0, p, 0, p)
        addView(TextView(ctx).apply {
            text = label
            setTextColor(0x99FFFFFF.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(ctx).apply {
            text = value
            setTextColor(if (sourced) 0xFFFFFFFF.toInt() else SIGNAL_UNKNOWN)
            textSize = 13f
            typeface = if (sourced) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        })
    }

    private fun renderInboxNotifications(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        // Between the summary above and the list below, on every card — the
        // page's own division of what arrived, so a card that lists none of a
        // kind still shows where that kind went.
        renderKindSummary(ctx, body)
        val app = inboxApp(panel)
        if (app == null) {
            // Nothing is drawn, on purpose. A card whose inbox declares no app
            // is a gap in build.json, and the person holding the phone cannot
            // close it — the declaration grammar printed under every card put a
            // paragraph of instructions where a count belongs, addressed to a
            // reader who was not there. The explanation now goes where the
            // reader who CAN act on it looks: logcat, at the moment the gap
            // bites and naming the panel that has it, plus the contract written
            // out beside the panels themselves (build.json _doc_stack_msgs).
            //
            // Only the inbox kinds are logged. A mail or chat card IS an inbox
            // by construction, so a missing declaration there is a bug; a
            // `stats` card is a generic dashboard surface other pages reuse
            // (Projects ▸ PM boards), and one that names no app is not a gap at
            // all — it simply stays the dashboard it already was.
            if (panel.kind in INBOX_KINDS) android.util.Log.w(TAG,
                "inbox panel '${panel.title}' (kind=${panel.kind}) declares no extapp: " +
                "target, so no notifications can be shown under it")
            return
        }
        val folderId = inboxFolderId(ctx, panel)
        // The header says WHAT the list below is a list of. A card speaking for
        // a kind draws every app of that kind, so calling it "notifications"
        // and leaving the reader to infer the scope is how a page ends up
        // looking wrong when it is right.
        body.addView(shadeLabel(ctx,
            if (folderId.isEmpty()) "NOTIFICATIONS" else "NOTIFICATIONS · ${kindLabel(folderId)}"))
        if (!isNotificationAccessGranted(ctx)) {
            // Drawing an empty list here would be a failure reporting success —
            // the same distinction [renderPhoneCenter] makes for a whole page.
            body.addView(stateLine(ctx, "unavailable · permission not granted", SIGNAL_UNKNOWN))
            body.addView(caption(ctx, "Notification Access is off, so nothing this app posts is " +
                "captured at all. This is empty because we cannot read it, not because the " +
                "inbox is quiet."))
            return
        }
        val feed = PhoneNotificationStore.all(ctx)
        // Classify the whole feed once, then select from it — the card
        // CONSUMES the stream every other surface reads instead of asking the
        // store its own narrower question.
        PhoneTaxonomy.prime(ctx, feed.associate { it.packageName to it.appLabel })
        val packages = inboxPackages(app)
        val stored = if (folderId.isEmpty()) feed.filter { it.packageName in packages }
            else feed.filter { PhoneTaxonomy.folderIdOf(it.packageName, it.appLabel, ctx) == folderId }
        if (stored.isEmpty()) {
            // Granted and empty is a real, different state: amber, and it names
            // what came up empty — the KIND when this card speaks for one, the
            // app when it speaks only for itself. The two are different claims
            // and the line has to say which one it is making.
            val subject = if (folderId.isEmpty()) app.label else kindLabel(folderId)
            body.addView(stateLine(ctx, "silent · nothing captured from $subject", SIGNAL_WARN))
            return
        }
        // Grouped by package, not folded into one box: a hub and its forks are
        // separate apps on the device, each with its own icon, count chip and
        // Archive control.
        //
        // No pruneRead here, on purpose. This is ONE app's slice of the phone
        // namespace, and [StackFilters.pruneRead] against a partial view is
        // exactly what makes read rows silently reappear. The complete
        // enumeration belongs to the Notify page's phone card, which has it.
        val groups = stored.groupBy { it.packageName }.map { (pkg, entries) ->
            NotifGroup(
                key           = pkg,
                // The package's own label, falling back to the package: a card
                // speaking for a kind draws apps the declaration never named,
                // so [app] is no longer the right thing to call an unlabelled
                // group.
                label         = entries.firstOrNull { it.appLabel.isNotBlank() }?.appLabel ?: pkg,
                sub           = pkg,
                launchPackage = pkg,
                // PHONE_NS, the same namespace [renderPhoneCenter] writes: a row
                // read here is read there too, because it is the same
                // notification and not a copy of one.
                rows          = entries.map {
                    NotifRow(it.ts, it.title.ifBlank { it.appLabel }, it.text,
                        id = PHONE_NS + it.key)
                },
            )
        }
        renderGroups(ctx, body, groups)
    }

    // ── kind=class_inbox: one card per CHANNEL CLASS ──────────────────
    //
    // The owner asked (2026-09-10) for this page to stop being one card per app
    // and become Mail / Chat / Messenger / RSS, each with a summary of its whole
    // class and, under it, only that class's notifications.
    //
    // WHICH PACKAGES ARE IN A CLASS IS DATA and is not reachable from this file.
    // [InboxClasses] reads build.json::ui.inbox_classes; a `when (packageName)`
    // here would put the owner's own phone behind a release every time they
    // installed a messaging app, which is the failure the whole shape exists to
    // avoid.

    /** The card name as the phone shows it: the declared string resource when
     *  the panel names one, otherwise the English word build.json carries.
     *
     *  Generic on purpose rather than a class_inbox special case — a card name
     *  is user-visible text on every kind, and the four names the owner chose
     *  (Mail, Chat, Messenger, RSS) are exactly the kind of plain word that
     *  reads as untranslated rather than as deliberately kept. */
    private fun panelTitle(ctx: android.content.Context, panel: Sections.StackPanel): String {
        if (panel.titleRes.isNotBlank()) {
            val id = resources.getIdentifier(panel.titleRes, "string", ctx.packageName)
            // A resource build.json names and this build does not carry is a
            // gap for whoever edits the file, so it goes to logcat and the card
            // falls back to its declared word rather than rendering blank.
            if (id != 0) return getString(id)
            android.util.Log.w(TAG, "panel '${panel.title}' declares title_res " +
                "'${panel.titleRes}', which this build has no string for")
        }
        return panel.title.ifBlank { panel.kind.replace('_', ' ') }
    }

    /** A string named in build.json, or "" when this build carries no such
     *  resource. Same contract as [panelTitle]: the data may name a string this
     *  build does not have, and that must not crash a card. */
    private fun stringByName(ctx: android.content.Context, name: String): String {
        if (name.isBlank()) return ""
        val id = resources.getIdentifier(name, "string", ctx.packageName)
        return if (id == 0) "" else getString(id)
    }

    private fun renderClassInbox(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        val name = panelTitle(ctx, panel)
        val cls = InboxClasses.byId(panel.classId)
        if (cls == null) {
            // The panel names a class ui.inbox_classes does not declare. Drawing
            // an empty list would say "nothing arrived"; this says "we cannot
            // tell", which is the true statement and the different one.
            android.util.Log.w(TAG, "class_inbox panel '${panel.title}' names class " +
                "'${panel.classId}', which build.json::ui.inbox_classes does not declare")
            body.addView(stateLine(ctx, getString(R.string.inbox_class_undeclared), SIGNAL_UNKNOWN))
            return
        }
        // The catch-all is what stops this page losing a notification, so its
        // absence is reported on every card rather than discovered by an app
        // going missing.
        if (InboxClasses.catchAll == null) {
            body.addView(caption(ctx, getString(R.string.inbox_class_no_catch_all)))
        }

        val granted = isNotificationAccessGranted(ctx)
        if (!granted) {
            // Identical treatment to every other card on this page: a missing
            // capability is not an empty inbox, and an empty list drawn here
            // would be a failure reporting success.
            body.addView(stateLine(ctx, "unavailable · permission not granted", SIGNAL_UNKNOWN))
            body.addView(caption(ctx, "Notification Access is off, so nothing this class " +
                "carries is captured at all. This is empty because we cannot read it, not " +
                "because the inbox is quiet."))
            return
        }

        // Classify the whole feed once and select from it — this card CONSUMES
        // the stream every other surface reads rather than asking the store its
        // own narrower question, which is how two surfaces start disagreeing
        // about the same notification.
        val feed = PhoneNotificationStore.all(ctx)
        PhoneTaxonomy.prime(ctx, feed.associate { it.packageName to it.appLabel })
        val mine = feed.filter {
            InboxClasses.claims(cls.id, it.packageName, it.appLabel, ctx)
        }
        val groups = mine.groupBy { it.packageName }.map { (pkg, entries) ->
            NotifGroup(
                key           = pkg,
                label         = entries.firstOrNull { it.appLabel.isNotBlank() }?.appLabel ?: pkg,
                sub           = pkg,
                launchPackage = pkg,
                // PHONE_NS, the same namespace renderPhoneCenter writes: a row
                // read here is read there too, because it is the same
                // notification and not a copy of one.
                rows          = entries.map {
                    NotifRow(it.ts, it.title.ifBlank { it.appLabel }, it.text,
                        id = PHONE_NS + it.key)
                },
            )
        }
        renderClassSummary(ctx, body, cls, name, groups, ntfyTopicsOf(panel).size)
        stringByName(ctx, cls.noteRes).takeIf { it.isNotBlank() }
            ?.let { body.addView(caption(ctx, it)) }

        body.addView(shadeLabel(ctx, getString(R.string.inbox_notifications_header, name)))
        // A class whose material is the ntfy CHANNEL stream reuses the panel
        // renderer the C3 Ntfy card already had — the owner asked for "only C3
        // ntfy channels", which is the scope that renderer already has, so this
        // is a retitle and not new plumbing. A class may declare both, and RSS
        // does: the same messages arrive by two roads and belong on one card.
        if (cls.source == C3NTFY_SOURCE) renderNtfyGroups(ctx, body, panel)
        if (groups.isNotEmpty()) {
            renderGroups(ctx, body, groups)
        } else if (cls.source != C3NTFY_SOURCE) {
            // Granted and empty is a real, different state from unavailable,
            // and the line names WHICH class came up empty rather than leaving
            // the reader to infer the scope of the silence.
            body.addView(stateLine(ctx, getString(R.string.inbox_class_silent, name), SIGNAL_WARN))
        }
    }

    /**
     * The counted summary above a class card.
     *
     * WHAT A SUMMARY IS ON THIS PAGE, honestly: before this, `kind=stats` printed
     * label/value rows declared in build.json under the caption "Mock data —
     * live fetch pending" — the Element card's "Rooms 14 · Unread 6" was a
     * number nobody measured. The only thing on this page that was ever counted
     * from the device is the BY KIND box. So the summary here is counts, taken
     * from the same feed the list below is taken from: how many apps or channels
     * the class holds, how many notifications, and how long ago the newest one
     * arrived. No model call — a digest the owner did not ask to pay for would
     * be a worse card than a number that is true.
     */
    private fun renderClassSummary(
        ctx: android.content.Context,
        body: LinearLayout,
        cls: InboxClasses.InboxClass,
        name: String,
        groups: List<NotifGroup>,
        channels: Int,
    ) {
        body.addView(shadeLabel(ctx, getString(R.string.inbox_summary_header, name)))
        val notifications = groups.sumOf { it.rows.size }
        val newest = groups.maxOfOrNull { it.newest } ?: 0L
        // A c3ntfy class counts CHANNELS as well as apps, and the two are
        // different things rather than two names for one number: the channels
        // are ntfy topics reached over the cloud, the apps are packages on this
        // phone. Labelling the phone-group count "Channels" would have been a
        // number under the wrong name, which is the failure this page keeps
        // being fixed for.
        if (cls.source == C3NTFY_SOURCE) {
            body.addView(kindRow(ctx,
                getString(R.string.inbox_summary_channels), channels.toString(), true))
        }
        // Suppressed at zero ONLY for a channel class, where "0 apps" is noise
        // beside the channel count. Every other class shows it, including 0:
        // a card that promises a class and holds nothing has to say so.
        if (cls.source != C3NTFY_SOURCE || groups.isNotEmpty()) {
            body.addView(kindRow(ctx,
                getString(R.string.inbox_summary_apps), groups.size.toString(), true))
        }
        body.addView(kindRow(ctx,
            getString(R.string.inbox_summary_notifications), notifications.toString(), true))
        body.addView(kindRow(ctx, getString(R.string.inbox_summary_latest),
            if (newest > 0L) ago(System.currentTimeMillis() - newest)
            else getString(R.string.inbox_summary_latest_none),
            newest > 0L))
        body.addView(caption(ctx, getString(R.string.inbox_summary_counted)))
        // The channel boxes fetch their own messages after this card is drawn,
        // so there is no channel message count to put in the rows above. A zero
        // there would be a measurement nobody took.
        if (cls.source == C3NTFY_SOURCE) {
            body.addView(caption(ctx, getString(R.string.inbox_summary_channels_note)))
        }
    }

    /** The ntfy topics a c3ntfy card will actually draw — the SAME expression
     *  [renderNtfyGroups] selects with, so the summary can never count a channel
     *  the card below does not show. */
    private fun ntfyTopicsOf(panel: Sections.StackPanel): List<String> {
        val scopes = com.diegonmarcos.superapp.rss.NtfyScopes.load()
        val catalog = com.diegonmarcos.superapp.rss.NtfyScopes.fallbackChannels()
        return (if (panel.scopes.isEmpty()) catalog else catalog.filter {
            com.diegonmarcos.superapp.rss.NtfyScopes.scopeOf(it, scopes).id in panel.scopes
        }).filter { cloudTaxonomyKeeps(it) }
    }

    /** Grey = we do not know. Deliberately NOT red: red is a claim about the
     *  fleet, and a failed fetch is a claim about this phone's network. */
    private val SIGNAL_UNKNOWN = 0xFF9E9E9E.toInt()
    private val SIGNAL_OK      = 0xFF34C759.toInt()
    private val SIGNAL_WARN    = 0xFFFFB020.toInt()

    /** kind=cloud_dashboard — live container map. Decodes the baked service
     *  inventory (data/services_{private,public}.json → BuildConfig.SERVICES_*_B64),
     *  buckets every container by category into the card's [dashGroups], draws a
     *  TCP-ping status dot per container ({name}.app — green up / red down / grey
     *  checking, meaningful only over WireGuard) and opens that .app in the in-app
     *  browser on tap. Provider groups render external consoles (no ping). */
    /** The anchor id [panel] declared for the header identified by [groupId] +
     *  [subLabel] (blank [subLabel] = the group header itself), or null when
     *  the panel declared none — an undeclared header is simply not an anchor
     *  target, which is what makes build.json the whole truth. */
    private fun declaredAnchor(
        panel: Sections.StackPanel, groupId: String, subLabel: String,
    ): String? = panel.anchors
        .firstOrNull { it.group == groupId && it.subgroup == subLabel }?.id

    private fun renderCloudDashboard(
        ctx: android.content.Context, body: LinearLayout, panel: Sections.StackPanel,
    ) {
        val dash = Sections.cloudServices()
        val cols = com.diegonmarcos.superapp.BuildConfig.UI_TILE_COLUMNS.coerceAtLeast(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val wanted = panel.dashGroupIds.ifEmpty { dash.groups.map { it.id } }
        val groups = dash.groups.filter { it.id in wanted }
        // Only label the group inside the card when one card shows >1 group
        // (the Others card = Providers + MCP & API). Single-group cards rely
        // on the card title.
        val showGroupHeader = groups.size > 1
        for (group in groups) {
            if (showGroupHeader) {
                val gh = groupHeader(ctx, group.label)
                // The card's sub-tables are anchor targets in their own right
                // (`anchor:stack/providers`), which is what lets one card
                // serve four index entries without being split into four
                // panels the data does not have. Only ids the panel DECLARED
                // are registered, so the set is what build.json says it is.
                declaredAnchor(panel, group.id, "")?.let { anchors.register(it, gh) }
                body.addView(gh)
            }
            if (group.providers.isNotEmpty()) {
                addCloudGrid(ctx, body, cols, executor, group.providers.map {
                    CloudTile(it.label, group.icon, it.url, showLight = false, ping = null)
                })
            }
            for (sub in group.subgroups) {
                if (sub.containers.isEmpty()) continue
                val sh = subHeader(ctx, sub.label)
                // Subgroups carry no id in cloud_services.json, so the panel's
                // declaration names the label it binds to. The id is still the
                // declared one: renaming the label breaks the binding loudly
                // (the checker fails) instead of silently renaming the anchor.
                declaredAnchor(panel, group.id, sub.label)?.let { anchors.register(it, sh) }
                body.addView(sh)
                addCloudGrid(ctx, body, cols, executor, sub.containers.map {
                    when {
                        // Explicit open-URL (e.g. VM dashboard) — open it verbatim
                        // but still light up from the url:port ping.
                        it.link.isNotBlank() -> CloudTile(it.label, sub.icon, it.link, showLight = true,
                            ping = if (it.port in 1..65535) it.url to it.port else null, name = it.name)
                        it.external -> CloudTile(it.label, sub.icon, it.url, showLight = false, ping = null,
                            name = it.name)
                        else -> CloudTile(it.label, sub.icon, "https://${it.url}", showLight = true,
                            ping = if (it.port in 1..65535) it.url to it.port else null, name = it.name)
                    }
                })
            }
        }
        executor.shutdown()
    }

    private data class CloudTile(
        val label: String, val icon: String, val openUrl: String,
        val showLight: Boolean, val ping: Pair<String, Int>?,
        /** Container name, the key every declarative lookup and every ops route
         *  is addressed by. Blank for a provider console, which is a web page
         *  and not a container — those keep the old open-the-URL behaviour. */
        val name: String = "",
    )

    /** Wrap-flowing `cols`-column grid of cloud tiles (same grid as the
     *  link icon grid: each row a horizontal LinearLayout, padding cells
     *  fill the last row). */
    private fun addCloudGrid(
        ctx: android.content.Context, body: LinearLayout, cols: Int,
        executor: java.util.concurrent.ExecutorService, tiles: List<CloudTile>,
    ) {
        var i = 0
        while (i < tiles.size) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (c in 0 until cols) {
                if (i < tiles.size) row.addView(cloudTile(ctx, tiles[i++], executor))
                else                row.addView(spacerTile(ctx))
            }
            body.addView(row)
        }
    }

    /** Icon + STATUS LIGHT (under the icon) + label tile. weight=1 → 1/Nth
     *  row width. Tap opens [ContainerSheet] — Infos / Actions for the box and
     *  the app inside it; a provider console has no container behind it and
     *  still opens its URL. The light TCP-pings the container's {name}.app
     *  (green up / red down / grey checking). */
    private fun cloudTile(
        ctx: android.content.Context, t: CloudTile,
        executor: java.util.concurrent.ExecutorService,
    ): View {
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val padH = dp(4); val padV = dp(8); setPadding(padH, padV, padH, padV)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                // Every tile in a container subgroup IS a container, so the tap
                // opens the sheet that can describe and operate it. Opening the
                // URL is still available — it is the first action inside.
                val act = activity
                if (t.name.isNotBlank() && act is androidx.fragment.app.FragmentActivity) {
                    com.diegonmarcos.superapp.cloud.ContainerSheet.show(act, t.name, t.label, t.openUrl)
                } else {
                    openUrlOrTarget(t.openUrl)
                }
            }
        }
        cell.addView(ImageView(ctx).apply {
            val resId = Sections.iconResFor(ctx, t.icon)
            if (resId != 0) setImageResource(resId)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE9D8FD.toInt())
            val sz = dp(28)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        })
        if (t.showLight) {
            val dot = View(ctx).apply {
                val sz = dp(8)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { topMargin = dp(3) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x66FFFFFF)
                }
            }
            cell.addView(dot)
            t.ping?.let { (host, port) ->
                runCatching {
                    executor.execute {
                        val up = try {
                            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 1200); true }
                        } catch (_: Throwable) { false }
                        dot.post {
                            (dot.background as? android.graphics.drawable.GradientDrawable)
                                ?.setColor(if (up) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
                        }
                    }
                }
            }
        }
        cell.addView(TextView(ctx).apply {
            text = t.label
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            gravity = android.view.Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        return cell
    }

    private fun groupHeader(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f
            setPadding(0, dp(14), 0, dp(4))
        }

    private fun subHeader(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(0xCCFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            setPadding(dp(4), dp(8), 0, dp(2))
        }

    // ── Row builders ───────────────────────────────────────────────────

    private fun colHeader(ctx: android.content.Context, text: String, headerUrl: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setTextColor(resources.getColor(R.color.cloud_primary, ctx.theme))
            typeface = Typeface.DEFAULT_BOLD
            val pad = dp(10)
            setPadding(pad, pad, pad, pad / 2)
            if (headerUrl.isNotBlank()) setOnClickListener { openUrlOrTarget(headerUrl) }
        }

    private fun linkRow(ctx: android.content.Context, link: Sections.LinkItem): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dp(12); setPadding(pad, dp(10), pad, dp(10))
            isClickable = true; isFocusable = true
        }
        val lbl = TextView(ctx).apply {
            text = link.label
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val url = TextView(ctx).apply {
            text = link.url
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.55f
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        row.addView(lbl); row.addView(url)
        row.setOnClickListener { openUrlOrTarget(link.url) }
        return row
    }

    // ── By / Show toggles, applied to a notification list ──────────────

    /**
     * Registers [render] so the By/Show toggles can rebuild THIS body in
     * place, and runs it once now. Only bodies made of plain views may be
     * registered: re-running a body that calls [embedChild] would allocate
     * the next fixed host id while the previous child fragment still holds
     * the old one, and the panel would render empty.
     */
    private fun refreshable(body: LinearLayout, render: () -> Unit) {
        bodyRefreshers += { body.removeAllViews(); render() }
        render()
    }

    // ── pull-down to refresh ───────────────────────────────────────────

    /**
     * The gesture's whole job: re-ask every source this page has, then SAY what
     * came back.
     *
     * The watermark is deliberately NOT advanced here. [visitSeenAt] answers
     * "what arrived since you were last on this page", and moving it on a
     * refresh would clear every "N new" chip at the exact moment the refresh
     * had earned them — the gesture would erase its own result.
     *
     * Neither is [ntfyCache] cleared, though every channel IS re-polled: the
     * cache is also the ordering key for Sort=Time, so dropping it would send
     * every channel box back to alphabetical on the one gesture meant to bring
     * the newest to the top, and would blank the boxes while the poll was out.
     */
    private fun startRefresh() {
        val host = refreshHost ?: return
        if (bodyRefreshers.isEmpty()) {
            // Belt and braces — the gesture is disarmed on such a page in
            // onCreateView. Nothing here can be re-queried, so nothing is
            // claimed and the spinner stops immediately rather than animating
            // over a fetch that never happened.
            host.isRefreshing = false
            return
        }
        refreshBefore = HashSet(pageRowIds)
        refreshPending = 0
        ntfyForceRepoll = true
        rebuildBodies()
        ntfyForceRepoll = false
        // WATCHDOG. A poll that never settles is a spinner that never stops,
        // which reads as a hang and is the one failure this gesture would be
        // judged by. It waits on the SAME scope the polls do, for the reason
        // that scope exists: this was `host.postDelayed`, which keeps its
        // runnable alive across the fragment's teardown and would then fire
        // [finishRefresh] — whose "Refreshed …" line is built with [stateLine],
        // and so reaches `dp()` and a Context that is gone. Cancelled with the
        // view, it can only fire while there is still a spinner to stop.
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(REFRESH_TIMEOUT_MS)
            if (host.isRefreshing) {
                refreshPending = 0
                finishRefresh(timedOut = true)
            }
        }
        // The stores are read synchronously, so a page with no channel poll out
        // is already done and must not sit spinning until the watchdog.
        if (refreshPending == 0) finishRefresh(timedOut = false)
    }

    /** One channel poll landed, or failed to be scheduled. */
    private fun ntfyPollSettled() {
        if (refreshPending > 0) refreshPending--
        if (refreshPending == 0 && refreshHost?.isRefreshing == true) {
            finishRefresh(timedOut = false)
        }
    }

    /**
     * Stop the spinner and state the OUTCOME — which is a different claim from
     * "done", and the difference is the point.
     *
     * A refresh that fetched nothing must not read like one that fetched
     * something, so the line counts the notifications that were NOT on the page
     * before this pull. And a channel we could not reach is not a channel that
     * was quiet: an unreachable count is reported separately and takes the grey
     * that means "we did not measure", never the green that means "nothing is
     * wrong". A gesture that always says success is exactly the misreporting
     * control this page was built to stop being.
     */
    private fun finishRefresh(timedOut: Boolean) {
        val host = refreshHost ?: return
        host.isRefreshing = false
        val column = cardColumn ?: return
        val ctx = column.context
        val fresh = pageRowIds.count { it !in refreshBefore }
        // Only channels THIS page polled are in the cache, so this is a count
        // about the tab in front of the reader and not about the fleet.
        val unreachable = ntfyCache.count { !it.value.ok }
        val at = android.text.format.DateFormat.getTimeFormat(ctx)
            .format(java.util.Date())
        val outcome = when {
            timedOut  -> "did not finish · sources did not answer"
            fresh > 0 -> "$fresh new"
            else      -> "nothing new"
        }
        // NOT called "unread": on this page unread is a per-notification state
        // the reader controls by swiping, and reusing the word for "we could
        // not reach the publisher" would collide with it in the one place both
        // could plausibly appear.
        val notReached = if (unreachable > 0)
            " · $unreachable channel${if (unreachable == 1) "" else "s"} not reached" else ""
        // Grey wins whenever any part of the answer is unknown: an incomplete
        // measurement may not be painted as a clean result just because the
        // half that did answer had nothing to report.
        val color = when {
            timedOut || unreachable > 0 -> SIGNAL_UNKNOWN
            fresh > 0                   -> SIGNAL_OK
            else                        -> 0x88FFFFFF.toInt()
        }
        // One line, reused rather than appended: a refresh note per pull would
        // push the notifications the pull just fetched off the top of the page.
        val note = column.findViewWithTag<TextView>(REFRESH_NOTE_TAG)
            ?: stateLine(ctx, "", color).also {
                it.tag = REFRESH_NOTE_TAG
                column.addView(it, 0)
            }
        note.text = "Refreshed $at · $outcome$notReached"
        note.setTextColor(color)
    }

    /** A non-empty store that rendered nothing. Only Show=Unread can do this —
     *  sorting never removes a row — so the label needs no explanation, only
     *  the count it is hiding, which is the one thing here that is data. */
    private fun filteredAwayNote(total: Int): String = "Nothing new · $total older"

    private fun caption(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            alpha = 0.55f
            val pad = dp(12); setPadding(pad, dp(4), pad, dp(4))
        }

    private fun emptyHint(ctx: android.content.Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            alpha = 0.6f
            val pad = dp(16); setPadding(pad, pad, pad, pad)
        }

    // ── Click dispatch ─────────────────────────────────────────────────

    /**
     * Targets follow the existing grammar:
     *   section:X / page:X/Y / action:X  → bubble to MainActivity tile dispatcher
     *   http(s)://…                       → open external browser
     */
    private fun openUrlOrTarget(target: String) {
        when {
            target.isEmpty() -> Unit
            // `anchor:` never leaves the page — it scrolls this stack to the
            // panel (or dashboard sub-table) that claimed the id. Checked
            // before the URI tests because it is not a URI.
            anchors.dispatch(target) -> Unit
            // URI-shaped targets bubble up to the activity, which owns the
            // intent:// parsing + browser_fallback_url handling — same path
            // tile clicks take, so behaviour is consistent everywhere.
            target.startsWith("http") || target.contains("://") -> onTileClicked(target)
            else -> onTileClicked(target)
        }
    }

    /** Forward tile clicks to the activity-level dispatcher (same one
     *  [TileGridFragment] uses for its tile grammar). */
    override fun onTileClicked(tileId: String) {
        (activity as? TileGridFragment.TileClickListener)?.onTileClicked(tileId)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_LABEL      = "label"
        private const val ARG_MODE       = "mode"
        /** Marks the "Source hid everything" note so it is reused, not
         *  appended once per toggle tap. */
        private const val SOURCE_EMPTY_TAG = "stack_source_empty"

        /** The two phone-taxonomy filter ids. Named because three call sites
         *  read them and the sibling-reset rule pairs them explicitly. */
        private const val FILTER_TOOLS    = "tools"
        private const val FILTER_SERVICES = "services"
        /** Filter ids starting with this are PAGE SETTINGS, not visible
         *  toggles: same JSON shape, same per-page persistence, but
         *  [filterRow] skips drawing a control for them. */
        private const val SETTING_PREFIX  = "__"
        private const val FILTER_COLLAPSED = "__collapsed"

        /** Segmented-control palette: one filled pill on a faint track. */
        private const val FILTER_TRACK       = 0x14FFFFFF
        private val FILTER_ACTIVE            = 0xFFE9D8FD.toInt()
        private val FILTER_ACTIVE_TEXT       = 0xFF1A1A24.toInt()
        private val FILTER_IDLE_TEXT         = 0x99FFFFFF.toInt()
        /** Marks a notification group's verdict chip so an async ntfy poll can
         *  find it again without a field per group. */
        private const val GROUP_STATE_TAG = "notif_group_state"
        /** Marks a notification group's row container, so a late ntfy poll and
         *  the collapse toggle can both find it from the block they were
         *  handed — the alternative is a field per group. */
        private const val GROUP_ROWS_TAG = "notif_group_rows"

        /** Read-key namespaces. Every stored read id starts with one, so
         *  [StackFilters.pruneRead] can retire the phone stream's keys from a
         *  complete enumeration of the phone store without touching ntfy keys
         *  it has no view of — see [ntfyNs] for why ntfy needs one per topic. */
        private const val PHONE_NS = "phone:"
        private const val APP_NS   = "app:"

        /** Archived-box keys, and the Archive section's own open/closed flag.
         *  Both `__`-prefixed page settings, stored by [StackFilters] under the
         *  page id beside the page's visible selections. */
        private const val ARCHIVED_PREFIX     = "__archived/"
        private const val FILTER_ARCHIVE_OPEN = "__archive_open"
        private const val ARCHIVE_LABEL         = "Archive"
        private const val ARCHIVE_RESTORE_LABEL = "Restore"

        /** Marks the pull-down outcome line so each refresh REPLACES it, the
         *  same tag-lookup the source-empty note and the group chips use. */
        private const val REFRESH_NOTE_TAG = "stack_refresh_note"
        /** How long a pull-down may keep the spinner up before it gives up and
         *  says so. Comfortably past the poll's own 4s connect timeout, so a
         *  slow-but-alive mesh reports its real answer rather than a timeout;
         *  this only catches the case where a callback never runs at all. */
        private const val REFRESH_TIMEOUT_MS = 20_000L

        private const val TAG = "AggregatorStack"

        /** `extapp:<id>` — the declared handle for a companion app, resolved
         *  through `ui.external_apps`. An id, never a package name. */
        private const val EXTAPP_PREFIX = "extapp:"

        /** ui.inbox_classes `source` marking a class whose material is the ntfy
         *  CHANNEL stream rather than phone packages. Same vocabulary as the
         *  `origin: c3ntfy` the Notify page's channel panel already declares. */
        private const val C3NTFY_SOURCE = "c3ntfy"

        /** The kinds that exist ONLY as inbox cards, and whose missing app
         *  declaration is therefore worth a log line. `stats` is deliberately
         *  absent: it is a generic dashboard card other pages reuse, and
         *  logging one that names no app would warn about every card on every
         *  page that is not Inboxes. */
        private val INBOX_KINDS = setOf("mail_accounts", "chat_matrix", "chat_mattermost")

        /** One namespace PER TOPIC: an ntfy poll is only ever complete for the
         *  topic it polled, so that is the largest set a successful poll is
         *  entitled to prune. */
        private fun ntfyNs(topic: String) = "ntfy:$topic:"

        fun newInstance(sectionId: String, label: String, mode: String): AggregatorStackFragment =
            AggregatorStackFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION_ID, sectionId)
                    putString(ARG_LABEL,      label)
                    putString(ARG_MODE,       mode)
                }
            }
    }
}
