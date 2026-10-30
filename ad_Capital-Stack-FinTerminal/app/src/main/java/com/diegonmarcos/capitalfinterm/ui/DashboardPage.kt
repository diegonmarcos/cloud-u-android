package com.diegonmarcos.capitalfinterm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.CachedHttp
import com.diegonmarcos.capitalfinterm.ChartPoint
import com.diegonmarcos.capitalfinterm.Catalog
import com.diegonmarcos.capitalfinterm.DashStatus
import com.diegonmarcos.capitalfinterm.Dashboard
import com.diegonmarcos.capitalfinterm.Decl
import com.diegonmarcos.capitalfinterm.Mesh
import com.diegonmarcos.capitalfinterm.Priority
import com.diegonmarcos.capitalfinterm.Dashboards
import com.diegonmarcos.capitalfinterm.Engine
import com.diegonmarcos.capitalfinterm.Http
import com.diegonmarcos.capitalfinterm.Panel
import com.diegonmarcos.capitalfinterm.PanelData
import com.diegonmarcos.capitalfinterm.R
import com.diegonmarcos.capitalfinterm.Source
import com.diegonmarcos.capitalfinterm.SourceKeys
import com.diegonmarcos.capitalfinterm.UrlHttp
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The process-wide network seam of the dashboards: Android's resolver through UrlHttp, an answer remembered for a minute. The mesh hosts
 * (taken from the baked fleet declaration) are the only ones allowed over plain http, WireGuard encrypting that hop.
 */
object Net {
    private val cleartext: Set<String> by lazy { Decl.fleetMesh.values.map { Mesh.hostOf(it) }.toSet() }
    val cache: CachedHttp by lazy { CachedHttp(UrlHttp(cleartextHosts = cleartext)) }
    val http: Http get() = cache

    /** Short timeouts: off the mesh the question must fail fast. */
    val probe: Http by lazy { UrlHttp(connectMs = 3_000, readMs = 4_000, cleartextHosts = cleartext) }

    /** The container actions: a start can take a while. */
    val control: Http by lazy { UrlHttp(connectMs = 3_000, readMs = 30_000, cleartextHosts = cleartext) }

    private val up = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    /** Whether [healthUrl] answers, remembered for 30 seconds so the panels of a dashboard ask once. */
    fun reachable(healthUrl: String): Boolean {
        val now = System.currentTimeMillis()
        up[healthUrl]?.let { (at, ok) -> if (now - at < 30_000L) return ok }
        val ok = runCatching { probe.get(healthUrl); true }.getOrDefault(false)
        up[healthUrl] = now to ok
        return ok
    }

    fun forget() { up.clear(); cache.clear() }
}

/** The word and state a dashboard's status is drawn with. */
@Composable
fun statusPill(status: DashStatus): Pair<String, KitState> = when (status) {
    DashStatus.LIVE -> stringResource(R.string.status_live) to KitState.OK
    DashStatus.PARTLY_LIVE -> stringResource(R.string.status_partly) to KitState.WARN
    DashStatus.NEEDS_KEY -> stringResource(R.string.status_needs_key) to KitState.WARN
    DashStatus.LAYOUT_ONLY -> stringResource(R.string.status_layout) to KitState.IDLE
}

/**
 * One dashboard (data/dashboards.json): a card per panel. A panel with a `fetch` reads its key-free source live (a chart and/or a table,
 * the date of the data, the source named); a `placeholder` panel draws its dashed layout and says to add the key in Config > Sources.
 */
