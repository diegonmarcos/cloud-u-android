package com.diegonmarcos.cloudsearch.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.cloudsearch.core.Analysis
import com.diegonmarcos.cloudsearch.core.Calculators
import com.diegonmarcos.cloudsearch.core.FeedItem
import com.diegonmarcos.cloudsearch.core.Listing
import com.diegonmarcos.cloudsearch.core.Market
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.SearchEngine
import com.diegonmarcos.cloudsearch.data.Browser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** One vertical's listing: the query, the answer, whether it is running, and whether the city changed under it. */
class ListingModel(initial: String) {
    var q by mutableStateOf(initial)
    var result by mutableStateOf<SearchEngine.QueryResult?>(null)
    var loading by mutableStateOf(false)
    var stale by mutableStateOf(true)

    fun run(state: SearchState, scope: CoroutineScope, vertical: String) {
        val query = q
        loading = true
        stale = false
        state.busy++
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) { runCatching { state.services.engine.query(vertical, query, state.city) }.getOrNull() }
                state.services.prefs.setLastQuery(vertical, query)
            } catch (e: CancellationException) {
                stale = true // the page left before the answer came: ask again when it is back
                throw e
            } finally {
                loading = false
                state.busy--
            }
        }
    }
}

fun money(v: Double, currency: String?): String {
    val n = String.format(Locale.ROOT, "%,.2f", v)
    return when (currency) { null -> n; "EUR" -> "€$n"; else -> "$n $currency" }
}

fun day(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.UK).format(Date(ms))

/** The page's own padding under the top bar and above the nav (the mockup's .content-area). */
val pagePadding = PaddingValues(start = Metrics.gutter, end = Metrics.gutter, top = Metrics.contentTop, bottom = Metrics.contentBottom)

/** Every vertical page starts with its title, its description and, when it has more than one, its sub-nav. */
fun LazyListScope.pageHeader(state: SearchState, v: SearchConfig.Vertical) {
    item(key = "header") {
        Column {
            TopicHeader(v.title, v.blurb)
            // #868 the fleet's page-tab strip, from build.json::ui.sections[].pages.
            if (v.subpages.size > 1) PageTabs(
                pages = NAV.section(v.id)?.pages.orEmpty(),
                selectedId = state.subpageOf(v),
                onSelect = { state.showSubpage(v, it.id) },
                underTopChrome = false,
            )
        }
    }
}

@Composable
fun ListingPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val g = LocalGlass.current
    val m = state.listing(v.id)
    val scope = rememberCoroutineScope()
    LaunchedEffect(v.id, state.city) { if (m.stale) m.run(state, scope, v.id) }
    val f = state.filtersOf(v.id)
    val shown = m.result?.let { f.apply(it.listings, v, state.cfg.recentDays, System.currentTimeMillis()) }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        pageHeader(state, v)
        item { GlassField(m.q, { m.q = it }, v.placeholder, Tags.SEARCH_BOX, onSearch = { m.run(state, scope, v.id) }) }
        item {
            ChipRow {
                Chip(stringResource(R.string.more_filters), Tags.MORE_FILTERS, accent = true, icon = R.drawable.ph_faders) { state.menu = Menu.FILTERS }
                Chip(stringResource(R.string.recent), Tags.chip("recent"), selected = f.recent) { state.filters[v.id] = f.copy(recent = !f.recent) }
                Chip(stringResource(R.string.verified), Tags.chip("verified"), selected = f.verified) { state.filters[v.id] = f.copy(verified = !f.verified) }
                v.chips.forEach { c ->
                    Chip(c.label, Tags.chip(c.id), selected = c.id in f.chips) {
                        state.filters[v.id] = f.copy(chips = if (c.id in f.chips) f.chips - c.id else f.chips + c.id)
                    }
                }
            }
        }
        if (m.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = g.accent, trackColor = g.glass) }
        m.result?.let { r -> item { SourceStrip(r.statuses) } }
        if (!m.loading && m.result != null && shown.isEmpty() && m.result!!.statuses.any { it.state.fetchedSomething() }) item {
            Text(stringResource(R.string.no_results), color = g.text2, style = Type.style(Type.small), modifier = Modifier.padding(vertical = Metrics.gutter))
        }
        items(shown, key = { it.key }) { ListingCard(it) }
    }
}

