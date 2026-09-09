package app.sterna.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.account.StoredAccount

/** The account row at the head of the move-to-folder picker (#189), drawn only when there is a
 *  choice to make. [owner] is the account of what is being moved, [chosen] the id recorded by the
 *  ViewModel (null = the owner's); tapping an entry reports [pickerChoice]. */
@Composable
internal fun MoveAccountRow(
    accounts: List<StoredAccount>,
    owner: String?,
    chosen: String?,
    onChoose: (String?) -> Unit,
) {
    val ordered = pickerAccounts(accounts, owner)
    if (ordered.size < 2) return
    val shownId = pickerAccount(chosen, owner)
    val shown = ordered.firstOrNull { it.id == shownId } ?: ordered.first()
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = shown.label(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = stringResource(R.string.inbox_move_account),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ordered.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.label()) },
                    onClick = {
                        open = false
                        onChoose(pickerChoice(account.id, owner))
                    },
                )
            }
        }
    }
}
