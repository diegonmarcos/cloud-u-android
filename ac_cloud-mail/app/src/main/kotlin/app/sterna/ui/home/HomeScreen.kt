package app.sterna.ui.home

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.ui.SCREEN_SLIDE_MS
import app.sterna.ui.components.Monogram
import app.sterna.ui.components.accountColorOf
import app.sterna.ui.rememberMotionEnabled
import app.sterna.ui.settings.DetailScaffold

/**
 * The Home screen: what this phone knows about each configured account's mail, one section per
 * account, plus the shortcuts and quickmarks that turn it into a real landing page rather than a
 * standalone statistics sheet (#501). Read-only for the accounts themselves — nothing here syncs or
 * writes; every row still just navigates or reads.
 *
 * The house style for a secondary screen is a sectioned scroll with NO CARDS (DESIGN.md → "Visual &
 * motion rules"), so accounts are told apart by an accent-coloured heading and whitespace rather
 * than by boxes, and every neutral tone below comes from `MaterialTheme.colorScheme` so the dark and
 * Samsung-black themes carry it unchanged. The one departure from that neutrality is deliberate and
 * already sanctioned elsewhere in this app (DESIGN.md's message-row account chip): each account's
 * OWN accent colour, the same one its Monogram already wears, now also tints its numbers — colour
 * that carries real per-account meaning, not decoration painted on top.
 */
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onOpenCompose: () -> Unit,
    onOpenSearch: (query: String) -> Unit,
    onOpenStarred: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenScheduled: () -> Unit,
    onOpenSnoozed: () -> Unit,
    onOpenOutbox: () -> Unit,
    onOpenMailBySender: () -> Unit,
    onOpenRss: () -> Unit,
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
        // One motion decision for the whole page, read once and threaded down: every animated
        // number below shares it with LoadingRing, the inbox and every other screen (Motion.kt), so
        // battery saver or "Remove animations" holds this page's numbers still exactly as it holds
        // everything else — never a second, unsynced gate.
        val motionOn = rememberMotionEnabled()
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            ShortcutsRow(
                listOf(
                    HomeAction(Icons.Filled.Create, stringResource(R.string.inbox_compose), onOpenCompose),
                    HomeAction(Icons.Filled.Search, stringResource(R.string.inbox_search), { onOpenSearch("") }),
                    HomeAction(Icons.Filled.Settings, stringResource(R.string.inbox_settings), onOpenAppSettings),
                ),
            )
            QuickmarksRow(
                listOf(
                    HomeAction(Icons.Filled.Star, stringResource(R.string.folder_flagged), onOpenStarred),
                    HomeAction(Icons.Filled.Schedule, stringResource(R.string.inbox_scheduled), onOpenScheduled),
                    HomeAction(Icons.Filled.Snooze, stringResource(R.string.inbox_snoozed), onOpenSnoozed),
                    HomeAction(Icons.AutoMirrored.Filled.Send, stringResource(R.string.inbox_outbox), onOpenOutbox),
                    HomeAction(Icons.Filled.Person, stringResource(R.string.inbox_by_sender), onOpenMailBySender),
                    HomeAction(Icons.Filled.RssFeed, stringResource(R.string.rss_title), onOpenRss),
                ),
            )
            ui.accounts.forEachIndexed { index, account ->
                // A rule between accounts and none before the first: the divider is what says
                // "these numbers stop belonging to the account above", which is the one thing this
                // page must never leave ambiguous.
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                AccountSection(account, motionOn)
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

/** One shortcut or quickmark: what it opens is a real navigation callback the caller already wires
 *  to a route this app declares — never a number, so it carries no data-authenticity risk of its
 *  own (see [HomeScreen]'s doc). Drawn as DATA, the same convention [statsOf] uses below, so the
 *  order and wording live in one list rather than in hand-placed rows. */
private data class HomeAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** The page's primary actions: compose, search, settings — the three every other screen reaches
 *  from its own top bar or FAB, now one tap from the landing page too. */
@Composable
private fun ShortcutsRow(actions: List<HomeAction>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        actions.forEach { action ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = action.onClick) {
                    Icon(action.icon, contentDescription = action.label, tint = MaterialTheme.colorScheme.primary)
                }
                Text(action.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Quickmarks: the destinations that already exist in the drawer and the inbox's overflow menu
 *  (Starred, Scheduled, Snoozed, Outbox, By sender, News) — a second door to each, not a second
 *  implementation of any of them. Horizontally scrollable, so a small phone never wraps them. */
@Composable
private fun QuickmarksRow(actions: List<HomeAction>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { action ->
            AssistChip(
                onClick = action.onClick,
                label = { Text(action.label) },
                leadingIcon = { Icon(action.icon, contentDescription = null) },
            )
        }
    }
}

/** One account: who it is, then its numbers. */
@Composable
private fun AccountSection(stats: AccountMailStats, motionOn: Boolean) {
    val accent = accountColorOf(stats.color) ?: MaterialTheme.colorScheme.primary
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
    statsOf(stats).forEach { stat -> StatRow(stat.label, stat.value, accent, motionOn) }
}

/** One number this account's card lists — a label and the value itself, RAW, so the row that draws
 *  it is the one place formatting and animation happen. */
private data class Stat(val label: String, val value: Int?)

/**
 * What this account's section lists, as DATA rather than as four hand-placed rows: the order and
 * the wording live in one list, and [StatRow] draws whatever is in it. [Stat.value] comes straight
 * off [stats] — nothing here is a literal, so a card can never show a number the store did not
 * return.
 */
@Composable
private fun statsOf(stats: AccountMailStats): List<Stat> = listOf(
    // Null is "this account cannot answer", not zero — see [AccountMailStats.unread].
    Stat(stringResource(R.string.home_stat_unread), stats.unread),
    Stat(stringResource(R.string.home_stat_cached), stats.cachedMessages),
    Stat(stringResource(R.string.home_stat_folders), stats.folders),
    Stat(stringResource(R.string.home_stat_subscribed), stats.subscribedFolders),
)

/**
 * One label/value line, laid out like the settings rows above it: label left, value at the end.
 *
 * A null [value] draws the "unavailable" sentence, unanimated — there is no number to count towards.
 * A real value counts up to itself from wherever it last was: [animateIntAsState]'s `targetValue` IS
 * [value], not a fixed number reached from a memorised start, so an account's mail arriving while
 * this page is open counts up by the real delta rather than replaying from zero. [motionOn] is
 * [app.sterna.ui.rememberMotionEnabled] read once by the caller (#501's second hard constraint): off
 * under battery saver or "Remove animations", the count-up is a `snap()` and the row shows the true
 * value on the very first frame rather than climbing to it.
 */
@Composable
private fun StatRow(label: String, value: Int?, accent: Color, motionOn: Boolean) {
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
        if (value == null) {
            Text(stringResource(R.string.home_stat_unread_unavailable), style = MaterialTheme.typography.titleMedium)
        } else {
            val animated by animateIntAsState(
                targetValue = value,
                animationSpec = if (motionOn) tween(SCREEN_SLIDE_MS) else snap(),
                label = "homeStat",
            )
            Text(
                stringResource(R.string.home_stat_number, animated),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
            )
        }
    }
}