private fun SearchEngine.State.fetchedSomething() = this == SearchEngine.State.OK || this == SearchEngine.State.CACHED || this == SearchEngine.State.STALE

/**
 * Where every result came from, or why a source gave none: read, cached, stale (offline, last
 * answer), failed, waiting for a term; links open the site's own search; disabled ones say why.
 */
@Composable
fun SourceStrip(statuses: List<SearchEngine.SourceStatus>) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
        statuses.filter { it.state != SearchEngine.State.LINK && it.state != SearchEngine.State.DISABLED }.forEach { s ->
            val line = when (s.state) {
                SearchEngine.State.OK -> stringResource(R.string.src_ok, s.label, s.count)
                SearchEngine.State.CACHED -> stringResource(R.string.src_cached, s.label, s.count, s.at?.let { day(it) } ?: "")
                SearchEngine.State.STALE -> stringResource(R.string.src_stale, s.label, s.count, s.at?.let { day(it) } ?: "", s.detail)
                SearchEngine.State.ERROR -> stringResource(R.string.src_error, s.label, s.detail)
                else -> stringResource(R.string.src_skipped, s.label, s.detail)
            }
            val bad = s.state == SearchEngine.State.ERROR || s.state == SearchEngine.State.STALE
            Text(line, color = if (bad) g.negative else g.text2, style = Type.style(Type.label), modifier = Modifier.testTag(Tags.source(s.id)))
        }
        val links = statuses.filter { it.state == SearchEngine.State.LINK }
        if (links.isNotEmpty()) {
            FieldLabel(stringResource(R.string.open_on), Modifier.padding(top = Metrics.small))
            ChipRow {
                links.forEach { s -> Chip(s.label, Tags.source(s.id), icon = R.drawable.ph_arrow_square_out) { s.link?.let { Browser.open(ctx, it) } } }
            }
        }
        statuses.filter { it.state == SearchEngine.State.DISABLED }.forEach { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.small), modifier = Modifier.testTag(Tags.source(s.id))) {
                Ph(R.drawable.ph_warning, Metrics.iconXs, g.text2)
                Text(stringResource(R.string.src_disabled, s.label, s.detail), color = g.text2, style = Type.style(Type.label))
            }
        }
    }
}

