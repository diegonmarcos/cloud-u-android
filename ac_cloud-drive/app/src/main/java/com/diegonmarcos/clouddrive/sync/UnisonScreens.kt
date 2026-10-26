package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig
import com.diegonmarcos.cloudlib.rclone.unison.UnisonResolve
import com.diegonmarcos.cloudlib.rclone.unison.UnisonStateStore
import java.text.DateFormat
import java.util.Date

/**
 * #1084 SYNC ▸ RSYNC ▸ UNISON PAIRS, in the same card language as the rules and jobs above it: one
 * DriveCard per declared pair (light, roots, schedule and policy, last result), a Sync now / Stop pill,
 * the run's live log, and — when the last run skipped files because both sides changed — one dense row per
 * conflicted path with Keep phone / Keep other / Keep both. Nothing is overwritten until a button is
 * tapped, and every choice keeps what it replaces (see UnisonResolve).
 *
 * Pure rendering over [UnisonCoordinator]: the declaration, the remembered state and the live log all come
 * from it, and the pill handlers are its three verbs.
 */
fun LazyListScope.unisonSection(
    coordinator: UnisonCoordinator,
    states: Map<String, UnisonStateStore.PairState>,
    live: Map<String, List<String>>,
    unavailable: String?,
    onMessage: (String) -> Unit,
    declared: com.diegonmarcos.cloudlib.rclone.unison.UnisonConfig.Declared,
    onAdd: () -> Unit,
    onDelete: (UnisonConfig.Pair) -> Unit,
    open: String = "",
    onToggle: (String) -> Unit = {},
) {
    item { SectionHeader(stringResource(R.string.unison_section), count = declared.pairs.size) }
    declared.problems.forEach { why ->
        item(key = "unison-problem-$why") {
            Text(stringResource(R.string.unison_problem, why), Modifier.padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (declared.pairs.isEmpty() && declared.problems.isEmpty()) item { EmptyState(Icons.Filled.Sync, stringResource(R.string.unison_empty), stringResource(R.string.unison_empty_hint), actionLabel = stringResource(R.string.unison_add), onAction = onAdd) }
    if (unavailable != null && declared.pairs.isNotEmpty()) item(key = "unison-unavailable") {
        Text(unavailable, Modifier.padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(declared.pairs, key = { "unison-" + it.id }) { pair ->
        UnisonPairCard(coordinator, pair, states[pair.id], live[pair.id], unavailable == null, onMessage, onDelete, expanded = open == "u-" + pair.id, onToggle = { onToggle("u-" + pair.id) })
    }
}

@Composable
private fun UnisonPairCard(
    coordinator: UnisonCoordinator,
    pair: UnisonConfig.Pair,
    state: UnisonStateStore.PairState?,
    live: List<String>?,
    engineReady: Boolean,
    onMessage: (String) -> Unit,
    onDelete: (UnisonConfig.Pair) -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val on = coordinator.enabled(pair)
    val running = live != null
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    val light = when {
        !on -> StatusLight.State.OFF
        state == null -> StatusLight.State.UNKNOWN
        state.conflicts.isNotEmpty() || state.failures.isNotEmpty() || state.rc !in 0..0 -> StatusLight.State.OFF
        else -> StatusLight.State.ON
    }
    val peer = when (val p = pair.peer) {
        is UnisonConfig.Peer.Ssh -> "${p.host}:${p.path}"
        is UnisonConfig.Peer.Local -> p.path
    }
    DriveCard(pair.name, light = light, summary = "${pair.rootA}  ⇄  $peer", summaryMonospace = true, onClick = onToggle, tag = DriveTags.UNISON_PAIR_CARD) {
        TapChevron(expanded)
        if (!expanded) return@DriveCard
        val schedule = pair.scheduleMinutes?.let { stringResource(R.string.unison_every, it) } ?: stringResource(R.string.unison_manual)
        val policy = when (pair.prefer) {
            null -> stringResource(R.string.unison_policy_ask)
            "a" -> stringResource(R.string.unison_policy_prefer, stringResource(R.string.unison_side_phone))
            "b" -> stringResource(R.string.unison_policy_prefer, stringResource(R.string.unison_side_other))
            "newer" -> stringResource(R.string.unison_policy_prefer, stringResource(R.string.unison_side_newer))
            else -> stringResource(R.string.unison_policy_prefer, stringResource(R.string.unison_side_older))
        }
        Text(stringResource(R.string.unison_meta, "unison", schedule, policy), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (state == null || state.lastRunEpochSeconds == 0L) stringResource(R.string.unison_never)
            else stringResource(R.string.unison_last, fmt.format(Date(state.lastRunEpochSeconds * 1000)), stringResource(if (state.trigger == UnisonSyncer.TRIGGER_SCHEDULED) R.string.unison_trigger_scheduled else R.string.unison_trigger_manual), state.headline),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = DriveMetrics.gap))
        val conflicts = state?.conflicts.orEmpty()
        if (conflicts.isNotEmpty()) {
            Text(stringResource(R.string.unison_conflicts, conflicts.size), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.unison_keep_hint, UnisonResolve.describe(pair, UnisonResolve.Choice.KEEP_A), UnisonResolve.describe(pair, UnisonResolve.Choice.KEEP_B), UnisonResolve.describe(pair, UnisonResolve.Choice.KEEP_BOTH)),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            conflicts.forEach { c ->
                Column(Modifier.fillMaxWidth().testTag(DriveTags.UNISON_CONFLICT_ROW).padding(top = DriveMetrics.gap)) {
                    Text(c.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(c.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PillRow {
                        Pill(stringResource(R.string.unison_keep_a), { coordinator.resolve(pair, c.path, UnisonResolve.Choice.KEEP_A) }, enabled = engineReady && !running)
                        Pill(stringResource(R.string.unison_keep_b), { coordinator.resolve(pair, c.path, UnisonResolve.Choice.KEEP_B) }, enabled = engineReady && !running)
                        Pill(stringResource(R.string.unison_keep_both), { coordinator.resolve(pair, c.path, UnisonResolve.Choice.KEEP_BOTH) }, enabled = engineReady && !running)
                    }
                }
                Hairline(Modifier.padding(top = DriveMetrics.gap))
            }
        }
        if ((state?.failures?.size ?: 0) > 0) {
            Text(stringResource(R.string.unison_failures, state!!.failures.size) + ": " + state.failures.joinToString { "${it.path} (${it.reason})" }, Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        PillRow {
            Pill(stringResource(if (on) R.string.rsync_rule_on else R.string.rsync_rule_off), { coordinator.setEnabled(pair, !on) }, filled = on)
            Pill(stringResource(R.string.storage_delete), { onDelete(pair) }, enabled = !running, danger = true)
            if (running) Pill(stringResource(R.string.unison_stop), { coordinator.cancel(pair) }, icon = Icons.Filled.Stop)
            else Pill(stringResource(R.string.unison_sync_now), { coordinator.syncNow(pair) }, modifier = Modifier.testTag(DriveTags.UNISON_SYNC_NOW), icon = Icons.Filled.PlayArrow, filled = true, enabled = engineReady)
        }
        val lines = (live ?: state?.log).orEmpty().takeLast(6)
        if (lines.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = DriveMetrics.gap).background(MaterialTheme.colorScheme.surfaceVariant).padding(DriveMetrics.gapWide)) {
                lines.forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
