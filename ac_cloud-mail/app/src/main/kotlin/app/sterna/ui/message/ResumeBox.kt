package app.sterna.ui.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import app.sterna.R
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import app.sterna.ui.theme.MailMetrics

/** The summary of the open message and the one thing the box can ask back: dismiss an error. */
class ReaderSummaryUi(
    val summary: ReaderSummary,
    /** Opens the composer as Reply all with the suggested [String] as the start of the body. Never sends. */
    val onReplyAll: (String) -> Unit = {},
    val dismissError: () -> Unit,
) {
    companion object {
        val None = ReaderSummaryUi(ReaderSummary(), dismissError = {})
    }
}

/**
 * Provided once by the reader's page. A composition local rather than a parameter for the reason the
 * text-tool runner was one: the box is drawn four composables below the place that knows the summary,
 * and threading it would add an argument to signatures that tests pin line for line. The default is an
 * empty summary, so a preview or a test that draws a header without a reader draws no box.
 */
val LocalReaderSummary = compositionLocalOf { ReaderSummaryUi.None }

/** (emailId, accountId, suggested text) -> open Reply all with the text as the start of the body. */
val LocalReplyAllWith = compositionLocalOf<(String, String?, String) -> Unit> { { _, _, _ -> } }

/**
 * "AI Resume" - the summary of this message, in a collapsible box directly below the header.
 *
 * RESUME MEANS SUMMARISE: the owner's product name for condensing the message, kept exactly. It is
 * not a curriculum vitae and does not resume anything paused.
 *
 * ALWAYS THERE ONCE IT EXISTS. The summary is cached per message and language, so a message opened
 * again shows its box at once, without anyone asking again; and the box is expanded by default, with a
 * chevron to collapse it (the choice is remembered for this message while the reader is on it).
 *
 * IT NEVER TOUCHES THE STORED MESSAGE. It draws text it was handed and offers Copy; there is no
 * ViewModel here and no path back to a body. A received message is the record of what somebody sent.
 *
 * The progress and the engine's own reason (verbatim - "no API key for OpenRouter", the provider's HTTP
 * error) are drawn here, because this box is where the summary was going to be and so where its absence
 * has to be explained.
 */
@Composable
fun ResumeBox(ui: ReaderSummaryUi, emailId: String) {
    val summary = ui.summary
    if (!summary.visible) return
    val clipboard = LocalClipboardManager.current
    var expanded by rememberSaveable(emailId) { mutableStateOf(true) }

    Column(Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s8)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.text_tool_resume),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.message_summary_collapse else R.string.message_summary_expand,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!expanded) return@Column
        when {
            summary.running -> {
                Text(
                    stringResource(R.string.text_tool_running),
                    Modifier.padding(top = MailMetrics.s4),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = MailMetrics.s8))
            }
            summary.error != null -> {
                Text(
                    summary.error,
                    Modifier.padding(top = MailMetrics.s4),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = ui.dismissError) { Text(stringResource(R.string.text_tool_close)) }
            }
            else -> {
                val text = summary.text.orEmpty()
                Text(
                    text,
                    Modifier.fillMaxWidth().padding(top = MailMetrics.s4),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                    Text(stringResource(R.string.text_tool_copy))
                }
                summary.reply?.let { reply -> SuggestedReplyBlock(reply, ui.onReplyAll) }
            }
        }
    }
}

/**
 * The reply suggested together with the summary: its text, a Reply all button, and a small Copy icon.
 * Reply all opens the composer with this text at the top of the body; it SENDS NOTHING - the reader edits
 * and sends from the composer. Dense: one text block and one row of two controls.
 */
@Composable
private fun SuggestedReplyBlock(reply: String, onReplyAll: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Surface(
        Modifier.fillMaxWidth().padding(top = MailMetrics.s4),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(horizontal = MailMetrics.s12, vertical = MailMetrics.s8)) {
            Text(
                stringResource(R.string.message_suggested_reply),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(reply, Modifier.padding(top = MailMetrics.s2), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onReplyAll(reply) }) { Text(stringResource(R.string.message_reply_all)) }
                IconButton(onClick = { clipboard.setText(AnnotatedString(reply)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.text_tool_copy))
                }
            }
        }
    }
}