/** .card for a result: the image area (the vertical's icon: no image loader), title, subtitle, badge, source line, save. */
@Composable
fun ListingCard(l: Listing) {
    val state = LocalState.current
    val g = LocalGlass.current
    val ctx = LocalContext.current
    var saved by remember(l.key) { mutableStateOf(state.services.saved.isSaved(l.key)) }
    GlassCard(Modifier.testTag(Tags.card(l.key)), onClick = l.url?.let { u -> { Browser.open(ctx, u) } }) {
        val v = state.cfg.verticals.firstOrNull { l.source in it.sources }
        Box(
            Modifier.fillMaxWidth().height(Metrics.placeholderHeight).clip(RoundedCornerShape(Metrics.placeholderRadius)).background(g.field),
            contentAlignment = Alignment.Center,
        ) { DeclaredIcon(v?.icon ?: "", Metrics.iconLg, g.text2) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Column(Modifier.weight(1f)) {
                Text(l.title, color = g.text, style = Type.style(Type.cardTitle, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (l.subtitle.isNotBlank()) Text(l.subtitle, color = g.text2, style = Type.style(Type.cardSubtitle), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val price = l.price?.let { p ->
                val unit = when (l.unit) { null -> ""; "h" -> stringResource(R.string.per_hour); "yr" -> stringResource(R.string.per_year); else -> "/" + l.unit }
                money(p, l.currency) + unit
            }
            if (price != null) Badge(price, g.priceBadge) else l.tags.firstOrNull()?.let { Badge(it, g.accent) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.small)) {
            if (l.verified) Ph(R.drawable.ph_seal_check, Metrics.iconSm, g.accent, stringResource(R.string.verified))
            val meta = listOfNotNull(state.cfg.sources[l.source]?.label, l.date?.let { day(it) }).joinToString(" · ")
            Text(meta, color = g.text2, style = Type.style(Type.label), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.clip(CircleShape).clickable { saved = state.services.saved.toggle(l) }.padding(Metrics.small)) {
                Ph(if (saved) R.drawable.ph_star_fill else R.drawable.ph_star, Metrics.icon, if (saved) g.accent else g.text2,
                    stringResource(if (saved) R.string.unsave else R.string.save))
            }
        }
    }
}

/** The mockup's Market Overview card, from the live sources: a figure no source carries is shown as missing, never invented. */
@Composable
fun AnalysisPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val g = LocalGlass.current
    var jobs by remember { mutableStateOf<Analysis.Result?>(null) }
    var market by remember { mutableStateOf<Market.Result?>(null) }
    var loading by remember { mutableStateOf(true) }
    val q = state.listing(v.id).q
    LaunchedEffect(v.id, state.city) {
        loading = true
        val (j, m) = withContext(Dispatchers.IO) {
            when (v.analysis) {
                "jobs" -> runCatching { state.services.engine.analysis(v.id, q, state.city) }.getOrNull() to null
                "market" -> null to runCatching { state.services.engine.market(v.id) }.getOrNull()
                else -> null to null
            }
        }
        jobs = j
        market = m
        loading = false
    }
    val chart = namedColor(v.chartColor)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        pageHeader(state, v)
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = g.accent, trackColor = g.glass) }
        jobs?.let { a -> item { JobsOverview(a, chart) } }
        market?.let { m ->
            item { MarketOverview(m, chart) }
            item { SourceStrip(m.statuses) }
        }
        if (!loading && jobs == null && market == null) item {
            Text(stringResource(R.string.analysis_failed), color = g.negative, style = Type.style(Type.small))
        }
    }
}

@Composable
private fun OverviewHeader(trailing: @Composable () -> Unit) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth().padding(bottom = Metrics.small), verticalAlignment = Alignment.CenterVertically) {
        Ph(R.drawable.ph_chart_line_up, Metrics.iconSm, g.accent)
        Text(stringResource(R.string.market_overview), color = g.text, style = Type.style(Type.calcTitle, FontWeight.SemiBold),
            modifier = Modifier.padding(start = Metrics.small).weight(1f))
        trailing()
    }
}

@Composable
private fun JobsOverview(a: Analysis.Result, chart: Color) {
    val state = LocalState.current
    val g = LocalGlass.current
    GlassCard {
        OverviewHeader {
            Select(state.cfg.city(state.city).label, state.cfg.cities.map { it.id to it.label }, Tags.city("select")) { state.pickCity(it) }
        }
        Tiles(listOf(
            Triple(stringResource(R.string.stat_remote_short), a.remoteShare?.let { String.format(Locale.ROOT, "%.0f%%", it * 100) }, g.accent),
            Triple(stringResource(R.string.stat_time_to_hire), null, g.text),
            Triple(stringResource(R.string.stat_open_positions), a.total?.let { String.format(Locale.ROOT, "%,d", it) }, g.positive),
        ))
        if (a.fields.isNotEmpty()) {
            FieldLabel(stringResource(R.string.top_fields), Modifier.padding(top = Metrics.gap))
            Bars(a.fields.map { Bar(it.label, it.value.toDouble(), it.value.toString()) }, chart)
        }
        Insights(listOfNotNull(
            a.medianYearly?.let { stringResource(R.string.insight_yearly, money(it, "EUR"), a.yearlySample) },
            a.medianHourly?.let { stringResource(R.string.insight_hourly, money(it, "EUR"), a.hourlySample) },
            a.remoteShare?.let { stringResource(R.string.insight_remote, a.remoteSample) },
            stringResource(R.string.analysis_sources),
        ))
    }
}

