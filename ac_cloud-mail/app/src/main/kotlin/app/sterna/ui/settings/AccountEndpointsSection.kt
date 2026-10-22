package app.sterna.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.core.data.account.AccountEndpoint
import com.diegonmarcos.superapp.fleetendpoints.EndpointPolicy
import com.diegonmarcos.superapp.fleetendpoints.EndpointState
import app.sterna.ui.theme.MailMetrics
import com.diegonmarcos.superapp.fleetendpoints.MailEndpointsContract

/** Test tags of the account Endpoints section and the row chip. */
object AccountEndpointTags {
    const val SECTION = "account_endpoints_section"
    const val CHIP = "account_endpoints_chip"
    const val MESH = "account_endpoints_mesh"
    const val PUBLIC = "account_endpoints_public"
    const val BEARER = "account_endpoints_bearer"
    const val SYNC = "account_endpoints_sync"
    const val MESSAGE = "account_endpoints_message"
    fun policy(p: EndpointPolicy) = "account_endpoints_policy_${p.wire}"
}

@Composable
fun endpointStateLabel(s: EndpointState): String = stringResource(
    when (s) {
        EndpointState.MESH -> R.string.ep_mesh
        EndpointState.PUBLIC -> R.string.ep_public
        EndpointState.OFFLINE -> R.string.ep_offline
        EndpointState.UNKNOWN -> R.string.ep_checking
    },
)

/** Which route an account's mail is using right now; same pill as the auth chip. */
@Composable
fun EndpointChip(state: EndpointState, modifier: Modifier = Modifier) =
    StatusPill(endpointStateLabel(state), bad = state == EndpointState.OFFLINE, tag = AccountEndpointTags.CHIP, modifier = modifier)

/** What the bearer row prints: a fixed mask, or a word when none was synced. Never the token, never its length. */
fun bearerDisplay(bearer: String): String = MailEndpointsContract.mask(bearer)

/**
 * Account ▸ Endpoints: the mesh URL, the public URL and the (masked) fleet bearer as Cloud Account synced them,
 * the account's endpoint policy, the route in use, and a Sync button. Read-only apart from the policy: the
 * values belong to Cloud Account. [endpoint] null = not a fleet account (it keeps its one server address).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEndpointsSection(
    endpoint: AccountEndpoint?,
    state: EndpointState,
    syncing: Boolean,
    message: String,
    onPolicy: (EndpointPolicy) -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().testTag(AccountEndpointTags.SECTION).padding(horizontal = MailMetrics.s16),
        verticalArrangement = Arrangement.spacedBy(MailMetrics.s8),
    ) {
        if (endpoint == null) {
            Hint(R.string.ep_not_synced)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                EndpointChip(state)
            }
            ValueRow(R.string.ep_mesh, endpoint.mesh, AccountEndpointTags.MESH)
            ValueRow(R.string.ep_public, endpoint.public, AccountEndpointTags.PUBLIC)
            ValueRow(R.string.gw_bearer, bearerDisplay(endpoint.bearer).ifEmpty { "—" }, AccountEndpointTags.BEARER)
            Text(stringResource(R.string.ep_policy), style = MaterialTheme.typography.titleSmall)
            val policies = EndpointPolicy.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                policies.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = endpoint.policy == p, onClick = { onPolicy(p) },
                        shape = SegmentedButtonDefaults.itemShape(i, policies.size),
                        modifier = Modifier.testTag(AccountEndpointTags.policy(p)),
                    ) {
                        Text(
                            stringResource(
                                when (p) {
                                    EndpointPolicy.MESH_FIRST -> R.string.ep_policy_mesh_first
                                    EndpointPolicy.MESH_ONLY -> R.string.ep_policy_mesh_only
                                    EndpointPolicy.PUBLIC_ONLY -> R.string.ep_policy_public_only
                                },
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
            Hint(R.string.ep_hint)
        }
        OutlinedButton(onClick = onSync, enabled = !syncing, modifier = Modifier.testTag(AccountEndpointTags.SYNC)) {
            Text(stringResource(R.string.ep_sync))
        }
        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(AccountEndpointTags.MESSAGE))
        }
    }
}

@Composable
private fun ValueRow(label: Int, value: String, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(tag))
    }
}
