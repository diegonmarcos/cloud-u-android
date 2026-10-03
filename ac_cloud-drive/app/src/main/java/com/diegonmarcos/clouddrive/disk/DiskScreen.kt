package com.diegonmarcos.clouddrive.disk

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IslandAction
import com.diegonmarcos.clouddrive.ui.LoadingState
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StorageBar
import com.diegonmarcos.clouddrive.ui.ToolbarIsland
import com.diegonmarcos.cloudlib.disk.AppSizes
import com.diegonmarcos.cloudlib.disk.DiskEngine
import com.diegonmarcos.cloudlib.disk.Memory
import com.diegonmarcos.cloudlib.disk.Volumes
import com.diegonmarcos.cloudlib.diskscan.Duplicates
import com.diegonmarcos.cloudlib.diskscan.Entry
import com.diegonmarcos.cloudlib.diskscan.HugeFiles
import com.diegonmarcos.cloudlib.diskscan.StorageMap
import com.diegonmarcos.cloudlib.diskscan.Tree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #813 HOME ▸ DISK MANAGEMENT. Renders libs:disk's engine ([DriveDisk.engine], the SAME instance
 * /api/disk/* answers from) in six sections: Storage (volumes + the map by top-level folder),
 * Huge files (threshold presets, open / share / delete), Duplicates (keep one), Apps (APK / data /
 * cache per fleet app, with the usage-access grant as a BUTTON — #639, never a sentence telling the
 * user where to go), Clean (a dry run first; the run deletes exactly the previewed plan) and Memory.
 * Every destructive action asks first and then reports the bytes really reclaimed.
 */
