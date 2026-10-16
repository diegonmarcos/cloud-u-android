package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.cloudagenda.agent.MailItem
import com.diegonmarcos.cloudagenda.agent.MailSource
import com.diegonmarcos.cloudagenda.data.Attachment
import com.diegonmarcos.cloudagenda.data.AttachmentKind
import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.Task
import com.diegonmarcos.cloudagenda.data.TaskFiltering
import com.diegonmarcos.cloudagenda.data.TaskNode
import com.diegonmarcos.cloudagenda.data.TaskStatus
import com.diegonmarcos.cloudagenda.links.newAttachment
import com.diegonmarcos.superapp.uikit.KitConfirmDialog
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.LocalKitPalette

object TasksTags {
    const val PAGE = "tasks_page"
    const val HEADER = "tasks_header"
    const val LIST = "tasks_list"
    const val EMPTY = "tasks_empty"
    const val NO_MATCH = "tasks_no_match"
    const val ADD_INPUT = "add_task_input"
    const val ADD = "add_task"
    fun row(id: String) = "task_row_$id"
    fun check(id: String) = "task_check_$id"
    fun sub(id: String) = "subtask_row_$id"
}

/** What the page needs from the app around it, beyond the controller: the mail source and the system pickers. */
interface TasksHost {
    val mail: MailSource
    fun pickDocument(kind: AttachmentKind)
    fun open(a: Attachment)
}

private val StringSetSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * The Tasks page. Supplies the hamburger drawer (projects, each expandable to its lists, collapsed by default), draws
 * the top filter bar over the selected list's tasks and subtasks, and opens the task editor.
 *
 * [pickedLink] is a link a system picker returned while the editor was open; the page hands it to the editor once.
 */
@Composable
fun TasksScreen(controller: TasksController, host: TasksHost, pickedLink: Attachment?, onPickedConsumed: () -> Unit, modifier: Modifier = Modifier) {
    val snap = controller.snapshot
    val p = LocalKitPalette.current
    var expanded by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf(emptySet<String>()) }
    var request by remember { mutableStateOf<DrawerRequest?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var tagsOpen by remember { mutableStateOf(false) }
    var mailPicking by remember { mutableStateOf(false) }
    // A mail the Cloud Mail picker returned, waiting for the editor to take it into its draft.
    var mailLink by remember { mutableStateOf<Attachment?>(null) }

    PageDrawer { close ->
        ProjectsDrawerContent(
            snapshot = controller.snapshot, selectedListId = controller.selectedListId, expanded = expanded,
            onToggle = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
            onSelectList = { l -> controller.selectList(l.id); close() },
            onRequest = { request = it },
        )
    }

    Column(modifier.fillMaxSize().testTag(TasksTags.PAGE)) {
        FilterBar(controller.filter, snap.tags, controller::updateFilter, onManageTags = { tagsOpen = true })
        val list = snap.list(controller.selectedListId)
        if (list == null) {
            KitEmptyState("No list yet", "Open the menu, add a project, then a list.", Modifier.testTag(TasksTags.EMPTY))
        } else {
            val nodes = TaskFiltering.visible(snap, list.id, controller.filter)
            val crumb = (snap.project(list.projectId)?.name?.let { "$it  ›  " } ?: "") + list.name
            Text(crumb, color = p.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(TasksTags.HEADER))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(TasksTags.LIST)) {
                if (nodes.isEmpty()) {
                    item {
                        val filtered = controller.filter.active
                        Column(Modifier.fillMaxWidth().padding(24.dp).testTag(if (filtered) TasksTags.NO_MATCH else TasksTags.EMPTY), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (filtered) "No task matches the filter." else "No tasks in this list yet.", color = p.textSecondary)
                            if (filtered) TextButton(onClick = { controller.updateFilter(controller.filter.clear()) }) { Text("Clear all") }
                        }
                    }
                }
                items(nodes, key = { it.task.id }) { node ->
                    TaskNodeRows(node, snap, controller, onOpen = { editingId = it.id })
                    HorizontalDivider(color = p.hairline)
                }
                item { AddTaskRow(onAdd = { controller.addTask(list.id, it) }) }
            }
        }
    }

    // ── dialogs the drawer asked for ────────────────────────────────────────────────────────────────────
    when (val r = request) {
        null -> {}
        DrawerRequest.NewProject -> NameDialog("New project", "", { controller.addProject(it); request = null }, { request = null })
        is DrawerRequest.NewList -> NameDialog("New list in ${r.project.name}", "", {
            controller.addList(r.project.id, it); expanded = expanded + r.project.id; request = null
        }, { request = null })
        is DrawerRequest.RenameProject -> NameDialog("Rename project", r.project.name, { controller.renameProject(r.project.id, it); request = null }, { request = null })
        is DrawerRequest.RenameList -> NameDialog("Rename list", r.list.name, { controller.renameList(r.list.id, it); request = null }, { request = null })
        is DrawerRequest.DeleteProject -> KitConfirmDialog(
            "Delete project?", "\"${r.project.name}\" with all its lists, tasks and subtasks will be deleted.", "Delete", "Cancel",
            onConfirm = { controller.deleteProject(r.project.id); request = null }, onDismiss = { request = null },
        )
        is DrawerRequest.DeleteList -> KitConfirmDialog(
            "Delete list?", "\"${r.list.name}\" with all its tasks and subtasks will be deleted.", "Delete", "Cancel",
            onConfirm = { controller.deleteList(r.list.id); request = null }, onDismiss = { request = null },
        )
    }

    // ── the editor ──────────────────────────────────────────────────────────────────────────────────────
    val editing = snap.task(editingId)
    if (editing != null) {
        Dialog(onDismissRequest = { editingId = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            TaskEditor(
                task = editing, snapshot = snap,
                actions = object : EditorActions {
                    override fun pickMail() { mailPicking = true }
                    override fun pickDocument(kind: AttachmentKind) = host.pickDocument(kind)
                    override fun open(a: Attachment) = host.open(a)
                },
                onSave = { controller.saveTask(it); editingId = null },
                onClose = { editingId = null },
                onDelete = { controller.deleteTask(editing.id); editingId = null },
                onOpenSubtask = { draft, sub -> controller.saveTask(draft); editingId = sub.id },
                onAddSubtask = { controller.addTask(editing.listId, it, parentId = editing.id) },
                onManageTags = { tagsOpen = true },
                pending = pickedLink ?: mailLink,
                onPendingConsumed = { if (pickedLink != null) onPickedConsumed(); mailLink = null },
            )
        }
    }
    if (mailPicking) {
        MailPickerDialog(host.mail, onPick = { m: MailItem ->
            mailPicking = false
            mailLink = newAttachment(AttachmentKind.MAIL, m.ref, m.subject.ifBlank { m.sender })
        }, onDismiss = { mailPicking = false })
    }
    if (tagsOpen) {
        TagsDialog(snap.tags, controller::addTag, controller::renameTag, controller::recolorTag, controller::deleteTag, onDismiss = { tagsOpen = false })
    }
}

