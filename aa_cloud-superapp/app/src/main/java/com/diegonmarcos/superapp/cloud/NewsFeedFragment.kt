package com.diegonmarcos.superapp.cloud
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.R

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.rss.FeedItem
import com.diegonmarcos.superapp.rss.RemoteFeed
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * News & RSS — each channel is a collapsable card. Header tap toggles
 * the body; on first expand the feed XML is fetched in the background,
 * parsed, and rendered as a tappable item list. Each item tap hands the
 * URL to [ShellActivity.onTileClicked].
 *
 * Tap targets follow the same target-grammar the rest of the app uses, so
 * http(s) URLs automatically route through ShellActivity.launchUri, which
 * renders them in the app's embedded browser ([WebPageFragment], pushed onto
 * the content back stack). Nothing here reaches an external browser.
 *
 * Compose since #773: the fetch is a coroutine tied to the card's composition (it is cancelled
 * when the page goes), and the colours are palette roles, not purple/white literals.
 */
class NewsFeedFragment : KitComposeFragment() {

    /** url → cached items so re-expand doesn't re-fetch. */
    private val cache = ConcurrentHashMap<String, List<FeedItem>>()

    /** What a card's body shows: nothing yet, the spinner, the items, or the failure. */
    private sealed interface Load {
        object Busy : Load
        class Done(val items: List<FeedItem>) : Load
        class Failed(val why: String) : Load
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val feeds = Sections.newsFeeds()
        Column(Modifier.fillMaxWidth().padding(4.dp)) {
            if (feeds.isEmpty()) {
                Hint(stringResource(R.string.news_feeds_empty), Modifier.testTag(TAG_EMPTY))
                return@Column
            }
            for ((cat, list) in feeds.groupBy { it.category.ifBlank { "Other" } }) {
                Text(cat, color = LocalKitPalette.current.accent, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 5.dp)
                        .testTag(catTag(cat)))
                for (feed in list) ChannelCard(feed)
            }
        }
    }

    @Composable
    private fun ChannelCard(feed: Sections.NewsFeed) {
        val p = LocalKitPalette.current
        val appCtx = LocalContext.current.applicationContext
        val act = LocalContext.current as? FragmentActivity
        var open by rememberSaveable(feed.url) { mutableStateOf(false) }
        var load by remember(feed.url) { mutableStateOf<Load?>(null) }
        // First expand fetches (once); a re-expand shows what is there.
        LaunchedEffect(open) {
            if (!open || load != null) return@LaunchedEffect
            cache[feed.url]?.let { load = Load.Done(it); return@LaunchedEffect }
            load = Load.Busy
            load = withContext(Dispatchers.IO) { runCatching { RemoteFeed.fetch(appCtx, feed.url) } }
                .fold({ items -> cache[feed.url] = items; Load.Done(items) },
                      { t -> Load.Failed(t.message ?: t.toString()) })
        }
        val turn by animateFloatAsState(if (open) 90f else 0f, label = "chevron")

        Column(Modifier.fillMaxWidth().padding(4.dp).clip(RoundedCornerShape(12.dp)).background(p.surface)) {
            // Header row — channel name + host + chevron.
            Row(Modifier.fillMaxWidth().clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 10.dp).testTag(headerTag(feed.id)),
                verticalAlignment = Alignment.CenterVertically) {
                Text(feed.name, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f))
                Text(hostOf(feed.url), color = p.textSecondary, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.8f))
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    tint = p.textSecondary, modifier = Modifier.size(20.dp).rotate(turn))
            }
            if (open) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .testTag(bodyTag(feed.id))) {
                    when (val l = load) {
                        null, Load.Busy -> {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), color = p.accent)
                            }
                            Hint("Fetching ${hostOf(feed.url)}…")
                        }
                        is Load.Failed -> Hint("Fetch failed: ${l.why}", Modifier.testTag(failTag(feed.id)))
                        is Load.Done -> if (l.items.isEmpty()) Hint("No items") else
                            for (item in l.items) {
                                Column(Modifier.fillMaxWidth()
                                    .clickable {
                                        // http(s) routes through ShellActivity.launchUri →
                                        // openEmbeddedBrowser → WebPageFragment (the app's own browser).
                                        (act as? TileGridFragment.TileClickListener)?.onTileClicked(item.link)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp)) {
                                    Text(item.title, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
                                    if (item.date.isNotBlank()) {
                                        Text(item.date, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    @Composable
    private fun Hint(text: String, modifier: Modifier = Modifier) {
        Text(text, color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(12.dp).alpha(0.8f))
    }

    private fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host ?: url }.getOrDefault(url)

    companion object {
        const val TAG_EMPTY = "news:empty"
        fun catTag(cat: String) = "news:cat:$cat"
        fun headerTag(id: String) = "news:header:$id"
        fun bodyTag(id: String) = "news:body:$id"
        fun failTag(id: String) = "news:failed:$id"
        fun newInstance() = NewsFeedFragment()
    }
}
