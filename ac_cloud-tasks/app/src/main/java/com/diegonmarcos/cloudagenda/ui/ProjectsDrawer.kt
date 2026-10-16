package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
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
import com.diegonmarcos.cloudagenda.data.Project
import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.TaskList
import com.diegonmarcos.superapp.uikit.LocalKitPalette

object DrawerTags {
    const val ADD_PROJECT = "drawer_add_project"
    fun project(id: String) = "drawer_project_$id"
    fun projectMenu(id: String) = "drawer_project_menu_$id"
    fun list(id: String) = "drawer_list_$id"
    fun listMenu(id: String) = "drawer_list_menu_$id"
    const val ADD_LIST = "drawer_add_list"
    const val RENAME = "drawer_rename"
    const val DELETE = "drawer_delete"
    const val NAME_INPUT = "name_input"
    const val NAME_OK = "name_ok"
    const val NAME_CANCEL = "name_cancel"
}

/** What the drawer asks the page to do through a dialog (the dialogs live on the page, not inside the sheet). */
sealed interface DrawerRequest {
    data object NewProject : DrawerRequest
    data class NewList(val project: Project) : DrawerRequest
    data class RenameProject(val project: Project) : DrawerRequest
    data class RenameList(val list: TaskList) : DrawerRequest
    data class DeleteProject(val project: Project) : DrawerRequest
    data class DeleteList(val list: TaskList) : DrawerRequest
}

/**
 * The Tasks page's hamburger drawer: the projects list. Each project expands and collapses to show its lists; all
 * are collapsed when the page opens ([expanded] starts empty). A list tap selects it and closes the drawer.
 */
@Composable
fun ColumnScope.ProjectsDrawerContent(
    snapshot: Snapshot,
    selectedListId: String,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onSelectList: (TaskList) -> Unit,
    onRequest: (DrawerRequest) -> Unit,
) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Projects", color = p.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = { onRequest(DrawerRequest.NewProject) }, modifier = Modifier.testTag(DrawerTags.ADD_PROJECT)) {
            Icon(Icons.Filled.Add, contentDescription = "New project", tint = p.textPrimary)
        }
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        if (snapshot.projects.isEmpty()) {
            Text("No projects yet. Add one with +.", color = p.textSecondary, modifier = Modifier.padding(20.dp))
        }
        for (project in snapshot.projects) {
            val open = project.id in expanded
            ProjectRow(project, open, onToggle, onRequest)
            if (open) {
                val lists = snapshot.listsOf(project.id)
                for (l in lists) ListRow(l, l.id == selectedListId, onSelectList, onRequest)
                TextButton(onClick = { onRequest(DrawerRequest.NewList(project)) }, modifier = Modifier.padding(start = 36.dp).testTag(DrawerTags.ADD_LIST + "_" + project.id)) {
                    Text("+ New list")
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: Project, open: Boolean, onToggle: (String) -> Unit, onRequest: (DrawerRequest) -> Unit) {
    val p = LocalKitPalette.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onToggle(project.id) }.padding(start = 12.dp, end = 4.dp).testTag(DrawerTags.project(project.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, contentDescription = if (open) "Collapse" else "Expand", tint = p.textSecondary)
        Text(project.name, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 4.dp))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(DrawerTags.projectMenu(project.id))) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Project menu", tint = p.textSecondary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("New list") }, onClick = { menu = false; onRequest(DrawerRequest.NewList(project)) })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRequest(DrawerRequest.RenameProject(project)) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onRequest(DrawerRequest.DeleteProject(project)) })
            }
        }
    }
}

@Composable
private fun ListRow(list: TaskList, selected: Boolean, onSelect: (TaskList) -> Unit, onRequest: (DrawerRequest) -> Unit) {
    val p = LocalKitPalette.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(if (selected) p.surfaceSelected else p.surface).clickable(role = Role.Button) { onSelect(list) }
            .padding(start = 44.dp, end = 4.dp).testTag(DrawerTags.list(list.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(list.name, color = p.textPrimary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(DrawerTags.listMenu(list.id))) {
                Icon(Icons.Filled.MoreVert, contentDescription = "List menu", tint = p.textSecondary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRequest(DrawerRequest.RenameList(list)) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onRequest(DrawerRequest.DeleteList(list)) })
            }
        }
    }
}

/** A one-line name prompt (new project, new list, rename). OK stays disabled while the name is blank. */
@Composable
fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.testTag(DrawerTags.NAME_INPUT)) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.testTag(DrawerTags.NAME_OK)) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag(DrawerTags.NAME_CANCEL)) { Text("Cancel") } },
    )
}
