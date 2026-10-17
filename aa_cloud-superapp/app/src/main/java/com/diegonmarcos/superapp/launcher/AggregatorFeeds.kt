package com.diegonmarcos.superapp.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.cloud.DaguRunsFeed
import com.diegonmarcos.superapp.cloud.GitHubFeed
import com.diegonmarcos.superapp.cloud.GiteaFeed
import com.diegonmarcos.superapp.cloud.OpsClient
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * kind=feed: ONE config-driven kind for every repo / run integration on the page. `source` picks the fetcher; `repos`
 * and `limit` are the only other knobs — a sixth integration is a new `when` arm here plus a client object, never a
 * new panel kind. Each source loads with produceState (staggered, so a dozen repos do not beat up either API at once)
 * and says what it is doing: loading, empty, rate limited, offline-cached — never a silently blank card.
 */
@Composable
internal fun FeedBody(panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    when (panel.source) {
        "github_runs" -> GithubRunsFeed(panel, onOpen)
        "github_run_stats" -> RunStatsFeed(panel, onOpen)
        "github_commits" -> RepoCommitsFeed(panel, gitea = false, onOpen)
        "gitea_commits" -> RepoCommitsFeed(panel, gitea = true, onOpen)
        "dagu_runs" -> DaguRunsFeedBody(panel, onOpen)
        else -> FeedEmpty("(kind=feed needs a \"source\": github_runs | github_run_stats | " +
            "github_commits | gitea_commits | dagu_runs — got '${panel.source}')")
    }
}

@Composable
private fun FeedLoading(label: String) {
    Text(label, color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(vertical = 8.dp).testTag(TAG_LOADING))
}

@Composable
internal fun FeedEmpty(label: String) {
    Text(label, color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp))
}

@Composable
private fun RepoHeader(title: String, sub: String) {
    val p = LocalKitPalette.current
    Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text(sub, color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

/** One run / commit / dagu row. A workflow-run link is a plain web page, so it goes to the activity dispatcher rather
 *  than straight out to ACTION_VIEW (an external browser would end the superapp task and Back would leave the page). */
@Composable
internal fun FeedRow(title: String, meta: String, url: String, severity: String, onOpen: (String) -> Unit,
                     action: (@Composable () -> Unit)? = null) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(severityWash(severity))
        .clickable(enabled = url.isNotBlank()) { onOpen(url) }.padding(8.dp)) {
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Text(meta, color = p.textSecondary, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp))
        action?.invoke()
    }
}

/** Last commit per repo, one group per repo — GH Repos (github_commits) and Gitea Repos (gitea_commits) differ only in
 *  which client fetches the rows. */
@Composable
private fun RepoCommitsFeed(panel: Sections.StackPanel, gitea: Boolean, onOpen: (String) -> Unit) {
    if (panel.repos.isEmpty()) {
        FeedEmpty("No repos declared. Add a `repos: [{owner, repo, label}]` array to the panel."); return
    }
    val ctx = LocalContext.current
    val perRepo = panel.limit.takeIf { it > 0 } ?: 1
    val loaded by produceState<List<Pair<Sections.RepoRef, GitHubFeed.Feed<GitHubFeed.Commit>>>?>(null, panel) {
        value = coroutineScope {
            panel.repos.mapIndexed { i, ref ->
                async {
                    delay(i * GitHubFeed.STAGGER_MS)
                    ref to (if (gitea) GiteaFeed.commits(ctx, ref.owner, ref.repo, perRepo)
                            else GitHubFeed.commits(ctx, ref.owner, ref.repo, perRepo))
                }
            }.awaitAll()
        }
    }
    val results = loaded ?: run { FeedLoading("Loading commits…"); return }
    val now = remember(results) { System.currentTimeMillis() }
    for ((ref, feed) in results) {
        RepoHeader(ref.label, "${ref.owner}/${ref.repo}")
        if (feed.items.isEmpty()) { FeedEmpty(AggregatorModel.feedEmptyLabel(feed.status, "commits")); continue }
        if (feed.status != GitHubFeed.Status.OK) FeedEmpty(AggregatorModel.feedStaleLabel(feed.status))
        for (c in feed.items) {
            FeedRow(c.message, "${c.sha} · ${c.author} · ${GitHubFeed.ago(now - c.tsMillis)}", c.htmlUrl, "info", onOpen)
        }
    }
}

