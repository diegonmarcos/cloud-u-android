package com.diegonmarcos.clouddrive.volumes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.sync.ConnectionRow
import com.diegonmarcos.clouddrive.sync.RcloneCoordinator
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.cloudlib.rclone.RcloneEntry
import com.diegonmarcos.cloudlib.rclone.RcloneOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #604 the three classes of the Volumes tab that are not already a screen — Cloud-Constellation,
 * Cloud-Machines and S3. Cloud-Containers is [com.diegonmarcos.clouddrive.sync.MountsSyncScreen]
 * scoped to the container connections, unchanged: this file adds no second mounts engine and no
 * second connection list, it reads the SAME declarations.
 */

/**
 * CLASS A — FLEET VOLUMES: the on-device volumes of our same-signature fleet apps. Every
 * `kind: app` entry of the constellation fleet manifest gets a row (#630 — the list is DERIVED
 * at build time, not hand-picked, which is why four apps used to be the whole section).
 *
 * #630 WHAT A ROW OPENS, AND WHAT IT CANNOT. A row used to address
 * `/storage/emulated/0/Android/data/<package>/files` and its Open did nothing, because that tree
 * is readable ONLY by its owning app since API 30: MANAGE_EXTERNAL_STORAGE is explicitly carved
 * out of Android/data and Android/obb, and Android 13+ closes the SAF/DocumentsProvider route as
 * well. The device is API 35. That is the OS refusing correctly and no code can get past it, so
 * the row STATES it — in plain words, naming the app and the API level from
 * ui.volumes.constellation_owner_only — and offers the two things that DO work: the app's folder
 * inside the ONE shared store (a location the fleet controls and any app with all-files access
 * can read), and a handoff, "Open in <app>", which launches the owner to read its own directory.
 * No Open affordance is ever rendered onto the owner-only path.
 */
