package com.diegonmarcos.clouddrive.sync

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.storage.BackupJobs
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.storage.UnisonPairs
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.StatusLightRow
import com.diegonmarcos.clouddrive.ui.ToolbarIsland
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * #1173 FSYNC: the old Rsync page renamed, with two sub-pages on the second row (icons from libs:ui-icons, declared in
 * build.json ui.sections[fsync].pages): Backup (rsync, one way, to an SSH host or a guest folder) and Folders Sync (Unison, two ways,
 * conflicts listed). Everything drawn is real state: the jobs and pairs the user made and what their last runs said. This
 * file routes the strip like the Git page does; each body owns its LazyColumn, which rides the shell's one chrome-collapse connection.
 */
@Composable
fun FsyncPageScreen(
    storage: StorageCoordinator,
    rclone: RcloneCoordinator,
    actions: DriveActions,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pages = Declarations.navPages(Declarations.SECTION_FSYNC)
    var current by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    LaunchedEffect(page) {
        if (page != null && pages.any { it.id == page }) current = page
        if (page != null) onPageConsumed()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }
    Column(modifier.fillMaxSize()) {
        ToolbarIsland(
            title = Declarations.nav.section(Declarations.SECTION_FSYNC)?.label.orEmpty(),
            subtitle = pages.firstOrNull { it.id == current }?.label,
            actions = { TerminalIslandAction(actions, ::say) },
        )
        NavBarTopSecondRow(pages = pages, selectedId = current, onSelect = { current = it.id }, modifier = Modifier.padding(horizontal = DriveMetrics.gap).testTag(DriveTags.SYNC_STRIP), underTopChrome = false)
        AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "fsync_page", modifier = Modifier.weight(1f)) { id ->
            when (id) {
                "backup" -> FsyncBackupScreen(storage, actions, ::say)
                "folders" -> FolderSyncScreen(rclone, storage, actions, ::say)
                else -> EmptyState(Icons.Filled.Sync, stringResource(R.string.chrome_unknown_tab), "")
            }
        }
        SnackbarHost(snackbar)
    }
}

/** What Account says about itself, one line: held counts, or why it is not answering. */
@Composable
internal fun AccountLine(storage: StorageCoordinator, actions: DriveActions) {
    val snap by storage.accountSnapshot.collectAsState()
    val note by storage.accountNote.collectAsState()
    val s = snap
    Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (s == null) stringResource(R.string.storage_account_missing) else stringResource(R.string.storage_account_ok, s.remotes.size, s.mounts.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!note.isNullOrBlank()) Text(stringResource(R.string.storage_account_note, note.orEmpty()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(DriveMetrics.pad))
        Pill(stringResource(R.string.storage_account_open), { actions.launchApp(com.diegonmarcos.clouddrive.storage.Wire.ACCOUNT_PACKAGE, "") })
    }
}

@Composable
fun FsyncBackupScreen(storage: StorageCoordinator, actions: DriveActions, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val jobs by storage.backups.collectAsState()
    val running by storage.backupRunning.collectAsState()
    val notes by storage.backupNotes.collectAsState()
    val rsync by storage.rsyncReady.collectAsState()
    val snap by storage.accountSnapshot.collectAsState()
    LaunchedEffect(Unit) { storage.refreshBackups(); storage.refreshAccount() }
    var adding by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HERO).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.backup_engine), style = MaterialTheme.typography.labelMedium)
                    Text(if (rsync == null) stringResource(R.string.backup_engine_checking) else if (rsync == "ready") "rsync" else rsync.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                StatusLightRow(StatusLight.of(rsync?.let { it == "ready" }), stringResource(R.string.backup_engine))
            }
        }
        item { AccountLine(storage, actions) }
        item {
            val key = snap
            Text(
                if (key != null && key.hasSshKey) stringResource(R.string.backup_ssh_key_present, key.sshPublic.split(' ').take(2).lastOrNull().orEmpty().take(16) + "…") else stringResource(R.string.backup_ssh_key_absent),
                Modifier.padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { SectionHeader(stringResource(R.string.backup_section), count = jobs.size, action = stringResource(R.string.backup_new)) { adding = true } }
        if (jobs.isEmpty()) item { EmptyState(Icons.Filled.Backup, stringResource(R.string.backup_empty), stringResource(R.string.backup_empty_hint), actionLabel = stringResource(R.string.backup_new)) { adding = true } }
        items(jobs, key = { "backup-" + it.id }) { job ->
            val isRunning = job.id in running
            val target = when (val t = job.target) { is BackupJobs.Target.Local -> t.path; is BackupJobs.Target.Ssh -> "${t.user}@${t.host}:${t.path}" }
            DriveCard(
                job.name, badge = stringResource(if (job.mirror) R.string.backup_mirror_badge else R.string.backup_copy_badge),
                light = if (isRunning) StatusLight.State.UNKNOWN else StatusLight.of(job.lastExit?.let { it == 0 }),
                summary = "${job.source}  →  $target", summaryMonospace = true, tag = DriveTags.RSYNC_RULE_CARD,
            ) {
                Text(
                    notes[job.id] ?: if (job.lastRunEpochSeconds > 0) stringResource(R.string.backup_last, fmt.format(Date(job.lastRunEpochSeconds * 1000)), job.lastSummary) else stringResource(R.string.backup_never),
                    Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                if (isRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = DriveMetrics.gap))
                PillRow {
                    Pill(stringResource(R.string.backup_run), { storage.runBackup(job) }, icon = Icons.Filled.PlayArrow, filled = true, enabled = !isRunning && rsync == "ready")
                    Pill(stringResource(R.string.storage_delete), { storage.removeBackup(job) }, enabled = !isRunning, danger = true)
                }
            }
        }
    }
    if (adding) NewBackupDialog(onDismiss = { adding = false }, onCreate = { job -> storage.addBackup(job) { why -> if (why == null) adding = false else onMessage(why) } })
}

