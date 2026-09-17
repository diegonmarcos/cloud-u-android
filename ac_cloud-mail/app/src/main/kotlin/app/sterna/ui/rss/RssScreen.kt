package app.sterna.ui.rss

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.ui.browser.MiniBrowserActivity
import app.sterna.ui.components.EmptyArt
import app.sterna.ui.components.EmptyState
import app.sterna.ui.settings.DetailScaffold
import java.net.URI

/**
 * The News screen (#465): subscribe to feed addresses, see each one's latest articles, open an
 * article in the app's own mini-browser, or remove a subscription. It must SAY what is wrong — no
 * subscriptions yet, a fetch that failed, a host that could not be reached, an address that is not
 * a feed, a feed that is empty — so an empty list is never drawn as a box with nothing in it.
 */
@Composable
fun RssScreen(
    onBack: () -> Unit,
    viewModel: RssViewModel = viewModel(),
) {
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Load the current subscriptions on entry, and again if the set changes on disk.
    LaunchedEffect(subscriptions) { viewModel.refresh() }

    var address by remember { mutableStateOf("") }

    DetailScaffold(title = stringResource(R.string.rss_title), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // The subscribe row: an address field and an Add button beside it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.rss_add_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        viewModel.subscribe(address)
                        address = ""
                    },
                    enabled = address.isNotBlank(),
                ) {
                    Text(stringResource(R.string.rss_add))
                }
            }

            if (subscriptions.isEmpty() && feeds.isEmpty()) {
                EmptyState(
                    art = EmptyArt.FOLDER,
                    title = stringResource(R.string.rss_no_subscriptions_title),
                    body = stringResource(R.string.rss_no_subscriptions_body),
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(feeds, key = { it.url }) { ui ->
                    when (ui) {
                        is RssFeedUi.Fetched -> FeedSection(
                            feed = ui.feed,
                            url = ui.url,
                            onOpen = { articleUrl ->
                                openInMiniBrowser(context, resolveArticleUrl(ui.url, articleUrl))
                            },
                            onRemove = { viewModel.unsubscribe(ui.url) },
                        )
                        is RssFeedUi.Unavailable -> FeedUnavailableRow(
                            url = ui.url,
                            message = when (ui.messageKey) {
                                RssMessage.FETCH_FAILED -> stringResource(R.string.rss_fetch_failed)
                                RssMessage.HOST_UNREACHABLE -> stringResource(R.string.rss_host_unreachable)
                                RssMessage.NOT_A_FEED -> stringResource(R.string.rss_not_a_feed)
                                RssMessage.EMPTY -> stringResource(R.string.rss_empty)
                            },
                            onRemove = { viewModel.unsubscribe(ui.url) },
                        )
                    }
                }
            }
        }
    }
}

/** One subscribed feed that loaded: its title and articles. */
@Composable
private fun FeedSection(
    feed: app.sterna.core.data.rss.RssFeed,
    url: String,
    onOpen: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.RssFeed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                feed.title.ifBlank { url },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.rss_unsubscribe))
            }
        }
        feed.items.forEach { item ->
            ArticleRow(
                title = item.title.ifBlank { stringResource(R.string.message_no_subject) },
                summary = item.summary,
                published = item.published,
                onClick = { if (item.link.isNotBlank()) onOpen(item.link) },
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** One article: title over a short summary, tappable to open. */
@Composable
private fun ArticleRow(title: String, summary: String, published: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (published.isNotBlank()) {
                Text(
                    published,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A subscription that could not be read, with the specific sentence and a remove action. */
@Composable
private fun FeedUnavailableRow(url: String, message: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(url, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.rss_unsubscribe))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Open [uri] in the app's own browser; if MiniBrowserActivity says it could not, say so. */
private fun openInMiniBrowser(context: android.content.Context, uri: String?) {
    if (uri.isNullOrBlank()) return
    val opened = MiniBrowserActivity.openMiniBrowser(context, Uri.parse(uri))
    if (!opened) {
        android.widget.Toast.makeText(context, R.string.rss_open_failed, android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** A feed item's link may be relative; resolve it against the feed's own address so it opens where
 *  the author meant, keeping the app's browser inside the article's site. Only http(s) survives;
 *  a feed pointing at any other scheme (mailto:, tel:, intent:…) is left unopened rather than
 *  handed to a browser that cannot render it. */
internal fun resolveArticleUrl(feedUrl: String, link: String): String? = runCatching {
    val base = URI(feedUrl)
    if (inputIsHttp(link)) {
        link
    } else {
        val resolved = base.resolve(link)
        if (inputIsHttp(resolved.toString())) resolved.toString() else null
    }
}.getOrNull()

/** Whether [s] names an http(s) resource — the only web pages this browser renders. */
private fun inputIsHttp(s: String): Boolean =
    s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)