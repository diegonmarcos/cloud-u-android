package com.diegonmarcos.clouddrive.agents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.CapsuleBadge
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.ErrorState
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.IslandAction
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.ProgressCard
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.ToolbarIsland
import com.diegonmarcos.clouddrive.viewer.ImageViewerActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AGENTS ▸ the Screenshot agent's page: is it on, how much of the phone it has read, the pass in
 * progress (with Stop), Run now / Retry failed / Turn on-off, where the output lives (the
 * contract), and the most recent screenshots it read with their text and labels. A row opens the
 * image viewer, whose Text layer draws the same record.
 */
@Composable
fun ScreenshotAgentPage(title: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val status by ScreenshotAgent.status.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var enabled by remember { androidx.compose.runtime.mutableStateOf(ScreenshotAgentPrefs.enabled(ctx)) }
    // Counters and the recent list are file reads: off the main thread, again after every pass.
    val counts by produceState<Pair<Int, Int>?>(null, status.running, status.finishedAtMs, tick) { value = withContext(Dispatchers.IO) { runCatching { ScreenshotAgent.counts() }.getOrNull() } }
    val recent by produceState(emptyList<ScreenshotRecord>(), status.done, status.finishedAtMs, tick) { value = withContext(Dispatchers.IO) { runCatching { ScreenshotAgent.store().recent(RECENT) }.getOrDefault(emptyList()) } }
    val state by produceState<ScreenshotStore.State?>(null, status.finishedAtMs, tick) { value = withContext(Dispatchers.IO) { runCatching { ScreenshotAgent.store().state() }.getOrNull() } }
    val last = remember(status.finishedAtMs, tick) { ScreenshotAgentPrefs.lastRun(ctx) }

    Column(modifier.fillMaxSize()) {
        ToolbarIsland(
            title = title,
            subtitle = stringResource(R.string.agent_shots_title),
            actions = { IslandAction(Icons.Filled.Refresh, stringResource(R.string.agent_shots_run_now), enabled = !status.running) { ScreenshotAgentWorker.runNow(ctx); tick++ } },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                val (total, read) = counts ?: (0 to 0)
                DriveCard(
                    header = stringResource(R.string.agent_shots_title),
                    badge = if (enabled) "on" else "off",
                    light = if (status.message != null && !status.running) StatusLight.State.OFF else if (enabled) StatusLight.State.ON else StatusLight.State.UNKNOWN,
                    summary = if (counts == null) stringResource(R.string.agent_shots_scanning) else stringResource(R.string.agent_shots_summary, read, total, state?.failed ?: 0),
                    tag = DriveTags.AGENT_CARD,
                ) {
                    Text(stringResource(if (enabled) R.string.agent_shots_on else R.string.agent_shots_off), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.agent_shots_about), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        last?.let { stringResource(R.string.agent_shots_last_run, DateUtils.getRelativeTimeSpanString(it.atMs).toString(), it.done, it.failed) } ?: stringResource(R.string.agent_shots_never_run),
                        Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.agent_shots_output, ScreenshotAgent.store().root.absolutePath),
                        Modifier.padding(top = DriveMetrics.tight), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    PillRow {
                        if (status.running) Pill(stringResource(R.string.agent_shots_stop), { ScreenshotAgent.requestStop() })
                        else Pill(stringResource(R.string.agent_shots_run_now), { ScreenshotAgentWorker.runNow(ctx); tick++ }, filled = true)
                        Pill(stringResource(R.string.agent_shots_retry_failed), { ScreenshotAgentWorker.runNow(ctx, retryFailed = true); tick++ }, enabled = !status.running && (state?.failed ?: 0) > 0)
                        Pill(stringResource(if (enabled) R.string.agent_shots_turn_off else R.string.agent_shots_turn_on), { enabled = !enabled; ScreenshotAgentPrefs.setEnabled(ctx, enabled); tick++ })
                    }
                }
            }
            if (status.running) item {
                ProgressCard(
                    title = stringResource(R.string.agent_shots_running, status.current.ifBlank { "…" }),
                    detail = stringResource(R.string.agent_shots_progress, status.done, status.done + status.todo),
                    fraction = if (status.todo + status.done > 0) status.done.toFloat() / (status.done + status.todo) else null,
                    onCancel = { ScreenshotAgent.requestStop() },
                    cancelLabel = stringResource(R.string.agent_shots_stop),
                )
            }
            status.message?.takeIf { !status.running }?.let { msg -> item { ErrorState(msg, onRetry = { ScreenshotAgentWorker.runNow(ctx); tick++ }) } }
            item { SectionHeader(stringResource(R.string.agent_shots_recent), count = recent.size.takeIf { it > 0 }) }
            if (recent.isEmpty()) item {
                EmptyState(Icons.Filled.DocumentScanner, stringResource(R.string.agent_shots_empty), stringResource(R.string.agent_shots_empty_hint))
            }
            items(recent, key = { it.id }) { r ->
                RecordRow(r) { ctx.startActivity(ImageViewerActivity.intent(ctx, r.sourcePath, recent.map { it.sourcePath })) }
                Hairline(Modifier.padding(horizontal = DriveMetrics.gutter))
            }
        }
    }
}

@Composable
private fun RecordRow(r: ScreenshotRecord, onOpen: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, r.id) { value = withContext(Dispatchers.IO) { runCatching { thumbnail(File(r.sourcePath)) }.getOrNull() } }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag(TAG_ROW).padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gapWide),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(DriveMetrics.pad),
    ) {
        Box(Modifier.size(DriveMetrics.appTile).clip(RoundedCornerShape(DriveMetrics.gapWide)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(r.sourcePath.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                r.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(" · ").ifBlank { stringResource(R.string.agent_shots_no_text) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val chips = (r.labels.filter { it.source != "heuristic" }.take(3).map { it.label } + r.entities.take(2).map { "${it.type}: ${it.text}" } + listOfNotNull(r.sourceApp))
            if (chips.isNotEmpty()) Row(Modifier.padding(top = DriveMetrics.tight)) { chips.take(5).forEach { CapsuleBadge(it) } }
            r.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun thumbnail(f: File): Bitmap? {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.absolutePath, it) }
    if (b.outWidth <= 0) return null
    var sample = 1
    while (minOf(b.outWidth, b.outHeight) / (sample * 2) >= THUMB_PX) sample *= 2
    return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
}

private const val RECENT = 40
private const val THUMB_PX = 160
private const val TAG_ROW = "agents_screenshots_row"
