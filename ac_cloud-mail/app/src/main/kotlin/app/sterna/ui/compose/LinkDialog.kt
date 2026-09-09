package app.sterna.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.linkAt
import app.sterna.core.data.text.normalizeLinkUrl
import app.sterna.core.data.text.removeLink
import app.sterna.core.data.text.setLink

/*
 * The "add a link" dialog (#131) and the two decisions behind it, kept out of the composable so a
 * JVM test can run them.
 */

/** What the dialog opens with. */
data class LinkDialogFields(
    /** The address to show: the link's own when there is one, empty otherwise. */
    val url: String,
    /** The label to show: the selected words, or what the link covers, or empty. */
    val text: String,
    /**
     * Whether the label may be typed. False whenever characters are already involved — a
     */
    val textEditable: Boolean,
    /** Whether "remove the link" has anything to remove. */
    val canRemove: Boolean,
)

/**
 * The dialog's opening state for [body] at [selection] — the link under the caret decides, through
 * `linkAt`, which is the same answer that lights the toolbar button.
 */
fun linkDialogFields(body: RichBody, selection: Span): LinkDialogFields {
    val existing = linkAt(body, selection)
    val span = when {
        !selection.isEmpty -> selection
        existing != null -> existing.span
        else -> null
    }
    return LinkDialogFields(
        url = existing?.url.orEmpty(),
        text = span?.let { body.text.substring(it.start, it.end) }.orEmpty(),
        textEditable = span == null,
        canRemove = existing != null,
    )
}

/**
 * What OK produces: [url] through the guard, then `setLink` — or `null` when the guard refuses it,
 */
fun linkApplied(body: RichBody, selection: Span, url: String, text: String): RichBody? {
    val normalised = normalizeLinkUrl(url) ?: return null
    return setLink(body, selection, normalised, text.ifEmpty { normalised })
}

/**
 * The dialog itself: an address, a label, and three buttons. [onApply] receives the body OK made,
 */
@Composable
fun LinkDialog(
    body: RichBody,
    selection: Span,
    onApply: (RichBody) -> Unit,
    onRemove: (RichBody) -> Unit,
    onDismiss: () -> Unit,
) {
    val opening = remember(body, selection) { linkDialogFields(body, selection) }
    var url by remember(opening) { mutableStateOf(opening.url) }
    var text by remember(opening) { mutableStateOf(opening.text) }
    var refused by remember(opening) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compose_link_title)) },
        text = {
            Column {
                TextField(
                    value = url,
                    onValueChange = { url = it; refused = false },
                    label = { Text(stringResource(R.string.compose_link_url)) },
                    singleLine = true,
                    isError = refused,
                )
                if (refused) {
                    Text(
                        stringResource(R.string.compose_link_bad_url),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.compose_link_text)) },
                    singleLine = true,
                    enabled = opening.textEditable,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val applied = linkApplied(body, selection, url, text)
                if (applied == null) refused = true else onApply(applied)
            }) { Text(stringResource(R.string.compose_link_apply)) }
        },
        dismissButton = {
            Row {
                if (opening.canRemove) {
                    TextButton(onClick = { onRemove(removeLink(body, selection)) }) {
                        Text(stringResource(R.string.compose_link_remove))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_link_cancel)) }
            }
        },
    )
}
