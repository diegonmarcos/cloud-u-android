package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudagenda.agent.MailItem
import com.diegonmarcos.cloudagenda.agent.MailSource
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object MailPickerTags {
    const val DIALOG = "mail_picker"
    const val SEARCH = "mail_picker_search"
    const val EMPTY = "mail_picker_empty"
    const val CANCEL = "mail_picker_cancel"
    fun item(id: String) = "mail_picker_item_$id"
}

/**
 * Pick a message to link to: the recent mail of Cloud Mail (through its read-only agent provider), filterable by
 * subject. Only the message's id is kept on the task; the mail stays in Cloud Mail.
 */
@Composable
fun MailPickerDialog(source: MailSource, onPick: (MailItem) -> Unit, onDismiss: () -> Unit) {
    val p = LocalKitPalette.current
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<MailItem>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        delay(if (query.isEmpty()) 0 else 250)
        val (rows, why) = withContext(Dispatchers.IO) {
            val unavailable = source.unavailableReason()
            if (unavailable != null) emptyList<MailItem>() to unavailable
            else runCatching { source.recent(0, 50, query) to null }.getOrElse { emptyList<MailItem>() to "Cloud Mail refused the request (${it.javaClass.simpleName})" }
        }
        items = rows
        problem = why
    }
    SheetDialog(
        "Pick a mail", onDismiss, Modifier.testTag(MailPickerTags.DIALOG),
        actions = { TextButton(onClick = onDismiss, modifier = Modifier.testTag(MailPickerTags.CANCEL)) { Text("Cancel") } },
    ) {
        run {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Subject contains") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(MailPickerTags.SEARCH))
                if (problem != null) {
                    Text(problem.orEmpty(), color = p.bad, modifier = Modifier.padding(top = 12.dp).testTag(MailPickerTags.EMPTY))
                } else if (items.isEmpty()) {
                    Text("No mail found.", color = p.textSecondary, modifier = Modifier.padding(top = 12.dp).testTag(MailPickerTags.EMPTY))
                }
                LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(items, key = { it.accountId + "/" + it.id }) { m ->
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(m) }.padding(vertical = 8.dp).testTag(MailPickerTags.item(m.id))) {
                            Text(m.subject.ifBlank { "(no subject)" }, color = p.textPrimary, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                            Text(m.sender, color = p.textSecondary, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
