package app.sterna.ui.assigned

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.R
import app.sterna.core.data.tasks.AssignedSort
import app.sterna.core.data.tasks.MailTask
import app.sterna.core.data.tasks.sortAssigned
import app.sterna.ui.theme.MailMetrics

/**
 * Assigned: every mail that has a task, with its deadline AND target date on the row, sortable by
 * either. A tap opens the mail; the pencil edits the task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignedScreen(onBack: () -> Unit, onOpenEmail: (emailId: String, accountId: String) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MailTasks.load(context) }
    val tasks by MailTasks.tasks.collectAsStateWithLifecycle()
    var sortName by rememberSaveable { mutableStateOf(AssignedSort.DEADLINE.name) }
    val sort = AssignedSort.valueOf(sortName)
    var editing by remember { mutableStateOf<MailTask?>(null) }
    editing?.let { t ->
        AssignTaskDialog(t.accountId, t.emailId, t.mailSubject, t.messageId, t, onDone = { editing = null })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.assigned_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = MailMetrics.s16), horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                for (s in AssignedSort.entries) {
                    FilterChip(
                        selected = s == sort,
                        onClick = { sortName = s.name },
                        label = { Text(stringResource(if (s == AssignedSort.DEADLINE) R.string.assigned_sort_deadline else R.string.assigned_sort_target)) },
                    )
                }
            }
            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(MailMetrics.s24), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.assigned_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sortAssigned(tasks, sort), key = { it.uid }) { t ->
                        AssignedRow(t, onOpen = { onOpenEmail(t.emailId, t.accountId) }, onEdit = { editing = t })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssignedRow(t: MailTask, onOpen: () -> Unit, onEdit: () -> Unit) {
    val none = stringResource(R.string.assigned_no_date)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = MailMetrics.s16, end = MailMetrics.s4, top = MailMetrics.s8, bottom = MailMetrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.mailSubject.isNotBlank() && t.mailSubject != t.title) {
                Text(t.mailSubject, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                stringResource(R.string.assigned_sort_deadline) + ": " + (taskDateLabel(t.deadlineMillis) ?: none) +
                    "   " + stringResource(R.string.assigned_sort_target) + ": " + (taskDateLabel(t.targetMillis) ?: none) +
                    "   " + stringResource(taskStatusLabel(t.status)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.task_assign)) }
    }
}
