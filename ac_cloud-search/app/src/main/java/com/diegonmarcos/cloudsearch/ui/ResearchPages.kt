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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.cloudsearch.R
import com.diegonmarcos.cloudsearch.core.ResearchConfig
import com.diegonmarcos.cloudsearch.data.Browser
import com.diegonmarcos.cloudsearch.data.FleetCalls
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ResearchTags {
    const val STRIP = "research_strip"
    const val STATUS = "research_status"
    fun topic(id: String) = "research_topic_$id"
    fun article(i: Int) = "research_article_$i"
}

/**
 * #1184 Research: News (the fleet news service, build.json::search.research) and Reports (what agents published; the Agents
 * pages are its third page). The second row is libs:ui-nav-bars's; each page below it is one bounded list, so the
 * shared scroll-collapse sees a single scroller and nothing scrolls inside another scroller of the same axis.
 */
@Composable
fun ResearchSection(state: SearchState) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.testTag(ResearchTags.STRIP)) {
            NavBarTopSecondRow(
                pages = NAV.section("research")?.pages.orEmpty(),
                selectedId = state.researchPage,
                onSelect = { state.researchPage = it.id },
                dark = state.dark,
                underTopChrome = false,
                surface = NAV.stripSurface("research", state.dark),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag(Tags.page("research_" + state.researchPage))) {
            when (state.researchPage) {
                "reports" -> ReportsSection(state)
                "agenthub" -> AgentsSection(state)
                else -> NewsPage(state)
            }
        }
    }
}

@Composable
private fun NewsPage(state: SearchState) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val cfg = state.cfg.research
    if (cfg == null) {
        Text(stringResource(R.string.research_none), Modifier.padding(Metrics.gutter), color = g.text2)
        return
    }
    val topic = cfg.topics.firstOrNull { it.id == state.newsTopic } ?: cfg.topics.first()
    val scope = rememberCoroutineScope()
    // A topic is fetched when it is picked (and once when the page first opens); an answer for a topic no longer picked is dropped.
    fun load(t: ResearchConfig.Topic) {
        state.newsTopic = t.id
        state.newsResult = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { state.services.fleet.news(cfg, t) }
            if (state.newsTopic == t.id) state.newsResult = r
        }
    }
    LaunchedEffect(Unit) { if (state.newsResult == null) load(topic) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Metrics.gutter, end = Metrics.gutter, top = Metrics.small, bottom = Metrics.contentBottom),
        verticalArrangement = Arrangement.spacedBy(Metrics.small),
    ) {
        // The status is read INSIDE the item, from the state itself: an item that only captured it would not follow the answer.
        item {
            val r = state.newsResult
            val status = when {
                r == null -> stringResource(R.string.research_loading)
                r.kind == FleetCalls.Kind.OK -> stringResource(R.string.research_count, r.items.size, r.via)
                r.kind == FleetCalls.Kind.EMPTY -> stringResource(R.string.research_empty)
                r.kind == FleetCalls.Kind.NOTE -> stringResource(R.string.research_note, r.detail)
                else -> stringResource(R.string.research_unavailable, r.detail)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.small)) {
                ChipRow { cfg.topics.forEach { t -> Chip(t.label, ResearchTags.topic(t.id), selected = t.id == topic.id) { load(t) } } }
                Text(status, Modifier.testTag(ResearchTags.STATUS), color = g.text2, style = Type.style(Type.tiny))
            }
        }
        items(state.newsResult?.items.orEmpty().withIndex().toList(), key = { it.value.url }) { (i, a) ->
            GlassCard(Modifier.testTag(ResearchTags.article(i)), onClick = { Browser.open(ctx, a.url) }) {
                Text(a.title, color = g.text, style = Type.style(Type.body, FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(a.domain + " · " + a.seen, color = g.text2, style = Type.style(Type.tiny), maxLines = 1)
            }
        }
    }
}
