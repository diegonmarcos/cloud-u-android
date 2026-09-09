package app.sterna.ui.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.sterna.R

/**
 * The one surface both tools report through, on both surfaces they run on.
 *
 * [onApply] is what separates the two directions the owner named, and it is deliberately
 * the ONLY difference:
 *
 *   null      the message is one that was RECEIVED. It is read-only: the result is shown
 *             here to read or copy, and nothing is written back. The stored body is not a
 *             target — an incoming message is a record of what somebody sent, and a tool
 *             that edits it in place has destroyed the only copy of that.
 *   non-null  the message is a DRAFT the user is writing. The result goes back into the
 *             compose field, because that is where the user wants it.
 *
 * A dialog rather than a sheet: it is modal on purpose. A rewrite lands in the field, and
 * a control that applies text to a field the user can still be typing in is a race.
 */
@Composable
fun TextToolPanel(runner: TextToolRunner, onApply: ((String) -> Unit)?) {
    val busy = runner.busy
    val outcome = runner.outcome
    if (busy == null && outcome == null) return
    val tool = busy ?: outcome!!.tool
    // AI Resume reports somewhere else: the owner asked for its summary in a box UNDER THE SENDER,
    // not in a modal over the message. Same runner, same progress and the same verbatim error — see
    // ResumeBox — so this is a routing decision about where one outcome is drawn, not a second copy
    // of the machinery. Returning early here is what keeps a dialog from opening over that box.
    // The tools this dialog answers for are exactly the ones drawn in an overflow menu, so it asks
    // TextTool rather than naming RESUME: a fourth tool that draws itself is handled already.
    if (!tool.inOverflow) return

    val clipboard = LocalClipboardManager.current
    val toolName = stringResource(tool.label)

    AlertDialog(
        // While a call is in flight there is nothing to dismiss TO: the reply would arrive
        // with no surface to land on. The engines are blocking and cannot be cancelled.
        onDismissRequest = { if (busy == null) runner.dismiss() },
        title = { Text(toolName) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    busy != null -> {
                        // Names the provider, as the keyboard's Enhance bar does: a slow call
                        // then reads as a slow provider rather than as a stuck app.
                        val provider = runner.providerLabel()
                        Text(
                            if (provider != null) {
                                stringResource(R.string.text_tool_running_with, provider)
                            } else {
                                stringResource(R.string.text_tool_running)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    // The engine's OWN reason, verbatim — "no API key for OpenRouter", "the
                    // model cut the reply off", the provider's HTTP error. A generic apology
                    // here would throw away the one thing that says what to do next.
                    outcome?.error != null -> Text(outcome.error, style = MaterialTheme.typography.bodyMedium)
                    else -> Text(
                        outcome?.text.orEmpty(),
                        Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            val text = outcome?.text
            if (text != null) {
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                        Text(stringResource(R.string.text_tool_copy))
                    }
                    // Only a draft gets this. On a received message there is nothing to apply
                    // it to, and offering the button would imply otherwise.
                    if (onApply != null) {
                        TextButton(onClick = { onApply(text); runner.dismiss() }) {
                            Text(stringResource(R.string.text_tool_apply))
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (busy == null) {
                TextButton(onClick = { runner.dismiss() }) {
                    Text(stringResource(R.string.text_tool_close))
                }
            }
        },
    )
}

/**
 * The overflow entries for [surface], drawn FROM the surface's own declaration.
 *
 * Both screens call this instead of hand-writing a DropdownMenuItem per tool. That is the point:
 * the reader used to list Enhance and Translate itself and the composer listed its own pair, so
 * the two could disagree about which tools exist — and did, which is the bug this replaces. Now
 * "which entries does this menu hold" and "which tools will the runner accept" are the same list
 * read twice, and neither can be edited without the other following.
 *
 * [TextTool.RESUME] never appears here even on [TextToolSurface.READ]: it is started from its own
 * toolbar icon and reports into the box under the sender. See [TextTool.inOverflow].
 *
 * [enabled] is the caller's own gate — the composer closes its tools while a send is in flight —
 * and is deliberately separate from membership. Whether an action EXISTS on a surface and whether
 * it can be tapped right now are different questions, and answering them with one flag is how an
 * action ends up permanently greyed out instead of honestly absent.
 */
@Composable
fun TextToolMenuItems(
    surface: TextToolSurface,
    enabled: Boolean = true,
    onPick: (TextTool) -> Unit,
) {
    surface.tools.filter { it.inOverflow }.forEach { tool ->
        DropdownMenuItem(
            text = { Text(stringResource(tool.label)) },
            leadingIcon = { Icon(tool.icon, contentDescription = null) },
            onClick = { onPick(tool) },
            enabled = enabled,
        )
    }
}