@Composable
private fun MarketOverview(m: Market.Result, chart: Color) {
    val g = LocalGlass.current
    GlassCard {
        OverviewHeader { Text(stringResource(R.string.germany), color = g.text2, style = Type.style(Type.label)) }
        Tiles(m.stats.map { st -> Triple(st.label, seriesValue(st), g.text) }, m.stats.map { seriesChange(it) })
        m.chart?.let { c ->
            if (m.chartPoints.isNotEmpty()) {
                FieldLabel(c.detail, Modifier.padding(top = Metrics.gap))
                Bars(m.chartPoints.map { Bar(it.period, it.value, String.format(Locale.ROOT, "%.1f", it.value)) }, chart)
            }
        }
        Insights(m.stats.mapNotNull { st ->
            st.latest?.let { p -> stringResource(R.string.insight_series, st.label, seriesValue(st) ?: "", seriesChange(st) ?: stringResource(R.string.no_year_before), st.source, p.period) }
        } + stringResource(R.string.market_sources))
    }
}

/** The mockup's three stat tiles; a missing figure reads "no source". */
@Composable
private fun Tiles(tiles: List<Triple<String, String?, Color>>, notes: List<String?> = emptyList()) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.chipGap)) {
        tiles.forEachIndexed { i, (label, value, color) ->
            val shape = RoundedCornerShape(Metrics.tileRadius)
            Column(
                Modifier.weight(1f).clip(shape).background(g.tile).border(Metrics.hairline, g.tileBorder, shape).padding(Metrics.tilePad)
                    .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, color = g.text2, style = Type.style(Type.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value ?: stringResource(R.string.no_source), color = if (value == null) g.text2 else color,
                    style = Type.style(Type.body, FontWeight.Bold), textAlign = TextAlign.Center, maxLines = 2)
                notes.getOrNull(i)?.let { Text(it, color = g.accent, style = Type.style(Type.tiny), textAlign = TextAlign.Center) }
            }
        }
    }
}

data class Bar(val label: String, val value: Double, val shown: String)

/** The mockup's bar chart: value over each bar, label under it; heights are value / max, from zero. */
@Composable
private fun Bars(bars: List<Bar>, color: Color) {
    val g = LocalGlass.current
    val top = bars.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: return
    Column {
        Row(Modifier.fillMaxWidth().height(Metrics.chartHeight), horizontalArrangement = Arrangement.spacedBy(Metrics.barGap), verticalAlignment = Alignment.Bottom) {
            bars.forEachIndexed { i, b ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(b.shown, color = g.text, style = Type.style(Type.tiny, FontWeight.Bold), maxLines = 1)
                    Box(
                        Modifier.fillMaxWidth().height(Metrics.chartHeight * (b.value / top).toFloat() * CHART_FILL)
                            .clip(RoundedCornerShape(topStart = Metrics.tiny, topEnd = Metrics.tiny))
                            .background(color.copy(alpha = Metrics.barAlphas.getOrElse(i) { Metrics.barAlphas.last() })),
                    )
                }
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().padding(top = Metrics.tiny), horizontalArrangement = Arrangement.spacedBy(Metrics.barGap)) {
            bars.forEach { b -> Text(b.label, Modifier.weight(1f), color = g.text2, style = Type.style(Type.tiny), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
        }
    }
}

/** The tallest bar leaves room for its value label. */
private const val CHART_FILL = 0.8f

/** The mockup's "Recent Insights" box, from the numbers above. */
@Composable
private fun Insights(lines: List<String>) {
    val g = LocalGlass.current
    Column(
        Modifier.fillMaxWidth().padding(top = Metrics.gap).clip(RoundedCornerShape(Metrics.tileRadius)).background(g.field).padding(Metrics.gap),
        verticalArrangement = Arrangement.spacedBy(Metrics.tiny),
    ) {
        FieldLabel(stringResource(R.string.recent_insights))
        lines.forEach { Text("• $it", color = g.text, style = Type.style(Type.label)) }
    }
}

/** 4.01 % for a rate, 153.5 for an index; null when the series did not answer. */
fun seriesValue(s: Market.Stat): String? = s.latest?.let { p ->
    if (s.unit == "%") String.format(Locale.ROOT, "%.2f %%", p.value) else String.format(Locale.ROOT, "%.1f", p.value)
}

/** +0.30 pp or +0.59 %, against the same period a year before. */
fun seriesChange(s: Market.Stat): String? = s.delta?.let { d ->
    String.format(Locale.ROOT, if (s.change == "pp") "%+.2f pp" else "%+.2f %%", d) + " vs " + s.yearAgo!!.period
}

@Composable
fun FeedPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val g = LocalGlass.current
    val ctx = LocalContext.current
    var feed by remember { mutableStateOf<SearchEngine.FeedResult?>(null) }
    LaunchedEffect(v.id) { feed = withContext(Dispatchers.IO) { runCatching { state.services.engine.feed(v.id) }.getOrNull() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        pageHeader(state, v)
        val f = feed
        if (f == null) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = g.accent, trackColor = g.glass) }
        else {
            item { SourceStrip(f.statuses) }
            if (f.items.isEmpty()) item {
                Text(stringResource(R.string.feed_empty, f.fetched), color = g.text2, style = Type.style(Type.small), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = Metrics.gutter))
            }
            items(f.items, key = { (it.url ?: it.title) + it.feed }) { FeedCard(it) { url -> Browser.open(ctx, url) } }
        }
    }
}

