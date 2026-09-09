package app.sterna.ui.snoozed

import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import app.sterna.ui.message.snoozePresets

/**
 * The "Snoozed" screen (Codeberg #82): the messages a snooze currently hides, each with the
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozedScreen(
    onBack: () -> Unit,
    viewModel: SnoozedViewModel = viewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.snoozed_title)) },
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
                Text(
                    stringResource(R.string.snoozed_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(items, key = { it.emailId }) { item ->
                val until = DateUtils.formatDateTime(
                    context,
                    item.until,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.subject.orEmpty().ifBlank { stringResource(R.string.message_no_subject) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.fromName.orEmpty().ifBlank { item.fromEmail.orEmpty() }
                                .ifBlank { stringResource(R.string.message_unknown_sender) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(R.string.snoozed_returns, until),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // Move the deadline: the very same presets the Snooze action offers, headed
                    // by the deadline in force, so the menu says WHEN it currently comes back
                    // instead of listing four mute delays.
                    var presetsOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { presetsOpen = true }) {
                            Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.snoozed_reschedule))
                        }
                        DropdownMenu(
                            expanded = presetsOpen,
                            onDismissRequest = { presetsOpen = false },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            SnoozeDeadlineHeader(until)
                            snoozePresets(context).forEach { (label, at) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        presetsOpen = false
                                        viewModel.reschedule(item.emailId, item.accountId, at)
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.cancel(item.accountId, item.emailId) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.snoozed_cancel))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/**
 * The "snoozed until …" line heading a snooze-preset menu. Informative, not an action: an
 */
@Composable
internal fun SnoozeDeadlineHeader(formattedUntil: String) {
    Text(
        stringResource(R.string.snooze_current, formattedUntil),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
