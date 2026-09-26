package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.EngineActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.ui.CapsuleBadge
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.StatusLightRow
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * #579 SYNC ▸ GIT — GitSync-class (cloud-drive-redesign.md §4): a hero row for the
 * store and the schedule, one card per managed repository with the live glance and the
 * last sync, stepped one-tap sync inside the card, conflicts surfaced in the off colour
 * with a Resolve verb, per-repo Settings, the declared-not-cloned family below, and the
 * history log. The engine's full manager is one tap away (Open), never re-drawn here.
 */
@Composable
fun GitReposScreen(coordinator: GitSyncCoordinator, actions: DriveActions, nextRunMinutes: Long?, modifier: Modifier = Modifier) {
    val repos by coordinator.repos.collectAsState()
    val glances by coordinator.glances.collectAsState()
    val running by coordinator.running.collectAsState()
    val events by coordinator.events.collectAsState()
    var settingsFor by remember { mutableStateOf<ManagedRepo?>(null) }
    var historyFor by remember { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { coordinator.refresh() }

    val root = remember { SharedStore.root().absolutePath }
    val family = Declarations.gitFamily
    val clonedNames = repos.map { File(it.path).name }.toSet()
    val declaredNotCloned = family.repos.filter { it.name !in clonedNames && !File(root, it.name).isDirectory }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HERO).padding(horizontal = DriveMetrics.gutter + 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Commit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sync_store) + " · " + root, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.sync_repos_count, repos.size) + " · " +
                            stringResource(R.string.sync_schedule, BuildConfig.GIT_SYNC_INTERVAL_MINUTES, stringResource(if (BuildConfig.GIT_SYNC_REQUIRE_UNMETERED) R.string.sync_network_unmetered else R.string.sync_network_any)) + " · " +
                            (nextRunMinutes?.let { stringResource(R.string.sync_next_run, it) } ?: stringResource(R.string.sync_next_run_unknown)),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    )
                }
                Pill(stringResource(R.string.sync_add_repo), { actions.openEngine(EngineActivity.ENGINE_GIT, root) }, filled = true)
            }
        }
        if (repos.isEmpty()) item { EmptyState(Icons.Filled.Commit, stringResource(R.string.sync_empty_title), stringResource(R.string.sync_empty_hint)) }
        items(repos, key = { it.id }) { repo ->
            RepoCard(
                repo = repo, glance = glances[repo.id], running = running[repo.id], fmt = fmt,
                events = if (historyFor == repo.id) events.filter { it.repoId == repo.id } else emptyList(),
                onSync = { coordinator.syncNow(repo) },
                onOpen = { actions.openEngine(EngineActivity.ENGINE_GIT, repo.path) },
                onHistory = { historyFor = if (historyFor == repo.id) null else repo.id },
                onSettings = { settingsFor = repo },
            )
        }
        if (declaredNotCloned.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.sync_declared_section), count = declaredNotCloned.size) }
            items(declaredNotCloned, key = { "decl-" + it.name }) { d ->
                val url = family.cloneUrl(d) ?: ""
                DriveCard(d.label, badge = if (d.private) stringResource(R.string.chrome_private) else null, summary = url.ifBlank { d.notes }, summaryMonospace = url.isNotBlank(), tag = DriveTags.SYNC_DECLARED_CARD) {
                    if (d.notes.isNotBlank() && url.isNotBlank()) Text(d.notes, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    PillRow { Pill(stringResource(R.string.sync_clone_into_store), { actions.openEngine(EngineActivity.ENGINE_GIT, File(root, d.name).absolutePath, url) }, filled = true) }
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.sync_history_section), count = events.size, action = if (showHistory) stringResource(R.string.chrome_close) else stringResource(R.string.sync_history)) { showHistory = !showHistory }
        }
        if (showHistory) {
            if (events.isEmpty()) item { Text(stringResource(R.string.sync_history_empty), Modifier.padding(horizontal = DriveMetrics.gutter + 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(events.take(50), key = { "${it.epochSeconds}-${it.repoId}" }) { e -> HistoryRow(e, fmt) }
        }
    }

    settingsFor?.let { repo -> RepoSettingsSheet(repo, coordinator, onDismiss = { settingsFor = null }) }
}

@Composable
private fun RepoCard(
    repo: ManagedRepo, glance: GitSyncCoordinator.Glance?, running: GitSyncCoordinator.Running?, fmt: DateFormat, events: List<SyncEvent>,
    onSync: () -> Unit, onOpen: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit,
) {
    val off = colorResource(R.color.status_light_off)
    val light = when {
        glance == null || !glance.read -> StatusLight.State.UNKNOWN
        glance.gone -> StatusLight.State.UNVERIFIABLE
        glance.error != null -> StatusLight.State.UNKNOWN
        glance.conflicts > 0 -> StatusLight.State.OFF
        repo.lastSyncEpochSeconds == 0L -> StatusLight.State.UNKNOWN
        repo.lastSyncSummary.contains("failed", true) || repo.lastSyncSummary.contains("conflict", true) || repo.lastSyncSummary.contains("rejected", true) -> StatusLight.State.OFF
        else -> StatusLight.State.ON
    }
    DriveCard(repo.name, light = light, tag = DriveTags.SYNC_REPO_CARD) {
        // Line 2: the live glance.
        val glanceText = when {
            glance == null || !glance.read -> stringResource(R.string.sync_glance_reading)
            glance.gone -> stringResource(R.string.sync_glance_gone)
            glance.error != null -> stringResource(R.string.sync_glance_unreadable, glance.error)
            else -> buildString {
                append(glance.branch ?: stringResource(R.string.sync_no_branch))
                glance.upstream?.let { append(" → ").append(it) }
                append("   ↑").append(glance.ahead).append(" ↓").append(glance.behind).append("   ")
                append(if (glance.changed == 0) stringResource(R.string.sync_clean) else stringResource(R.string.sync_changed, glance.changed))
                if (glance.repositoryState != "SAFE") append("   ").append(glance.repositoryState)
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(glanceText, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (glance != null && glance.conflicts > 0) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = off, modifier = Modifier.padding(horizontal = 4.dp))
                Text(stringResource(R.string.sync_conflicts, glance.conflicts), color = off, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
        // Line 3: the last sync.
        Text(
            if (repo.lastSyncEpochSeconds > 0) stringResource(R.string.sync_last, fmt.format(Date(repo.lastSyncEpochSeconds * 1000)), repo.lastSyncSummary) else stringResource(R.string.sync_never),
            Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        // The stepped progress of a running sync.
        if (running != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GitSyncCoordinator.Step.values().forEach { step ->
                    val active = step == running.step
                    Text(stepLabel(step), style = MaterialTheme.typography.labelSmall, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (running.step == GitSyncCoordinator.Step.PUSHING) Text(stringResource(R.string.sync_step_uninterruptible), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PillRow {
            if (glance != null && glance.conflicts > 0) Pill(stringResource(R.string.sync_resolve_conflicts, glance.conflicts), onOpen, filled = true)
            else Pill(stringResource(R.string.sync_now), onSync, icon = Icons.Filled.Sync, filled = true, enabled = running == null && glance?.gone != true)
            Pill(stringResource(R.string.sync_open), onOpen, icon = Icons.Filled.FolderOpen)
            Pill(stringResource(R.string.sync_history), onHistory, icon = Icons.Filled.History)
            Pill(stringResource(R.string.sync_settings), onSettings, icon = Icons.Filled.Settings)
        }
        Text(
            if (repo.autoSync) stringResource(R.string.sync_auto, if (repo.syncIntervalMinutes > 0) repo.syncIntervalMinutes else BuildConfig.GIT_SYNC_INTERVAL_MINUTES, stringResource(if (repo.syncRequireUnmetered) R.string.sync_network_unmetered else R.string.sync_network_any)) else stringResource(R.string.sync_manual),
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (events.isNotEmpty()) {
            Column(Modifier.padding(top = 6.dp)) { events.take(10).forEach { e -> HistoryRow(e, fmt, compact = true) } }
        }
    }
}

@Composable
private fun stepLabel(step: GitSyncCoordinator.Step): String = when (step) {
    GitSyncCoordinator.Step.STAGING -> stringResource(R.string.sync_step_staging)
    GitSyncCoordinator.Step.COMMITTING -> stringResource(R.string.sync_step_committing)
    GitSyncCoordinator.Step.PULLING -> stringResource(R.string.sync_step_pulling)
    GitSyncCoordinator.Step.PUSHING -> stringResource(R.string.sync_step_pushing)
}

@Composable
private fun HistoryRow(e: SyncEvent, fmt: DateFormat, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val state = if (e.ok) StatusLight.State.ON else StatusLight.State.OFF
    Column(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HISTORY).clickable { open = !open }.padding(horizontal = if (compact) 0.dp else DriveMetrics.gutter + 4.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLightRow(state, e.repoName)
            Spacer(Modifier.width(8.dp))
            Text(fmt.format(Date(e.epochSeconds * 1000)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            if (!compact) Text(e.repoName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            CapsuleBadge(stringResource(if (e.trigger == SyncHistory.TRIGGER_SCHEDULED) R.string.sync_trigger_scheduled else R.string.sync_trigger_manual))
        }
        Text(e.summary, style = MaterialTheme.typography.bodySmall, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
        if (open && e.details.isNotBlank()) Text(e.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 12)
    }
}

/** Per-repository settings: schedule (on/off, period, network), auth, author, rebase, message, remove. Writes the ManagedRepo the engine reads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoSettingsSheet(repo: ManagedRepo, coordinator: GitSyncCoordinator, onDismiss: () -> Unit) {
    var autoSync by remember { mutableStateOf(repo.autoSync) }
    var interval by remember { mutableStateOf(repo.syncIntervalMinutes) }
    var unmetered by remember { mutableStateOf(repo.syncRequireUnmetered) }
    var authKind by remember { mutableStateOf(repo.authKind) }
    var username by remember { mutableStateOf(repo.authUsername) }
    var secret by remember { mutableStateOf("") }
    var keyPath by remember { mutableStateOf(repo.sshKeyPath) }
    var authorName by remember { mutableStateOf(repo.authorName) }
    var authorEmail by remember { mutableStateOf(repo.authorEmail) }
    var rebase by remember { mutableStateOf(repo.pullRebase) }
    var message by remember { mutableStateOf(repo.syncMessage) }
    var confirmRemove by remember { mutableStateOf(false) }
    val hasSecret = remember(repo.id) { coordinator.hasSecret(repo) }
    val periods = Declarations.configs.gitPeriodsMinutes

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(repo.name, style = MaterialTheme.typography.titleLarge)
            Text(repo.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = autoSync, onCheckedChange = { autoSync = it }); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.sync_settings_auto)) }
            Text(stringResource(R.string.sync_settings_period), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(0L) + periods.map { it.toLong() }).forEach { p ->
                    Pill(if (p == 0L) stringResource(R.string.sync_settings_minutes, BuildConfig.GIT_SYNC_INTERVAL_MINUTES) else stringResource(R.string.sync_settings_minutes, p), { interval = p }, filled = interval == p)
                }
            }
            Text(stringResource(R.string.sync_settings_network), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(stringResource(R.string.sync_network_unmetered), { unmetered = true }, filled = unmetered)
                Pill(stringResource(R.string.sync_network_any), { unmetered = false }, filled = !unmetered)
            }
            Text(stringResource(R.string.sync_settings_auth), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
            Row {
                listOf("none" to R.string.sync_auth_none, "https" to R.string.sync_auth_https, "ssh" to R.string.sync_auth_ssh).forEach { (k, label) ->
                    Row(Modifier.clickable { authKind = k }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = authKind == k, onClick = { authKind = k }); Text(stringResource(label), style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (authKind == "https") {
                OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.sync_auth_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(if (hasSecret) R.string.sync_auth_token_stored else R.string.sync_auth_token)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            if (authKind == "ssh") {
                OutlinedTextField(keyPath, { keyPath = it }, label = { Text(stringResource(R.string.sync_auth_key_path)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.sync_auth_passphrase)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.sync_settings_author), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(authorName, { authorName = it }, label = { Text(stringResource(R.string.sync_settings_author_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(authorEmail, { authorEmail = it }, label = { Text(stringResource(R.string.sync_settings_author_email)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Switch(checked = rebase, onCheckedChange = { rebase = it }); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.sync_settings_rebase)) }
            OutlinedTextField(message, { message = it }, label = { Text(stringResource(R.string.sync_settings_message)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Pill(stringResource(R.string.sync_settings_remove), { confirmRemove = true })
                Pill(stringResource(R.string.chrome_save), {
                    if (secret.isNotBlank()) coordinator.setSecret(repo, secret)
                    coordinator.save(repo.copy(
                        autoSync = autoSync, syncIntervalMinutes = interval, syncRequireUnmetered = unmetered,
                        authKind = authKind, authUsername = username.trim(), sshKeyPath = keyPath.trim(),
                        authorName = authorName.trim(), authorEmail = authorEmail.trim(), pullRebase = rebase,
                        syncMessage = message.trim().ifBlank { repo.syncMessage },
                    ))
                    onDismiss()
                }, filled = true)
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.sync_settings_remove)) },
            text = { Text(stringResource(R.string.sync_settings_remove_body)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; coordinator.remove(repo); onDismiss() }) { Text(stringResource(R.string.sync_settings_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.chrome_keep)) } },
        )
    }
}
