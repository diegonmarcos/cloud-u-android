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
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
 * #579 the declared-connection rows and the Volumes ▸ Cloud-Containers mount cards, in the same card language as Git
 * (cloud-drive-redesign.md §5). #1173 moved the Rsync and Mounts PAGES to FsyncPage.kt and MountsPage.kt (real state only);
 * what stays here is what Volumes draws from the fleet's declared connections.
 */
@Composable
fun MountsSyncScreen(
    coordinator: RcloneCoordinator,
    prefs: DrivePrefs,
    actions: DriveActions,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    // #604 WHICH declared connections this instance draws. Configs ▸ Mounts draws them all;
    // Volumes ▸ Cloud-Containers passes the container ones, because Cloud-Machines draws
    // the host-level rest. One screen, one declaration, two scopes — never a second list.
    connections: List<Declarations.ConnectionDecl> = Declarations.connections,
) {
    @Suppress("UNUSED_VARIABLE") val prefsTick by prefs.snapshot.collectAsState()
    val mounts by coordinator.mounts.collectAsState()
    val testing by coordinator.testing.collectAsState()
    val results by coordinator.testResults.collectAsState()
    LaunchedEffect(Unit) { coordinator.refresh() }
    val mountable = connections.filter { it.uri.isNotBlank() && MountType.fromScheme(it.uri.substringBefore("://")) != null }.map { it.name }.toSet()
    val fleet = connections.filter { it.name !in mountable }

    LazyColumn(modifier.fillMaxSize()) {
        item { SectionHeader(stringResource(R.string.mounts_section), count = mounts.size, action = stringResource(R.string.mounts_add)) { actions.openEngine(EngineActivity.ENGINE_MOUNTS, "") } }
        if (mounts.isEmpty()) item { EmptyState(Icons.Filled.Lan, stringResource(R.string.mounts_empty), stringResource(R.string.mounts_empty_hint), actionLabel = stringResource(R.string.mounts_open)) { actions.openEngine(EngineActivity.ENGINE_MOUNTS, "") } }
        items(mounts, key = { it.id }) { m ->
            val last = prefs.lastTest("mount:" + m.id)
            val credential = when (coordinator.mountCredentialState(m)) { 2 -> stringResource(R.string.mounts_key_auth); 1 -> stringResource(R.string.mounts_password_stored); else -> stringResource(R.string.mounts_no_credential) }
            DriveCard(m.name, badge = if (m.declared) stringResource(R.string.chrome_declared) else null, light = StatusLight.of(last), summary = m.uri, summaryMonospace = true, tag = DriveTags.SYNC_MOUNT_CARD) {
                Text(m.type.label + " · " + credential + (results[m.id]?.let { r -> " · " + (if (last == true) stringResource(R.string.mounts_reachable, r.toIntOrNull() ?: 0) else r) } ?: ""), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PillRow {
                    Pill(stringResource(R.string.rclone_test), { coordinator.testMount(m) { _, text -> onMessage("${m.name}: $text") } }, filled = true, enabled = m.id !in testing)
                    Pill(stringResource(R.string.rclone_browse), { actions.openEngine(EngineActivity.ENGINE_MOUNTS, m.id) })
                    Pill(stringResource(R.string.rclone_edit), { actions.openEngine(EngineActivity.ENGINE_MOUNTS, "") })
                }
            }
        }
        item { SectionHeader(stringResource(R.string.mounts_fleet_section), count = fleet.size) }
        items(fleet, key = { "conn-" + it.name }) { c -> ConnectionRow(c) }
    }
}

/**
 * #604 ONE declared connection as a row. Extracted so Volumes ▸ Cloud-Machines draws the
 * host-level connections in the SAME language as Configs ▸ Mounts draws the container ones.
 */
@Composable
fun ConnectionRow(c: Declarations.ConnectionDecl) {
    Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_CONNECTION_ROW).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) {
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
