package com.diegonmarcos.superapp.rss
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.cloud.CloudData
import com.diegonmarcos.superapp.launcher.TileGridFragment

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RSS / feed all — lists ONLY ntfy channels declared in
 * `cloud-data/ntfy-api/src/topics.json` (fetched live via
 * [CloudData.loadNtfyTopics]). Each topic is a tappable card; tapping
 * a future revision will open the ntfy SSE stream for that topic.
 *
 * Topics are auto-grouped by their snake_case prefix (deploy_/dev_/
 * health_/ops_/sec_) so the list reads as a categorised inventory of
 * the cloud's pub/sub channels.
 *
 * Compose since #773 (was fragment_rss_feed.xml + item_rss_topic.xml). The grouping is [plan],
 * pure, so a test pins the USER-above-INFRA order and the empty-scope rule without a network.
 * Standalone the page scrolls itself; embedded in the Infos stack it sits in the stack's body.
 */
class RssFeedFragment : KitComposeFragment() {

    /** What the registry fetch answered. */
    private sealed interface Topics {
        object Loading : Topics
        class Ok(val topics: List<String>) : Topics
        class Err(val why: String) : Topics
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        var state by remember { mutableStateOf<Topics>(Topics.Loading) }
        LaunchedEffect(Unit) {
            state = withContext(Dispatchers.IO) { runCatching { CloudData.loadNtfyTopics(ctx) } }
                .fold({ Topics.Ok(it) }, { Topics.Err(it.message ?: "?") })
        }
        val wanted = arguments?.getStringArrayList(ARG_SCOPES).orEmpty()