@Composable
private fun NewBackupDialog(onDismiss: () -> Unit, onCreate: (BackupJobs.Job) -> Unit) {
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var ssh by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var dest by remember { mutableStateOf("") }
    var mirror by remember { mutableStateOf(false) }
    var useKey by remember { mutableStateOf(true) }
    val job = BackupJobs.Job(
        id = UnisonPairs.slug(name), name = name.trim(), source = source.trim(),
        target = if (ssh) BackupJobs.Target.Ssh(user.trim(), host.trim(), port.toIntOrNull() ?: 0, dest.trim()) else BackupJobs.Target.Local(dest.trim()),
        mirror = mirror, useKey = ssh && useKey,
    )
    val problem = if (name.isBlank() && source.isBlank() && dest.isBlank()) null else BackupJobs.refusal(job)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FormField(stringResource(R.string.backup_f_name), name, { name = it })
                FormField(stringResource(R.string.backup_f_source), source, { source = it })
                SwitchRow(stringResource(R.string.backup_f_ssh), ssh, { ssh = it })
                if (ssh) {
                    FormField(stringResource(R.string.backup_f_host), host, { host = it })
                    FormField(stringResource(R.string.backup_f_user), user, { user = it })
                    FormField(stringResource(R.string.backup_f_port), port, { port = it }, number = true)
                    SwitchRow(stringResource(R.string.backup_f_usekey), useKey, { useKey = it })
                }
                FormField(stringResource(R.string.backup_f_dest), dest, { dest = it })
                SwitchRow(stringResource(R.string.backup_f_mirror), mirror, { mirror = it })
                if (problem != null) NoteText(problem, error = true)
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(job) }, enabled = BackupJobs.refusal(job) == null) { Text(stringResource(R.string.storage_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_cancel)) } },
    )
}

@Composable
fun FolderSyncScreen(rclone: RcloneCoordinator, storage: StorageCoordinator, actions: DriveActions, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val unison = rclone.unison
    val declared by unison.declaredState.collectAsState()
    val states by unison.states.collectAsState()
    val live by unison.live.collectAsState()
    val unavailable by unison.unavailable.collectAsState()
    LaunchedEffect(Unit) { storage.refreshAccount(); unison.refresh() }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig.Pair?>(null) }
    LazyColumn(modifier.fillMaxSize()) {
        item { AccountLine(storage, actions) }
        unisonSection(unison, states, live, unavailable, onMessage, declared, onAdd = { adding = true }, onDelete = { deleting = it })
    }
    if (adding) NewPairDialog(onDismiss = { adding = false }, onCreate = { draft -> unison.addPair(draft) { why -> if (why == null) adding = false else onMessage(why) } })
    deleting?.let { pair ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.pair_delete_title)) },
            text = { Text(stringResource(R.string.pair_delete_body)) },
            confirmButton = { TextButton(onClick = { unison.removePair(pair); deleting = null }) { Text(stringResource(R.string.storage_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.storage_cancel)) } },
        )
    }
}

@Composable
private fun NewPairDialog(onDismiss: () -> Unit, onCreate: (UnisonPairs.Draft) -> Unit) {
    var name by remember { mutableStateOf("") }
    var rootA by remember { mutableStateOf("") }
    var ssh by remember { mutableStateOf(true) }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var remote by remember { mutableStateOf("") }
    var local by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var policy by remember { mutableStateOf("") }
    val draft = UnisonPairs.Draft(
        name = name.trim(), rootA = rootA, host = if (ssh) host else "", user = user, port = port.toIntOrNull() ?: 22, remotePath = remote, localPeer = if (ssh) "" else local,
        scheduleMinutes = minutes.trim().toIntOrNull(), prefer = policy.ifBlank { null },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pair_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FormField(stringResource(R.string.pair_f_name), name, { name = it })
                FormField(stringResource(R.string.pair_f_root_a), rootA, { rootA = it })
                SwitchRow(stringResource(R.string.pair_f_ssh), ssh, { ssh = it })
                if (ssh) {
                    FormField(stringResource(R.string.pair_f_host), host, { host = it })
                    FormField(stringResource(R.string.pair_f_user), user, { user = it })
                    FormField(stringResource(R.string.pair_f_port), port, { port = it }, number = true)
                    FormField(stringResource(R.string.pair_f_remote), remote, { remote = it })
                } else FormField(stringResource(R.string.pair_f_local), local, { local = it })
                FormField(stringResource(R.string.pair_f_schedule), minutes, { minutes = it }, number = true)
                NoteText(stringResource(R.string.pair_f_policy))
                ChoiceRow(listOf("" to stringResource(R.string.pair_policy_ask), "a" to stringResource(R.string.pair_policy_a), "b" to stringResource(R.string.pair_policy_b), "newer" to stringResource(R.string.pair_policy_newer)), policy, { policy = it })
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(draft) }, enabled = name.isNotBlank() && rootA.isNotBlank()) { Text(stringResource(R.string.storage_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_cancel)) } },
    )
}
