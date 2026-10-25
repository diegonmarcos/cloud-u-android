package com.diegonmarcos.cloudsuperintel.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudsuperintel.Feed
import com.diegonmarcos.cloudsuperintel.NewsTopic
import com.diegonmarcos.cloudsuperintel.R
import com.diegonmarcos.cloudsuperintel.SiNews
import com.diegonmarcos.cloudsuperintel.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

object NewsTags {
    const val FEED = "news_feed"
    const val EMPTY = "news_empty"
    const val STORY = "news_story"
    const val FOLLOW_UP = "news_follow_up"
    const val ASK = "news_ask"
    fun card(key: String) = "news_card_$key"
}

private val images = HashMap<String, ImageBitmap?>()

/** A story's picture from its source's own URL (https only); nothing is drawn when it cannot be fetched. */
@Composable
private fun RemoteImage(url: String, modifier: Modifier) {
    if (url.isBlank()) return
    val bmp by produceState<ImageBitmap?>(null, url) {
        value = synchronized(images) { images[url] } ?: withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeStream(URL(url.replaceFirst("http://", "https://")).openStream(), null, o)?.asImageBitmap()
            }.getOrNull()
        }?.also { synchronized(images) { images[url] = it } }
    }
    bmp?.let { Image(it, null, modifier.clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
}

/**
 * One topic: a vertical feed of story cards (image, headline, AI summary, sources count); a tap opens the story page.
 * [onAsk] gets a follow-up question with its story context (the shell drops it into the SI chat box).
 */
@Composable
fun NewsTopicPage(topic: NewsTopic, onAsk: (String) -> Unit) {
    val ctx = LocalContext.current
    var feed by remember(topic.id) { mutableStateOf<Feed?>(null) }
    var open by remember(topic.id) { mutableStateOf<Story?>(null) }
    var reload by remember(topic.id) { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(topic.id, reload) { feed = null; feed = SiNews.feed(ctx, topic, refresh = reload > 0) }
    val story = open
    if (story != null) {
        BackHandler { open = null }
        StoryPage(story, onAsk)
        return
    }
    val f = feed
    when {
        f == null -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) { CircularProgressIndicator() }
        f.stories.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp).testTag(NewsTags.EMPTY), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.news_empty), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.news_empty_why, f.error.orEmpty()), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ reload++ }) { Text(stringResource(R.string.news_retry)) }
        }
        else -> LazyColumn(Modifier.fillMaxSize().testTag(NewsTags.FEED), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { TextButton({ reload++ }) { Text(stringResource(R.string.news_refresh)) } }
            items(f.stories, key = { it.key }) { s -> StoryCard(s) { open = s } }
        }
    }
}

@Composable
private fun StoryCard(s: Story, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val summary by produceState<String?>(null, s.key) { value = SiNews.summary(ctx, s, story = false) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag(NewsTags.card(s.key)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RemoteImage(s.image, Modifier.fillMaxWidth().height(170.dp))
        Text(s.headline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
        summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        Text(stringResource(R.string.news_sources, s.sources, s.articles.first().domain), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** The story: the synthesized summary (from the fetched headlines only), its cited sources, and the follow-up box. */
@Composable
private fun StoryPage(s: Story, onAsk: (String) -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val summary by produceState<String?>(null, s.key) { value = SiNews.summary(ctx, s, story = true) }
    var q by remember(s.key) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().testTag(NewsTags.STORY), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { RemoteImage(s.image, Modifier.fillMaxWidth().height(200.dp)) }
        item { Text(s.headline, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface) }
        item {
            Text(summary ?: stringResource(R.string.news_summarising), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        item { Text(stringResource(R.string.news_cited, s.articles.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        items(s.articles.size) { i ->
            val a = s.articles[i]
            Column(Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(a.url) } }) {
                Text("[${i + 1}] ${a.title}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(a.domain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().testTag(NewsTags.FOLLOW_UP), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f).testTag(NewsTags.ASK), label = { Text(stringResource(R.string.news_follow_up)) })
                Button({
                    if (q.isNotBlank()) {
                        val ctxText = s.articles.take(8).joinToString("\n") { "- ${it.title} (${it.domain}) ${it.url}" }
                        onAsk(ctx.getString(R.string.news_ask_prefix, s.headline, ctxText, q.trim()))
                    }
                }) { Text(stringResource(R.string.news_ask)) }
            }
        }
    }
}
