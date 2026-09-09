package app.sterna.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.push.PushController
import app.sterna.ui.components.AccountPalette

/** The account the `sharedAccount/{id}` route may show: a delegated one, and only that. An id naming
 *  a login resolves to nothing, so this screen's writes can never land on a login's row. */
internal fun sharedAccountFor(id: String, accounts: List<StoredAccount>): StoredAccount? =
    accounts.firstOrNull { it.id == id && it.isShared }

/**
 * The inner settings route that opens [accountId]: the short shared-mailbox screen when the id
 */
internal fun settingsRouteFor(accountId: String, accounts: List<StoredAccount>): String =
    if (sharedAccountFor(accountId, accounts) != null) "sharedAccount/$accountId" else "account/$accountId"

/**
 * Settings for a delegated (shared) account — the two it genuinely owns (#31): accent colour and
 */
@Composable
internal fun SharedAccountScreen(
    accountId: String,
    viewModel: AccountsViewModel,
    onBack: () -> Unit,
    onAccountsChanged: () -> Unit,
) {
    // The live list: a share revoked on the server is dropped by the next discovery pass while this
    // screen is open, and the empty scaffold below is what it then shows.
    // PINNED BY SharedAccountScreenTest — the guard, statement by statement
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val account = sharedAccountFor(accountId, accounts)
    if (account == null) {
        DetailScaffold(title = stringResource(R.string.settings_account_screen_title), onBack = onBack) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
        return
    }

    // PINNED BY SharedAccountScreenTest — what each row opens on
    var colorArgb by remember(accountId) { mutableStateOf(account.color) }
    var notificationsEnabled by remember(accountId) { mutableStateOf(account.notificationsEnabled) }

    DetailScaffold(title = account.label(), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // The title is the account's own label, so this line says what kind of account it is.
            Text(
                stringResource(R.string.account_shared),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SettingsSection(stringResource(R.string.settings_account_colour_section)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ColourSwatch(color = null, selected = colorArgb == null) {
                        colorArgb = null; viewModel.setColor(accountId, null); onAccountsChanged()
                    }
                    AccountPalette.colors.forEach { swatch ->
                        val argb = swatch.toArgb()
                        ColourSwatch(color = swatch, selected = colorArgb == argb) {
                            colorArgb = argb; viewModel.setColor(accountId, argb); onAccountsChanged()
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_account_notifications_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_account_notifications_title),
                    subtitle = stringResource(R.string.settings_account_notifications_subtitle),
                    checked = notificationsEnabled,
                    onCheckedChange = {
                        notificationsEnabled = it
                        viewModel.setNotificationsEnabled(accountId, it)
                    },
                )
                // A delegated account is always linked, so the note stays silent today; asked in
                // full anyway, so the rule stays PushController's to change.
                if (PushController.shouldShowUnwatchedNote(account.isLinked, viewModel.isWatched(accountId), notificationsEnabled)) {
                    Text(
                        stringResource(R.string.settings_account_notifications_unwatched_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
