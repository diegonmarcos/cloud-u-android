package app.sterna.ui.outbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import app.sterna.R
import app.sterna.core.data.db.OutboxLogic
import app.sterna.ui.components.EmptyArt
import app.sterna.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboxScreen(
    onBack: () -> Unit,
    /** Open the composer on the row just taken — with its id, which the route keeps across a process death. */
    onEditDraft: (Long) -> Unit,
    viewModel: OutboxViewModel = viewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val readyToEdit by viewModel.readyToEdit.collectAsStateWithLifecycle()

    // Id of the item whose Delete button was tapped, awaiting confirmation. A queued message has
    // never reached the server, so deleting it destroys the only copy along with its attachments —
    var pendingDelete by remember { mutableStateOf<Long?>(null) }

    pendingDelete?.let { id ->
        // Which body this row is owed, from the list ALREADY collected above — no second read of
        // the repository, no flow of its own. An INTERRUPTED row may already be at the recipient,
        val pendingState = items.firstOrNull { it.id == id }?.state
        val deleteBody = pendingState?.let(::outboxDeleteBody) ?: R.string.outbox_delete_body
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.outbox_delete_title)) },
            text = { Text(stringResource(deleteBody)) },
            confirmButton = {
                // Named for what it does, not for the button that opened it: this is the one action
                // that destroys the only copy of the message (#70).
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(id)
                }) {
                    Text(
                        stringResource(R.string.outbox_delete_permanently),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.inbox_cancel)) }
            },
        )
    }

    // Once an item is staged for editing, open compose with the restored draft — and with the
    // row's id in the route, so a composer rebuilt after a process death can take it back.
    LaunchedEffect(readyToEdit) {
        readyToEdit?.let { id ->
            viewModel.consumeEdit()
            onEditDraft(id)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.outbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    art = EmptyArt.OFFLINE,
                    title = stringResource(R.string.outbox_empty),
                    body = stringResource(R.string.outbox_empty_body),
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(items, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        item.subject.ifBlank { stringResource(R.string.message_no_subject) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.recipients.ifBlank { stringResource(R.string.message_unknown_sender) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // What the row says, and in which colour, is decided in OutboxRowText and
                    // only EXECUTED here. A `when (item.state)` written back into this composable
                    Text(
                        if (outboxStateShowsReason(item.state)) {
                            stringResource(
                                outboxStateLabel(item.state),
                                // The reason is decided there too: a sentinel stored on the row is
                                // translated here, the server's words are printed as they came.
                                when (val reason = outboxFailureReason(item.lastError)) {
                                    is OutboxFailureReason.Words -> stringResource(reason.id)
                                    is OutboxFailureReason.Text -> reason.text
                                },
                            )
                        } else {
                            stringResource(outboxStateLabel(item.state))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (outboxStateIsError(item.state)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    // A row that is still waiting sometimes knows WHY — the server's refusal on one
                    // of the first attempts, or an arming that never happened — and that reason was
                    if (outboxShowsWaitingReason(item.state, item.lastError)) {
                        Text(
                            stringResource(
                                outboxWaitingReasonLabel(),
                                when (val reason = outboxFailureReason(item.lastError)) {
                                    is OutboxFailureReason.Words -> stringResource(reason.id)
                                    is OutboxFailureReason.Text -> reason.text
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { viewModel.retry(item.id) }) {
                            Text(stringResource(R.string.outbox_retry))
                        }
                        // Edit is offered only for a genuinely waiting row the composer could
                        // rebuild (#70): a row whose entity nothing can produce again (a read
                        val unreplayable =
                            OutboxLogic.isUnreplayablePrebuiltEntity(item.pgpMode, item.pgpEntityPath)
                        if (OutboxLogic.canEdit(item.pgpMode, item.state, unreplayable)) {
                            TextButton(onClick = { viewModel.edit(item.id) }) {
                                Text(stringResource(R.string.outbox_edit))
                            }
                        }
                        TextButton(onClick = { pendingDelete = item.id }) {
                            Text(
                                stringResource(R.string.outbox_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
