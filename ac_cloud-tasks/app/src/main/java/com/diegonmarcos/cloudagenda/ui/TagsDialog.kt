package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudagenda.data.Tag
import com.diegonmarcos.superapp.uikit.LocalKitPalette

object TagTags {
    const val DIALOG = "tags_dialog"
    const val NEW_NAME = "tags_new_name"
    const val NEW_ADD = "tags_new_add"
    const val DONE = "tags_done"
    fun row(id: String) = "tags_row_$id"
    fun name(id: String) = "tags_name_$id"
    fun color(id: String) = "tags_color_$id"
    fun delete(id: String) = "tags_delete_$id"
}

/** The colours a tag can take, in the order the swatch cycles through them. */
val TagPalette: List<Long> = listOf(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFF6D4C41)

/** The next colour after [current] in [TagPalette] (the first when [current] is not in it). */
fun nextTagColor(current: Long): Long = TagPalette[(TagPalette.indexOf(current) + 1) % TagPalette.size]

/**
 * Tag management: create, rename, recolour (tap the swatch to step through the palette) and delete. A deleted tag
 * leaves every task it was on. Names are unique case-insensitively, which the repository enforces; a rename that
 * clashes is simply not applied.
 */
@Composable
fun TagsDialog(
    tags: List<Tag>,
    onAdd: (String, Long) -> Unit,
    onRename: (String, String) -> Unit,
    onRecolor: (String, Long) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalKitPalette.current
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TagTags.DIALOG),
        title = { Text("Tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(tags, key = { it.id }) { t ->
                        var name by remember(t.id, t.name) { mutableStateOf(t.name) }
                        Row(Modifier.fillMaxWidth().testTag(TagTags.row(t.id)), verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(
                                tagColor(t.color), size = 22,
                                modifier = Modifier.testTag(TagTags.color(t.id)).semantics { contentDescription = "Change colour of ${t.name}" }
                                    .clickable(role = Role.Button) { onRecolor(t.id, nextTagColor(t.color)) },
                            )
                            OutlinedTextField(
                                name, { name = it; if (it.isNotBlank()) onRename(t.id, it) }, singleLine = true,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag(TagTags.name(t.id)),
                            )
                            IconButton(onClick = { onDelete(t.id) }, modifier = Modifier.testTag(TagTags.delete(t.id))) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${t.name}", tint = p.bad)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("New tag") }, singleLine = true, modifier = Modifier.weight(1f).testTag(TagTags.NEW_NAME))
                    TextButton(
                        onClick = { onAdd(newName.trim(), TagPalette[tags.size % TagPalette.size]); newName = "" },
                        enabled = newName.isNotBlank(), modifier = Modifier.testTag(TagTags.NEW_ADD),
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag(TagTags.DONE)) { Text("Done") } },
    )
}
