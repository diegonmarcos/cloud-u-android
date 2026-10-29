package com.diegonmarcos.clouddrive.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveTags
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1149 Sync ▸ Git ▸ Me ▸ Local Repos Parentless: the local folders that hold a `.git` whose name is not their origin's
 * repository name ([LocalRepos]). #1288 drawn by the ONE folder table ([FolderRepoTable]: the main table's columns and
 * header), each row with the declared folder commands Rename / Archive / Delete, and "Rename all". A rename whose target
 * exists goes to <name>___CONFLICT[n] (nothing overwritten or merged); a rename that happened re-points every saved path
 * and bookmark at the new folder ([LocalRepoRefs]). [onChanged] lets the page re-read its registry.
 */
@Composable
fun ParentlessPanel(
    roots: List<File>,
    prefs: DrivePrefs,
    coordinator: GitSyncCoordinator,
    glances: Map<String, GitSyncCoordinator.Glance>,
    lastCommits: Map<String, Long>,
    run: BulkDepth.Run?,
    onOpenReport: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<FolderActions.Target>?>(null) }
    val key = roots.map { it.absolutePath }
    // Rescan on open and whenever a folder command ends.
    LaunchedEffect(key, run?.actionId, run?.ended) {
        val found = withContext(Dispatchers.IO) { LocalRepos.parentless(LocalRepos.scan(roots)).map { FolderActions.Target(it.dir.absolutePath, it.origin, it.repoName) } }
        rows = found
        coordinator.requestFolderStatus(found.map { it.path })
        if (run != null && run.ended && run.kind in Declarations.FOLDER_KINDS) onChanged()
    }
    FolderRepoTable(
        tableId = "parentless",
        title = stringResource(R.string.parentless_title),
        hint = stringResource(R.string.parentless_hint),
        empty = stringResource(R.string.parentless_empty),
        rows = rows, glances = glances, lastCommits = lastCommits, run = run,
        kinds = listOf(Declarations.FOLDER_RENAME, Declarations.FOLDER_ARCHIVE, Declarations.FOLDER_DELETE),
        coordinator = coordinator,
        subtitle = { stringResource(R.string.parentless_row, it.repoName) },
        bulk = Declarations.FOLDER_RENAME to stringResource(R.string.parentless_rename_all),
        repoint = { from, to ->
            val r = LocalRepoRefs.repoint(ctx.applicationContext, prefs, from, to)
            ctx.getString(R.string.parentless_refs, r.repos, r.jobs, r.bookmarks)
        },
        onOpenReport = onOpenReport,
        modifier = Modifier.testTag(DriveTags.PARENTLESS),
    )
}
