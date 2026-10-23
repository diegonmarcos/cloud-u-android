package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill

/** #1138 the repo table's column width: the name column is wide, every other column is one table cell. */
internal fun repoCellModifier(c: RepoColumn): Modifier =
    Modifier.width(if (c == RepoColumn.NAME) DriveMetrics.tableName else DriveMetrics.tableCell).padding(horizontal = DriveMetrics.gap)

@Composable
private fun columnLabel(c: RepoColumn): String = stringResource(when (c) {
    RepoColumn.NAME -> R.string.repo_col_name
    RepoColumn.VISIBILITY -> R.string.repo_col_vis
    RepoColumn.SIZE_MAIN -> R.string.repo_col_size_main
    RepoColumn.SIZE_TOTAL -> R.string.repo_col_size_total
    RepoColumn.BRANCHES -> R.string.repo_col_branches
    RepoColumn.DIRTY -> R.string.repo_col_dirty
    RepoColumn.AHEAD_BEHIND -> R.string.repo_col_ab
})

/** The sortable header: a tap on a column sorts by it, a second tap flips the direction. Scrolls with the rows ([scroll] is shared). */
@Composable
internal fun RepoTableHeader(column: RepoColumn, ascending: Boolean, scroll: ScrollState, onSort: (RepoColumn) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min = DriveMetrics.tap).testTag(DriveTags.SYNC_GIT_TABLE_HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.horizontalScroll(scroll).padding(horizontal = DriveMetrics.sectionInset), verticalAlignment = Alignment.CenterVertically) {
            RepoColumn.entries.forEach { c ->
                val arrow = if (c == column) (if (ascending) " ▲" else " ▼") else ""
                Text(
                    columnLabel(c) + arrow, repoCellModifier(c).clickable { onSort(c) },
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    textAlign = if (c == RepoColumn.NAME) TextAlign.Start else TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The six fact cells after the name, in column order. */
@Composable
internal fun RepoCells(r: RepoTableRow) {
    val yes = stringResource(R.string.manage_yes)
    val no = stringResource(R.string.manage_no)
    val texts = listOf(
        RepoColumn.VISIBILITY to RepoTable.visibility(r.isPrivate, stringResource(R.string.repo_vis_pvt), stringResource(R.string.repo_vis_pub)),
        RepoColumn.SIZE_MAIN to RepoTable.size(r.sizeMainBytes),
        RepoColumn.SIZE_TOTAL to RepoTable.size(r.sizeTotalBytes),
        RepoColumn.BRANCHES to RepoTable.count(r.branches),
        RepoColumn.DIRTY to RepoTable.dirty(r.dirtyFiles, yes, no),
        RepoColumn.AHEAD_BEHIND to RepoTable.aheadBehind(r.ahead, r.behind),
    )
    texts.forEach { (c, t) ->
        Text(t, repoCellModifier(c), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun groupTitle(g: RepoGroup): String = stringResource(when (g) {
    RepoGroup.SYNC -> R.string.repo_group_sync
    RepoGroup.STEPS -> R.string.repo_group_steps
    RepoGroup.METADATA -> R.string.repo_group_metadata
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
                        enabled = (!busy || isClone || g.group == RepoGroup.METADATA || g.group == RepoGroup.GOTO) && !(op != null && blocked(op)),
                        danger = danger,
                    )
                }
            }
        }
    }
}
