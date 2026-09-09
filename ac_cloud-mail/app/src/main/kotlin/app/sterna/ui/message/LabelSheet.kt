package app.sterna.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.jmap.model.Mailbox

/**
 * "Add a tag / Move folder", built honestly for the protocol underneath it.
 *
 * THE MODEL, AND IT IS THE WHOLE DESIGN. This is JMAP. A message belongs to a SET of mailboxes at
 * once (`mailboxIds`, RFC 8621 §4.1.1), and on this fleet's server every drawer category is a
 * mailbox — so a message really can be in Inbox and Work and Receipts together. There is no
 * single-folder slot to overwrite, which means "move" is not an operation that exists here; it is
 * a word borrowed from a different mail model, and code that believes it deletes labels. The two
 * honest operations are ADD to a mailbox and REMOVE from one, and that is what this offers.
 *
 * JMAP has a SECOND multi-valued thing beside membership: `keywords`, per-message flags. The
 * `$`-prefixed ones are the protocol's (`$seen` is the unread state, `$flagged` is the star on the
 * toolbar) and are not shown; everything else is a name the user or their server-side filters
 * chose. Both kinds are offered here, both are labelled as what they are, because calling either
 * one "the tags" on its own would be a lie about which server state a tap changes. MessageTags in
 * MessageMetadata.kt is where that decision lives, so the chip row under the sender and this sheet
 * cannot disagree about what a tag is.
 *
 * DESTRUCTIVENESS, stated per gesture:
 *   add a mailbox      one tap. It only ever widens where a message can be found.
 *   add a keyword      one tap. A flag marks a message; it never files it.
 *   remove a keyword   one tap. Same reason — nothing moves.
 *   remove a MAILBOX   CONFIRMED. This is the one that can hide a message from every folder the
 *                      user browses, and on a message in one mailbox the server refuses it
 *                      outright rather than leaving an unreachable message. The dialog names the
 *                      mailbox, because "are you sure?" about an unnamed thing is not a question.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LabelSheet(
    /** Mailboxes this message is in right now, straight from the server. */
    tags: List<MessageTag>,
    /** Mailboxes of this message's account it is NOT in — what "add" can offer. */
    addableMailboxes: List<Mailbox>,
    nameOf: (Mailbox) -> String,
    onAddMailbox: (String) -> Unit,
    onRemoveMailbox: (String) -> Unit,
    onRemoveKeyword: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The removal awaiting an answer, held as the tag itself so the dialog can name it.
    var confirming by remember { mutableStateOf<MessageTag?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.message_labels),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            // Said in one sentence at the top rather than discovered by a user whose message
            // vanished: this is membership, not location, and a message can be in several places.
            Text(
                stringResource(R.string.message_labels_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            LabelGroup(R.string.message_labels_in) {
                for (tag in tags) {
                    InputChip(
                        selected = true,
                        onClick = {
                            // A mailbox removal is the only one that asks. See the class note.
                            if (removalNeedsConfirming(tag)) confirming = tag else onRemoveKeyword(tag.id)
                        },
                        label = { Text(tag.label) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.message_label_remove, tag.label),
                            )
                        },
                    )
                }
                if (tags.isEmpty()) {
                    Text(
                        stringResource(R.string.message_labels_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LabelGroup(R.string.message_labels_add) {
                for (mailbox in addableMailboxes) {
                    AssistChip(
                        onClick = { onAddMailbox(mailbox.id) },
                        label = { Text(nameOf(mailbox)) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    )
                }
            }
        }
    }

    confirming?.let { tag ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.message_label_remove_title)) },
            // NAMES the mailbox, and says what removal actually does — which is not "delete this
            // message" and not "nothing". A user who has just been told a message lives in several
            // mailboxes needs to know that taking away this one leaves the others.
            text = { Text(stringResource(R.string.message_label_remove_body, tag.label)) },
            confirmButton = {
                TextButton(onClick = { onRemoveMailbox(tag.id); confirming = null }) {
                    Text(stringResource(R.string.message_label_remove_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.inbox_cancel))
                }
            },
        )
    }
}

/** One headed block of chips. Renders its heading even when empty — the "in" group says so in
 *  words, and an "add" group with nothing in it means the message is already everywhere. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelGroup(titleRes: Int, content: @Composable () -> Unit) {
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
