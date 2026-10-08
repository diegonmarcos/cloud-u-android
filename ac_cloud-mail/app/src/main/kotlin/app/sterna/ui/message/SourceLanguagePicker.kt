package app.sterna.ui.message

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.ui.text.MailLanguages
import app.sterna.ui.theme.MailMetrics

/**
 * The last resort, and never a dead end: no translator could tell what language the message is in and
 * none that needs no source was available, so the reader is asked - one compact row with a menu of
 * languages. Choosing one runs the translation again with that source.
 */
@Composable
internal fun SourceLanguagePicker(modifier: Modifier, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = MailMetrics.s12, vertical = MailMetrics.s4), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.message_translate_source_prompt), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.message_translate_source_choose)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.text_tool_close)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                MailLanguages.options("").forEach { (tag, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(tag) })
                }
            }
        }
    }
}