@Composable
fun DiskScreen(actions: DriveActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val engine = remember { DriveDisk.engine(ctx) }
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(SECTIONS.first()) }
    var hasFiles by remember { mutableStateOf(Places.hasAllFilesAccess(ctx)) }
    var hasUsage by remember { mutableStateOf(AppSizes.hasUsageAccess(ctx)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { hasFiles = Places.hasAllFilesAccess(ctx); hasUsage = AppSizes.hasUsageAccess(ctx) } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    BackHandler(onBack = onClose)

    var tree by remember { mutableStateOf<Tree?>(null) }
    var volumes by remember { mutableStateOf(emptyList<Volumes.Volume>()) }
    var dups by remember { mutableStateOf<List<Duplicates.Group>?>(null) }
    var apps by remember { mutableStateOf<List<AppSizes.Size>?>(null) }
    var preview by remember { mutableStateOf<DiskEngine.CleanPreview?>(null) }
    var memory by remember { mutableStateOf<Memory.Snapshot?>(null) }
    var threshold by rememberSaveable { mutableStateOf(HugeFiles.DEFAULT_THRESHOLD) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var tick by remember { mutableStateOf(0) }

    fun reload(rescan: Boolean) { if (rescan) { tree = null; dups = null; preview = null }; tick++ }

    LaunchedEffect(tick, section, hasFiles, hasUsage) {
        withContext(Dispatchers.IO) {
            if (volumes.isEmpty() || section == SECTION_MAP) volumes = Volumes.list(ctx)
            if (hasFiles && tree == null && section in TREE_SECTIONS) tree = if (tick > 0) engine.rescan() else engine.tree()
            when (section) {
                SECTION_DUPS -> if (hasFiles && dups == null) dups = engine.duplicates()
                SECTION_APPS -> apps = AppSizes.sizes(ctx, (com.diegonmarcos.clouddrive.Declarations.constellation.map { it.packageName } + ctx.packageName).filter { it.isNotBlank() })
                SECTION_CLEAN -> if (preview == null) preview = engine.cleanPreview()
                SECTION_MEMORY -> memory = Memory.snapshot(ctx, com.diegonmarcos.clouddrive.Declarations.constellation.map { it.packageName }.filter { it.isNotBlank() })
            }
        }
    }

    fun destroy(paths: List<String>) {
        scope.launch {
            val res = withContext(Dispatchers.IO) { engine.delete(paths) }
            message = ctx.getString(R.string.disk_reclaimed, FileOps.humanBytes(res.reclaimed))
            reload(rescan = true)
        }
    }

    Column(modifier.fillMaxSize().testTag(DriveTags.DISK_SCREEN)) {
        ToolbarIsland(
            title = stringResource(R.string.disk_title),
            subtitle = message ?: stringResource(R.string.disk_hint),
            leading = { IslandAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.disk_back), onClick = onClose) },
            actions = { IslandAction(Icons.Filled.Refresh, stringResource(R.string.disk_rescan)) { reload(rescan = true) } },
        )
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter)) {
            items(SECTIONS) { id -> Pill(stringResource(labelOf(id)), { section = id }, filled = id == section, modifier = Modifier.padding(end = DriveMetrics.gap).testTag(DriveTags.DISK_SECTION)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (section in TREE_SECTIONS && !hasFiles) {
                item {
                    DriveCard(stringResource(R.string.disk_no_access)) {
                        PillRow { Pill(stringResource(R.string.disk_grant_files), { actions.requestStorageAccess() }, filled = true) }
                    }
                }
            }
            when (section) {
                SECTION_MAP -> mapSection(volumes, tree, hasFiles)
                SECTION_HUGE -> {
                    item {
                        PillRow(Modifier.padding(horizontal = DriveMetrics.gutter)) {
                            THRESHOLDS.forEach { t -> Pill(FileOps.humanBytes(t), { threshold = t }, filled = t == threshold) }
                        }
                    }
                    val t = tree
                    if (hasFiles && t == null) item { LoadingState() }
                    if (t != null) {
                        val huge = HugeFiles.find(t.entries, threshold, HugeFiles.DEFAULT_TOP)
                        item { SectionHeader(stringResource(R.string.disk_threshold, FileOps.humanBytes(threshold)), count = huge.size) }
                        if (huge.isEmpty()) item { Text(stringResource(R.string.disk_none_huge, FileOps.humanBytes(threshold)), Modifier.padding(DriveMetrics.gutter)) }
                        items(huge, key = { it.path }) { e -> HugeRow(e, actions) { confirm = Confirm.Delete(e) } }
                    }
                }
                SECTION_DUPS -> {
                    val d = dups
                    if (hasFiles && d == null) item { LoadingState() }
                    if (d != null && d.isEmpty()) item { Text(stringResource(R.string.disk_dups_none), Modifier.padding(DriveMetrics.gutter)) }
                    if (d != null) items(d, key = { it.hash + it.bytes }) { g ->
                        DriveCard(stringResource(R.string.disk_dups_group, g.paths.size, FileOps.humanBytes(g.bytes), FileOps.humanBytes(g.reclaimable))) {
                            g.paths.forEach { p ->
                                Text(p, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                PillRow { Pill(stringResource(R.string.disk_keep), { confirm = Confirm.KeepOne(g, p) }) }
                            }
                        }
                    }
                }
                SECTION_APPS -> {
                    if (!hasUsage) item {
                        DriveCard(stringResource(R.string.disk_apps_need_access)) {
                            PillRow { Pill(stringResource(R.string.disk_grant_usage), { grantUsage(ctx) }, filled = true) }
                        }
                    }
                    val a = apps
                    if (a == null) item { LoadingState() } else items(a, key = { it.pkg }) { s ->
                        DriveCard(s.pkg, summary = s.error ?: stringResource(R.string.disk_app_sizes, FileOps.humanBytes(s.apkBytes), FileOps.humanBytes(s.dataBytes), FileOps.humanBytes(s.cacheBytes)), badge = if (s.error == null) FileOps.humanBytes(s.totalBytes) else null) {
                            if (s.pkg != ctx.packageName) PillRow { Pill(stringResource(R.string.disk_app_info), { openAppInfo(ctx, s.pkg) }) }
                        }
                    }
                }
                SECTION_CLEAN -> {
                    val p = preview
                    if (p == null) item { LoadingState() } else {
                        item {
                            DriveCard(stringResource(R.string.disk_clean_preview, FileOps.humanBytes(p.bytes), p.plan.items.size)) {
                                (p.plan.bySource() + p.owned).forEach { (src, bytes) -> Text(stringResource(R.string.disk_clean_source, src, FileOps.humanBytes(bytes)), style = MaterialTheme.typography.bodySmall) }
                                PillRow { Pill(stringResource(R.string.disk_clean_run), { confirm = Confirm.Clean(p) }, filled = true, enabled = p.bytes > 0) }
                            }
                        }
                        item { Text(stringResource(R.string.disk_clean_others), Modifier.padding(DriveMetrics.gutter), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                SECTION_MEMORY -> {
                    val m = memory
                    if (m == null) item { LoadingState() } else {
                        item {
                            DriveCard(stringResource(R.string.disk_section_memory), summary = if (m.low) stringResource(R.string.disk_memory_low) else null) {
                                StorageBar(m.availableBytes to m.totalBytes, stringResource(R.string.disk_memory_summary, FileOps.humanBytes(m.availableBytes), FileOps.humanBytes(m.totalBytes)))
                            }
                        }
                        items(m.processes, key = { it.pkg + (it.process ?: "") }) { pr ->
                            Text(
                                if (pr.readable) stringResource(R.string.disk_memory_proc, pr.process ?: pr.pkg, FileOps.humanBytes((pr.pssKb ?: 0) * 1024L))
                                else stringResource(R.string.disk_memory_unreadable, pr.pkg),
                                Modifier.padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    when (val c = confirm) {
        is Confirm.Delete -> ConfirmDialog(stringResource(R.string.disk_delete_title), stringResource(R.string.disk_delete_body, c.entry.path, FileOps.humanBytes(c.entry.bytes)), { confirm = null }) {
            confirm = null; destroy(listOf(c.entry.path))
        }
        is Confirm.KeepOne -> {
            val others = Duplicates.keepOne(c.group, c.keep)
            ConfirmDialog(stringResource(R.string.disk_keep_title), stringResource(R.string.disk_keep_body, c.keep, others.size, FileOps.humanBytes(c.group.reclaimable)), { confirm = null }) {
                confirm = null; destroy(others)
            }
        }
        is Confirm.Clean -> ConfirmDialog(stringResource(R.string.disk_clean_title), stringResource(R.string.disk_clean_body, FileOps.humanBytes(c.preview.bytes)), { confirm = null }) {
            confirm = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { engine.clean(c.preview) }
                message = ctx.getString(R.string.disk_reclaimed, FileOps.humanBytes(r.reclaimed))
                reload(rescan = true)
            }
        }
        null -> {}
    }
}

private fun LazyListScope.mapSection(volumes: List<Volumes.Volume>, tree: Tree?, hasFiles: Boolean) {
    item { SectionHeader(stringResource(R.string.disk_volumes), count = volumes.size) }
    items(volumes, key = { it.id }) { v ->
        DriveCard(v.label, summary = v.path, summaryMonospace = true) {
            StorageBar(v.freeBytes to v.totalBytes, stringResource(R.string.disk_volume_bar, FileOps.humanBytes(v.usedBytes), FileOps.humanBytes(v.freeBytes), FileOps.humanBytes(v.totalBytes)))
        }
    }
    if (hasFiles && tree == null) item { LoadingState() }
    if (tree != null) {
        val slices = StorageMap.slices(tree)
        item { SectionHeader(stringResource(R.string.disk_map_header), count = slices.size) }
        if (tree.truncated) item { Text(stringResource(R.string.disk_truncated), Modifier.padding(DriveMetrics.gutter), style = MaterialTheme.typography.bodySmall) }
        items(slices, key = { it.name }) { s ->
            DriveCard(s.name, summary = stringResource(R.string.disk_map_summary, FileOps.humanBytes(s.bytes), s.files)) {
                StorageBar((tree.bytes - s.bytes) to tree.bytes, FileOps.humanBytes(s.bytes))
            }
        }
    }
}

@Composable
private fun HugeRow(e: Entry, actions: DriveActions, onDelete: () -> Unit) {
    DriveCard(e.path.substringAfterLast('/'), summary = e.path, summaryMonospace = true, badge = FileOps.humanBytes(e.bytes)) {
        PillRow {
            Pill(stringResource(R.string.disk_open), { actions.openWith(e.path) })
            Pill(stringResource(R.string.disk_share), { actions.share(listOf(e.path)) })
            Pill(stringResource(R.string.disk_delete), onDelete)
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.disk_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.disk_cancel)) } },
    )
}

private sealed class Confirm {
    class Delete(val entry: Entry) : Confirm()
    class KeepOne(val group: Duplicates.Group, val keep: String) : Confirm()
    class Clean(val preview: DiskEngine.CleanPreview) : Confirm()
}

/** #639 the per-package usage-access toggle first, the system list if no activity claims it. */
private fun grantUsage(ctx: android.content.Context) {
    for (i in AppSizes.grantIntents(ctx)) if (runCatching { ctx.startActivity(i) }.isSuccess) return
}

private fun openAppInfo(ctx: android.content.Context, pkg: String) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private const val SECTION_MAP = "map"
private const val SECTION_HUGE = "huge"
private const val SECTION_DUPS = "duplicates"
private const val SECTION_APPS = "apps"
private const val SECTION_CLEAN = "clean"
private const val SECTION_MEMORY = "memory"
private val SECTIONS = listOf(SECTION_MAP, SECTION_HUGE, SECTION_DUPS, SECTION_APPS, SECTION_CLEAN, SECTION_MEMORY)
private val TREE_SECTIONS = setOf(SECTION_MAP, SECTION_HUGE, SECTION_DUPS)
private val THRESHOLDS = listOf(50L shl 20, 100L shl 20, 500L shl 20, 1L shl 30)

private fun labelOf(id: String): Int = when (id) {
    SECTION_MAP -> R.string.disk_section_map
    SECTION_HUGE -> R.string.disk_section_huge
    SECTION_DUPS -> R.string.disk_section_dups
    SECTION_APPS -> R.string.disk_section_apps
    SECTION_CLEAN -> R.string.disk_section_clean
    else -> R.string.disk_section_memory
}