@Composable
fun ConstellationVolumes(actions: DriveActions, onOpenPath: (String) -> Unit, say: (String) -> Unit, modifier: Modifier = Modifier) {
    val apps = Declarations.constellation
    val ownerOnly = Declarations.volumes.ownerOnly
    LazyColumn(modifier.fillMaxSize()) {
        item { SectionHeader(stringResource(R.string.volumes_constellation_section), count = apps.size) }
        if (apps.isEmpty()) item { EmptyState(Icons.Filled.Folder, stringResource(R.string.volumes_class_empty), stringResource(R.string.volumes_constellation_hint)) }
        items(apps, key = { "fleet-" + it.packageName }) { app ->
            val ctx = LocalContext.current
            // The declared leg is RELATIVE to the store; SharedStore resolves it, so the absolute
            // path is still written down in exactly one place (#575).
            val path = SharedStore.resolve(Declarations.volumes.constellationPathOf(app.packageName))
            val reachable = path.isNotBlank() && !path.startsWith("<")
            DriveCard(
                app.label,
                light = StatusLight.of(reachable),
                summary = path,
                summaryMonospace = true,
                tag = DriveTags.VOLUMES_CARD,
            ) {
                Text(stringResource(R.string.volumes_store_folder), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The platform wall, said out loud. A row never renders "Not verifiable" as a
                // mystery and never offers an Open onto a path the OS guarantees will fail.
                if (ownerOnly != null && !ownerOnly.openable) {
                    Text(
                        stringResource(R.string.volumes_owner_only, ownerOnly.blockedSinceSdk, app.label, Declarations.volumes.ownerOnlyPathOf(app.packageName)),
                        Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.VOLUMES_OWNER_ONLY),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PillRow {
                    Pill(stringResource(R.string.volumes_open), { if (reachable) onOpenPath(path) }, filled = true, enabled = reachable)
                    if (ownerOnly?.handoff == true) Pill(stringResource(R.string.volumes_open_in_app, app.label), {
                        if (!actions.launchApp(app.packageName, "")) say(ctx.getString(R.string.volumes_open_in_app_missing, app.label))
                    })
                }
            }
        }
    }
}

/**
 * CLASS C — VMs, PCs and phones, grouped by the declared machine kinds. The rows are the SAME
 * [ConnectionRow] Configs ▸ Mounts draws, filtered to the host-level entries; a kind with
 * nothing declared renders an honest empty group rather than disappearing.
 */
@Composable
fun MachineVolumes(modifier: Modifier = Modifier) {
    val kinds = Declarations.volumes.machineKinds
    val byKind = Declarations.connections.filter { it.machine != Declarations.MACHINE_CONTAINER }.groupBy { it.machine }
    LazyColumn(modifier.fillMaxSize()) {
        kinds.forEach { kind ->
            val group = byKind[kind].orEmpty()
            item(key = "kind-$kind") { SectionHeader(kind, count = group.size) }
            if (group.isEmpty()) item(key = "kind-empty-$kind") { EmptyState(Icons.Filled.Computer, stringResource(R.string.volumes_class_empty), stringResource(R.string.volumes_machines_hint)) }
            items(group, key = { "machine-" + it.name }) { c -> ConnectionRow(c) }
        }
        // A declared connection whose machine kind is not one of the declared kinds would
        // otherwise vanish: show it rather than lose it (the #292 rule — never an empty box).
        val stray = byKind.filterKeys { it !in kinds }.values.flatten()
        if (stray.isNotEmpty()) {
            item(key = "kind-other") { SectionHeader(stringResource(R.string.volumes_machines_other), count = stray.size) }
            items(stray, key = { "stray-" + it.name }) { c -> ConnectionRow(c) }
        }
    }
}

/**
 * CLASS D — the OCI S3 buckets and Google Drive, through libs:rclone. The remotes are the
 * declared ones whose type build.json::ui.volumes.s3_remote_types names; a tap drills into the
 * remote with `rclone lsjson` (RcloneRunner.lsjson), the engine's own listing, not a new client.
 */
@Composable
fun S3Volumes(coordinator: RcloneCoordinator, modifier: Modifier = Modifier) {
    val remotes = Declarations.remotes.filter { it.type in Declarations.volumes.s3RemoteTypes }
    var open by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RcloneEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val available = coordinator.runner.isAvailable
    LaunchedEffect(open, path) {
        val remote = open ?: return@LaunchedEffect
        loading = true; error = null
        val res = withContext(Dispatchers.IO) { coordinator.runner.lsjson("$remote:$path") }
        loading = false
        entries = res.getOrDefault(emptyList())
        error = res.exceptionOrNull()?.message
    }

    LazyColumn(modifier.fillMaxSize()) {
        item { SectionHeader(stringResource(R.string.volumes_s3_section), count = remotes.size) }
        if (remotes.isEmpty()) item { EmptyState(Icons.Filled.Cloud, stringResource(R.string.volumes_class_empty), stringResource(R.string.volumes_s3_hint)) }
        items(remotes, key = { "s3-" + it.name }) { r ->
            val opened = open == r.name
            val note = Modifier.padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gapWide)
            Column(Modifier.fillMaxWidth()) {
                DriveCard(
                    r.name,
                    badge = stringResource(R.string.chrome_declared),
                    light = if (r.status == "unreachable") StatusLight.State.UNVERIFIABLE else StatusLight.State.UNKNOWN,
                    summary = if (opened) "$path/" else r.endpoint,
                    summaryMonospace = true,
                    tag = DriveTags.VOLUMES_CARD,
                ) {
                    if (r.status == "unreachable" && r.reason.isNotBlank()) Text(r.reason, Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    PillRow {
                        Pill(stringResource(if (opened) R.string.volumes_close else R.string.rclone_browse), {
                            if (opened) { open = null; entries = emptyList() } else { open = r.name; path = "" }
                        }, filled = !opened, enabled = available)
                        if (opened && path.isNotBlank()) Pill(stringResource(R.string.volumes_up), { path = path.substringBeforeLast('/', "") })
                    }
                }
                if (opened) {
                    if (loading) Text(stringResource(R.string.volumes_loading), note, style = MaterialTheme.typography.labelSmall)
                    error?.let { Text(it, note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    if (!loading && error == null && entries.isEmpty()) Text(stringResource(R.string.volumes_folder_empty), note, style = MaterialTheme.typography.labelSmall)
                    entries.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().testTag(DriveTags.VOLUMES_ENTRY_ROW)
                                .clickable(enabled = e.isDir) { path = if (path.isBlank()) e.path else path + "/" + e.name }
                                .padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.pad),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(IconCatalog.forEntry(e.isDir, e.mimeType, e.name.substringAfterLast('.', ""), false), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f).padding(start = DriveMetrics.padWide)) {
                                Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!e.isDir) Text(RcloneOutput.humanBytes(e.size), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Hairline(Modifier.padding(horizontal = DriveMetrics.gutter))
                    }
                }
            }
        }
    }
}
