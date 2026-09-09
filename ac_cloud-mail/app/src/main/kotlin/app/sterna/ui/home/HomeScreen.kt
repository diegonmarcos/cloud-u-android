package app.sterna.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.ui.components.Monogram
import app.sterna.ui.components.accountColorOf
import app.sterna.ui.settings.DetailScaffold

/**
 * The Home screen: what this phone knows about each configured account's mail, one section per
 * account. Read-only — nothing here syncs, writes or navigates anywhere; it is the summary the
 * drawer's rows only ever show one folder of at a time.
 *
 * The house style for a secondary screen is a sectioned scroll with NO CARDS (DESIGN.md → "Visual &
 * motion rules"), so the accounts are told apart by an accent-coloured heading and whitespace
 * rather than by boxes, and every tone below comes from `MaterialTheme.colorScheme` so the dark and
 * Samsung-black themes carry it unchanged.
 */
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    DetailScaffold(title = stringResource(R.string.home_title), onBack = onBack) { padding ->
        // Nothing at all until the account list has been read: an empty state that flashes on the
        // way to two accounts of statistics reads as "your accounts are gone".
        if (!ui.loaded) return@DetailScaffold
        if (ui.accounts.isEmpty()) {
            NoAccounts(Modifier.fillMaxSize().padding(padding))
            return@DetailScaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            ui.accounts.forEachIndexed { index, account ->
                // A rule between accounts and none before the first: the divider is what says
                // "these numbers stop belonging to the account above", which is the one thing this
                // page must never leave ambiguous.
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                AccountSection(account)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A fresh install: an account list that is empty is not an error, and says what to do next. */
@Composable
private fun NoAccounts(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                stringResource(R.string.home_no_accounts_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.home_no_accounts_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One account: who it is, then its numbers. */
@Composable
private fun AccountSection(stats: AccountMailStats) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    ) {
        Monogram(seed = stats.label, label = stats.label, color = accountColorOf(stats.color))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                stats.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The protocol and the host together, because the owner runs one JMAP account and
                // one IMAP account side by side and several numbers below mean different things on
                // each. A host is blank on an account that has never been configured that far.
                if (stats.host.isBlank()) {
                    stats.protocol.name
                } else {
                    stringResource(R.string.home_account_server, stats.protocol.name, stats.host)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    statRows(stats).forEach { (label, value) -> StatRow(label, value) }
}

/**
 * What this account's section lists, as DATA rather than as four hand-placed rows: the order and
 * the wording live in one list, and [StatRow] draws whatever is in it.
 */
@Composable
private fun statRows(stats: AccountMailStats): List<Pair<String, String>> = listOf(
    stringResource(R.string.home_stat_unread) to (
        // Null is "this account cannot answer", not zero — see [AccountMailStats.unread].
        stats.unread
            ?.let { stringResource(R.string.home_stat_number, it) }
            ?: stringResource(R.string.home_stat_unread_unavailable)
        ),
    stringResource(R.string.home_stat_cached) to stringResource(R.string.home_stat_number, stats.cachedMessages),
    stringResource(R.string.home_stat_folders) to stringResource(R.string.home_stat_number, stats.folders),
    stringResource(R.string.home_stat_subscribed) to stringResource(R.string.home_stat_number, stats.subscribedFolders),
)

/** One label/value line, laid out like the settings rows above it: label left, value at the end. */
@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
