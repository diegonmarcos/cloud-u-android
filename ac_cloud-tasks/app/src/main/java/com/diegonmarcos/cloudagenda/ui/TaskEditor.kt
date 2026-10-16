package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudagenda.data.Attachment
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.Task
import com.diegonmarcos.cloudagenda.data.TaskStatus
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object EditorTags {
    const val ROOT = "editor"
    const val NAME = "editor_name"
    const val DETAILS = "editor_details"
    const val DATE_TARGET = "editor_date_target"
    const val DATE_DEADLINE = "editor_date_deadline"
    const val DATE_CLEAR_SUFFIX = "_clear"
    const val LOCATION = "editor_location"
    const val SAVE = "editor_save"
    const val CLOSE = "editor_close"
    const val DELETE = "editor_delete"
    const val DELETE_CONFIRM = "editor_delete_confirm"
    const val MANAGE_TAGS = "editor_manage_tags"
    const val SUBTASK_INPUT = "editor_subtask_input"
    const val SUBTASK_ADD = "editor_subtask_add"
    fun status(s: TaskStatus) = "editor_status_${s.id}"
    fun tag(id: String) = "editor_tag_$id"
    fun add(kind: AttachmentKind) = "editor_add_${kind.id}"
    fun attachment(id: String) = "editor_attachment_$id"
    fun removeAttachment(id: String) = "editor_remove_$id"
    fun subtask(id: String) = "editor_subtask_$id"
}

/** What the editor needs from the app around it: the pickers, and opening a link in the app that owns it. */
interface EditorActions {
    fun pickMail()
    fun pickDocument(kind: AttachmentKind)
    fun open(a: Attachment)
}