        Column(pageModifier().padding(12.dp)) {
            Text(stringResource(R.string.rss_title), color = p.textPrimary,
                style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state is Topics.Loading) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), color = p.accent, strokeWidth = 2.dp)
                }
                Text(when (val s = state) {
                    Topics.Loading -> stringResource(R.string.rss_loading)
                    is Topics.Err  -> stringResource(R.string.rss_error, s.why)
                    is Topics.Ok   -> if (s.topics.isEmpty()) stringResource(R.string.rss_empty)
                                      else stringResource(R.string.rss_status, s.topics.size)
                }, color = p.textSecondary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag(TAG_STATUS))
            }
            val ok = state as? Topics.Ok
            if (ok != null && ok.topics.isNotEmpty()) {
                // The advisory topic is pinned OPEN at the top with its live messages,
                // ahead of every other channel. This screen is where a user is sent
                // when their app can no longer update itself, and at that moment the
                // difference between "the fix is here" and "the fix is three taps into
                // an alphabetical list of twenty-six addresses" is whether they find
                // it at all. Every other channel stays a link; this one is the page.
                Advisories()
                for (sp in plan(ok.topics, NtfyScopes.load(), wanted)) ScopeSection(sp)
            }
        }
    }

    /** Standalone the page scrolls; embedded (a child fragment) it rides the stack's scrolling body. */
    @Composable
    private fun pageModifier(): Modifier =
        if (parentFragment != null) Modifier.fillMaxWidth()
        else Modifier.fillMaxSize().verticalScroll(rememberScrollState())

    @Composable
    private fun ScopeSection(sp: ScopePlan) {
        val p = LocalKitPalette.current
        Text(sp.scope.label, color = p.accent, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 10.dp, top = 18.dp).testTag(scopeTag(sp.scope.id)))
        if (sp.empty) {
            // A scope this page ASKED for and that matched nothing gets a header and a reason
            // rather than silence: a section that simply is not drawn is indistinguishable
            // from a broken page.
            Text(stringResource(R.string.rss_scope_empty, sp.scope.label), color = p.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 10.dp).alpha(0.8f).testTag(emptyTag(sp.scope.id)))
            return
        }
        if (sp.scope.subtitle.isNotBlank()) {
            Text(sp.scope.subtitle, color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 10.dp).alpha(0.8f))
        }
        val act = LocalContext.current as? FragmentActivity
        for ((prefix, items) in sp.groups) {
            Text(if (prefix == "_") stringResource(R.string.rss_group_other) else prefix.uppercase(),
                color = p.accent, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp).alpha(0.9f))
            for (topic in items) {
                // The topic page a PERSON opens, so the gated public host is
                // the right one — the in-app browser carries the Authelia
                // cookie. Declared alongside the poll origin so the two can
                // never silently become the same string again.
                val url = "${NtfyCatalog.webBaseUrl()}/$topic"
                // Title line is what the channel CARRIES; the address moves to
                // the subtitle next to the URL that was already there. The raw
                // topic stays visible because it is what you publish to.
                TopicCard(NtfyCatalog.labelOf(topic), "$topic · ${NtfyCatalog.webBaseUrl().substringAfter("://")}/$topic",
                    Modifier.testTag(topicTag(topic)).clickable {
                        // The topic page is ordinary web content on our own host, so it belongs
                        // in the app's browser; ACTION_VIEW left superapp on every topic tap and
                        // lost the way back to this registry.
                        (act as? TileGridFragment.TileClickListener)?.onTileClicked(url)
                    })
            }
        }
    }

    @Composable
    private fun TopicCard(name: String, sub: String, modifier: Modifier) {
        val p = LocalKitPalette.current
        Column(modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(p.surface).padding(14.dp)) {
            Text(name, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall)
            Text(sub, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp).alpha(0.8f))
        }
    }

    /**
     * The advisory channel, expanded in place. Fetched separately from the
     * topic registry and rendered whatever the result: messages if there are
     * any, "nothing published" if the channel is reachable and quiet, the
     * error if it is not. All three are useful; a section that silently
     * vanishes on failure teaches the user the channel is not worth checking.
     */
    @Composable
    private fun Advisories() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        val topic = NtfyCatalog.advisoryTopic()
        val act = ctx as? FragmentActivity
        val outcome by produceState<Result<List<AdvisoryChannel.Message>>?>(null, topic) {
            value = withContext(Dispatchers.IO) { runCatching { AdvisoryChannel.recent(topic) } }
        }
        Text(NtfyCatalog.labelOf(topic), color = p.accent, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 10.dp, top = 8.dp).testTag(TAG_ADVISORY))
        val o = outcome
        Text(when {
            o == null -> stringResource(R.string.rss_advisory_loading)
            o.isFailure -> stringResource(R.string.rss_error, o.exceptionOrNull()?.message ?: "?")
            o.getOrThrow().isEmpty() -> stringResource(R.string.rss_advisory_empty)
            // getQuantityString, not a plain string: the count decides the wording, and Spanish picks
            // a different item for 1 than for 0 or 2. The size is passed twice on purpose — once to
            // select the item, once to fill its %1$d.
            else -> ctx.resources.getQuantityString(R.plurals.rss_advisory_status, o.getOrThrow().size, o.getOrThrow().size)
        }, color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 4.dp).alpha(0.8f).testTag(TAG_ADVISORY_STATUS))
        for (m in o?.getOrNull().orEmpty()) {
            val hasLink = m.link != null
            TopicCard(m.title, if (hasLink) stringResource(R.string.rss_advisory_install) else m.body.take(160),
                Modifier.testTag(advisoryTag(m.title)).clickable {
                    if (hasLink) {
                        // Straight to the bootstrap installer, which downloads, verifies the sha256
                        // sidecar and hands the system installer bytes it can vouch for. Handing the
                        // raw URL to a browser would skip every one of those steps. This is an
                        // Activity of this app, not a browser hop, so it stays a direct startActivity.
                        runCatching {
                            startActivity(com.diegonmarcos.superapp.recovery.RecoveryActivity.intent(ctx, m.appId))
                        }
                    } else {
                        // No install link: the row just shows the channel, which is a plain web
                        // page and so renders in the app's own browser.
                        (act as? TileGridFragment.TileClickListener)?.onTileClicked("${NtfyCatalog.webBaseUrl()}/$topic")
                    }
                })
        }
    }

    /** One scope as drawn: its topics grouped by snake_case prefix ("_" = no prefix), or empty-by-design. */
    internal class ScopePlan(val scope: NtfyScopes.Scope, val groups: List<Pair<String, List<String>>>, val empty: Boolean)

    companion object {
        private const val ARG_SCOPES = "scopes"

        const val TAG_STATUS = "rss:status"
        const val TAG_ADVISORY = "rss:advisory"
        const val TAG_ADVISORY_STATUS = "rss:advisory:status"
        fun scopeTag(id: String) = "rss:scope:$id"
        fun emptyTag(id: String) = "rss:scope:$id:empty"
        fun topicTag(topic: String) = "rss:topic:$topic"
        fun advisoryTag(title: String) = "rss:advisory:$title"

        /**
         * USER above INFRA, before anything is grouped by prefix. These are two different inboxes
         * that happened to share one list: a notification about THIS PHONE and one about a
         * container restarting read identically when sorted alphabetically together. Scope comes
         * from the topic prefix, declared in build.json::ui.ntfy.scopes, and an unrecognised prefix
         * falls to the LAST scope rather than being dropped — a new channel shows up in the wrong
         * bucket, never in none.
         *
         * A page may show only SOME scopes ([wanted]; Apps RSS is the personal inbox, not the whole
         * registry). Classification still runs against the FULL scope list so the catch-all last
         * scope keeps working — filtering happens after, on display, never on the rule. A scope the
         * page ASKED for that matched nothing is kept (empty); scopes it never asked for stay hidden.
         */
        internal fun plan(topics: List<String>, scopes: List<NtfyScopes.Scope>, wanted: List<String>): List<ScopePlan> {
            val byScope = LinkedHashMap<NtfyScopes.Scope, MutableList<String>>()
            for (sc in scopes) byScope[sc] = mutableListOf()
            for (t in topics) byScope.getValue(NtfyScopes.scopeOf(t, scopes)).add(t)
            if (wanted.isNotEmpty()) byScope.keys.retainAll { it.id in wanted }
            return byScope.mapNotNull { (scope, ts) ->
                if (ts.isEmpty()) {
                    if (wanted.isEmpty()) null else ScopePlan(scope, emptyList(), true)
                } else {
                    val groups = ts.groupBy { it.substringBefore('_', it).let { p -> if (p == it) "_" else p } }
                        .toSortedMap().map { (prefix, items) -> prefix to items.sorted() }
                    ScopePlan(scope, groups, false)
                }
            }
        }

        /**
         * [scopeIds] restricts the page to those `ui.ntfy.scopes` ids; empty
         * shows every scope. This is the ONLY difference between Infra RSS
         * (the full channel browser) and Apps RSS (the personal inbox): one
         * catalog, one grouping rule, two filters over it. A subset declared
         * here would have been a second channel list that goes stale the day
         * a topic is added — so it is declared in build.json instead.
         */
        fun newInstance(scopeIds: List<String> = emptyList()) = RssFeedFragment().apply {
            arguments = Bundle().apply { putStringArrayList(ARG_SCOPES, ArrayList(scopeIds)) }
        }
    }
}
