package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.CgcConfig
import com.diegonmarcos.cloudsearch.core.CgcEdge
import com.diegonmarcos.cloudsearch.core.CgcHit
import com.diegonmarcos.cloudsearch.core.CgcNode
import com.diegonmarcos.cloudsearch.core.Cgc
import com.diegonmarcos.cloudsearch.core.CloudConfig
import com.diegonmarcos.cloudsearch.data.Browser
import com.diegonmarcos.cloudsearch.data.FleetCalls
import com.diegonmarcos.cloudsearch.data.Launch
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CgcTags {
    const val STRIP = "cgc_strip"
    const val QUERY = "cgc_query"
    const val SEARCH = "cgc_search"
    const val STATUS = "cgc_status"
    fun repo(id: String) = "cgc_repo_$id"
    fun hit(i: Int) = "cgc_hit_$i"
    fun node(i: Int) = "cgc_node_$i"
    fun web(key: String) = "cgc_web_$key"
    fun drive(key: String) = "cgc_drive_$key"
    fun edge(i: Int) = "cgc_edge_$i"
}

/** What one code-index section remembers while the app is up: the repo, the query and the last answer of each mode. */
class CgcModel(val ix: CgcConfig.Index) {
    var repo by mutableStateOf(ix.repos.first())
    var query by mutableStateOf("")
    var busy by mutableStateOf(false)
    var hits by mutableStateOf<FleetCalls.Result<CgcHit>?>(null)
    var nodes by mutableStateOf<FleetCalls.Result<CgcNode>?>(null)
    var open by mutableStateOf<CgcNode?>(null)
    var edges by mutableStateOf<FleetCalls.Result<CgcEdge>?>(null)
}

/**
 * #1197 CGC: Cloud CGC (the public index) and Me CGC (the private one), one row each in the section's second row, the same page over a
 * different declared index (build.json::search.cgc); each row's Semantic and Graph RAG pages are the index's semantic_page and
 * graph_page. Each page is one LazyColumn, the only vertical scroller.
 */
@Composable
fun CgcSection(state: SearchState) {
    val cfg = state.cfg.cgc
    val page = state.cgcPage
    val hit = cfg?.indexOfPage(page)
    val decl = NAV.section("cgc") ?: return
    NavSectionLayout(
        section = decl,
        selectedId = page,
        onSelect = { state.cgcPage = it.id },
        modifier = Modifier.fillMaxSize().testTag(CgcTags.STRIP),
        dark = state.dark,
        surface = NAV.stripSurface("cgc", state.dark),
    ) {
        Box(Modifier.fillMaxSize().testTag(Tags.page("cgc_$page"))) {
            if (cfg == null || hit == null) Text(stringResource(R.string.cgc_none), Modifier.padding(Metrics.gutter), color = LocalGlass.current.text2)
            else CgcPage(state, cfg, hit.first, state.cgc(hit.first.id), hit.second)
        }
    }
}

