package com.diegonmarcos.cloudstore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.appstore.FeedViewer
import com.diegonmarcos.superapp.appstore.StoreDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Store > Feed: the repo's Commits and CI-CD feeds, one page with the declared feeds as its top
 * tabs (build.json::ui feed.pages, drawn by MainActivity's PageTabs; [feedId] is the open one).
 * It was two chips on the Cloud page, drawn with Views; it is Compose now and reads the same
 * [FeedViewer] declaration and loader, so the proxy-first read, the rate-limit wording and the
 * neutral colour of an unfinished run are unchanged. A failed read says why and is never an empty
 * list: "nothing in this feed" is reserved for a 2xx that carried none.
 */
@Composable
fun FeedPage(feedId: String) {
    val ctx = LocalContext.current
    val feeds = remember { FeedViewer.feeds(ctx) }
    val feed = feeds.firstOrNull { it.id == feedId } ?: feeds.firstOrNull()
    val pad = StoreDensity.dpValue(StoreDensity.S12).dp
    if (feed == null) {
        Text("No feed is declared.", Modifier.padding(pad), color = DIM, fontSize = StoreDensity.T_META.sp)
        return
    }
    var loaded by remember(feed.id) { mutableStateOf<Loaded?>(null) }
    LaunchedEffect(feed.id) {
        loaded = withContext(Dispatchers.IO) {
            runCatching { FeedViewer.load(feed) }
                .fold({ Loaded(it, null) }, { Loaded(emptyList(), FeedViewer.explain(it)) })
        }
    }
    val open = remember { FeedViewer.opener(ctx) }
    val gap = StoreDensity.dpValue(StoreDensity.S2).dp
    LazyColumn(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(gap)) {
        item { Text(feed.blurb, color = DIM, fontSize = StoreDensity.T_CAPTION.sp) }
        val l = loaded
        when {
            l == null -> item { Text("loading…", color = DIM, fontSize = StoreDensity.T_CAPTION.sp) }
            l.error != null -> item { Text(l.error, color = DIM, fontSize = StoreDensity.T_CAPTION.sp) }
            l.entries.isEmpty() -> item { Text("Nothing in this feed.", color = DIM, fontSize = StoreDensity.T_CAPTION.sp) }
            else -> items(l.entries) { e -> FeedRow(feed, e, open) }
        }
    }
}

private class Loaded(val entries: List<FeedViewer.Entry>, val error: String?)

@Composable
private fun FeedRow(feed: FeedViewer.Feed, e: FeedViewer.Entry, open: (String) -> Unit) {
    // A value in NEITHER list is neutral: an in-flight run is not a failure.
    val state = when (e.state) { in feed.ok -> OK; in feed.bad -> BAD; else -> DIM }
    val h = StoreDensity.dpValue(StoreDensity.S12).dp
    val v = StoreDensity.dpValue(StoreDensity.S6).dp
    val tap = if (e.link.isNotEmpty()) Modifier.clickable { open(e.link) } else Modifier
    Column(Modifier.fillMaxWidth().background(CARD).then(tap).padding(h, v)) {
        Row {
            // 7 characters is a readable sha and a short run number alike.
            Text(e.ref.take(7), color = state, fontFamily = FontFamily.Monospace, fontSize = StoreDensity.T_CAPTION.sp,
                modifier = Modifier.padding(end = StoreDensity.dpValue(StoreDensity.S8).dp))
            // Only the FIRST line of a commit message: the body belongs on the page the row opens.
            Text(e.title.lineSequence().firstOrNull().orEmpty(), Modifier.weight(1f), color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = StoreDensity.T_BODY.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (e.subtitle.isNotEmpty()) Text(e.subtitle, color = DIM, fontSize = StoreDensity.T_CAPTION.sp)
    }
}

private val OK = Color(0xFF48BB78)
private val BAD = Color(0xFFF56565)
private val DIM = Color(0x99FFFFFF)
private val CARD = Color(0xFF1C1C24)
