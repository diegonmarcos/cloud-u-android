package com.diegonmarcos.cloudkgstore.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudkgstore.KgConfig
import com.diegonmarcos.cloudkgstore.KgPage
import com.diegonmarcos.cloudkgstore.KgServices
import com.diegonmarcos.cloudkgstore.R
import com.diegonmarcos.cloudsearch.core.FeedItem
import com.diegonmarcos.cloudsearch.core.Listing
import com.diegonmarcos.cloudsearch.core.SearchEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset

/** The Web section's pages. The sources, parsers, feeds and cities are Cloud Search's declaration, run by its linked core. */
@Composable
fun WebPage(page: KgPage, svc: KgServices) {
    when (page.kind) {
        KgConfig.KIND_NEWS -> NewsPage(svc)
        KgConfig.KIND_VERTICALS -> VerticalsPage(page, svc)
    }
}

private fun day(ms: Long?): String = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }.orEmpty()

private fun money(l: Listing): String =
    l.price?.let { String.format(java.util.Locale.ROOT, "%.2f", it) + (l.currency?.let { c -> " $c" } ?: "") + (l.unit?.let { u -> "/$u" } ?: "") }.orEmpty()

@Composable
private fun statusLines(statuses: List<SearchEngine.SourceStatus>, svc: KgServices) {
    statuses.forEach { s ->
        val text = when (s.state) {
            SearchEngine.State.OK, SearchEngine.State.CACHED -> stringResource(R.string.web_source_ok, s.label, s.count)
            SearchEngine.State.STALE -> stringResource(R.string.web_source_stale, s.label, s.count, s.detail)
            SearchEngine.State.ERROR -> stringResource(R.string.web_source_error, s.label, s.detail)
            SearchEngine.State.SKIPPED -> stringResource(R.string.web_source_skipped, s.label, s.detail)
            SearchEngine.State.LINK -> stringResource(R.string.web_source_link, s.label)
            SearchEngine.State.DISABLED -> stringResource(R.string.web_source_disabled, s.label, s.detail)
        }
        val link = s.link
        if (link != null) KgRow(text, s.detail, onClick = { svc.open(link) }) else KgNote(text, s.state == SearchEngine.State.ERROR)
    }
}

// ── Real Estate, Jobs, Business, Media, Shopping ──────────────────────────────────────────────────

@Composable
private fun VerticalsPage(page: KgPage, svc: KgServices) {
    val cfg = svc.search
    val verticals = page.verticals.mapNotNull { cfg.vertical(it) }
    if (verticals.isEmpty()) {
        Column(Modifier.fillMaxSize()) { KgNote(stringResource(R.string.web_undeclared), true) }
        return
    }
    val scope = rememberCoroutineScope()
    var vid by rememberSaveable(page.id) { mutableStateOf(verticals.first().id) }
    val v = verticals.firstOrNull { it.id == vid } ?: verticals.first()
    var city by rememberSaveable(page.id) { mutableStateOf(cfg.defaultCity) }
    var q by rememberSaveable(page.id) { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchEngine.QueryResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val usesCity = v.sources.mapNotNull { cfg.sources[it] }.any { it.cityFilter || it.url.contains("{city}") }
    fun go() {
        busy = true
        scope.launch {
            result = withContext(Dispatchers.IO) { runCatching { svc.engine.query(v.id, q, city) }.getOrNull() }
            busy = false
        }
    }
    LaunchedEffect(v.id) { go() }
    Column(Modifier.fillMaxSize()) {
        KgChips(verticals.map { it.id to it.label }, v.id) { vid = it; result = null }
        if (usesCity) KgChips(cfg.cities.map { it.id to it.label }, city) { city = it }
        KgQueryBar(q, { q = it }, v.placeholder.ifBlank { v.title }, busy, ::go)
        KgNote(if (busy) stringResource(R.string.status_busy) else result?.let { stringResource(R.string.web_count, it.listings.size) }.orEmpty())
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(result?.listings.orEmpty(), key = { it.key }) { l ->
                KgRow(
                    l.title, l.subtitle,
                    listOf(money(l), l.location.orEmpty(), day(l.date), l.source).filter { it.isNotBlank() }.joinToString(" · "),
                    onClick = l.url?.let { u -> ({ svc.open(u) }) },
                )
            }
            item { Column { statusLines(result?.statuses.orEmpty(), svc) } }
        }
    }
}

// ── News ──────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun NewsPage(svc: KgServices) {
    val cfg = svc.search
    if (cfg.newsPages.isEmpty()) {
        Column(Modifier.fillMaxSize()) { KgNote(stringResource(R.string.web_undeclared), true) }
        return
    }
    val scope = rememberCoroutineScope()
    var pid by rememberSaveable { mutableStateOf(cfg.newsPages.first().id) }
    val np = cfg.newsPages.firstOrNull { it.id == pid } ?: cfg.newsPages.first()
    var fid by rememberSaveable(np.id) { mutableStateOf(np.feeds.first()) }
    var result by remember { mutableStateOf<SearchEngine.FeedResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun load() {
        busy = true
        scope.launch {
            result = withContext(Dispatchers.IO) { runCatching { svc.engine.feedOf(fid) }.getOrNull() }
            busy = false
        }
    }
    LaunchedEffect(fid) { load() }
    Column(Modifier.fillMaxSize()) {
        KgChips(cfg.newsPages.map { it.id to it.id }, np.id) { pid = it; fid = cfg.newsPage(it)?.feeds?.firstOrNull().orEmpty(); result = null }
        KgChips(np.feeds.map { it to (cfg.feeds[it]?.label ?: it) }, fid) { fid = it; result = null }
        KgNote(np.blurb)
        KgNote(if (busy) stringResource(R.string.status_busy) else result?.let { stringResource(R.string.web_count, it.items.size) }.orEmpty())
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(result?.items.orEmpty()) { n: FeedItem ->
                KgRow(n.title, n.text, listOf(day(n.date), n.feed).filter { it.isNotBlank() }.joinToString(" · "), onClick = n.url?.let { u -> ({ svc.open(u) }) })
            }
            item { Column { statusLines(result?.statuses.orEmpty(), svc) } }
        }
    }
}