@Composable
private fun TaskNodeRows(node: TaskNode, snap: Snapshot, controller: TasksController, onOpen: (Task) -> Unit) {
    TaskRow(node.task, snap, depth = 0, controller, onOpen)
    for (s in node.subtasks) TaskRow(s, snap, depth = 1, controller, onOpen)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(t: Task, snap: Snapshot, depth: Int, controller: TasksController, onOpen: (Task) -> Unit) {
    val p = LocalKitPalette.current
    val done = t.status == TaskStatus.COMPLETED || t.status == TaskStatus.ARCHIVED
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(t) }.padding(start = (12 + depth * 28).dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .testTag(if (depth == 0) TasksTags.row(t.id) else TasksTags.sub(t.id)),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = t.status == TaskStatus.COMPLETED,
            onCheckedChange = { controller.setStatus(t.id, if (it) TaskStatus.COMPLETED else TaskStatus.ACTION) },
            modifier = Modifier.testTag(TasksTags.check(t.id)),
        )
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Text(
                t.name, color = if (done) p.textSecondary else p.textPrimary, style = if (depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (depth == 0) FontWeight.Medium else FontWeight.Normal, textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            val meta = listOfNotNull(
                t.status.label, t.dateTarget?.let { "target $it" }, t.dateDeadline?.let { "due $it" }, t.location.ifBlank { null },
                t.attachments.size.takeIf { it > 0 }?.let { "$it link${if (it == 1) "" else "s"}" },
            ).joinToString("  ·  ")
            Text(meta, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            if (t.tagIds.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                    for (id in t.tagIds) snap.tag(id)?.let { tag ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(tagColor(tag.color), size = 8)
                            Spacer(Modifier.width(4.dp))
                            Text(tag.name, color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddTaskRow(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, label = { Text("New task") }, singleLine = true, modifier = Modifier.weight(1f).testTag(TasksTags.ADD_INPUT))
        TextButton(onClick = { onAdd(text.trim()); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.testTag(TasksTags.ADD)) { Text("Add") }
    }
}