/** The mockup's feed post: the feed as author with its initials, the age, the headline, share and open. */
@Composable
private fun FeedCard(item: FeedItem, open: (String) -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    GlassCard(onClick = item.url?.let { u -> { open(u) } }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Box(Modifier.size(Metrics.avatar).clip(CircleShape).background(g.accent), contentAlignment = Alignment.Center) {
                Text(initials(item.feed), color = g.onBadge, style = Type.style(Type.tiny, FontWeight.Bold))
            }
            Column {
                Text(item.feed, color = g.text, style = Type.style(Type.small, FontWeight.Bold))
                item.date?.let {
                    Text(DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        color = g.text2, style = Type.style(Type.tiny))
                }
            }
        }
        Text(item.title, color = g.text, style = Type.style(Type.body, FontWeight.SemiBold))
        if (item.text.isNotBlank()) Text(item.text, color = g.text2, style = Type.style(Type.small), maxLines = 4, overflow = TextOverflow.Ellipsis)
        item.url?.let { u ->
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gutter)) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.clip(CircleShape).clickable {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${item.title}\n$u"), null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Ph(R.drawable.ph_share_network, Metrics.iconSm, g.text2, stringResource(R.string.share)) }
                Box(Modifier.clip(CircleShape).clickable { open(u) }) { Ph(R.drawable.ph_arrow_square_out, Metrics.iconSm, g.text2, stringResource(R.string.open)) }
            }
        }
    }
}

/** "tagesschau Wirtschaft" -> "TW". */
fun initials(name: String): String = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

/** Every calculator the vertical declares: its fields as typed, its outputs in declared order and tone. */
@Composable
fun CalculatorsPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        pageHeader(state, v)
        items(v.calculators.mapNotNull { state.cfg.calculators[it] }, key = { it.id }) { CalculatorCard(it) }
    }
}