/**
 * The task editor: every field of a task, as the owner listed them -- Name, Details, Date Target, Date Deadline,
 * Location, Attachments (Mail / Image / File, links only), Status, Tags -- plus, for a top-level task, its subtasks.
 * A subtask is edited by this same form: it has all the same fields.
 *
 * Edits stay in a draft until Save. Attachments and tags are part of the draft; the pickers add to it through
 * [pending] (a link the picker returned, consumed once).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditor(
    task: Task,
    snapshot: Snapshot,
    actions: EditorActions,
    onSave: (Task) -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onOpenSubtask: (Task, Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    onManageTags: () -> Unit,
    pending: Attachment? = null,
    onPendingConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val p = LocalKitPalette.current
    var draft by remember(task.id) { mutableStateOf(task) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newSub by remember { mutableStateOf("") }
    // A link a picker returned joins the draft once, then the host forgets it.
    LaunchedEffect(pending) {
        if (pending != null) {
            draft = draft.copy(attachments = draft.attachments + pending)
            onPendingConsumed()
        }
    }
    val subtasks = snapshot.subtasksOf(task.id)

    Column(modifier.fillMaxSize().background(p.surface).statusBarsPadding().imePadding().testTag(EditorTags.ROOT)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag(EditorTags.CLOSE)) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = p.textPrimary) }
            Text(
                if (task.parentId == null) "Task" else "Subtask", color = p.textPrimary, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag(EditorTags.DELETE)) { Text("Delete", color = p.bad) }
            Button(onClick = { onSave(draft) }, enabled = draft.name.isNotBlank(), modifier = Modifier.testTag(EditorTags.SAVE).padding(end = 8.dp)) { Text("Save") }
        }
        HorizontalDivider(color = p.hairline)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(EditorTags.NAME))
            OutlinedTextField(draft.details, { draft = draft.copy(details = it) }, label = { Text("Details") }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag(EditorTags.DETAILS))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField("Date target", draft.dateTarget, EditorTags.DATE_TARGET, { draft = draft.copy(dateTarget = it) }, Modifier.weight(1f))
                DateField("Date deadline", draft.dateDeadline, EditorTags.DATE_DEADLINE, { draft = draft.copy(dateDeadline = it) }, Modifier.weight(1f))
            }
            OutlinedTextField(draft.location, { draft = draft.copy(location = it) }, label = { Text("Location") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(EditorTags.LOCATION))

            SectionLabel("Status")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in TaskStatus.entries) {
                    FilterChip(selected = draft.status == s, onClick = { draft = draft.copy(status = s) }, label = { Text(s.label) }, modifier = Modifier.testTag(EditorTags.status(s)))
                }
            }

            SectionLabel("Tags")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in snapshot.tags) {
                    FilterChip(
                        selected = t.id in draft.tagIds,
                        onClick = { draft = draft.copy(tagIds = if (t.id in draft.tagIds) draft.tagIds - t.id else draft.tagIds + t.id) },
                        label = { Text(t.name) }, leadingIcon = { ColorDot(tagColor(t.color), size = 10) },
                        modifier = Modifier.testTag(EditorTags.tag(t.id)),
                    )
                }
                TextButton(onClick = onManageTags, modifier = Modifier.testTag(EditorTags.MANAGE_TAGS)) { Text(if (snapshot.tags.isEmpty()) "Create tags" else "Manage tags") }
            }

            SectionLabel("Attachments (links only)")
            for (kind in AttachmentKind.entries) {
                AttachmentSection(kind, draft.attachments.filter { it.kind == kind }, actions,
                    onRemove = { a -> draft = draft.copy(attachments = draft.attachments.filterNot { it.id == a.id }) })
            }

            if (task.parentId == null) {
                SectionLabel("Subtasks")
                for (s in subtasks) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenSubtask(draft, s) }.padding(vertical = 8.dp).testTag(EditorTags.subtask(s.id)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.name, color = p.textPrimary, modifier = Modifier.weight(1f))
                        Text(s.status.label, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newSub, { newSub = it }, label = { Text("New subtask") }, singleLine = true, modifier = Modifier.weight(1f).testTag(EditorTags.SUBTASK_INPUT))
                    TextButton(
                        onClick = { onAddSubtask(newSub.trim()); newSub = "" }, enabled = newSub.isNotBlank(),
                        modifier = Modifier.testTag(EditorTags.SUBTASK_ADD),
                    ) { Text("Add") }
                }
            }
        }
    }

    if (confirmDelete) {
        KitConfirmDialog(
            title = "Delete ${if (task.parentId == null) "task" else "subtask"}?",
            text = if (task.parentId == null && subtasks.isNotEmpty()) "\"${task.name}\" and its ${subtasks.size} subtasks will be deleted." else "\"${task.name}\" will be deleted.",
            confirmLabel = "Delete", dismissLabel = "Cancel",
            onConfirm = { confirmDelete = false; onDelete() }, onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun AttachmentSection(kind: AttachmentKind, items: List<Attachment>, actions: EditorActions, onRemove: (Attachment) -> Unit) {
    val p = LocalKitPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kind.label, color = p.textPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { if (kind == AttachmentKind.MAIL) actions.pickMail() else actions.pickDocument(kind) },
                modifier = Modifier.testTag(EditorTags.add(kind)),
            ) { Text(if (kind == AttachmentKind.MAIL) "Pick from Cloud Mail" else "Pick from Cloud Drive") }
        }
        for (a in items) {
            Row(Modifier.fillMaxWidth().testTag(EditorTags.attachment(a.id)), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.label.ifBlank { a.uri }, color = p.accent, maxLines = 1,
                    modifier = Modifier.weight(1f).clickable(role = Role.Button) { actions.open(a) }.padding(vertical = 8.dp),
                )
                IconButton(onClick = { onRemove(a) }, modifier = Modifier.testTag(EditorTags.removeAttachment(a.id))) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove link", tint = p.textSecondary)
                }
            }
        }
    }
}

/** A date field: shows the value (or "Not set"), a tap opens the picker, the cross clears it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, value: String?, tag: String, onChange: (String?) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    var picking by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f).testTag(tag)) { Text(value ?: "Not set", maxLines = 1) }
            if (value != null) {
                IconButton(onClick = { onChange(null) }, modifier = Modifier.testTag(tag + EditorTags.DATE_CLEAR_SUFFIX)) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear $label", tint = p.textSecondary)
                }
            }
        }
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value?.let { runCatching { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() })
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
