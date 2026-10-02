@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.Analysis
import com.diegonmarcos.cloudsearch.core.Calculators
import com.diegonmarcos.cloudsearch.core.FeedItem
import com.diegonmarcos.cloudsearch.core.Listing
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.SearchEngine
import com.diegonmarcos.cloudsearch.data.Browser
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitEmptyState
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
    return when (currency) { null -> n; "EUR" -> "$n €"; else -> "$n $currency" }
}

fun day(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.UK).format(Date(ms))

@Composable
fun ListingPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val m = state.listing(v.id)
    val scope = rememberCoroutineScope()
    LaunchedEffect(v.id, state.city) { if (m.stale) m.run(state, scope, v.id) }
    val f = state.filtersOf(v.id)
    val shown = m.result?.let { f.apply(it.listings, v, state.cfg.recentDays, System.currentTimeMillis()) }.orEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Metrics.gutter), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item { Header(v) }
        item {
            OutlinedTextField(
                value = m.q, onValueChange = { m.q = it },
                modifier = Modifier.fillMaxWidth().testTag(Tags.SEARCH_BOX),
                placeholder = { Text(v.placeholder) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(Metrics.corner),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { m.run(state, scope, v.id) }),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                item {
                    AssistChip(
                        onClick = { state.sheet = Sheet.FILTERS },
                        label = { Text(stringResource(R.string.more_filters)) },
                        leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                        modifier = Modifier.testTag(Tags.MORE_FILTERS),
                    )
                }
                item { FilterChip(selected = f.recent, onClick = { state.filters[v.id] = f.copy(recent = !f.recent) }, label = { Text(stringResource(R.string.recent)) }) }
                item { FilterChip(selected = f.verified, onClick = { state.filters[v.id] = f.copy(verified = !f.verified) }, label = { Text(stringResource(R.string.verified)) }) }
                items(v.chips, key = { it.id }) { c ->
                    FilterChip(
                        selected = c.id in f.chips,
                        onClick = { state.filters[v.id] = f.copy(chips = if (c.id in f.chips) f.chips - c.id else f.chips + c.id) },
                        label = { Text(c.label) },
                    )
                }
            }
        }
        if (m.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        m.result?.let { r -> item { SourceStrip(r.statuses) } }
        if (!m.loading && m.result != null && shown.isEmpty()) item {
            Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Metrics.gutter))
        }
        items(shown, key = { it.key }) { ListingCard(it) }
    }
}