/** "GHA": the last `limit` workflow runs across EVERY declared repo, merged into one chronological list. Each row is
 *  labelled with its repo so the merge does not lose the context grouping used to give for free. */
@Composable
private fun GithubRunsFeed(panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    if (panel.repos.isEmpty()) { FeedEmpty("No repos declared. Add a `repos` array to the panel."); return }
    val ctx = LocalContext.current
    val limit = panel.limit.takeIf { it > 0 } ?: 5
    val loaded by produceState<List<Pair<Sections.RepoRef, GitHubFeed.Feed<GitHubFeed.Run>>>?>(null, panel) {
        value = fetchRuns(ctx, panel, limit)
    }
    val results = loaded ?: run { FeedLoading("Loading workflow runs…"); return }
    val now = remember(results) { System.currentTimeMillis() }
    val flat = results.flatMap { (ref, feed) -> feed.items.map { ref to it } }
        .sortedByDescending { it.second.tsMillis }.take(limit)
    if (flat.isEmpty()) {
        val worst = results.map { it.second.status }.firstOrNull { it != GitHubFeed.Status.OK } ?: GitHubFeed.Status.OK
        FeedEmpty(AggregatorModel.feedEmptyLabel(worst, "workflow runs")); return
    }
    for ((ref, r) in flat) {
        val statusLabel = if (r.conclusion.isNotBlank()) r.conclusion else r.status
        FeedRow(
            title = r.displayTitle.ifBlank { r.name },
            meta = "${ref.label} · ${r.name} · $statusLabel · ${GitHubFeed.ago(now - r.tsMillis)}",
            url = r.htmlUrl, severity = AggregatorModel.runSeverity(r.conclusion), onOpen = onOpen,
            // Only rows whose run identified its workflow file can be re-dispatched.
            action = if (r.workflowFile.isNotBlank()) ({ RerunControl(ref, r.workflowFile) }) else null,
        )
    }
}

private suspend fun fetchRuns(ctx: Context, panel: Sections.StackPanel, limit: Int) = coroutineScope {
    panel.repos.mapIndexed { i, ref ->
        async { delay(i * GitHubFeed.STAGGER_MS); ref to GitHubFeed.runs(ctx, ref.owner, ref.repo, limit) }
    }.awaitAll()
}

/**
 * "Analytics": the only card on the page that COUNTS rather than lists. Over the last `limit` runs per repo: how many
 * ran, what share of the FINISHED ones went green, how many failed and how long ago the last green was. The
 * denominator is the finished runs, not all of them (a run in progress is not yet anything), cancelled is not a
 * failure (the ship workflows run under cancel-in-progress), and with nothing finished the card says so rather than
 * printing 0%. Reads the SAME [GitHubFeed.runs] the GHA card reads, through the same cache.
 */
@Composable
private fun RunStatsFeed(panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    if (panel.repos.isEmpty()) { FeedEmpty("No repos declared. Add a `repos` array to the panel."); return }
    val ctx = LocalContext.current
    val limit = panel.limit.takeIf { it > 0 } ?: 30
    val loaded by produceState<List<Pair<Sections.RepoRef, GitHubFeed.Feed<GitHubFeed.Run>>>?>(null, panel) {
        value = fetchRuns(ctx, panel, limit)
    }
    val results = loaded ?: run { FeedLoading("Counting workflow runs…"); return }
    val now = remember(results) { System.currentTimeMillis() }
    val all = results.flatMap { it.second.items }
    if (all.isEmpty()) {
        // The WORST status, not the first: one reachable repo with an empty window must not read as a healthy fleet.
        val worst = results.map { it.second.status }.firstOrNull { it != GitHubFeed.Status.OK } ?: GitHubFeed.Status.OK
        FeedEmpty(AggregatorModel.feedEmptyLabel(worst, "workflow runs")); return
    }
    if (results.any { it.second.status != GitHubFeed.Status.OK }) {
        FeedEmpty(AggregatorModel.feedStaleLabel(results.map { it.second.status }.first { it != GitHubFeed.Status.OK }))
    }
    // The fleet line is the card's handoff to the full app; per-repo rows are not (a tap has exactly one meaning).
    StatsRow("Fleet", all, now, panel.url, onOpen)
    for ((ref, feed) in results) {
        if (feed.items.isEmpty()) {
            FeedEmpty("${ref.label} — ${AggregatorModel.feedEmptyLabel(feed.status, "workflow runs")}"); continue
        }
        StatsRow(ref.label, feed.items, now, "", onOpen)
    }
}

