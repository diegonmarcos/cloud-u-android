package app.sterna.ui.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.ui.text.TextTool
import app.sterna.ui.text.TextToolRunner

/**
 * "AI Resume" — the summary of this message, in a box under the sender.
 *
 * RESUME MEANS SUMMARISE. It is the owner's product name for condensing this email to its
 * essentials, kept exactly as they spell it; it is not a curriculum vitae and it does not resume
 * anything that was paused.
 *
 * IT NEVER TOUCHES THE STORED MESSAGE. The summary lives in this composable's own state and
 * nowhere else. There is no callback out of here that writes a body, no ViewModel method taken,
 * nothing saved: a received message is the record of what somebody sent, and a feature that edited
 * it in place would have destroyed the only copy of that. The box is editable because the owner
 * asked for it to be — a near-miss summary is worth fixing by hand and taking away — and what the
 * edit changes is the text in this box, which is thrown away when the reader leaves the message.
 * That is the deal, and the Copy button is how anything survives it.
 *
 * The progress and the error are the runner's, i.e. the same ones Enhance and Translate show, so a
 * slow call reads as a slow provider rather than a stuck app and a failure carries the engine's own
 * reason verbatim instead of a generic apology. This composable draws that state; it does not
 * reimplement it. See TextToolRunner.
 */
@Composable
fun ResumeBox(runner: TextToolRunner, emailId: String) {
    val busy = runner.busy == TextTool.RESUME
    val outcome = runner.outcome?.takeIf { it.tool == TextTool.RESUME }
    if (!busy && outcome == null) return

    val clipboard = LocalClipboardManager.current
    // Seeded from the outcome and keyed on it, so a fresh run replaces the text while a user's own
    // edits survive every recomposition in between. Keyed on the MESSAGE too: the same box drawn
    // for the next message must not inherit this one's words.
    var edited by remember(emailId, outcome) { mutableStateOf(outcome?.text.orEmpty()) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.text_tool_resume),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            busy -> {
                val provider = runner.providerLabel()
                Text(
                    if (provider != null) {
                        stringResource(R.string.text_tool_running_with, provider)
                    } else {
                        stringResource(R.string.text_tool_running)
                    },
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            // The engine's OWN reason, verbatim — "no API key for OpenRouter", "the model cut the
            // reply off", the provider's HTTP error. Not a toast: this box is where the summary was
            // going to be, so it is where its absence has to be explained.
            outcome?.error != null -> Text(
                outcome.error,
                Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> {
                OutlinedTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(edited)) }) {
                        Text(stringResource(R.string.text_tool_copy))
                    }
                    TextButton(onClick = { runner.dismiss() }) {
                        Text(stringResource(R.string.text_tool_close))
                    }
                }
            }
        }
        if (!busy && outcome?.error != null) {
            TextButton(onClick = { runner.dismiss() }) {
                Text(stringResource(R.string.text_tool_close))
            }
        }
    }
}
