package com.diegonmarcos.clouddrive.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.SectionHeader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1149 Sync ▸ Git ▸ Me ▸ Local Repos Parentless: the local folders that hold a `.git` whose name is not their origin's
 * repository name ([LocalRepos]). Per row "Rename folder to match repo name"; "Rename all" for the lot. Both ask first,
 * both REFUSE a row whose target already exists (nothing is overwritten), and a rename that happened re-points every
 * saved path and bookmark at the new folder ([LocalRepoRefs]). [onChanged] lets the page re-read its registry.
 */
@Composable
fun ParentlessPanel(roots: List<File>, prefs: DrivePrefs, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<LocalRepos.Parentless>?>(null) }
    var results by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var confirm by remember { mutableStateOf<List<LocalRepos.Parentless>?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun rescan() { scope.launch { rows = withContext(Dispatchers.IO) { LocalRepos.parentless(LocalRepos.scan(roots)) } } }
    LaunchedEffect(roots.map { it.absolutePath }) { rescan() }

    fun say(o: LocalRepos.Outcome): String = when (o) {
        is LocalRepos.Outcome.Renamed -> ctx.getString(R.string.parentless_renamed, o.from.name, o.to.name)
        is LocalRepos.Outcome.TargetExists -> ctx.getString(R.string.parentless_refused_exists, o.from.name, o.to.name)
        is LocalRepos.Outcome.Missing -> ctx.getString(R.string.parentless_missing, o.from.name)
        is LocalRepos.Outcome.Invalid -> ctx.getString(R.string.parentless_invalid, o.from.name, o.name)
        is LocalRepos.Outcome.Failed -> ctx.getString(R.string.parentless_failed, o.from.name, o.to.name)
    }

    fun run(list: List<LocalRepos.Parentless>) {
        busy = true
        scope.launch {
            val lines = withContext(Dispatchers.IO) {
                LocalRepos.renameAll(list).flatMap { o ->
                    val moved = (o as? LocalRepos.Outcome.Renamed)?.let { LocalRepoRefs.repoint(ctx.applicationContext, prefs, it.from, it.to) }
                    listOfNotNull((say(o) to (o is LocalRepos.Outcome.Renamed)), moved?.let { ctx.getString(R.string.parentless_refs, it.repos, it.jobs, it.bookmarks) to true })
                }
            }
            results = lines
            busy = false
            onChanged()
            rescan()
        }
    }

    Column(Modifier.fillMaxWidth().testTag(DriveTags.PARENTLESS)) {
        SectionHeader(stringResource(R.string.parentless_title), count = rows?.size)
        Text(
            stringResource(R.string.parentless_hint), Modifier.padding(horizontal = DriveMetrics.sectionInset),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val list = rows
        if (list != null && list.isEmpty()) Text(
            stringResource(R.string.parentless_empty), Modifier.padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!list.isNullOrEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap), horizontalArrangement = Arrangement.End) {
                Pill(stringResource(R.string.parentless_rename_all), { confirm = list }, filled = true, enabled = !busy, modifier = Modifier.testTag(DriveTags.PARENTLESS_ALL))
            }
            list.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = DriveMetrics.rowHeight).padding(horizontal = DriveMetrics.sectionInset, vertical = DriveMetrics.gap).testTag(DriveTags.parentlessRow(p.dir.name)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.dir.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.parentless_row, p.repoName), style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Pill(stringResource(R.string.parentless_rename), { confirm = listOf(p) }, enabled = !busy)
                }
                Hairline()
            }
        }
        results.forEach { (line, ok) ->
            Text(
                line, Modifier.padding(horizontal = DriveMetrics.sectionInset), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else colorResource(R.color.status_light_off),
            )
        }
    }

    confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.parentless_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.parentless_confirm_body, pending.size))
                    pending.take(PREVIEW).forEach { Text(it.dir.name + " → " + it.repoName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
                    if (pending.size > PREVIEW) Text(stringResource(R.string.parentless_confirm_more, pending.size - PREVIEW), style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = { val l = pending; confirm = null; run(l) }, modifier = Modifier.testTag(DriveTags.PARENTLESS_CONFIRM)) { Text(stringResource(R.string.parentless_confirm_go)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.chrome_cancel)) } },
        )
    }
}

private const val PREVIEW = 8