@Composable
private fun CalculatorCard(c: SearchConfig.Calculator) {
    val state = LocalState.current
    val g = LocalGlass.current
    val typed = remember(c.id) { mutableStateMapOf<String, String>().apply { c.fields.forEach { put(it.id, plain(it.default)) } } }
    val values = c.fields.associate { f -> f.id to (num(typed[f.id] ?: "") ?: f.default) }
    val result = remember(values) { runCatching { Calculators.run(state.cfg, c.id, values) }.getOrNull() }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.small)) {
            DeclaredIcon(c.icon, Metrics.iconSm, g.text)
            Text(c.label, color = g.text, style = Type.style(Type.calcTitle, FontWeight.Bold))
        }
        if (c.blurb.isNotBlank()) Text(c.blurb, color = g.text2, style = Type.style(Type.tiny))
        c.fields.forEach { f ->
            when (f.kind) {
                "toggle" -> Row(Modifier.fillMaxWidth().testTag(Tags.field(c.id, f.id)), verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel(f.label, Modifier.weight(1f))
                    Switch(
                        checked = values[f.id] != 0.0, onCheckedChange = { typed[f.id] = if (it) "1" else "0" },
                        colors = SwitchDefaults.colors(checkedTrackColor = g.accent, uncheckedTrackColor = g.field, uncheckedBorderColor = g.tileBorder),
                    )
                }
                "choice" -> Column(Modifier.testTag(Tags.field(c.id, f.id)), verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
                    FieldLabel(f.label)
                    ChipRow {
                        f.options.forEach { (label, value) -> Chip(label, Tags.option("${c.id}_${f.id}_$label"), selected = values[f.id] == value) { typed[f.id] = plain(value) } }
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(Metrics.tiny)) {
                    FieldLabel(f.label)
                    GlassField(typed[f.id] ?: "", { typed[f.id] = it }, "", Tags.field(c.id, f.id), icon = null, number = true, size = Type.body)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(top = Metrics.small).clip(RoundedCornerShape(Metrics.tileRadius)).background(g.field).padding(Metrics.cardPad),
            verticalArrangement = Arrangement.spacedBy(Metrics.tiny),
        ) {
            if (result == null) Text(stringResource(R.string.calc_failed), color = g.negative, style = Type.style(Type.small))
            result?.let { r ->
                c.outputs.forEach { o -> OutputRow(c, o, r.values[o.id]) }
                r.warnings.forEach { w -> Text(c.warnings[w] ?: w, color = g.negative, style = Type.style(Type.label)) }
            }
        }
        c.notes.forEach { n -> Text(n, color = g.text2, style = Type.style(Type.tiny)) }
    }
}

/** A result row in its declared tone: total (bold), minus (a deduction, red), result (green, bold), detail (a sub-line). */
@Composable
private fun OutputRow(c: SearchConfig.Calculator, o: SearchConfig.Output, x: Double?) {
    val g = LocalGlass.current
    val (size, weight, color) = when (o.tone) {
        "total" -> Triple(Type.body, FontWeight.Bold, g.text)
        "minus" -> Triple(Type.label, FontWeight.Normal, g.negative)
        "result" -> Triple(Type.calcTitle, FontWeight.Bold, g.positive)
        "detail" -> Triple(Type.tiny, FontWeight.Normal, g.text2)
        else -> Triple(Type.label, FontWeight.Normal, g.text)
    }
    if (o.tone == "result") Hairline()
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(Tags.output(c.id, o.id))
            .padding(start = if (o.tone == "detail") Metrics.gap else Metrics.zero),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(o.label.trim(), Modifier.weight(1f), color = color, style = Type.style(size, weight))
        Text(x?.let { format(it, o.format) } ?: "—", color = color, style = Type.style(size, weight))
    }
}

/** A declared output format: eur, pct, ratio, or a plain number. */
fun format(v: Double, fmt: String): String = when (fmt) {
    "eur" -> money(v, "EUR")
    "pct" -> String.format(Locale.ROOT, "%.1f %%", v)
    "ratio" -> String.format(Locale.ROOT, "%.1f×", v)
    else -> String.format(Locale.ROOT, "%,.2f", v)
}

/** A default as the user would type it: 30, not 30.0. */
fun plain(d: Double): String = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

/** Saved Items: every starred result, newest first, kept whole so it opens offline. */
@Composable
fun SavedPage() {
    val state = LocalState.current
    val g = LocalGlass.current
    val saved = remember { state.services.saved.all() }
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.SAVED), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item { TopicHeader(stringResource(R.string.saved_items), stringResource(R.string.saved_desc)) }
        if (saved.isEmpty()) item { Text(stringResource(R.string.saved_empty), color = g.text2, style = Type.style(Type.small)) }
        items(saved, key = { it.key }) { ListingCard(it) }
    }
}