@Composable
private fun Header(v: SearchConfig.Vertical) {
    Column {
        Text(v.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (v.blurb.isNotBlank()) Text(v.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Where every result came from, or why a source gave none: read, cached, stale (offline, last
 * answer), failed, waiting for a term; links open the site's own search; disabled ones say why.
 */
@Composable
fun SourceStrip(statuses: List<SearchEngine.SourceStatus>) {
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
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(Tags.source(s.id)))
        }
        val links = statuses.filter { it.state == SearchEngine.State.LINK }
        if (links.isNotEmpty()) {
            Text(stringResource(R.string.open_on), style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                items(links, key = { it.id }) { s ->
                    AssistChip(
                        onClick = { s.link?.let { Browser.open(ctx, it) } },
                        label = { Text(s.label) },
                        trailingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                        modifier = Modifier.testTag(Tags.source(s.id)),
                    )
                }
            }
        }
        statuses.filter { it.state == SearchEngine.State.DISABLED }.forEach { s ->
            Text(stringResource(R.string.src_disabled, s.label, s.detail), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(Tags.source(s.id)))
        }
    }
}

@Composable
fun ListingCard(l: Listing) {
    val state = LocalState.current
    val ctx = LocalContext.current
    var saved by remember(l.key) { mutableStateOf(state.services.saved.isSaved(l.key)) }
    Card(
        Modifier.fillMaxWidth().testTag(Tags.card(l.key)).clickable(enabled = l.url != null) { l.url?.let { Browser.open(ctx, it) } },
        shape = RoundedCornerShape(Metrics.corner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(Metrics.gutter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gutter)) {
            Box(
                Modifier.size(Metrics.thumb).clip(RoundedCornerShape(Metrics.gap)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val v = state.cfg.verticals.firstOrNull { l.source in it.sources }
                Icon(IconCatalog.vector(v?.icon ?: ""), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
                Text(l.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (l.subtitle.isNotBlank()) Text(l.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    state.cfg.sources[l.source]?.label,
                    l.date?.let { day(it) },
                    l.tags.firstOrNull(),
                ).joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.small)) {
                    if (l.verified) Icon(Icons.Filled.Verified, contentDescription = stringResource(R.string.verified), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Metrics.bar))
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                l.price?.let { p ->
                    val unit = when (l.unit) { null -> ""; "h" -> stringResource(R.string.per_hour); "yr" -> stringResource(R.string.per_year); else -> "/" + l.unit }
                    Text(money(p, l.currency) + unit, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { saved = state.services.saved.toggle(l) }) {
                    Icon(if (saved) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(if (saved) R.string.unsave else R.string.save))
                }
            }
        }
    }
}

/** Market numbers computed from the live sources; a figure no source carries is shown as missing, never invented. */
@Composable
fun AnalysisPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    var result by remember { mutableStateOf<Analysis.Result?>(null) }
    var loading by remember { mutableStateOf(true) }
    val q = state.listing(v.id).q
    LaunchedEffect(v.id, state.city) {
        loading = true
        result = withContext(Dispatchers.IO) { runCatching { state.services.engine.analysis(v.id, q, state.city) }.getOrNull() }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Metrics.gutter), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item { Header(v) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                items(state.cfg.cities, key = { it.id }) { c -> FilterChip(selected = c.id == state.city, onClick = { state.pickCity(c.id) }, label = { Text(c.label) }) }
            }
        }
        if (v.analysis == "none") {
            item {
                Box(Modifier.fillMaxWidth().height(Metrics.chartHeight * 2)) {
                    KitEmptyState(stringResource(R.string.analysis_none_title), stringResource(R.string.analysis_none))
                }
            }
            item { SourceStrip(state.services.engine.unfetched(v.id, q, state.city)) }
        } else {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            result?.let { a ->
                item {
                    KitCard {
                        Text(stringResource(R.string.market_overview, state.cfg.city(state.city).label), style = MaterialTheme.typography.titleMedium)
                        Stat(stringResource(R.string.stat_total), a.total?.let { String.format(Locale.ROOT, "%,d", it) })
                        Stat(stringResource(R.string.stat_remote, a.remoteSample), a.remoteShare?.let { String.format(Locale.ROOT, "%.0f %%", it * 100) })
                        Stat(stringResource(R.string.stat_hourly, a.hourlySample), a.medianHourly?.let { money(it, "EUR") })
                        Stat(stringResource(R.string.stat_yearly, a.yearlySample), a.medianYearly?.let { money(it, "EUR") })
                        Stat(stringResource(R.string.stat_time_to_hire), null)
                    }
                }
                if (a.fields.isNotEmpty()) item {
                    KitCard {
                        Text(stringResource(R.string.top_fields), style = MaterialTheme.typography.titleMedium)
                        val top = a.fields.first().value.coerceAtLeast(1)
                        a.fields.forEach { b ->
                            Text(b.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                                Box(Modifier.weight(1f).height(Metrics.bar)) {
                                    Box(Modifier.fillMaxWidth(b.value.toFloat() / top).height(Metrics.bar).clip(RoundedCornerShape(Metrics.small)).background(MaterialTheme.colorScheme.primary))
                                }
                                Text(b.value.toString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                item { Text(stringResource(R.string.analysis_sources), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (!loading && result == null) item { Text(stringResource(R.string.analysis_failed), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: stringResource(R.string.no_source), style = MaterialTheme.typography.titleSmall,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun FeedPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val ctx = LocalContext.current
    var feed by remember { mutableStateOf<SearchEngine.FeedResult?>(null) }
    LaunchedEffect(v.id) { feed = withContext(Dispatchers.IO) { runCatching { state.services.engine.feed(v.id) }.getOrNull() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Metrics.gutter), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item { Header(v) }
        val f = feed
        if (f == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else {
            item { SourceStrip(f.statuses) }
            if (f.items.isEmpty()) item { Text(stringResource(R.string.feed_empty, f.fetched), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(f.items, key = { (it.url ?: it.title) + it.feed }) { FeedCard(it) { url -> Browser.open(ctx, url) } }
        }
    }
}

@Composable
private fun FeedCard(item: FeedItem, open: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = item.url != null) { item.url?.let(open) },
        shape = RoundedCornerShape(Metrics.corner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(Metrics.gutter), verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
            Text(listOfNotNull(item.feed, item.date?.let { day(it) }).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.title, style = MaterialTheme.typography.titleSmall)
            if (item.text.isNotBlank()) Text(item.text, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Every calculator the vertical declares: its fields as typed, its outputs in declared order. */
@Composable
fun CalculatorsPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Metrics.gutter), verticalArrangement = Arrangement.spacedBy(Metrics.gutter)) {
        item { Header(v) }
        items(v.calculators.mapNotNull { state.cfg.calculators[it] }, key = { it.id }) { CalculatorCard(it) }
    }
}

@Composable
private fun CalculatorCard(c: SearchConfig.Calculator) {
    val state = LocalState.current
    val typed = remember(c.id) { mutableStateMapOf<String, String>().apply { c.fields.forEach { put(it.id, plain(it.default)) } } }
    val values = c.fields.associate { f -> f.id to (num(typed[f.id] ?: "") ?: f.default) }
    val result = remember(values) { runCatching { Calculators.run(state.cfg, c.id, values) }.getOrNull() }
    KitCard {
        Text(c.label, style = MaterialTheme.typography.titleLarge)
        if (c.blurb.isNotBlank()) Text(c.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        c.fields.forEach { f ->
            when (f.kind) {
                "toggle" -> Row(Modifier.fillMaxWidth().testTag(Tags.field(c.id, f.id)), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = values[f.id] != 0.0, onCheckedChange = { typed[f.id] = if (it) "1" else "0" })
                }
                "choice" -> Column(Modifier.testTag(Tags.field(c.id, f.id))) {
                    Text(f.label, style = MaterialTheme.typography.bodyMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        items(f.options, key = { it.first }) { (label, value) ->
                            FilterChip(selected = values[f.id] == value, onClick = { typed[f.id] = plain(value) }, label = { Text(label) })
                        }
                    }
                }
                else -> OutlinedTextField(
                    value = typed[f.id] ?: "", onValueChange = { typed[f.id] = it },
                    modifier = Modifier.fillMaxWidth().testTag(Tags.field(c.id, f.id)),
                    label = { Text(f.label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        }
        if (result == null) Text(stringResource(R.string.calc_failed), color = MaterialTheme.colorScheme.error)
        result?.let { r ->
            c.outputs.forEach { o ->
                val x = r.values[o.id]
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(Tags.output(c.id, o.id)), verticalAlignment = Alignment.CenterVertically) {
                    Text(o.label, Modifier.weight(1f), style = if (o.emphasis) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                    Text(x?.let { format(it, o.format) } ?: "—", style = if (o.emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (o.emphasis) FontWeight.Bold else FontWeight.Normal,
                        color = if (o.emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            r.warnings.forEach { w -> Text(c.warnings[w] ?: w, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        c.notes.forEach { n -> Text(n, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

@Composable
fun SavedPage(v: SearchConfig.Vertical) {
    val state = LocalState.current
    val saved = remember(v.id) { state.services.saved.all().filter { it.source in v.sources } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Metrics.gutter), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item { Header(v) }
        if (saved.isEmpty()) item { Text(stringResource(R.string.saved_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(saved, key = { it.key }) { ListingCard(it) }
    }
}
