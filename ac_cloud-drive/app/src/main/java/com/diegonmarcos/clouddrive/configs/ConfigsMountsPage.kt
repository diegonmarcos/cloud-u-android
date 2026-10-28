package com.diegonmarcos.clouddrive.configs

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.storage.MountProviders
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.sync.RcloneCoordinator
import com.diegonmarcos.clouddrive.sync.RemoteWizard
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight

/**
 * #1216 CONFIGS ▸ MOUNTS: the rclone setup, one line per provider of ui.sync.mounts.providers (Cloudflare R2, Cloudflare IA, Oracle S3,
 * Hetzner S3, GitHub LFS) and the free Others line last. A line shows the remote name the mount needs, whether this phone's rclone.conf
 * has it and of the right backend, and Set up / Edit, which open the same wizard Mounts > Rclone uses (new remotes start from the line's
 * backend, name and public options). No key is typed into or stored by this file: the wizard writes rclone.conf and Cloud Account owns the rest.
 * The mounts themselves are on Mounts > FUSE.
 */
@Composable
fun ConfigsMountsPage(storage: StorageCoordinator, rclone: RcloneCoordinator, prefs: DrivePrefs, modifier: Modifier = Modifier) {
    androidx.compose.runtime.LaunchedEffect(Unit) { rclone.refresh() }
    val remotes by rclone.remotes.collectAsState()
    val types = remotes.associate { it.name to it.type }
    val lines = Declarations.mountProviders
    val claimed = lines.map { it.remote }.toSet()
    var wizard by remember { mutableStateOf<MountWizard?>(null) }
    LazyColumn(modifier.fillMaxSize()) {
        item { SectionHeader(stringResource(R.string.configs_mounts_providers), count = lines.size) }
        if (lines.isEmpty()) item { Text(stringResource(R.string.configs_mounts_none), Modifier.padding(horizontal = DriveMetrics.sectionInset), style = MaterialTheme.typography.bodySmall) }
        items(lines, key = { "cfg-prov-" + it.id }) { d ->
            if (d.others) {
                val extra = remotes.filter { it.name !in claimed }
                DriveCard(d.label, light = StatusLight.State.UNKNOWN, summary = stringResource(R.string.configs_mounts_others, extra.size), tag = DriveTags.CONFIGS_CARD) {
                    if (extra.isNotEmpty()) Text(extra.joinToString("  ") { it.name + ":" }, Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall)
                    PillRow { Pill(stringResource(R.string.remote_add), { wizard = MountWizard(null, null, null, emptyMap()) }, filled = true) }
                }
            } else {
                val type = types[d.remote]
                val ok = type != null && (d.rcloneType.isBlank() || type == d.rcloneType)
                DriveCard(
                    d.label, badge = d.rcloneType.ifBlank { null },
                    light = if (type == null) StatusLight.State.OFF else if (ok) StatusLight.State.ON else StatusLight.State.UNKNOWN,
                    summary = d.remote + ":" + d.path + if (d.endpoint.isNotBlank()) "  ·  " + d.endpoint else "",
                    summaryMonospace = true, tag = DriveTags.CONFIGS_CARD,
                ) {
                    Text(
                        when {
                            type == null -> stringResource(R.string.fuse_provider_missing, d.remote)
                            !ok -> stringResource(R.string.fuse_provider_wrong_type, d.remote, d.rcloneType)
                            else -> stringResource(R.string.configs_mounts_configured)
                        },
                        Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PillRow {
                        if (type == null) Pill(stringResource(R.string.configs_mounts_setup), { wizard = MountWizard(null, d.rcloneType, d.remote, MountProviders.wizardPreset(d)) }, filled = true)
                        else Pill(stringResource(R.string.rclone_edit), { wizard = MountWizard(d.remote, null, null, emptyMap()) })
                    }
                }
            }
        }
    }
    wizard?.let { w -> RemoteWizard(storage, w.editing, presetType = w.type, presetName = w.name, presetValues = w.values) { wizard = null; rclone.refresh() } }
}

private data class MountWizard(val editing: String?, val type: String?, val name: String?, val values: Map<String, String>)
