package com.diegonmarcos.cloudsearch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.cloudsearch.core.SearchEngine
import com.diegonmarcos.cloudsearch.core.Templates
import com.diegonmarcos.cloudsearch.data.Browser
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object NewsTags {
    const val STRIP = "news_strip"
    const val STATUS = "news_status"
    fun feed(id: String) = "news_feed_$id"
    fun item(i: Int) = "news_item_$i"
}

object EnginesTags {
    const val QUERY = "engines_query"
    fun engine(id: String) = "engines_engine_$id"
    fun vertical(id: String) = "engines_vertical_$id"
}

/**
 * #1197 News: the fleet news service (ResearchPages.NewsPage), then the declared feed pages (build.json::search.news_pages: YouTube
 * channel feeds, Google News topic feeds, RSS). The second row is libs:ui-nav-bars's; each page is one bounded list.
 */
@Composable
fun NewsSection(state: SearchState) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.testTag(NewsTags.STRIP)) {
            NavBarTopSecondRow(
                pages = NAV.section("news")?.pages.orEmpty(),
                selectedId = state.newsPage,
                onSelect = { state.newsPage = it.id },
                dark = state.dark,
                underTopChrome = false,
                surface = NAV.stripSurface("news", state.dark),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag(Tags.page("news_" + state.newsPage))) {
            val page = state.cfg.newsPage(state.newsPage)
            if (page == null) NewsPage(state) else FeedPage(state, page)
        }
    }
}

/** One declared feed page: a chip per feed, the chosen feed's items newest first, and the feed's own status line (never invented items). */
@Composable
private fun FeedPage(state: SearchState, page: SearchConfig.NewsPage) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val feeds = page.feeds.mapNotNull { state.cfg.feeds[it] }
    val picked = state.feedPicks[page.id]?.takeIf { it in page.feeds } ?: page.feeds.first()
    fun load(id: String) {
        state.feedPicks[page.id] = id
        state.feedResults.remove(id)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { state.services.engine.feedOf(id) }.getOrElse { e ->
                    SearchEngine.FeedResult(emptyList(), 0, listOf(SearchEngine.SourceStatus(id, id, SearchEngine.State.ERROR, 0, e.message ?: e.javaClass.simpleName, null, null)))
                }
            }
            state.feedResults[id] = r
        }
    }
    LaunchedEffect(page.id) { if (state.feedResults[picked] == null) load(picked) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Metrics.gutter, end = Metrics.gutter, top = Metrics.small, bottom = Metrics.contentBottom),
        verticalArrangement = Arrangement.spacedBy(Metrics.small),
    ) {
        // The status is read INSIDE the item, from the state itself: an item that only captured it would not follow the answer.
        item {
            val r = state.feedResults[picked]
            val st = r?.statuses?.firstOrNull()
            val status = when {
                r == null || st == null -> stringResource(R.string.newsfeed_loading)
                st.state == SearchEngine.State.ERROR -> stringResource(R.string.newsfeed_unavailable, st.detail)
                st.state == SearchEngine.State.STALE -> stringResource(R.string.newsfeed_stale, r.items.size, st.detail)
                r.items.isEmpty() -> stringResource(R.string.newsfeed_empty)
                else -> stringResource(R.string.newsfeed_count, r.items.size)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
                Text(page.blurb, color = g.text2, style = Type.style(Type.small))
                ChipRow { feeds.forEach { f -> Chip(f.label, NewsTags.feed(f.id), selected = f.id == picked) { load(f.id) } } }
                Text(status, Modifier.testTag(NewsTags.STATUS), color = g.text2, style = Type.style(Type.tiny))
            }
        }
        items(state.feedResults[picked]?.items.orEmpty().withIndex().toList(), key = { it.index }) { (i, a) ->
            val u = a.url
            GlassCard(Modifier.testTag(NewsTags.item(i)), onClick = if (u == null) null else { { Browser.open(ctx, u) } }) {
                Text(a.title, color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (a.text.isNotBlank() && !a.text.contains(a.title)) Text(a.text, color = g.text2, style = Type.style(Type.small), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(a.feed, a.date?.let { stamp(it) }).joinToString(" · "), color = g.text2, style = Type.style(Type.tiny), maxLines = 1)
            }
        }
    }
}

/**
 * #1197 Search > Engines: the major web and AI search engines (build.json::search.engines_major), each opened in Cloud Browser with the
 * typed query, and the verticals' own searches (Brokers, Shopping, Media): a chip hands the query to that vertical and opens it.
 */
@Composable
fun EnginesPage(state: SearchState) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    val city = state.cfg.city(state.city)
    val searchable = state.cfg.verticals.filter { v -> v.subpages.any { state.cfg.subpage(it)?.kind.let { k -> k == "listing" || k == "things" } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        item { TopicHeader(stringResource(R.string.engines_title), stringResource(R.string.engines_blurb)) }
        item { GlassField(q, { q = it }, stringResource(R.string.engines_placeholder), EnginesTags.QUERY) }
        items(state.cfg.majorEngines, key = { it.id }) { e ->
            GlassCard(Modifier.testTag(EnginesTags.engine(e.id)), onClick = { Browser.open(ctx, Templates.fill(e.url, q.trim(), city)) }) {
                Text(e.label, color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 1)
                Text(stringResource(if (e.kind == "ai") R.string.engines_kind_ai else R.string.engines_kind_web), color = g.text2, style = Type.style(Type.tiny))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
                Text(stringResource(R.string.engines_in), color = g.text2, style = Type.style(Type.label, FontWeight.Bold))
                ChipRow {
                    searchable.forEach { v ->
                        Chip(v.label, EnginesTags.vertical(v.id)) {
                            val text = q.trim()
                            if (v.subpages.any { state.cfg.subpage(it)?.kind == "listing" }) state.listing(v.id).let { it.q = text; it.stale = true }
                            else state.things(v.id).let { it.q = text; it.stale = true }
                            state.open(v.id)
                        }
                    }
                }
            }
        }
    }
}
