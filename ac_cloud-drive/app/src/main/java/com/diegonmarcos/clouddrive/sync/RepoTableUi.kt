package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.StatusDot
import com.diegonmarcos.clouddrive.ui.StatusLight

/** #1138 the repo table's column width: the name column is wide, every other column is one table cell. */
internal fun repoCellModifier(c: RepoColumn): Modifier =
    Modifier.width(when (c) { RepoColumn.NAME -> DriveMetrics.tableName; RepoColumn.STATUS -> DriveMetrics.tap * 2; else -> DriveMetrics.tableCell }).padding(horizontal = DriveMetrics.gap)

@Composable
private fun columnLabel(c: RepoColumn): String = stringResource(when (c) {
    RepoColumn.STATUS -> R.string.repo_col_status
    RepoColumn.NAME -> R.string.repo_col_name
    RepoColumn.PROVIDER -> R.string.repo_col_provider
    RepoColumn.VISIBILITY -> R.string.repo_col_vis
    RepoColumn.LFS -> R.string.repo_col_lfs
    RepoColumn.SIZE_MAIN -> R.string.repo_col_size_main
    RepoColumn.SIZE_TOTAL -> R.string.repo_col_size_total
    RepoColumn.SIZE_DISK -> R.string.repo_col_size_disk
    RepoColumn.BRANCHES -> R.string.repo_col_branches
    RepoColumn.DIRTY -> R.string.repo_col_dirty
    RepoColumn.AHEAD_BEHIND -> R.string.repo_col_ab
    RepoColumn.LAST_COMMIT -> R.string.repo_col_last_commit
})

