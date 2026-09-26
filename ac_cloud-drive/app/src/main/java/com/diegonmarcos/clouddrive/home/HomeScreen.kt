package com.diegonmarcos.clouddrive.home

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import com.diegonmarcos.clouddrive.sync.SyncEvent
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.StatusLightRow
import com.diegonmarcos.clouddrive.ui.StorageBar
import com.diegonmarcos.clouddrive.ui.ToolbarIsland
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #603 HOME — the drive's overview, made only of primitives that already exist: the two
 * DECLARED Files sections (ui.files.sections) as cards with a live [StorageBar], the
 * volumes/mesh glance as [StatusLightRow]s over the declared connections, the most recent
 * sync events the git engine already persisted (SyncHistory), and quick actions as [Pill]s.
 * Nothing here is a new mechanism and nothing here is a second source of a number.
 *
 * Every caption is a string resource and every card's name comes from the declaration, so
 * this page has no list of its own to drift. The quick actions are CALLBACKS: the tab ids
 * they select live in MainActivity's dispatch, the one place Kotlin names them.
 */
@Composable
fun HomeScreen(
    git: GitSyncCoordinator,
    prefs: DrivePrefs,
    onOpenFiles: () -> Unit,
    onOpenVolumes: () -> Unit,
    onOpenConfigs: () -> Unit,
    onSyncAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val snap by prefs.snapshot.collectAsState()
    val sections = Declarations.files.sections
    val connections = Declarations.connections
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    var events by remember { mutableStateOf(emptyList<SyncEvent>()) }
    var usage by remember { mutableStateOf(emptyMap<String, Pair<Long, Long>>()) }
    LaunchedEffect(Unit) {
        // Both are disk reads: never on the frame's thread.
        events = withContext(Dispatchers.IO) { runCatching { git.history.load() }.getOrDefault(emptyList()) }
        usage = withContext(Dispatchers.IO) {
            sections.mapNotNull { s -> statOf(dirOf(s.id))?.let { s.id to it } }.toMap()
        }
    }
    val volumes = remember(snap) { Places.discovered(ctx, snap, Places.hasAllFilesAccess(ctx)).filter { it.kind == Places.Kind.PATH } }

    Column(modifier.fillMaxSize()) {
        ToolbarIsland(title = stringResource(R.string.home_title), subtitle = stringResource(R.string.home_hint))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item { SectionHeader(stringResource(R.string.home_stores_section), count = sections.size) }
            items(sections.size) { i ->
                val s = sections[i]
                val dir = dirOf(s.id)
                DriveCard(s.label, light = StatusLight.of(dir?.isDirectory), summary = dir?.absolutePath, summaryMonospace = true, tag = DriveTags.HOME_CARD) {
                    usage[s.id]?.let { u ->
                        StorageBar(u, stringResource(R.string.files_storage_bar, FileOps.humanBytes(u.first), FileOps.humanBytes(u.second)), tag = DriveTags.HOME_STORAGE_BAR)
                    }
                    PillRow { Pill(stringResource(R.string.home_open_files), onOpenFiles, filled = true) }
                }
            }
            item { SectionHeader(stringResource(R.string.home_volumes_section), count = volumes.size + connections.size) }
            item {
                DriveCard(stringResource(R.string.home_volumes_card), summary = stringResource(R.string.home_volumes_summary, volumes.size, connections.size), tag = DriveTags.HOME_CARD) {
                    connections.take(HOME_GLANCE_ROWS).forEach { c ->
                        // The declared status is the fleet's word about the fleet, not a look from this phone: honest grey.
                        StatusLightRow(if (c.status == "ok") StatusLight.State.UNVERIFIABLE else StatusLight.State.OFF, c.name)
                        Text(c.name + " · " + c.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PillRow { Pill(stringResource(R.string.home_open_volumes), onOpenVolumes, filled = true) }
                }
            }
            item { SectionHeader(stringResource(R.string.home_history_section), count = events.size) }
            item {
                DriveCard(stringResource(R.string.home_history_card), light = if (events.isEmpty()) StatusLight.State.UNKNOWN else StatusLight.of(events.first().ok), tag = DriveTags.HOME_CARD) {
                    if (events.isEmpty()) {
                        Text(stringResource(R.string.sync_never), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        events.take(HOME_GLANCE_ROWS).forEach { e ->
                            Text(
                                fmt.format(Date(e.epochSeconds * 1000)) + " · " + e.repoName + " · " + e.summary,
                                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    PillRow {
                        Pill(stringResource(R.string.home_sync_now), onSyncAll, filled = true)
                        Pill(stringResource(R.string.home_open_configs), onOpenConfigs)
                    }
                }
            }
        }
    }
}

private const val HOME_GLANCE_ROWS = 3

/** The folder a declared Files section stands for; null when this device has no such folder. */
private fun dirOf(sectionId: String): File? {
    val place = Declarations.files.places.firstOrNull { it.section == sectionId && (it.kind == "shared_root" || it.kind == "external_root") } ?: return null
    return if (place.kind == "shared_root") SharedStore.root() else Environment.getExternalStorageDirectory()
}

private fun statOf(dir: File?): Pair<Long, Long>? =
    if (dir == null) null else runCatching { StatFs(dir.absolutePath).let { it.availableBytes to it.totalBytes } }.getOrNull()