@Composable
private fun CgcPage(state: SearchState, cfg: CgcConfig, ix: CgcConfig.Index, m: CgcModel, graph: Boolean) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drivePkg = remember { CloudConfig.fleetApps(String(android.util.Base64.decode(BuildConfig.FLEET_APPS_B64, android.util.Base64.DEFAULT), Charsets.UTF_8)).firstOrNull { it.id == ix.driveFleet }?.pkg }
    val fleet = state.services.fleet
    fun run() {
        val q = m.query.trim()
        if (q.isEmpty() || m.busy) return
        m.busy = true; m.open = null; m.edges = null
        scope.launch {
            if (graph) m.nodes = withContext(Dispatchers.IO) { fleet.graph(cfg, ix, m.repo, q) }
            else m.hits = withContext(Dispatchers.IO) { fleet.semantic(cfg, ix, m.repo, q) }
            m.busy = false
        }
    }
    fun openNode(n: CgcNode) { relations(scope, m, fleet, cfg, ix, n) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Metrics.gutter, end = Metrics.gutter, top = Metrics.small, bottom = Metrics.contentBottom),
        verticalArrangement = Arrangement.spacedBy(Metrics.small),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), verticalAlignment = Alignment.CenterVertically) {
                    GlassField(
                        m.query, { m.query = it },
                        stringResource(if (graph) R.string.cgc_graph_placeholder else R.string.cgc_semantic_placeholder), CgcTags.QUERY,
                        Modifier.weight(1f), onSearch = { run() },
                    )
                    Chip(stringResource(R.string.cgc_search), CgcTags.SEARCH, accent = true) { run() }
                }
                ChipRow { ix.repos.forEach { r -> Chip(r, CgcTags.repo(r), selected = r == m.repo) { m.repo = r } } }
                // Read here, in the item, from the state itself: an item that only captured the answer would not follow it.
                val shown: FleetCalls.Result<*>? = if (graph) m.nodes else m.hits
                Text(statusLine(ix, shown, m.busy, graph), Modifier.testTag(CgcTags.STATUS), color = g.text2, style = Type.style(Type.tiny))
                if (ix.needsAccount && shown == null) Text(stringResource(R.string.cgc_account_hint), color = g.text2, style = Type.style(Type.tiny))
            }
        }
        if (graph) {
            m.open?.let { n ->
                item {
                    val (callers, callees) = Cgc.split(n.id, m.edges?.items.orEmpty())
                    GlassCard {
                        Text(n.id, color = g.text, style = Type.style(Type.body, FontWeight.Bold))
                        if (n.description.isNotBlank()) Text(n.description, color = g.text2, style = Type.style(Type.small), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.cgc_relations), color = g.text2, style = Type.style(Type.label, FontWeight.Bold))
                        val rel = m.edges
                        if (rel != null && rel.kind != FleetCalls.Kind.OK) Text(relationsNote(rel), color = g.text2, style = Type.style(Type.small))
                        Text(stringResource(R.string.cgc_callers, callers.size), color = g.text, style = Type.style(Type.small, FontWeight.SemiBold))
                        callers.forEachIndexed { i, e -> EdgeRow(i, e.source, e.type) { openNode(CgcNode(e.source, m.repo, e.source, "", "")) } }
                        Text(stringResource(R.string.cgc_callees, callees.size), color = g.text, style = Type.style(Type.small, FontWeight.SemiBold))
                        callees.forEachIndexed { i, e -> EdgeRow(100 + i, e.target, e.type) { openNode(CgcNode(e.target, m.repo, e.target, "", "")) } }
                    }
                }
            }
            val nodes = m.nodes?.items.orEmpty()
            items(nodes.withIndex().toList(), key = { it.index }) { (i, n) ->
                GlassCard(Modifier.testTag(CgcTags.node(i)), onClick = { openNode(n) }) {
                    Text(n.path, color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (n.kind.isNotBlank() || n.description.isNotBlank()) Text(listOf(n.kind, n.description).filter { it.isNotBlank() }.joinToString(" · "), color = g.text2, style = Type.style(Type.tiny), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    OpenChips("n$i", ix, m.repo, n.path, null, drivePkg)
                }
            }
        } else {
            val hits = m.hits?.items.orEmpty()
            items(hits.withIndex().toList(), key = { it.value.repo + "/" + it.value.path + ":" + (it.value.line ?: 0) + "#" + it.index }) { (i, h) ->
                GlassCard(Modifier.testTag(CgcTags.hit(i))) {
                    Text(h.repo + "/" + h.path + (h.line?.let { ":$it" } ?: ""), color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (h.symbols.isNotEmpty()) Text(h.symbols.take(6).joinToString(", "), color = g.accent, style = Type.style(Type.tiny), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (h.snippet.isNotBlank()) Text(h.snippet, color = g.text2, style = Type.style(Type.small), maxLines = 6, overflow = TextOverflow.Ellipsis)
                    OpenChips("h$i", ix, h.repo, h.path, h.line, drivePkg)
                }
            }
        }
    }
}

private fun relations(scope: CoroutineScope, m: CgcModel, fleet: FleetCalls, cfg: CgcConfig, ix: CgcConfig.Index, n: CgcNode) {
    m.open = n; m.edges = null
    scope.launch { m.edges = withContext(Dispatchers.IO) { fleet.related(cfg, ix, m.repo, n.id) } }
}

@Composable
private fun EdgeRow(i: Int, other: String, type: String, onClick: () -> Unit) {
    val g = LocalGlass.current
    Text(
        other + if (type.isBlank()) "" else " · $type", color = g.accent, style = Type.style(Type.small), maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().testTag(CgcTags.edge(i)).clickable(onClick = onClick),
    )
}

/** Open a result in the web (Cloud Browser, else the system browser) or hand over to Cloud Drive. */
@Composable
private fun OpenChips(key: String, ix: CgcConfig.Index, repo: String, path: String, line: Int?, drivePkg: String?) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Chip(stringResource(R.string.cgc_open_web), CgcTags.web(key), icon = R.drawable.ph_arrow_square_out) { Browser.open(ctx, ix.web(repo, path, line)) }
        if (drivePkg != null) Chip(stringResource(R.string.cgc_open_drive), CgcTags.drive(key)) { Launch.app(ctx, drivePkg) }
    }
}

@Composable
private fun relationsNote(r: FleetCalls.Result<CgcEdge>): String = when (r.kind) {
    FleetCalls.Kind.EMPTY -> stringResource(R.string.cgc_related_none)
    FleetCalls.Kind.UNAVAILABLE -> stringResource(R.string.cgc_unavailable, r.detail)
    FleetCalls.Kind.REFUSED -> stringResource(R.string.cgc_refused, r.detail)
    else -> stringResource(R.string.cgc_note, r.detail)
}

/** The one line under the field: what the last query came to, in the service's own words where it gave any. */
@Composable
private fun statusLine(ix: CgcConfig.Index, r: FleetCalls.Result<*>?, busy: Boolean, graph: Boolean): String = when {
    busy -> stringResource(R.string.cgc_busy)
    r == null -> stringResource(R.string.cgc_idle)
    else -> when (r.kind) {
        FleetCalls.Kind.OK -> stringResource(if (graph) R.string.cgc_nodes else R.string.cgc_hits, r.items.size, r.via)
        FleetCalls.Kind.EMPTY -> if (ix.needsAccount) stringResource(R.string.cgc_private_empty, r.detail.ifBlank { stringResource(R.string.cgc_no_health) }) else stringResource(R.string.cgc_empty, ix.label)
        FleetCalls.Kind.UNAVAILABLE -> stringResource(R.string.cgc_unavailable, r.detail)
        FleetCalls.Kind.REFUSED -> if (ix.needsAccount) stringResource(R.string.cgc_private_not_built, r.detail) else stringResource(R.string.cgc_refused, r.detail)
        FleetCalls.Kind.NOTE -> if (ix.needsAccount) stringResource(R.string.cgc_private_not_built, r.detail) else stringResource(R.string.cgc_note, r.detail)
    }
}
