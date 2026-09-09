package app.sterna.ui.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.ui.PaneSplit
import app.sterna.ui.message.MessageScreen

/** The reading pane beside the list (#103), built as the `message/…` destination builds it.
 *  `key(session)` is what makes a tap a new reading; a swipe changes the anchor but not the session.
 *  offscreen layer (#10). */
@Composable
fun ReadingPane(
    state: ReadingPaneState,
    inboxViewModel: InboxViewModel,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
    onComposeTo: (address: String) -> Unit,
) {
    val anchor = state.anchor
    if (anchor == null) {
        EmptyReadingPane()
        return
    }
    key(state.session) {
        val listSource = if (anchor.src == "list") inboxViewModel.pagedEmails else null
        val searchResults = if (anchor.src == "search") {
            remember(inboxViewModel) { inboxViewModel.state.value.searchResults }
        } else {
            null
        }
        val threadKey = if (anchor.src == "thread") anchor.thread?.let { ThreadKey.decode(it) } else null
        val threadEntries = if (threadKey != null) {
            remember(inboxViewModel, threadKey) { inboxViewModel.threadEntries(threadKey) }
        } else {
            null
        }
        MessageScreen(
            anchorEmailId = anchor.emailId,
            anchorAccountId = anchor.accountId,
            initialIndex = anchor.index,
            listSource = listSource,
            searchResults = searchResults,
            threadEntries = threadEntries,
            onBack = { inboxViewModel.closePane() },
            onDelete = { email -> if (inboxViewModel.closePane()) inboxViewModel.delete(email) },
            onArchive = { email -> if (inboxViewModel.closePane()) inboxViewModel.archive(email) },
            onMove = { email, targetMailboxId, targetAccountId ->
                if (inboxViewModel.closePane()) inboxViewModel.moveTo(email, targetMailboxId, targetAccountId)
            },
            onReply = onReply,
            onComposeTo = onComposeTo,
            paneMode = true,
            onPageSettled = inboxViewModel::followPage,
        )
    }
}

/** The reading pane while nothing is open in it: one line of invitation, centred (#103). */
@Composable
fun EmptyReadingPane(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.reading_pane_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/** The list alone, or the list beside a [detail] pane, on the [split] the host decided from the
 * window width. With no split the list is composed unwrapped: a narrow window must not move. */
@Composable
internal fun ListDetailPanes(
    split: PaneSplit?,
    detail: (@Composable () -> Unit)?,
    list: @Composable () -> Unit,
) {
    if (split == null || detail == null) {
        list()
        return
    }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(split.listWidthDp.dp).fillMaxHeight()) { list() }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
    }
}
