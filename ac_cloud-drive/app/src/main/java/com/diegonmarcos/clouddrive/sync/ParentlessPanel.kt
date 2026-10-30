package com.diegonmarcos.clouddrive.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.sync.inventory.RepoInventory
import com.diegonmarcos.clouddrive.ui.DriveTags

/**
 * #1149 Sync ▸ Git ▸ Me ▸ Local Repos Parentless: the clones that need a folder fix. #1315 drawn FROM THE ONE INVENTORY
 * (no scan of its own): a MISNAMED row (the folder does not carry its repository's registry name: Rename) and a STALE_ORIGIN
 * row (named right, origin naming a retired name: Fix origin). #1288 the ONE folder table ([FolderRepoTable]) with the declared
 * folder commands and "Rename all" / "Fix all origins"; a rename whose target exists goes to <name>___CONFLICT[n]; a rename
 * re-points every saved path and bookmark ([LocalRepoRefs]). When a command ends the inventory is read again and [onChanged]
 * lets the page re-read its registry.
 */
@Composable
fun ParentlessPanel(
    prefs: DrivePrefs,
    coordinator: GitSyncCoordinator,
    glances: Map<String, GitSyncCoordinator.Glance>,
    lastCommits: Map<String, Long>,
    run: BulkDepth.Run?,
    onOpenReport: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val inventory by coordinator.inventory.collectAsState()
    val names = RegistryNames.live()
    val rows = inventory?.rows?.filter { it.cls == RepoInventory.RepoClass.MISNAMED || it.cls == RepoInventory.RepoClass.STALE_ORIGIN }
        ?.map { FolderActions.Target(it.path, it.origin, it.registryName) }
    // The statuses of these rows ride the ONE reader; a finished folder command re-reads the inventory and the registry.
    LaunchedEffect(rows?.map { it.path }) { rows?.let { coordinator.requestFolderStatus(it.map { t -> t.path }) } }
    LaunchedEffect(run?.actionId, run?.ended) {
        if (run != null && run.ended && run.kind in Declarations.FOLDER_KINDS) { coordinator.scanUnlisted(); onChanged() }
    }
    FolderRepoTable(
        tableId = "parentless",
        title = stringResource(R.string.parentless_title),
        hint = stringResource(R.string.parentless_hint),
        empty = stringResource(R.string.parentless_empty),
        rows = rows, glances = glances, lastCommits = lastCommits, run = run,
        kinds = listOf(Declarations.FOLDER_RENAME, Declarations.FOLDER_ARCHIVE, Declarations.FOLDER_DELETE, Declarations.FOLDER_REPOINT),
        coordinator = coordinator,
        subtitle = { stringResource(R.string.parentless_row, FolderActions.originLabel(LocalRepos.repoNameOf(it.origin), names)) },
        bulk = listOf(Declarations.FOLDER_RENAME to stringResource(R.string.parentless_rename_all), Declarations.FOLDER_REPOINT to stringResource(R.string.parentless_fix_all)),
        repoint = { from, to ->
            val r = LocalRepoRefs.repoint(ctx.applicationContext, prefs, from, to)
            ctx.getString(R.string.parentless_refs, r.repos, r.jobs, r.bookmarks)
        },
        onOpenReport = onOpenReport,
        modifier = Modifier.testTag(DriveTags.PARENTLESS),
    )
}