/** The sortable header: a tap on a column sorts by it, a second tap flips the direction. Scrolls with the rows ([scroll] is shared). */
@Composable
internal fun RepoTableHeader(column: RepoColumn, ascending: Boolean, scroll: ScrollState, tag: String = DriveTags.SYNC_GIT_TABLE_HEADER, onSort: (RepoColumn) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min = DriveMetrics.tap).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.horizontalScroll(scroll).padding(horizontal = DriveMetrics.sectionInset), verticalAlignment = Alignment.CenterVertically) {
            RepoTable.COLUMNS.forEach { c ->
                val arrow = if (c == column) (if (ascending) " ▲" else " ▼") else ""
                Text(
                    columnLabel(c) + arrow, repoCellModifier(c).clickable { onSort(c) },
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    textAlign = when (c) { RepoColumn.NAME -> TextAlign.Start; RepoColumn.STATUS -> TextAlign.Center; else -> TextAlign.End }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The nine fact cells after the name, in column order (the leftmost STATUS cell is [RepoStatusCell]). */
@Composable
internal fun RepoCells(r: RepoTableRow) {
    val yes = stringResource(R.string.manage_yes)
    val no = stringResource(R.string.manage_no)
    val texts = listOf(
        RepoColumn.PROVIDER to (r.provider ?: RepoTable.DASH),
        RepoColumn.VISIBILITY to RepoTable.visibility(r.isPrivate, stringResource(R.string.repo_vis_pvt), stringResource(R.string.repo_vis_pub)),
        RepoColumn.LFS to RepoTable.lfs(r.lfs, stringResource(R.string.repo_lfs_lfs), stringResource(R.string.repo_lfs_normal)),
        RepoColumn.SIZE_MAIN to RepoTable.size(r.sizeMainBytes),
        RepoColumn.SIZE_TOTAL to RepoTable.size(r.sizeTotalBytes),
        RepoColumn.SIZE_DISK to RepoTable.size(r.sizeDiskBytes),
        RepoColumn.BRANCHES to RepoTable.count(r.branches),
        RepoColumn.DIRTY to RepoTable.dirty(r.dirtyFiles, yes, no),
        RepoColumn.AHEAD_BEHIND to RepoTable.aheadBehind(r.ahead, r.behind),
        RepoColumn.LAST_COMMIT to RepoTable.age(r.lastCommitMs, System.currentTimeMillis()),
    )
    texts.forEach { (c, t) ->
        Text(t, repoCellModifier(c), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** #1238 the leftmost cell: the status icon; a tap on it (not on the row) opens the repository's full-screen status window. */
@Composable
internal fun RepoStatusCell(icons: RepoStatus.Icons, name: String, onOpen: () -> Unit, caption: String = "") {
    val decl = com.diegonmarcos.clouddrive.Declarations.sync.git.status.icons
    Box(
        repoCellModifier(RepoColumn.STATUS).heightIn(min = DriveMetrics.tap).testTag(DriveTags.SYNC_GIT_STATUS_CELL)
            .clickable(onClickLabel = stringResource(R.string.repo_status_open), onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // LOCAL | SERVER | SYNC, each its own light with its own kind in the accessible label.
            Row(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gap)) {
                // "not checked yet" / "never" draw a hollow dotted circle, never the "?" of a read that does not end.
                fun glyph(l: StatusLight.State) = if (l == StatusLight.State.UNVERIFIABLE) "\u25CC" else StatusLight.glyph(l)
                val lo = RepoStatus.lightOf("local", icons.local, decl); val se = RepoStatus.lightOf("server", icons.server, decl); val sy = RepoStatus.lightOf("sync", icons.sync, decl)
                StatusDot(lo, "$name local ${icons.local.name.lowercase()}", glyph = glyph(lo))
                StatusDot(se, "$name server ${icons.server.name.lowercase()}", glyph = glyph(se))
                StatusDot(sy, "$name sync ${icons.sync.name.lowercase()}", glyph = glyph(sy))
            }
            if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** "last check: <time> / n of m done": the owner sees the checks progress and finish. */
@Composable
internal fun RepoCheckProgressLine(p: RepoStatus.Progress, fmt: java.text.DateFormat) {
    Text(
        stringResource(R.string.repo_check_progress, if (p.lastCheckMs > 0) fmt.format(java.util.Date(p.lastCheckMs)) else stringResource(R.string.sync_never), p.done, p.total),
        Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_CHECK_PROGRESS),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun groupTitle(g: RepoGroup): String = stringResource(when (g) {
    RepoGroup.SYNC -> R.string.repo_group_sync
    RepoGroup.STEPS -> R.string.repo_group_steps
    RepoGroup.GOTO -> R.string.repo_group_goto
    RepoGroup.WORKFLOW -> R.string.repo_group_workflow
    RepoGroup.DANGEROUS -> R.string.repo_group_dangerous
    RepoGroup.REPO_CLEAN -> R.string.repo_group_clean
    RepoGroup.OTHERS -> R.string.repo_group_others
    RepoGroup.CLONE_SETTINGS -> R.string.repo_group_clone
})

/** The label of one action: a string of its own for the added ids and the renamed declared ones, else the declared label. */
@Composable
internal fun actionLabel(id: String, declared: Declarations.GitOpDecl?): String = when (id) {
    RepoActions.SYNC_NO_WINNER -> stringResource(R.string.repo_act_sync_no_winner)
    RepoActions.SYNC_REMOTE_WINS -> stringResource(R.string.repo_act_sync_remote_wins)
    RepoActions.SYNC_LOCAL_WINS -> stringResource(R.string.repo_act_sync_local_wins)
    "stats" -> stringResource(R.string.repo_act_status)
    "remote" -> stringResource(R.string.repo_act_auth)
    "metadata" -> stringResource(R.string.repo_act_remote_url)
    RepoActions.META_BRANCH -> stringResource(R.string.repo_act_default_branch)
    RepoActions.META_SYNC -> stringResource(R.string.repo_act_last_sync)
    RepoActions.CHECKOUT_MAIN -> stringResource(R.string.repo_act_checkout_main)
    RepoActions.BRANCHES -> stringResource(R.string.repo_act_branches)
    RepoActions.PRS -> stringResource(R.string.repo_act_prs)
    RepoActions.UNPUSHED -> stringResource(R.string.repo_act_unpushed)
    RepoActions.OPEN_FOLDER -> stringResource(R.string.sync_open)
    "history" -> stringResource(R.string.repo_act_commit_history)
    RepoActions.RUNS -> stringResource(R.string.repo_act_runs)
    RepoActions.CLEAN_1, RepoActions.CLEAN_5, RepoActions.CLEAN_10 -> stringResource(R.string.repo_act_clean, RepoActions.cleanDepth(id))
    RepoActions.SYNC_NOW -> stringResource(R.string.sync_now)
    RepoActions.SETTINGS -> stringResource(R.string.sync_settings)
    RepoActions.CLONE_FULL -> stringResource(R.string.repo_act_clone_full)
    RepoActions.CLONE_DEPTH1 -> stringResource(R.string.repo_act_clone_depth1)
    RepoActions.CLONE_OAUTH -> stringResource(R.string.repo_act_clone_oauth)
    RepoActions.CLONE_SSH -> stringResource(R.string.repo_act_clone_ssh)
    else -> declared?.label ?: id
}

/**
 * The repository detail's actions: ONE ROW PER COMMAND TYPE in [RepoActions.GROUPS] order, each row a
 * title and chips that wrap onto as many lines as they need. Dangerous chips are red. [active] is the
 * chip to draw filled (the open panel or the current clone setting); [onAction] receives the action id.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepoActionGroupsView(
    page: Declarations.GitPageDecl,
    busy: Boolean,
    blocked: (Declarations.GitOpDecl) -> Boolean,
    active: (String) -> Boolean,
    onAction: (String) -> Unit,
    /** #1195 an action that cannot run now (a not-cloned repository: clone-required ones), drawn disabled. */
    unavailable: (String) -> Boolean = { false },
) {
    Column(Modifier.fillMaxWidth().padding(top = DriveMetrics.pad), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
        RepoActions.GROUPS.forEach { g ->
            val danger = g.group == RepoGroup.DANGEROUS
            Text(
                groupTitle(g.group), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide), verticalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
                g.actions.forEach { id ->
                    val op = page.op(id)
                    val isClone = g.group == RepoGroup.CLONE_SETTINGS
                    Pill(
                        actionLabel(id, op), { onAction(id) },
                        icon = op?.let { IconCatalog.vectorOrDefault(it.icon) },
                        filled = active(id),
                        // Clone settings and the readers stay available while an operation runs; the verbs wait.
                        enabled = (!busy || isClone || g.group == RepoGroup.GOTO) && !(op != null && blocked(op)) && !unavailable(id),
                        danger = danger,
                    )
                }
            }
        }
    }
}

/** The bulk bar: ABOVE the column header, one pill per [RepoActions.Bulk] command over every repo, then the Settings gear (the shared PageActionsBar). */
@Composable
internal fun RepoBulkBar(busy: Boolean, prefs: DrivePrefs?, onRefreshSizes: () -> Unit, fullRunning: Boolean = false, onCancelChecks: () -> Unit = {}, onBulk: (RepoActions.Bulk) -> Unit) {
    PageActionsBar(
        AutoChecks.Page.GIT, prefs,
        RepoActions.Bulk.entries.map { b ->
            BarAction(
                stringResource(when (b) {
                    RepoActions.Bulk.CHECK_ALL -> R.string.repo_bulk_check
                    RepoActions.Bulk.CHECK_FULL -> R.string.repo_bulk_check_full
                    RepoActions.Bulk.SYNC_ALL -> R.string.repo_bulk_sync
                    RepoActions.Bulk.CLONE_ALL -> R.string.repo_bulk_clone
                    RepoActions.Bulk.STASH_PUSH_ALL -> R.string.repo_bulk_stash_push
                }),
                { onBulk(b) }, enabled = !busy || b == RepoActions.Bulk.CHECK_ALL || (b == RepoActions.Bulk.CHECK_FULL && !fullRunning),
            )
        } + BarAction(stringResource(R.string.repo_bulk_sizes), onRefreshSizes) +
            (if (fullRunning) listOf(BarAction(stringResource(R.string.repo_bulk_cancel_checks), onCancelChecks)) else emptyList()),
        tag = DriveTags.SYNC_GIT_BULK_BAR,
    )
}

/** The status summary line under the bulk bar. */
@Composable
internal fun RepoSummaryLine(s: RepoTable.Summary) {
    Text(
        stringResource(R.string.repo_summary, s.total, s.cloned, s.notCloned, s.dirty, s.ahead, s.behind),
        Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap).testTag(DriveTags.SYNC_GIT_SUMMARY),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The repo metadata as a key | value table; the values are selectable (long-press, copy). */
@Composable
internal fun RepoMetaTable(rows: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().padding(top = DriveMetrics.pad).testTag(DriveTags.SYNC_GIT_META_TABLE)) {
        Text(stringResource(R.string.repo_meta_table_title), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Column {
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = DriveMetrics.tight)) {
                        Text(k, Modifier.width(DriveMetrics.tableName), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
