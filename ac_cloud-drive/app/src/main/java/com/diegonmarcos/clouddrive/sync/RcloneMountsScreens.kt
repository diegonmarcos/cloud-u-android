package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.EngineActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.CapsuleBadge
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.StatusLightRow
import com.diegonmarcos.cloudlib.mounts.MountType
import com.diegonmarcos.cloudlib.rclone.RcloneOutput
import java.text.DateFormat
import java.util.Date

/**
 * #579 SYNC ▸ RCLONE and SYNC ▸ MOUNTS in the same card language as Git
 * (cloud-drive-redesign.md §5): remote cards with a Test light, job rows with a live
 * progress row, mount cards with a Test light, and the fleet's declared connections
 * with their declared status. The engines' own screens are one tap away.
 */
@Composable
fun RcloneSyncScreen(coordinator: RcloneCoordinator, prefs: DrivePrefs.Snapshot, actions: DriveActions, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val version by coordinator.version.collectAsState()
    val remotes by coordinator.remotes.collectAsState()
    val jobs by coordinator.jobs.collectAsState()
    val runs by coordinator.runs.collectAsState()
    val testing by coordinator.testing.collectAsState()
    val results by coordinator.testResults.collectAsState()
    LaunchedEffect(Unit) { coordinator.refresh() }
    val fmt = java.text.DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    val declared = Declarations.remotes.associateBy { it.name }
    val available = coordinator.runner.isAvailable

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_HERO).padding(horizontal = DriveMetrics.gutter + 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (available) stringResource(R.string.rclone_binary_present, version ?: "") else stringResource(R.string.rclone_binary_missing), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.rclone_remotes_count, remotes.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusLightRow(StatusLight.of(available), stringResource(R.string.rclone_remotes_section))
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.rclone_open), { actions.openEngine(EngineActivity.ENGINE_RCLONE, "remotes") })
            }
        }
        item { SectionHeader(stringResource(R.string.rclone_remotes_section), count = remotes.size) }
        if (remotes.isEmpty()) item { EmptyState(Icons.Filled.CloudSync, stringResource(R.string.rclone_remotes_empty), stringResource(R.string.rclone_remotes_empty_hint)) }
        items(remotes, key = { "remote-" + it.name }) { r ->
            val decl = declared[r.name]
            val unreachableDeclared = decl?.status == "unreachable"
            val last = prefs.lastTest("remote:" + r.name)
            val light = when {
                last != null -> StatusLight.of(last)
                unreachableDeclared -> StatusLight.State.UNVERIFIABLE
                else -> StatusLight.State.UNKNOWN
            }
            val options = r.options.filterKeys { k -> !k.contains("pass") && k != "token" && !k.contains("secret") && !k.contains("key") }.entries.joinToString("  ") { "${it.key}=${it.value}" }
            DriveCard("${r.name}:", badge = if (decl != null) stringResource(R.string.chrome_declared) else null, light = light, summary = "${r.type}  $options", summaryMonospace = true, tag = DriveTags.SYNC_REMOTE_CARD) {
                val note = when {
                    unreachableDeclared && last == null -> decl?.reason
                    results[r.name] != null -> if (last == true) stringResource(R.string.rclone_reachable, results[r.name]?.toIntOrNull() ?: 0) else results[r.name]
                    last == null -> stringResource(R.string.rclone_untested)
                    else -> null
                }
                if (!note.isNullOrBlank()) Text(note, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                PillRow {
                    Pill(stringResource(R.string.rclone_test), { coordinator.testRemote(r) { _, text -> onMessage("${r.name}: $text") } }, filled = true, enabled = available && r.name !in testing)
                    Pill(stringResource(R.string.rclone_browse), { actions.openEngine(EngineActivity.ENGINE_RCLONE, "browse") }, enabled = available)
                    Pill(stringResource(R.string.rclone_edit), { actions.openEngine(EngineActivity.ENGINE_RCLONE, "remotes") })
                }
            }
        }
        item { SectionHeader(stringResource(R.string.rclone_jobs_section), count = jobs.size, action = stringResource(R.string.rclone_new_job)) { actions.openEngine(EngineActivity.ENGINE_RCLONE, "jobs") } }
        if (jobs.isEmpty()) item { EmptyState(Icons.Filled.PlayArrow, stringResource(R.string.rclone_jobs_empty), stringResource(R.string.rclone_jobs_empty_hint)) }
        items(jobs, key = { "job-" + it.id }) { job ->
            val run = runs[job.id]
            val runningNow = run != null && run.exit == null
            Column(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_JOB_ROW).padding(horizontal = DriveMetrics.gutter + 4.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(job.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (job.declared) CapsuleBadge(stringResource(R.string.chrome_declared))
                        }
                        Text("${job.op}  ${job.source}  →  ${job.destination}" + (if (job.flags.isNotEmpty()) "  " + job.flags.joinToString(" ") else ""), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (job.lastRunEpochSeconds > 0) stringResource(R.string.rclone_last_run, fmt.format(Date(job.lastRunEpochSeconds * 1000)), job.lastRunSummary) else stringResource(R.string.rclone_never_run), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (runningNow) Pill(stringResource(R.string.rclone_stop), { coordinator.cancel(job) }, icon = Icons.Filled.Stop)
                    else Pill(stringResource(R.string.rclone_run), { coordinator.run(job) }, icon = Icons.Filled.PlayArrow, filled = true, enabled = available)
                }
                if (run != null) {
                    val s = run.stats
                    if (run.exit == null) LinearProgressIndicator(progress = { s?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    if (s != null) Text(stringResource(R.string.rclone_progress, RcloneOutput.humanBytes(s.bytes), RcloneOutput.humanBytes(s.totalBytes), RcloneOutput.humanBytes(s.speedBytesPerSecond.toLong()), RcloneOutput.humanEta(s.etaSeconds), s.transfers, s.totalTransfers, s.errors), style = MaterialTheme.typography.labelSmall)
                    if (run.exit != null) Text(when (run.exit) { 0 -> stringResource(R.string.rclone_exit_ok); -2 -> stringResource(R.string.rclone_exit_cancelled); else -> stringResource(R.string.rclone_exit_code, run.exit) }, style = MaterialTheme.typography.labelSmall)
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp)) {
                        run.lines.takeLast(6).forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            Hairline(Modifier.padding(horizontal = DriveMetrics.gutter))
        }
        // Fleet-side rclone operations the phone cannot run (kind not an rclone verb, e.g. mount).
        val fleetOnly = Declarations.rcloneJobs.filter { it.kind !in com.diegonmarcos.cloudlib.rclone.RcloneJob.OPS }
        items(fleetOnly, key = { "fleet-" + it.name }) { j ->
            Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_JOB_ROW).padding(horizontal = DriveMetrics.gutter + 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text(j.name, style = MaterialTheme.typography.titleSmall); CapsuleBadge(stringResource(R.string.rclone_fleet_side)) }
                    Text("${j.kind}  ${j.source}  →  ${j.destination}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (j.schedule.isNotBlank()) Text(j.schedule, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                StatusLightRow(StatusLight.State.UNVERIFIABLE, j.name)
            }
            Hairline(Modifier.padding(horizontal = DriveMetrics.gutter))
        }
    }
}

@Composable
fun MountsSyncScreen(coordinator: RcloneCoordinator, prefs: DrivePrefs.Snapshot, actions: DriveActions, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val mounts by coordinator.mounts.collectAsState()
    val testing by coordinator.testing.collectAsState()
    val results by coordinator.testResults.collectAsState()
    LaunchedEffect(Unit) { coordinator.refresh() }
    val mountable = Declarations.connections.filter { it.uri.isNotBlank() && MountType.fromScheme(it.uri.substringBefore("://")) != null }.map { it.name }.toSet()
    val fleet = Declarations.connections.filter { it.name !in mountable }

    LazyColumn(modifier.fillMaxSize()) {
        item { SectionHeader(stringResource(R.string.mounts_section), count = mounts.size, action = stringResource(R.string.mounts_add)) { actions.openEngine(EngineActivity.ENGINE_MOUNTS, "") } }
        if (mounts.isEmpty()) item { EmptyState(Icons.Filled.Lan, stringResource(R.string.mounts_empty), stringResource(R.string.mounts_empty_hint), actionLabel = stringResource(R.string.mounts_open)) { actions.openEngine(EngineActivity.ENGINE_MOUNTS, "") } }
        items(mounts, key = { it.id }) { m ->
            val last = prefs.lastTest("mount:" + m.id)
            val credential = when (coordinator.mountCredentialState(m)) { 2 -> stringResource(R.string.mounts_key_auth); 1 -> stringResource(R.string.mounts_password_stored); else -> stringResource(R.string.mounts_no_credential) }
            DriveCard(m.name, badge = if (m.declared) stringResource(R.string.chrome_declared) else null, light = StatusLight.of(last), summary = m.uri, summaryMonospace = true, tag = DriveTags.SYNC_MOUNT_CARD) {
                Text(m.type.label + " · " + credential + (results[m.id]?.let { r -> " · " + (if (last == true) stringResource(R.string.mounts_reachable, r.toIntOrNull() ?: 0) else r) } ?: ""), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PillRow {
                    Pill(stringResource(R.string.rclone_test), { coordinator.testMount(m) { _, text -> onMessage("${m.name}: $text") } }, filled = true, enabled = m.id !in testing)
                    Pill(stringResource(R.string.rclone_browse), { actions.openEngine(EngineActivity.ENGINE_MOUNTS, m.id) })
                    Pill(stringResource(R.string.rclone_edit), { actions.openEngine(EngineActivity.ENGINE_MOUNTS, "") })
                }
            }
        }
        item { SectionHeader(stringResource(R.string.mounts_fleet_section), count = fleet.size) }
        items(fleet, key = { "conn-" + it.name }) { c ->
            Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_CONNECTION_ROW).padding(horizontal = DriveMetrics.gutter + 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${c.kind} · ${c.endpoint}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val note = if (c.status == "unreachable") c.reason.ifBlank { c.notes } else c.notes
                    if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // The declared status is the fleet's word about the fleet, not a look from this phone: honest grey.
                StatusLightRow(if (c.status == "ok") StatusLight.State.UNVERIFIABLE else StatusLight.State.OFF, c.name)
            }
            Hairline(Modifier.padding(horizontal = DriveMetrics.gutter))
        }
    }
}
