package com.diegonmarcos.clouddrive.backups

import android.os.Environment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.ProgressCard
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.StatusLightRow
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The mirror jobs' runner: one cancellable coroutine per declared job, live tally, result to DrivePrefs. */
class MirrorRunner(private val scope: CoroutineScope, private val prefs: DrivePrefs) {
    data class Run(val tally: FileOps.MirrorTally = FileOps.MirrorTally(), val current: String = "", val cancel: AtomicBoolean = AtomicBoolean(false), val done: Boolean = false, val error: String? = null)

    val runs = MutableStateFlow<Map<String, Run>>(emptyMap())

    /** Declared paths are RELATIVE to shared storage (drive-mirror-jobs.json _doc_paths); resolved here, once. */
    fun resolve(path: String): File = if (path.startsWith("/")) File(path) else File(Environment.getExternalStorageDirectory(), path)

    fun run(job: Declarations.MirrorJobDecl) {
        if (runs.value[job.name]?.done == false) return
        val run = Run()
        runs.update { it + (job.name to run) }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    FileOps.mirror(resolve(job.source), resolve(job.destination), job.delete, { run.cancel.get() }) { tally, current ->
                        runs.update { m -> m + (job.name to (m[job.name] ?: run).copy(tally = tally.copy(), current = current)) }
                    }
                }
            }
            val tally = result.getOrNull()
            val summary = tally?.let { "${it.copied} copied · ${it.skipped} skipped · ${it.deleted} deleted · ${it.failed} failed" } ?: (result.exceptionOrNull()?.message ?: "failed")
            val ok = tally?.ok == true && !run.cancel.get()
            prefs.setMirrorResult(job.name, System.currentTimeMillis() / 1000, summary + if (run.cancel.get()) " · cancelled" else "", ok)
            runs.update { m -> m + (job.name to (m[job.name] ?: run).copy(done = true, error = result.exceptionOrNull()?.message)) }
        }
    }

    fun cancel(job: Declarations.MirrorJobDecl) { runs.value[job.name]?.cancel?.set(true) }
}

/**
 * #579 BACKUPS (cloud-drive-redesign.md §6): one card per declared mirror (rsync)
 * job with its last result as a StatusLight and a cancellable live ProgressCard while
 * it runs — never a UI-thread call; then the fleet repositories that receive backups.
 */
@Composable
fun BackupsScreen(runner: MirrorRunner, prefs: DrivePrefs, modifier: Modifier = Modifier) {
    val runs by runner.runs.collectAsState()
    val snap by prefs.snapshot.collectAsState()
    val jobs = Declarations.mirrorJobs
    val fleet = Declarations.connections.filter { it.kind == "borg" || it.kind == "bup" }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    // #603 a Configs SUB-PAGE, not a tab: the island and the page's name are drawn once
    // by ConfigsScreen from ui.configs.pages, so this screen is its card list and nothing else.
    LazyColumn(modifier.fillMaxSize()) {
        item { SectionHeader(stringResource(R.string.backups_mirrors_section), count = jobs.size) }
        if (jobs.isEmpty()) item { EmptyState(Icons.Filled.Backup, stringResource(R.string.backups_empty), stringResource(R.string.backups_empty_hint)) }
        items(jobs, key = { it.name }) { job ->
            // snap is read so a finished run's stored result re-renders the card.
            @Suppress("UNUSED_VARIABLE") val tick = snap
            val last = prefs.mirrorResult(job.name)
            val run = runs[job.name]
            val running = run != null && !run.done
            val light = when {
                last == null -> StatusLight.State.UNKNOWN
                last.third -> StatusLight.State.ON
                else -> StatusLight.State.OFF
            }
            DriveCard(job.name, badge = if (job.delete) stringResource(R.string.backups_delete_badge) else null, light = light, summary = "${job.source}  →  ${job.destination}", summaryMonospace = true, tag = DriveTags.BACKUPS_MIRROR_CARD) {
                if (job.notes.isNotBlank()) Text(job.notes, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (last != null) stringResource(R.string.backups_last_run, fmt.format(Date(last.first * 1000)), last.second) else stringResource(R.string.backups_never_run),
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (running && run != null) {
                    ProgressCard(
                        title = job.name,
                        detail = stringResource(R.string.backups_scanning, run.tally.scanned, run.current) + "\n" + stringResource(R.string.backups_result, run.tally.copied, run.tally.skipped, run.tally.deleted, run.tally.failed),
                        onCancel = { runner.cancel(job) },
                    )
                }
                PillRow { Pill(stringResource(R.string.backups_run), { runner.run(job) }, icon = Icons.Filled.PlayArrow, filled = true, enabled = !running) }
            }
        }
        item { SectionHeader(stringResource(R.string.backups_fleet_section), count = fleet.size) }
        items(fleet, key = { "fleet-" + it.name }) { c ->
            Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter + 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    Text("${c.kind} · ${c.endpoint} · ${c.auth}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (c.notes.isNotBlank()) Text(c.notes, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                StatusLightRow(if (c.status == "ok") StatusLight.State.UNVERIFIABLE else StatusLight.State.OFF, c.name)
            }
            Hairline(Modifier.padding(horizontal = DriveMetrics.gutter))
        }
    }
}
