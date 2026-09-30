package com.diegonmarcos.cloudc3.cloud

import android.graphics.Typeface
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.diegonmarcos.cloudc3.R
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #648 the SuperApp's AggregatorStackFragment, MOVED with the c3 surface it renders and
 * trimmed to EXACTLY the panel kinds the carried c3 stacks declare — nothing the two
 * stacks use is missing, and nothing they never used came along:
 *
 *   c3_public / c3_private → embedded [C3HealthFragment] (scope-filtered address cards)
 *   wg_mesh                → embedded [C3MeshFragment]
 *   section_title          → plain heading between cards (Addresses / Containers / Stack)
 *   tile_row               → the Index and More rows ([IndexTiles], `anchor:`/`page:` targets)
 *   feed                   → the FIVE feed cards; `source` picks the fetcher
 *                            (github_run_stats | github_runs | dagu_runs |
 *                             github_commits | gitea_commits)
 *   notification_center    → the NTFY centre, stream=channels: one group per ntfy topic,
 *                            live verdicts, swipe-to-read, per-box Archive
 *   cloud_dashboard        → the three container dashboards over data/cloud_services.json,
 *                            TCP-ping status lights, [ContainerSheet] on tap
 *
 * The stream arms the c3 stacks do not declare (`phone`, `cloud`) are stated as absent
 * rather than silently dropped — the SuperApp's phone-notification stores belong to the
 * launcher and did not move.
 *
 * All panels default to **expanded**. Tap the header chevron to collapse/expand. Rendering,
 * wording, colours, timeouts, cache rules, the one-request ntfy poll, the lifecycle-scoped
 * paints (the SM-G996B crash note), the pull-down refresh arithmetic and the anchor
 * mechanics are the ones that shipped.
 *
 * Navigation leaves through ONE seam: [TargetListener.onTargetClicked], which the host
 * activity implements — the same role MainActivity's tile dispatcher played, sized to an
 * app whose targets are `page:`/`extapp:`/URLs.
 */
class C3StackFragment : Fragment() {

    /** Where a non-anchor target goes. The host decides; this fragment only reports it. */
    interface TargetListener {
        fun onTargetClicked(target: String)
    }

    private val stackId: String get() = arguments?.getString(ARG_STACK).orEmpty()

    /** Body container + its chevron for every panel we built. */
    private data class PanelRefs(val body: View, val chevron: View)
    private val panelRefs = mutableListOf<PanelRefs>()

    /** In-page `anchor:` links for this stack. Generic — the registry is fed
     *  from the panels' own declarations. */
    private val anchors = StackAnchors()

    // ── pull-down to refresh ───────────────────────────────────────────
    private val bodyRefreshers = mutableListOf<() -> Unit>()
    private var refreshHost: SwipeRefreshLayout? = null
    private var refreshPending = 0
    private val pageRowIds = LinkedHashSet<String>()
    private var refreshBefore: Set<String> = emptySet()
    private var ntfyForceRepoll = false
    private var cardColumn: LinearLayout? = null
    /** The page-level Archive: ONE collapsed section at the bottom holding every channel
     *  box the user archived. Built before the cards so a card can hand its archived boxes
     *  straight over as it renders, added to the column after them so it still draws last. */
    private var archiveBox:    LinearLayout? = null
    private var archiveWrap:   LinearLayout? = null
    private var archiveHeader: TextView?     = null
    private var archiveOpen = false
    private var filterPage = ""
    private var sortMode   = "time"
    private var showMode   = "all"
    /** Start of this visit's NEW window: the ts of the PREVIOUS visit. This is what a
     *  group's "N new" chip counts — arrival, not attention. */
    private var visitSeenAt = 0L
    /** Ids the user has explicitly swiped read on this page. */
    private var readIds: MutableSet<String> = HashSet()

