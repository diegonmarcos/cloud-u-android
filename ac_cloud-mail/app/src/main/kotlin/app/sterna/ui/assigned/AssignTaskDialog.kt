package app.sterna.ui.assigned

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.core.data.tasks.MailTask
import app.sterna.ui.theme.MailMetrics
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** A date (UTC midnight millis) as the dialog and the list show it, `yyyy-MM-dd`. */
internal fun taskDateLabel(millis: Long?): String? =
    millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }

/** The localised name of a task status. */
internal fun taskStatusLabel(status: String): Int = when (status) {
    MailTask.STATUS_DOING -> R.string.task_status_doing
    MailTask.STATUS_DONE -> R.string.task_status_done
    MailTask.STATUS_CANCELLED -> R.string.task_status_cancelled
    else -> R.string.task_status_todo
}

private val PRIORITIES = listOf(0 to R.string.task_priority_none, 1 to R.string.task_priority_high, 5 to R.string.task_priority_medium, 9 to R.string.task_priority_low)
private val STATUSES = listOf(MailTask.STATUS_TODO, MailTask.STATUS_DOING, MailTask.STATUS_DONE, MailTask.STATUS_CANCELLED)

/**
 * Attach a task to one mail, or edit the one it has: title, deadline AND target date, notes, links,
 * location, priority, status and people. The link back to the mail is the mail itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignTaskDialog(
    accountId: String,
    emailId: String,
    mailSubject: String,
    messageId: String?,
    existing: MailTask?,
    onDone: (savedToStore: Boolean?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(existing?.title ?: mailSubject) }
    var deadline by remember { mutableStateOf(existing?.deadlineMillis) }
    var target by remember { mutableStateOf(existing?.targetMillis) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    var links by remember { mutableStateOf(existing?.links.orEmpty().joinToString("\n")) }
    var location by remember { mutableStateOf(existing?.location.orEmpty()) }
    var people by remember { mutableStateOf(existing?.people.orEmpty().joinToString(", ")) }
    var priority by remember { mutableStateOf(existing?.priority ?: 0) }
    var status by remember { mutableStateOf(existing?.status ?: MailTask.STATUS_TODO) }
    var picking by remember { mutableStateOf<String?>(null) }

    picking?.let { which ->
        val state = rememberDatePickerState(initialSelectedDateMillis = if (which == "deadline") deadline else target)
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    if (which == "deadline") deadline = state.selectedDateMillis else target = state.selectedDateMillis
                    picking = null
                }) { Text(stringResource(R.string.task_save)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(R.string.task_cancel)) } },
        ) { DatePicker(state = state) }
    }

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.task_assign).trimEnd('…')) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.task_title)) }, modifier = Modifier.fillMaxWidth())
                DateRow(stringResource(R.string.assigned_sort_deadline), deadline, { picking = "deadline" }, { deadline = null })
                DateRow(stringResource(R.string.assigned_sort_target), target, { picking = "target" }, { target = null })
                OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.task_notes)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(links, { links = it }, label = { Text(stringResource(R.string.task_links)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(location, { location = it }, label = { Text(stringResource(R.string.task_location)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(people, { people = it }, label = { Text(stringResource(R.string.task_people)) }, modifier = Modifier.fillMaxWidth())
                Choice(stringResource(R.string.task_priority), stringResource(PRIORITIES.firstOrNull { it.first == priority }?.second ?: R.string.task_priority_none),
                    PRIORITIES.map { (v, l) -> stringResource(l) to { priority = v } })
                Choice(stringResource(R.string.task_status), stringResource(taskStatusLabel(status)),
                    STATUSES.map { s -> stringResource(taskStatusLabel(s)) to { status = s } })
                if (existing != null) {
                    TextButton(onClick = {
                        scope.launch { MailTasks.remove(context, existing); onDone(null) }
                    }) { Text(stringResource(R.string.task_remove), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val task = MailTask(
                    uid = existing?.uid ?: UUID.randomUUID().toString(),
                    title = title.ifBlank { mailSubject },
                    accountId = accountId,
                    emailId = emailId,
                    mailSubject = mailSubject,
                    messageId = messageId,
                    deadlineMillis = deadline,
                    targetMillis = target,
                    notes = notes,
                    links = links.lines().map { it.trim() }.filter { it.isNotEmpty() },
                    location = location.trim(),
                    priority = priority,
                    status = status,
                    people = people.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    collectionId = existing?.collectionId.orEmpty(),
                    createdMillis = existing?.createdMillis ?: System.currentTimeMillis(),
                )
                scope.launch { onDone(MailTasks.save(context, task)) }
            }) { Text(stringResource(R.string.task_save)) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.task_cancel)) } },
    )
}

@Composable
private fun DateRow(label: String, millis: Long?, onPick: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("$label: " + (taskDateLabel(millis) ?: stringResource(R.string.assigned_no_date)), Modifier.weight(1f))
        TextButton(onClick = onPick) { Text(stringResource(R.string.task_pick_date)) }
        if (millis != null) TextButton(onClick = onClear) { Text(stringResource(R.string.task_clear_date)) }
    }
}

@Composable
private fun Choice(label: String, current: String, options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("$label: $current", Modifier.weight(1f))
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.task_pick_date)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((text, pick) in options) DropdownMenuItem(text = { Text(text) }, onClick = { pick(); open = false })
        }
    }
}
