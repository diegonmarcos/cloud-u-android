package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.StatusDot
import java.text.DateFormat

/**
 * #1254 the declared depth commands' row (scope all) and, at its end, #1258's Activity Logs button: the second row of the
 * page's top bar, under the bulk bar. Cancel shows while a depth command runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DepthBar(actions: List<Declarations.GitDepthActionDecl>, running: Boolean, onAction: (Declarations.GitDepthActionDecl) -> Unit, onCancel: () -> Unit, onActivity: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_DEPTH_BAR),
        horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
        actions.forEach { a -> Pill(a.label, { onAction(a) }, icon = IconCatalog.vectorOrDefault(a.icon), enabled = !running) }
        if (running) Pill(stringResource(R.string.repo_depth_cancel), onCancel, danger = true)
        Pill(stringResource(R.string.sync_history), onActivity, modifier = Modifier.testTag(DriveTags.SYNC_GIT_ACTIVITY_BUTTON), icon = IconCatalog.vectorOrDefault("history"))
    }
}

/** The one-line progress of the depth command; a tap opens the full report (skipped and failed repositories with why). */
@Composable
internal fun DepthProgress(r: BulkDepth.Run, onOpen: () -> Unit) {
    val text = stringResource(
        if (r.finished) (if (r.cancelled) R.string.repo_depth_cancelled else R.string.repo_depth_done) else R.string.repo_depth_progress,
        r.actionId, r.done, r.total, r.ok.size, r.skipped.size, r.failed.size,
    )
    Text(text, Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.tight).testTag(DriveTags.SYNC_GIT_DEPTH_PROGRESS),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

/** #1254 the status window of a depth command: every repository's outcome, skipped (dirty / ahead / already cloned) and failed first. */
@Composable
internal fun DepthReportWindow(r: BulkDepth.Run, onCancel: () -> Unit, onClose: () -> Unit) {
    FullWindow(stringResource(R.string.repo_depth_report), DriveTags.SYNC_GIT_DEPTH_REPORT, onClose) {
        Text(stringResource(if (r.finished) (if (r.cancelled) R.string.repo_depth_cancelled else R.string.repo_depth_done) else R.string.repo_depth_progress,
            r.actionId, r.done, r.total, r.ok.size, r.skipped.size, r.failed.size), style = MaterialTheme.typography.bodyMedium)
        if (!r.finished) Pill(stringResource(R.string.repo_depth_cancel), onCancel, danger = true)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(r.lines()) { l -> Text(l, Modifier.padding(vertical = DriveMetrics.tight), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }
    }
}

/** #1256 THE FILTER ROW: one labelled group per declared filter, its options as chips (the selected one filled). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepoFilterRow(filters: List<Declarations.GitFilterDecl>, selection: Map<String, String>, onSelect: (String, String) -> Unit) {
    if (filters.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_FILTER_ROW), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
        Text(stringResource(R.string.repo_filters_title), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
            filters.forEach { f ->
                val cur = selection[f.id] ?: RepoFilters.ALL
                Text(f.label, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelSmall)
                f.options.forEach { o -> Pill(o.label, { onSelect(f.id, o.id) }, filled = o.id == cur) }
            }
        }
    }
}

/** #1255 UNLISTED GIT FOLDERS: path, remote (or none) and the same three status icons, from the ONE reader's cached statuses. */
@Composable
internal fun UnlistedPanel(
    label: String,
    folders: List<UnlistedRepos.Folder>,
    glances: Map<String, GitSyncCoordinator.Glance>,
    lastCommits: Map<String, Long>,
    run: BulkDepth.Run?,
    coordinator: GitSyncCoordinator,
    onOpenReport: () -> Unit,
) {
    // #1288 the ONE folder table (the main table's columns and header); Archive / Delete per row, Rename when the origin names a repository.
    FolderRepoTable(
        tableId = "unlisted",
        title = label.ifBlank { stringResource(R.string.repo_unlisted_title) },
        hint = "",
        empty = stringResource(R.string.repo_unlisted_none),
        rows = folders.map { FolderActions.Target(it.path, it.origin, LocalRepos.repoNameOf(it.origin)) },
        glances = glances, lastCommits = lastCommits, run = run,
        kinds = listOf(Declarations.FOLDER_RENAME, Declarations.FOLDER_ARCHIVE, Declarations.FOLDER_DELETE),
        coordinator = coordinator,
        subtitle = { it.origin.ifBlank { stringResource(R.string.repo_unlisted_no_remote) } },
        onOpenReport = onOpenReport,
        modifier = Modifier.padding(vertical = DriveMetrics.pad).testTag(DriveTags.SYNC_GIT_UNLISTED),
    )
}

/** #1258 ACTIVITY LOGS (formerly History): the shared sync / operation history, newest first, its own full-screen view. */
@Composable
internal fun ActivityLogsWindow(events: List<SyncEvent>, fmt: DateFormat, onClose: () -> Unit, @Suppress("UNUSED_PARAMETER") row: @Composable (SyncEvent) -> Unit) {
    FullWindow(stringResource(R.string.sync_history_section), DriveTags.SYNC_GIT_ACTIVITY_WINDOW, onClose) {
        // Copy / Copy all / Copy filtered / Share, tap-to-expand and empty-entry filtering live in ActivityLog.kt; [row] stays for callers.
        ActivityLogsView(events, fmt)
    }
}

@Composable
private fun FullWindow(title: String, tag: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag(tag), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = DriveMetrics.sectionInset), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                Row(Modifier.fillMaxWidth().padding(vertical = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Pill(stringResource(R.string.repo_status_window_close), onClose)
                }
                content()
            }
        }
    }
}