    /** Read is EXPLICIT — the user swiped this row away. A row with no stable identity
     *  has nothing to remember, so it falls back to the arrival watermark. */
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
        // The pull-down host, wrapping the ScrollView and nothing else — it must stay a
        // SINGLE child so SwipeRefreshLayout's canChildScrollUp lookup finds the ScrollView.
        val host = SwipeRefreshLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(scroll)
            setColorSchemeColors(SIGNAL_OK)
            setOnRefreshListener { startRefresh() }
            // DISARMED until the panels are built and we know this page has something
            // re-queryable — a spinner over a page with nothing to re-ask is a control
            // reporting a fetch it never made.
            isEnabled = false
        }
        refreshHost = host
        pageRowIds.clear()
        refreshBefore = emptySet()
        refreshPending = 0
        archiveBox = null; archiveWrap = null; archiveHeader = null
        anchors.reset(scroll)

        val panels = when (stackId) {
            STACK_TOPOLOGY -> Stacks.stackTopology()
            STACK_OBSERVABILITY -> Stacks.stackObservability()
            else -> emptyList()
        }
        if (panels.isEmpty()) {
            column.addView(emptyHint(ctx, "No panels declared for stack: $stackId"))
            return host
        }
        panelRefs.clear()
        bodyRefreshers.clear()
        nextEmbedIdx = 0

        // The page id is the prefs key: swipes, the visit watermark and the Archive all
        // land under it, never in a blank-keyed bucket shared with the other stack page.
        filterPage = stackId
        readIds = StackFilters.readKeys(ctx, filterPage)
        visitSeenAt = StackFilters.lastSeen(ctx, filterPage)
        StackFilters.markSeen(ctx, filterPage, System.currentTimeMillis())

        // Before the cards: a card files its archived boxes into this while it builds.
        // Added to the column after them, so it still renders last.
        val archive = buildArchiveSection(ctx)
        for (panel in panels) {
            val view = if (panel.kind == "section_title") sectionTitleView(ctx, panel.title)
                       else buildPanel(ctx, inflater, panel)
            anchors.register(panel.anchor, view)
            column.addView(view)
        }
        column.addView(archive)
        syncArchiveHeader()
        // ARM THE GESTURE, and only now: [bodyRefreshers] is what a refresh actually
        // re-runs, so a page that registered none must not offer a gesture that would
        // report otherwise.
        host.isEnabled = bodyRefreshers.isNotEmpty()
        return host
    }

    /** kind=section_title — a plain heading between cards (NOT a card). */
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
        panel: Stacks.StackPanel,
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
            text = panel.title.ifBlank { panel.kind.replace('_', ' ') }
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
        renderBody(ctx, body, panel)

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
        body: LinearLayout,
        panel: Stacks.StackPanel,
    ) = when (panel.kind) {
        "c3_public"           -> embedChild(body, C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PUBLIC))
        "c3_private"          -> embedChild(body, C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PRIVATE))
        "wg_mesh"             -> embedChild(body, C3MeshFragment.newInstance())
        "tile_row"            -> renderTileRow(body, panel.tiles)
        "notification_center" -> refreshable(body) { renderNotificationCenter(ctx, body, panel) }
        "feed"                -> renderFeed(ctx, body, panel)
        "cloud_dashboard"     -> renderCloudDashboard(ctx, body, panel)
        else                  -> renderUnknownKind(ctx, body, panel)
    }

    // ── kind=feed ──────────────────────────────────────────────────────
    //
    // ONE config-driven kind for every repo/run integration on this page, per build.json's
    // own instruction: a new integration is a new `when` arm here plus a client, never a
    // new panel `kind`.

    private fun renderFeed(ctx: android.content.Context, body: LinearLayout, panel: Stacks.StackPanel) {
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
        TextView(ctx).apply {
            text = label
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(8), 0, dp(8))
        }

    /** Last commit per repo, one group per repo — "GH Repos" (source=github_commits) and
     *  "Gitea Repos" (source=gitea_commits). The two only differ in which client fetches
     *  the rows. */
    private fun renderRepoCommitsFeed(
        ctx: android.content.Context, body: LinearLayout, panel: Stacks.StackPanel, gitea: Boolean,
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
                // Stagger the fan-out: a dozen repos hitting either API at once beats up
                // the anonymous GitHub quota just the same as it would beat up the Gitea
                // container, so both sources share the same trickle.
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

    /** "GHA": the last [limit] workflow runs across EVERY declared repo, merged into one
     *  chronological list. Each row is labelled with its repo. */
    private fun renderGithubRunsFeed(ctx: android.content.Context, body: LinearLayout, panel: Stacks.StackPanel) {
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
                    // Only rows whose run identified its workflow file can be re-dispatched.
                    action   = r.workflowFile.takeIf { it.isNotBlank() }
                        ?.let { ghaTriggerRow(ctx, ref, it) },
                ))
            }
        }
    }

    /** "Analytics": the only card on this page that COUNTS rather than lists. Over the
     *  last [limit] runs per declared repo: how many ran, what share of the FINISHED ones
     *  went green, how many failed, and how long ago the last green was. THE DENOMINATOR
     *  IS THE FINISHED RUNS — a rate over an empty denominator is not a rate. CANCELLED IS
     *  NOT A FAILURE (cancel-in-progress). Reads the SAME GitHubFeed.runs the GHA card
     *  reads, through the same cache, so the two cards cost ONE request per repo between
     *  them and cannot disagree about a number. Tapping the fleet row hands off to
     *  panel.url (extapp:c3-watchtower) through the ordinary dispatcher. */
    private fun renderRunStatsFeed(ctx: android.content.Context, body: LinearLayout, panel: Stacks.StackPanel) {
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
                // The WORST status, not the first: one reachable repo with an empty window
                // must not read as a healthy fleet when the rest were rate limited.
                val worst = results.map { it.second.status }.firstOrNull { it != GitHubFeed.Status.OK }
                    ?: GitHubFeed.Status.OK
                body.addView(emptyRow(ctx, feedEmptyLabel(worst, "workflow runs")))
                return@launch
            }
            if (results.any { it.second.status != GitHubFeed.Status.OK }) {
                body.addView(emptyRow(ctx, feedStaleLabel(
                    results.map { it.second.status }.first { it != GitHubFeed.Status.OK })))
            }

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

    /** One aggregate line. [url] is blank for the per-repo rows — only the fleet row is a
     *  handoff, so a tap anywhere in the card has exactly one meaning. */
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

    /** "Dagu": the last [limit] runs across EVERY registered DAG. Server + bearer token
     *  come from libs:ops' DaguPrefs, shared with the native Dagu page, so there is one
     *  Dagu config on the device rather than two that can disagree. */
    private fun renderDaguRunsFeed(ctx: android.content.Context, body: LinearLayout, panel: Stacks.StackPanel) {
        val limit = panel.limit.takeIf { it > 0 } ?: 5
        val loading = loadingRow(ctx, "Loading Dagu runs…")
        body.addView(loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = com.diegonmarcos.superapp.ops.dagu.DaguPrefs(ctx)
            val now = System.currentTimeMillis()
            val outcome = withContext(Dispatchers.IO) {
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

    /** What an EMPTY GitHub feed actually means — a spent quota says "rate limited" in as
     *  many words, because rendering a 403 as an empty repo is a silent failure. */
    private fun feedEmptyLabel(status: GitHubFeed.Status, noun: String): String = when (status) {
        GitHubFeed.Status.RATE_LIMITED ->
            "(rate limited — GitHub's anonymous 60 requests/hour quota is spent; retry in a few minutes)"
        GitHubFeed.Status.UNREACHABLE ->
            "(unreachable — GitHub did not answer; check the network)"
        GitHubFeed.Status.OK -> "(no recent $noun)"
    }

    /** Shown ABOVE rows that came from cache because the live fetch failed. */
    private fun feedStaleLabel(status: GitHubFeed.Status): String = when (status) {
        GitHubFeed.Status.RATE_LIMITED -> "(rate limited — showing the last cached rows)"
        else                           -> "(offline — showing the last cached rows)"
    }

    /** Dagu run-status code → words. Codes are Dagu's own. */
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

    /** Never-run maps to "idle", NOT to the default row colour. */
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
        box.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        })
        box.addView(TextView(ctx).apply {
            text = sub
            setTextColor(0x77FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
        })
        return box
    }

    private fun emptyRow(ctx: android.content.Context, label: String): View =
        TextView(ctx).apply {
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
                // "idle" = declared but never run: a neutral dim wash, legible as its own
                // state at a glance.
                "idle"  -> 0x22FFFFFF
                else    -> 0x331A0033
            })
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (url.isNotBlank()) onTargetClicked(url)
            }
        }
        row.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFFE9D8FD.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(TextView(ctx).apply {
            text = meta
            setTextColor(0x88FFFFFF.toInt())
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(0, dp(2), 0, 0)
        })
        if (action != null) row.addView(action)
        return row
    }

    /**
     * "Re-run" control for one GitHub Actions workflow row. Dispatch goes through
     * c3-infra-api (POST /workflows/dispatch) with the Authelia bearer this device already
     * stores, NOT with a GitHub token: a PAT with actions:write shipped inside the APK
     * would be extractable by anyone who unzips it. The server holds the PAT. Every
     * outcome is displayed in place — the control never finishes without saying what
     * happened.
     */
    private fun ghaTriggerRow(ctx: android.content.Context, ref: Stacks.RepoRef,
                              workflowFile: String): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val status = TextView(ctx).apply {
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
                val outcome = withContext(Dispatchers.IO) {
                    OpsClient.dispatchWorkflow(
                        repo = "${ref.owner}/${ref.repo}",
                        workflow = workflowFile,
                        ref = "main",
                        bearer = bearer,
                    )
                }
                btn.isEnabled = true
                when (outcome) {
                    is OpsClient.Outcome.Ok -> {
                        status.setTextColor(0xFF7BE38B.toInt())
                        status.text = outcome.message
                    }
                    is OpsClient.Outcome.Failed -> {
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

    // ── kind=notification_center, stream=channels ──────────────────────
    //
    // Every view built below is a plain inline View. Nothing here goes through
    // [embedChild], and that is load-bearing: embedChild allocates from a FIXED pool of
    // host ids and only commits when nothing is already attached, so any body that re-runs
    // would come back permanently blank. These bodies re-run on every pull-down (they are
    // registered through [refreshable]).

    /** One publisher and everything it has posted. [key] is the ntfy topic we grouped on;
     *  [label] is what the user reads. */
    private data class NotifGroup(
        val key: String,
        val label: String,
        val sub: String,
        val rows: List<NotifRow>,
        val url: String = "",
    )

    private data class NotifRow(
        val ts: Long,
        val title: String,
        val text: String,
        val severity: String = "info",
        /** Stable per-entry identity, NAMESPACED per topic ("ntfy:<topic>:…") so
         *  [StackFilters.pruneRead] can retire one topic's read keys without seeing the
         *  others. Blank means this row has no identity to remember. */
        val id: String = "",
    )

    /** A group's one-line verdict. Same four-state vocabulary as the C3 health page:
     *  the two things this card must never confuse are a channel that has genuinely
     *  posted nothing, and a stream we could not read. */
    private data class GroupState(val text: String, val color: Int)

    private data class NtfyResult(val ok: Boolean, val error: String, val rows: List<NotifRow>)

    /** Per-topic poll results for this visit — also the ordering key for Sort=Time. */
    private val ntfyCache = mutableMapOf<String, NtfyResult>()

    /** The newest timestamp we have actually MEASURED for [topic], or Long.MIN_VALUE.
     *  Unmeasured and unreachable both sort last — the honest placement. */
    private fun newestMeasured(topic: String): Long =
        ntfyCache[topic]?.takeIf { it.ok }?.rows?.maxOfOrNull { it.ts } ?: Long.MIN_VALUE

    /** Which groups the user has folded away. Held on the fragment, not on the view. */
    private val collapsedGroups = mutableSetOf<String>()

    private fun renderNotificationCenter(
        ctx: android.content.Context, body: LinearLayout, panel: Stacks.StackPanel,
    ) {
        // A notification shade is edge-to-edge: the group headers and rows supply their
        // own structure, so the card's inner gutter is chrome that only steals width.
        body.setPadding(dp(6), 0, dp(6), dp(6))
        when (panel.stream) {
            // C3 Obsv's stream: the ntfy CHANNELS and nothing else.
            "channels" -> renderNtfyGroups(ctx, body, panel)
            // The launcher's phone/cloud streams did not move with the c3 surface: they
            // read the SuperApp's notification-listener stores, which belong to the
            // launcher. Stated rather than silently dropped.
            "phone", "cloud" -> {
                android.util.Log.w(TAG, "notification panel '${panel.title}' declares " +
                    "stream=${panel.stream}, which is the SuperApp launcher's store and is " +
                    "not carried in this app")
                body.addView(stateLine(ctx, "stream '${panel.stream}' is the SuperApp " +
                    "launcher's and is not carried here", SIGNAL_UNKNOWN))
            }
            else -> {
                android.util.Log.w(TAG, "notification panel '${panel.title}' declares no " +
                    "`stream`: add \"stream\": \"channels\" in build.json")
                body.addView(stateLine(ctx, "no stream declared", SIGNAL_UNKNOWN))
            }
        }
    }

    /**
     * One group per ntfy topic in this card's declared scopes, each with a live verdict:
     *
     *   N · 5m ago  — messages arrived (green).
     *   silent 24h  — the poll SUCCEEDED and the topic is empty (amber): a dead publisher
     *                 looks exactly like a quiet one and amber says we cannot tell them
     *                 apart.
     *   unavailable — the poll itself failed. GREY, never red and never absent: a failed
     *                 fetch is a claim about THIS PHONE'S network, not about the fleet.
     *
     * The topic list comes from the baked ui.ntfy catalog. The order is decided ONCE from
     * the last measurement [ntfyCache] holds, and the poll landing never re-sorts.
     */
    private fun renderNtfyGroups(
        ctx: android.content.Context, body: LinearLayout, panel: Stacks.StackPanel,
    ) {
        val scopes = NtfyScopes.load()
        val catalog = NtfyScopes.fallbackChannels()
        val topics = if (panel.scopes.isEmpty()) catalog else catalog.filter {
            NtfyScopes.scopeOf(it, scopes).id in panel.scopes
        }
        if (topics.isEmpty()) {
            android.util.Log.w(TAG, "ntfy panel '${panel.title}' matched no channel: no " +
                "build.json::ui.ntfy topic falls in scopes (" +
                panel.scopes.joinToString(", ").ifBlank { "all" } + ")")
            body.addView(stateLine(ctx, "no channels in scope", SIGNAL_UNKNOWN))
            return
        }
        // Every card is drawn first, then ONE request fills them all in.
        val slots = LinkedHashMap<String, Pair<TextView, LinearLayout>>()
        val byLabel = compareBy(String.CASE_INSENSITIVE_ORDER) { t: String ->
            NtfyCatalog.labelOf(t)
        }
        val ordered =
            if (sortMode == "app") topics.sortedWith(byLabel)
            else topics.sortedWith(
                compareByDescending<String> { newestMeasured(it) }.then(byLabel))
        for (topic in ordered) {
            val group = NotifGroup(
                key   = topic,
                label = NtfyCatalog.labelOf(topic),
                sub   = topic,
                rows  = emptyList(),
                // The HUMAN address, deliberately the gated public one: it opens in a
                // browser that can carry the Authelia session. Only the programmatic poll
                // needs the open route.
                url   = "${NtfyCatalog.webBaseUrl()}/$topic",
            )
            // "checking…", never OK: an unpolled channel must not spend even its first
            // frame looking healthy.
            val block   = groupBlock(ctx, group, GroupState("checking…", SIGNAL_UNKNOWN), body)
            val state   = block.findViewWithTag<TextView>(GROUP_STATE_TAG) ?: continue
            val rowsBox = block.findViewWithTag<LinearLayout>(GROUP_ROWS_TAG) ?: continue
            placeGroup(ctx, block, topic, body)

            val cached = ntfyCache[topic]
            // Paint the last measurement first so the box does not flash empty while the
            // poll is out, but still QUEUE the poll when the user asked for fresh data.
            if (cached != null) paintNtfyGroup(ctx, state, rowsBox, cached, topic, panel.limit)
            if (cached == null || ntfyForceRepoll) slots[topic] = state to rowsBox
        }
        if (slots.isEmpty()) return

        // ONE REQUEST FOR ALL OF THEM. ntfy takes a comma-separated topic list and stamps
        // every envelope with its own `topic`. Per-topic requests spend the visitor burst
        // quota (60, one token back per 10s) and hand back scattered 429s — grey cards
        // that move around between visits, the hardest kind of broken to report.
        val counted = refreshHost?.isRefreshing == true
        if (counted) refreshPending++
        // THE PAINT BELONGS TO THIS VIEW, SO IT IS SCOPED TO THIS VIEW: viewLifecycleOwner's
        // scope is cancelled in onDestroyView, so the continuation cannot run once the
        // views it paints are gone (the SM-G996B notifRowView crash this replaced).
        viewLifecycleOwner.lifecycleScope.launch {
            val byTopic = withContext(Dispatchers.IO) {
                pollTopics(slots.keys.toList())
            }
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
            // The verdict already says what went wrong and what to do about it.
            state.text = result.error
            state.setTextColor(SIGNAL_UNKNOWN)
            return
        }
        // A SUCCESSFUL poll is the complete current window for this topic, and only then
        // may its read keys be retired — a mesh hiccup does not resurrect swiped rows.
        if (topic.isNotBlank()) StackFilters.pruneRead(
            ctx, filterPage, ntfyNs(topic), result.rows.mapTo(HashSet()) { it.id })
        // `limit` caps how many of this GROUP's rows are shown (the c3 card wants "last 5
        // per channel"); 0 means every row the poll window returned.
        val rows = withinGroup(result.rows).let { if (limit > 0) it.take(limit) else it }
        if (rows.isEmpty()) {
            // Empty channel and hidden-by-filter are different facts.
            if (result.rows.isEmpty()) {
                state.text = "silent ${NtfyCatalog.pollWindow()} · publisher?"
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
            rowsBox.addView(notifRowView(ctx, r))
            if (r.id.isNotBlank()) pageRowIds += r.id
        }
    }

    /**
     * ntfy's poll API, for EVERY topic on the card in one request. Any non-200, any
     * exception and any unparseable body is UNAVAILABLE — the honest answer is that we
     * did not measure. Every requested topic gets an entry back, so a card can never be
     * left reading "checking…". THE ADDRESS comes from [NtfyCatalog.pollUrl] — one
     * declared place, the mesh origin ahead of the public edge's authorization gate.
     * NO REDIRECTS: a 3xx is the SSO bounce, and followed it becomes a 200 of login HTML
     * that parses to zero messages.
     */
    private fun pollTopics(topics: List<String>): Map<String, NtfyResult> {
        fun all(r: NtfyResult) = topics.associateWith { r }
        return try {
            val url = java.net.URL(NtfyCatalog.pollUrl(topics.joinToString(",")))
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 4000
                // Long enough to actually finish: health_resources alone replays ~851 KB
                // in the declared window, and a 4-second read turned the busiest channel
                // on the fleet into "no answer".
                readTimeout = 15_000
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            try {
                if (conn.responseCode != 200)
                    all(NtfyResult(false, NtfyCatalog.readVerdict(conn.responseCode), emptyList()))
                else {
                    val rows = topics.associateWith { mutableListOf<NotifRow>() }
                    conn.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) runCatching {
                            val o = org.json.JSONObject(line)
                            // Each envelope names its own topic — that field is the whole
                            // reason one request can fill many cards.
                            val topic = o.optString("topic")
                            if (o.optString("event") == "message") rows[topic]?.let { bucket ->
                                val ts = o.optLong("time", 0L) * 1000L
                                bucket += NotifRow(
                                    ts    = ts,
                                    title = o.optString("title").ifBlank { topic },
                                    text  = o.optString("message"),
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
            // Nothing answered at all. On a phone that is almost always the mesh being
            // down, and saying so is the difference between the owner reconnecting
            // WireGuard and the owner filing another bug about the fleet.
            all(NtfyResult(false, NtfyCatalog.UNREACHABLE_VERDICT, emptyList()))
        }
    }

    /** Show=Unread inside one group: everything not swiped away, newest-first. */
    private fun withinGroup(rows: List<NotifRow>): List<NotifRow> {
        val shown = if (showMode == "unread") rows.filter { isUnread(it) } else rows
        return shown.sortedByDescending { it.ts }
    }

    /**
     * One publisher drawn as a notification-shade GROUP: a real header bar — monogram,
     * name, count chip — with its notifications folded under it. Tapping the header
     * COLLAPSES the group; launching lives on the monogram. Everything here is a plain
     * View; the row container is reached back through [GROUP_ROWS_TAG] so a late ntfy
     * poll can fill a group in without a field per group.
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
        // The count chip. ONE TextView holding the whole verdict, because an async ntfy
        // group repaints it with a failure sentence that has no count in it at all.
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
        // Clear all — the group-level twin of a swipe.
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
                    block.isVisible = false
                } else {
                    rows.removeAllViews()
                    for (r in g.rows) rows.addView(notifRowView(ctx, r))
                }
            }
        })
        // Archive — the tick's twin, one level up: about the CHANNEL, not the rows.
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

    // ── archived channel boxes ─────────────────────────────────────────
    //
    // Archived state is a StackFilters SELECTION under this page id: same prefs file, same
    // "<page>/<id>" key shape, same commit() discipline. The id carries the `__`
    // page-setting prefix; there is one key per box, discovered at runtime from what
    // exists, so the yes/no option set is supplied here rather than declared.

    private fun yesNo(id: String) = Stacks.StackFilter(
        id      = id,
        label   = "",
        default = "no",
        options = listOf(Stacks.FilterOption("yes", "yes"), Stacks.FilterOption("no", "no")),
    )

    private fun isArchived(ctx: android.content.Context, key: String): Boolean =
        StackFilters.selected(ctx, filterPage, yesNo(ARCHIVED_PREFIX + key)) == "yes"

    private fun setArchived(ctx: android.content.Context, key: String, archived: Boolean) {
        StackFilters.select(ctx, filterPage, ARCHIVED_PREFIX + key, if (archived) "yes" else "no")
    }

    /** The Archive control on a box's header. A WORD, not a glyph, and it MOVES THE BOX
     *  immediately — writing the preference alone would read as a dead button. */
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
                if (archived) "Restore this channel out of the Archive"
                else "Archive this channel"
        }
        paint(isArchived(ctx, key))
        setOnClickListener {
            Haptics.tap(it)
            val archived = !isArchived(ctx, key)
            setArchived(ctx, key, archived)
            paint(archived)
            // Falls back to the card it came from rather than to nothing: a box removed
            // from one parent and added to none is a box the user just deleted by accident.
            val target = (if (archived) archiveBox else homeBody) ?: homeBody
            (block.parent as? ViewGroup)?.removeView(block)
            target.addView(block)
            syncArchiveHeader()
        }
    }

    /** File a freshly built box where the user left it: its card, or the page's Archive. */
    private fun placeGroup(
        ctx: android.content.Context, block: View, key: String, homeBody: LinearLayout,
    ) {
        val archive = archiveBox
        if (archive != null && isArchived(ctx, key)) {
            archive.addView(block)
            syncArchiveHeader()
        } else homeBody.addView(block)
    }

    /** The page's Archive section: one collapsed line over the boxes the user filed away.
     *  Hidden entirely while it holds nothing. */
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

    /** Keep the Archive line honest about what is behind it. */
    private fun syncArchiveHeader() {
        val box = archiveBox ?: return
        archiveWrap?.isVisible = box.childCount > 0
        archiveHeader?.text =
            "Archive  ${if (archiveOpen) "▾" else "▸"}  ${box.childCount}"
    }

    /** A coloured monogram per channel — an ntfy topic has no launcher icon, and a shade
     *  with a blank column where every icon should be reads as broken rather than as
     *  cloud. Tapping opens the channel's web page through the dispatcher. */
    private fun groupAvatar(ctx: android.content.Context, g: NotifGroup): View {
        val holder = FrameLayout(ctx)
        holder.addView(TextView(ctx).apply {
            text = g.label.trim().take(1).uppercase()
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(monogramColor(g.key))
            }
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val sz = dp(30)
        holder.layoutParams = LinearLayout.LayoutParams(sz, sz)
        if (g.url.isNotBlank()) {
            holder.isClickable = true
            holder.setOnClickListener { onTargetClicked(g.url) }
        }
        return holder
    }

    /** A stable colour per publisher, derived from the grouping key, so the same topic
     *  keeps the same monogram between visits and no colour table has to be maintained
     *  alongside the channel catalog. */
    private fun monogramColor(key: String): Int {
        val hues = intArrayOf(
            0xFF6D5AE6.toInt(), 0xFF1F8A70.toInt(), 0xFFB5556D.toInt(),
            0xFF2C6FB5.toInt(), 0xFF9A6A2F.toInt(), 0xFF4E7A2A.toInt(),
        )
        return hues[((key.hashCode() % hues.size) + hues.size) % hues.size]
    }

    /**
     * One notification as a shade ROW: a status stripe, then title and relative time on
     * one line with the snippet under it. Dense on purpose. Unread is said three times
     * over: the stripe lights up, the title goes bold, and the row takes a faint tint.
     * READ IS A SWIPE — see [attachSwipeToRead].
     */
    private fun notifRowView(ctx: android.content.Context, r: NotifRow): View {
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

        // All three unread cues in one place, so a repaint after a swipe cannot set two of
        // them and forget the third.
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

        if (r.id.isNotBlank()) attachSwipeToRead(holder, r, paint)
        return holder
    }

    /**
     * Swipe a row sideways — either direction — to flip its read state. Hand-rolled on a
     * touch listener because this list is plain Views in a LinearLayout inside a
     * ScrollView. VERTICAL SCROLLING IS PRESERVED by claiming the gesture late: only a
     * clearly sideways drag calls requestDisallowInterceptTouchEvent. The gesture is
     * always visibly answered.
     */
    private fun attachSwipeToRead(holder: View, r: NotifRow, paint: (Boolean) -> Unit) {
        val ctx = holder.context
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var decided = false; var horizontal = false
        // rawX/rawY, not x/y: the view is being translated under the finger.
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
                        else              -> settle(v)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> { settle(v); true }
                else -> false
            }
        }
    }

    /** Slide [v] out towards [toRight], persist the flipped state, then bring it back from
     *  the opposite edge already repainted. Under Show=Unread a row that just became read
     *  has nowhere to return TO, so it stays gone. */
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

    /** A card-level verdict line, for the states that apply to a whole stream. */
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

    /** Round-robin pool of stable host ids. View.generateViewId() crashes on
     *  FragmentManager restore because the new id won't match the saved-state host id.
     *  Stable resource ids survive process death. */
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
        // Only commit when there's nothing already attached at that host — on restore the
        // FragmentManager re-binds the existing inner fragment to the same id.
        if (childFragmentManager.findFragmentById(hostId) == null) {
            childFragmentManager.beginTransaction()
                .replace(hostId, frag)
                .commit()
        }
    }

    /** Mini-tile index row — the card look and the wrap-at-tile_columns layout live in
     *  [IndexTiles]. openUrlOrTarget, not the listener directly, so `anchor:` tiles
     *  scroll this page instead of being handed to the navigating dispatcher. */
    private fun renderTileRow(body: LinearLayout, tiles: List<Stacks.AggTile>) {
        val ctx = body.context
        body.addView(IndexTiles.grid(
            ctx,
            Stacks.tileColumns(),
            tiles.map { tile ->
                IndexTiles.Cell(
                    label   = tile.label,
                    iconRes = Stacks.iconResFor(ctx, tile.iconName),
                    onClick = { openUrlOrTarget(tile.target) },
                )
            },
        ))
    }

    /** A kind the carried stacks never declare. STATED, not drawn blank — the same
     *  fail-visible rule an unknown feed `source` gets; there is no not-built body for a
     *  declared card to fall back into (#648). */
    private fun renderUnknownKind(
        ctx: android.content.Context, body: LinearLayout, panel: Stacks.StackPanel,
    ) {
        body.addView(emptyRow(ctx,
            "(unknown panel kind '${panel.kind}' — this app carries no renderer for it)"))
    }

    // ── kind=cloud_dashboard ───────────────────────────────────────────

    /** The anchor id [panel] declared for the header identified by [groupId] + [subLabel]
     *  (blank [subLabel] = the group header itself), or null when the panel declared none
     *  — an undeclared header is simply not an anchor target, which is what makes
     *  build.json the whole truth. */
    private fun declaredAnchor(
        panel: Stacks.StackPanel, groupId: String, subLabel: String,
    ): String? = panel.anchors
        .firstOrNull { it.group == groupId && it.subgroup == subLabel }?.id

    /** kind=cloud_dashboard — live container map. Decodes the baked service inventory
     *  (data/cloud_services.json → BuildConfig.CLOUD_SERVICES_B64), buckets every
     *  container by category into the card's dashGroups, draws a TCP-ping status dot per
     *  container (green up / red down / grey checking, meaningful only over WireGuard) and
     *  opens [ContainerSheet] on tap. Provider groups render external consoles (no ping). */
    private fun renderCloudDashboard(
        ctx: android.content.Context, body: LinearLayout, panel: Stacks.StackPanel,
    ) {
        val dash = Stacks.cloudServices()
        val cols = Stacks.tileColumns()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val wanted = panel.dashGroupIds.ifEmpty { dash.groups.map { it.id } }
        val groups = dash.groups.filter { it.id in wanted }
        // Only label the group inside the card when one card shows >1 group.
        val showGroupHeader = groups.size > 1
        for (group in groups) {
            if (showGroupHeader) {
                val gh = groupHeader(ctx, group.label)
                // The card's sub-tables are anchor targets in their own right
                // (`anchor:stack/providers`). Only ids the panel DECLARED are registered.
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
                // Subgroups carry no id in cloud_services.json, so the panel's declaration
                // names the label it binds to.
                declaredAnchor(panel, group.id, sub.label)?.let { anchors.register(it, sh) }
                body.addView(sh)
                addCloudGrid(ctx, body, cols, executor, sub.containers.map {
                    when {
                        // Explicit open-URL (e.g. VM dashboard) — open it verbatim but
                        // still light up from the url:port ping.
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
        /** Container name, the key every declarative lookup and every ops route is
         *  addressed by. Blank for a provider console. */
        val name: String = "",
    )

    /** Wrap-flowing `cols`-column grid of cloud tiles. */
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

    /** Icon + STATUS LIGHT (under the icon) + label tile. Tap opens [ContainerSheet] —
     *  Infos / Actions for the box and the app inside it; a provider console has no
     *  container behind it and still opens its URL. */
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
                val act = activity
                if (t.name.isNotBlank() && act is androidx.fragment.app.FragmentActivity) {
                    ContainerSheet.show(act, t.name, t.label, t.openUrl)
                } else {
                    openUrlOrTarget(t.openUrl)
                }
            }
        }
        cell.addView(ImageView(ctx).apply {
            val resId = Stacks.iconResFor(ctx, t.icon)
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

    private fun spacerTile(ctx: android.content.Context): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

    /**
     * Registers [render] so the pull-down can rebuild THIS body in place, and runs it once
     * now. Only bodies made of plain views may be registered: re-running a body that calls
     * [embedChild] would allocate the next fixed host id while the previous child fragment
     * still holds the old one, and the panel would render empty.
     */
    private fun refreshable(body: LinearLayout, render: () -> Unit) {
        bodyRefreshers += { body.removeAllViews(); render() }
        render()
    }

    // ── pull-down to refresh ───────────────────────────────────────────

    /**
     * The gesture's whole job: re-ask every source this page has, then SAY what came back.
     * The watermark is deliberately NOT advanced here — moving it on a refresh would clear
     * every "N new" chip at the exact moment the refresh had earned them. Neither is
     * [ntfyCache] cleared, though every channel IS re-polled: the cache is also the
     * ordering key for Sort=Time.
     */
    private fun startRefresh() {
        val host = refreshHost ?: return
        if (bodyRefreshers.isEmpty()) {
            host.isRefreshing = false
            return
        }
        refreshBefore = HashSet(pageRowIds)
        refreshPending = 0
        ntfyForceRepoll = true
        rebuildBodies()
        ntfyForceRepoll = false
        // WATCHDOG. A poll that never settles is a spinner that never stops. It waits on
        // the SAME scope the polls do — cancelled with the view, it can only fire while
        // there is still a spinner to stop.
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(REFRESH_TIMEOUT_MS)
            if (host.isRefreshing) {
                refreshPending = 0
                finishRefresh(timedOut = true)
            }
        }
        if (refreshPending == 0) finishRefresh(timedOut = false)
    }

    /** Re-render every registered body — emptying the Archive first stops a rebuild
     *  growing a second copy of every archived box; clearing [pageRowIds] keeps the
     *  refresh's arithmetic honest. */
    private fun rebuildBodies() {
        archiveBox?.removeAllViews()
        pageRowIds.clear()
        for (refresh in bodyRefreshers) refresh()
        syncArchiveHeader()
    }

    /** One channel poll landed, or failed to be scheduled. */
    private fun ntfyPollSettled() {
        if (refreshPending > 0) refreshPending--
        if (refreshPending == 0 && refreshHost?.isRefreshing == true) {
            finishRefresh(timedOut = false)
        }
    }

    /**
     * Stop the spinner and state the OUTCOME — which is a different claim from "done". A
     * refresh that fetched nothing must not read like one that fetched something, and a
     * channel we could not reach is not a channel that was quiet: grey wins whenever any
     * part of the answer is unknown.
     */
    private fun finishRefresh(timedOut: Boolean) {
        val host = refreshHost ?: return
        host.isRefreshing = false
        val column = cardColumn ?: return
        val ctx = column.context
        val fresh = pageRowIds.count { it !in refreshBefore }
        val unreachable = ntfyCache.count { !it.value.ok }
        val at = android.text.format.DateFormat.getTimeFormat(ctx)
            .format(java.util.Date())
        val outcome = when {
            timedOut  -> "did not finish · sources did not answer"
            fresh > 0 -> "$fresh new"
            else      -> "nothing new"
        }
        val notReached = if (unreachable > 0)
            " · $unreachable channel${if (unreachable == 1) "" else "s"} not reached" else ""
        val color = when {
            timedOut || unreachable > 0 -> SIGNAL_UNKNOWN
            fresh > 0                   -> SIGNAL_OK
            else                        -> 0x88FFFFFF.toInt()
        }
        // One line, reused rather than appended.
        val note = column.findViewWithTag<TextView>(REFRESH_NOTE_TAG)
            ?: stateLine(ctx, "", color).also {
                it.tag = REFRESH_NOTE_TAG
                column.addView(it, 0)
            }
        note.text = "Refreshed $at · $outcome$notReached"
        note.setTextColor(color)
    }

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
     * Targets follow the SuperApp's grammar: `anchor:` never leaves the page — it scrolls
     * this stack to the panel (or dashboard sub-table) that claimed the id, checked before
     * anything else because it is not a URI. Everything else bubbles to the host activity.
     */
    private fun openUrlOrTarget(target: String) {
        when {
            target.isEmpty() -> Unit
            anchors.dispatch(target) -> Unit
            else -> onTargetClicked(target)
        }
    }

    /** Forward non-anchor targets to the activity-level dispatcher. */
    private fun onTargetClicked(target: String) {
        (activity as? TargetListener)?.onTargetClicked(target)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_STACK = "stack"
        const val STACK_TOPOLOGY = "stack_topology"
        const val STACK_OBSERVABILITY = "stack_observability"

        /** Marks a notification group's verdict chip so an async ntfy poll can find it
         *  again without a field per group. */
        private const val GROUP_STATE_TAG = "notif_group_state"
        /** Marks a notification group's row container — same tag-lookup trick. */
        private const val GROUP_ROWS_TAG = "notif_group_rows"

        /** Archived-box keys, and the Archive section's own open/closed flag. Both
         *  `__`-prefixed page settings, stored by [StackFilters] under the page id. */
        private const val ARCHIVED_PREFIX     = "__archived/"
        private const val FILTER_ARCHIVE_OPEN = "__archive_open"
        private const val ARCHIVE_LABEL         = "Archive"
        private const val ARCHIVE_RESTORE_LABEL = "Restore"

        /** Marks the pull-down outcome line so each refresh REPLACES it. */
        private const val REFRESH_NOTE_TAG = "stack_refresh_note"
        /** Comfortably past the poll's own 4s connect timeout, so a slow-but-alive mesh
         *  reports its real answer rather than a timeout. */
        private const val REFRESH_TIMEOUT_MS = 20_000L

        private const val TAG = "C3Stack"

        /** Grey = we do not know. Deliberately NOT red: red is a claim about the fleet,
         *  and a failed fetch is a claim about this phone's network. */
        private val SIGNAL_UNKNOWN = 0xFF9E9E9E.toInt()
        private val SIGNAL_OK      = 0xFF34C759.toInt()
        private val SIGNAL_WARN    = 0xFFFFB020.toInt()

        /** One namespace PER TOPIC: an ntfy poll is only ever complete for the topic it
         *  polled, so that is the largest set a successful poll is entitled to prune. */
        private fun ntfyNs(topic: String) = "ntfy:$topic:"

        fun newInstance(stackId: String): C3StackFragment =
            C3StackFragment().apply {
                arguments = Bundle().apply { putString(ARG_STACK, stackId) }
            }
    }
}