@Composable
fun DashboardPage(dashboardId: String, onBack: () -> Unit, onOpenSources: () -> Unit) {
    val ctx = LocalContext.current
    val sources = remember { AssetData.sources(ctx) }
    val dash = remember(dashboardId) { Dashboards.byId(AssetData.dashboards(ctx), dashboardId) }
    val keys = remember { SourceKeys(EncryptedSecretBox(ctx)) }
    var refresh by remember { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        KitTextButton(onClick = onBack, modifier = Modifier.testTag(Tags.BACK)) { Text(stringResource(R.string.dash_back)) }
        Text(dash?.entry.orEmpty(), color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (dash != null) {
            val (word, state) = statusPill(Dashboards.status(dash, sources))
            KitStatePill(word, state)
        }
        KitTextButton(onClick = { Net.forget(); refresh += 1 }, modifier = Modifier.testTag(Tags.REFRESH)) { Text(stringResource(R.string.dash_refresh)) }
    }
    if (dash == null) {
        Text(stringResource(R.string.dash_missing), color = p.textSecondary)
        return
    }
    dash.panels.forEach { panel ->
        PanelCard(dash, panel, sources, Catalog.byId(sources, panel.source), panel.source.isNotEmpty() && keys.has(panel.source), refresh, onOpenSources)
    }
}

private sealed interface Load {
    object Loading : Load
    object Failed : Load
    class Ok(val data: PanelData, val fetchedAt: String) : Load
}

@Composable
private fun PanelCard(dash: Dashboard, panel: Panel, all: List<Source>, source: Source?, hasKey: Boolean, refresh: Int, onOpenSources: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    KitCard(Modifier.testTag(Tags.panel(panel.id))) {
        Text(panel.title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        when {
            panel.isStatic -> PanelContent(panel, Engine.alloc(panel, dash.legs))
            panel.isLive && source != null -> LiveBody(dash, panel, all, source, refresh)
            else -> PlaceholderBody(panel, source, hasKey, onOpenSources)
        }
        if (source != null) {
            KitTextButton(onClick = { if (source.docs.isNotBlank()) openUrl(ctx, source.docs) }) {
                Text(stringResource(R.string.panel_source, source.name))
            }
        }
    }
}

@Composable
private fun LiveBody(dash: Dashboard, panel: Panel, all: List<Source>, source: Source, refresh: Int) {
    val p = LocalKitPalette.current
    var state by remember(panel.id, refresh) { mutableStateOf<Load>(Load.Loading) }
    LaunchedEffect(panel.id, refresh) {
        state = Load.Loading
        state = withContext(Dispatchers.IO) {
            runCatching {
                val route = Priority.route(panel, dash, all, Decl.fleetMesh) { url -> Net.reachable(url) }
                Engine.load(panel, source, Net.http, LocalDate.now(ZoneOffset.UTC), route)
            }
                .fold({ Load.Ok(it, LocalTime.now().truncatedTo(ChronoUnit.MINUTES).toString()) }, { Load.Failed })
        }
    }
    when (val s = state) {
        Load.Loading -> Text(stringResource(R.string.panel_loading), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        Load.Failed -> Text(stringResource(R.string.panel_failed, source.name), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        is Load.Ok -> {
            PanelContent(panel, s.data)
            val asOf = if (s.data.asOf.isBlank()) "" else stringResource(R.string.panel_updated, s.data.asOf) + " - "
            Text(asOf + stringResource(R.string.panel_fetched, s.fetchedAt) + if (s.data.servedBy.isEmpty()) "" else " - " + stringResource(R.string.panel_served, s.data.servedBy),
                color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PanelContent(panel: Panel, data: PanelData) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    when (panel.view) {
        Dashboards.VIEW_CURVE -> LineChart(data.chart, panel.unit)
        Dashboards.VIEW_BARS, Dashboards.VIEW_ALLOC -> BarChart(data.chart, panel.unit)
    }
    if (data.rows.isNotEmpty()) {
        DataTable(data.columns, data.rows, data.links, wideFirst = panel.view == Dashboards.VIEW_NEWS) { url -> openUrl(ctx, url) }
    }
    if (data.note.isNotBlank()) Text(data.note, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LineChart(points: List<ChartPoint>, unit: String) {
    if (points.size < 2) return
    val p = LocalKitPalette.current
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val minX = points.first().x
    val maxX = points.last().x
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(fmt(maxY) + unit, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text(fmt(minY) + unit, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
    Canvas(Modifier.fillMaxWidth().height(120.dp).testTag(Tags.CHART)) {
        val spanY = if (maxY - minY > 1e-9) maxY - minY else 1.0
        val spanX = if (maxX - minX > 1e-9) maxX - minX else 1.0
        val pts = points.map { pt -> Offset(((pt.x - minX) / spanX * size.width).toFloat(), (size.height - (pt.y - minY) / spanY * size.height).toFloat()) }
        val path = Path()
        pts.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawLine(p.hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        drawPath(path, p.accent, style = Stroke(width = 2.dp.toPx()))
        pts.forEach { drawCircle(p.accent, 3.dp.toPx(), it) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(points.first().label, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text(points.last().label, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BarChart(points: List<ChartPoint>, unit: String) {
    if (points.isEmpty()) return
    val p = LocalKitPalette.current
    val maxAbs = points.maxOf { Math.abs(it.y) }.let { if (it > 0.0) it else 1.0 }
    Column(Modifier.testTag(Tags.CHART), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        points.forEach { pt ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pt.label, Modifier.width(112.dp), color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(8.dp)) {
                    Box(Modifier.fillMaxWidth((Math.abs(pt.y) / maxAbs).toFloat().coerceIn(0.01f, 1f)).fillMaxHeight().background(if (pt.y < 0) p.bad else p.accent))
                }
                Text(fmt(pt.y) + unit, Modifier.width(76.dp), color = p.textPrimary, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun DataTable(columns: List<String>, rows: List<List<String>>, links: List<String>, wideFirst: Boolean, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    val n = maxOf(columns.size, rows.maxOf { it.size })
    val matrix = n > 4
    val scroll = rememberScrollState()
    fun cellMod(i: Int, scope: androidx.compose.foundation.layout.RowScope): Modifier =
        if (matrix) Modifier.width(if (i == 0) 56.dp else 72.dp)
        else with(scope) { Modifier.weight(if (i == 0) (if (wideFirst) 3f else 2f) else 1f) }
    Column(if (matrix) Modifier.horizontalScroll(scroll) else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (columns.isNotEmpty()) {
            Row {
                (0 until n).forEach { i -> Text(columns.getOrElse(i) { "" }, cellMod(i, this), color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
        }
        rows.forEachIndexed { r, row ->
            val link = links.getOrNull(r).orEmpty()
            Row(if (link.isNotEmpty()) Modifier.clickable { onOpen(link) } else Modifier) {
                (0 until n).forEach { i ->
                    Text(row.getOrElse(i) { "" }, cellMod(i, this), color = if (link.isNotEmpty() && i == 0) p.accent else p.textPrimary,
                        style = MaterialTheme.typography.bodySmall, maxLines = if (wideFirst && i == 0) 3 else 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The dashed layout of a panel whose source needs a key (or has no connector yet): the columns drawn, the rows empty. */
@Composable
private fun PlaceholderBody(panel: Panel, source: Source?, hasKey: Boolean, onOpenSources: () -> Unit) {
    val p = LocalKitPalette.current
    val n = panel.placeholderColumns.size
    Column(Modifier.testTag(Tags.PLACEHOLDER), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (n > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                panel.placeholderColumns.forEach { c ->
                    Text(c, Modifier.weight(1f).dashed(p.hairline).padding(4.dp), color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            repeat(panel.placeholderRows) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(n) { Box(Modifier.weight(1f).height(20.dp).dashed(p.hairline)) }
                }
            }
        }
    }
    val name = source?.name.orEmpty()
    when {
        source != null && source.needsKey && !hasKey -> {
            Text(stringResource(R.string.ph_add_key, name), color = p.textPrimary, style = MaterialTheme.typography.bodySmall)
            KitTextButton(onClick = onOpenSources, modifier = Modifier.testTag(Tags.OPEN_SOURCES)) { Text(stringResource(R.string.ph_open_sources)) }
        }
        source != null && source.needsKey -> Text(stringResource(R.string.ph_key_saved, name), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        else -> Text(stringResource(R.string.ph_no_connector, name), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

private fun Modifier.dashed(color: Color): Modifier = this.drawBehind {
    drawRect(color, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
}

private fun fmt(v: Double): String = if (Math.abs(v) >= 1000.0) String.format(Locale.ROOT, "%,.0f", v) else String.format(Locale.ROOT, "%.2f", v)