@Composable
private fun StatsRow(label: String, runs: List<GitHubFeed.Run>, now: Long, url: String, onOpen: (String) -> Unit) {
    val (verdict, severity) = AggregatorModel.runVerdict(runs, now)
    FeedRow(label, verdict, url, severity, onOpen)
}

/** "Dagu": the last `limit` runs across EVERY registered DAG. Server + bearer come from [DaguPrefs], shared with the
 *  native page:c3/dagu list, so there is one Dagu config on the device. */
@Composable
private fun DaguRunsFeedBody(panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val limit = panel.limit.takeIf { it > 0 } ?: 5
    val prefs = remember { DaguPrefs(ctx) }
    val loaded by produceState<Result<List<DaguRunsFeed.Run>>?>(null, panel) {
        value = withContext(Dispatchers.IO) { runCatching { DaguRunsFeed.recentRuns(prefs.serverUrl, prefs.bearerToken, limit) } }
    }
    val outcome = loaded ?: run { FeedLoading("Loading Dagu runs…"); return }
    outcome.onFailure { e ->
        FeedEmpty("(Dagu unreachable — ${e.message ?: "no response"}. If the token expired, sign in again on the Dagu page.)")
    }
    outcome.onSuccess { runs ->
        if (runs.isEmpty()) { FeedEmpty("(no runs recorded on ${prefs.serverUrl})"); return@onSuccess }
        val now = System.currentTimeMillis()
        for (r in runs) {
            val ts = if (r.finishedAtMs > 0L) r.finishedAtMs else r.startedAtMs
            FeedRow(r.name, "${AggregatorModel.daguStatusLabel(r.status)} · ${GitHubFeed.ago(now - ts)}",
                "page:c3/dagu", AggregatorModel.daguSeverity(r.status), onOpen)
        }
    }
}

/**
 * "Re-run" control for one GitHub Actions workflow row. Dispatch goes through c3-infra-api (POST /workflows/dispatch)
 * with the Authelia bearer this device already stores, NOT with a GitHub token: a PAT shipped inside the APK would be
 * extractable. Every outcome is displayed in place — dispatched, refused, proxy not configured, no token on the
 * device — the control never finishes without saying what happened.
 */
@Composable
private fun RerunControl(ref: Sections.RepoRef, workflowFile: String) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(true) }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.diegonmarcos.superapp.uikit.KitTextButton(enabled = !busy, modifier = Modifier.testTag(TAG_RERUN), onClick = {
            busy = true
            status = "Dispatching $workflowFile…"
            ok = true
            val bearer = runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("")
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    OpsClient.dispatchWorkflow(repo = "${ref.owner}/${ref.repo}", workflow = workflowFile,
                        ref = "main", bearer = bearer)
                }
                busy = false
                when (outcome) {
                    is OpsClient.Outcome.Ok -> { ok = true; status = outcome.message }
                    is OpsClient.Outcome.Failed -> { ok = false; status = "${outcome.kind}: ${outcome.message}" }
                }
            }
        }) { Text("Re-run", fontSize = 12.sp, color = p.accent) }
        Text(status, color = if (ok) (if (status.startsWith("Dispatching")) p.textSecondary else p.ok) else p.bad,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(TAG_RERUN_STATUS))
    }
}

internal const val TAG_LOADING = "agg:feed-loading"
internal const val TAG_RERUN = "agg:rerun"
internal const val TAG_RERUN_STATUS = "agg:rerun-status"
