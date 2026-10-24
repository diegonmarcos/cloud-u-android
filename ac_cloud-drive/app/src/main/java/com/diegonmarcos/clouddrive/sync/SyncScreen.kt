package com.diegonmarcos.clouddrive.sync

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.GitSyncWorker
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.IslandAction
import com.diegonmarcos.clouddrive.StoreReady
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.ToolbarIsland
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1149 the former Sync tab is three bottom-nav pages: Git (two sub-pages, Me and Cloud, drawn by libs:bottomnav's
 * PageTabs over ui.sections[git].pages), Rsync and Mounts (a screen each, #567's engines' surfaces). This file routes,
 * it does not draw a card; each page keeps the island with the terminal action it always had.
 *
 * GitPageScreen's dispatch on a page id is the ONE place Kotlin names them; test-drive-shell.sh diffs it against the
 * declaration in both directions. [page] is a DECLARED page id another screen asked for (an Apps tile's route); it is
 * applied once and [onPageConsumed] clears the request.
 */
@Composable
fun GitPageScreen(
    git: GitSyncCoordinator,
    prefs: DrivePrefs,
    actions: DriveActions,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val pages = Declarations.navPages(Declarations.SECTION_GIT)
    var current by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    LaunchedEffect(page) {
        if (page != null && pages.any { it.id == page }) current = page
        if (page != null) onPageConsumed()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var nextRun by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        // WorkManager's own word on when the base period fires next; null when it will not say.
        nextRun = withContext(Dispatchers.IO) {
            runCatching {
                val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(GitSyncWorker.WORK_NAME).first()
                val next = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis
                SyncSchedule.minutesUntilNext(next, System.currentTimeMillis())
            }.getOrNull()
        }
    }
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    Column(modifier.fillMaxSize()) {
        ToolbarIsland(
            title = Declarations.nav.section(Declarations.SECTION_GIT)?.label.orEmpty(),
            subtitle = pages.firstOrNull { it.id == current }?.label,
            actions = { TerminalIslandAction(actions, ::say) },
        )
        // #868 the strip is libs:bottomnav's PageTabs over ui.sections[git].pages.
        PageTabs(pages = pages, selectedId = current, onSelect = { current = it.id }, modifier = Modifier.padding(horizontal = DriveMetrics.gap).testTag(DriveTags.SYNC_STRIP), underTopChrome = false)
        AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "git_page", modifier = Modifier.weight(1f)) { id ->
            when (id) {
                "me" -> GitReposScreen(git, actions, nextRun, view = GitView.ME, prefs = prefs)
                "cloud" -> GitReposScreen(git, actions, nextRun, view = GitView.CLOUD, prefs = prefs)
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
        SnackbarHost(snackbar)
    }
}

/** Rsync (libs:rclone's surface): the island with the terminal action over [RcloneSyncScreen]. */
@Composable
fun RsyncPageScreen(rclone: RcloneCoordinator, prefs: DrivePrefs, actions: DriveActions, modifier: Modifier = Modifier) =
    SyncSurface(Declarations.SECTION_RSYNC, actions, modifier) { say -> RcloneSyncScreen(rclone, prefs, actions, say) }

/** Mounts (libs:mounts' surface): the island with the terminal action over [MountsSyncScreen]. */
@Composable
fun MountsPageScreen(rclone: RcloneCoordinator, prefs: DrivePrefs, actions: DriveActions, modifier: Modifier = Modifier) =
    SyncSurface(Declarations.SECTION_MOUNTS, actions, modifier) { say -> MountsSyncScreen(rclone, prefs, actions, say) }

@Composable
private fun SyncSurface(sectionId: String, actions: DriveActions, modifier: Modifier, body: @Composable (say: (String) -> Unit) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }
    Column(modifier.fillMaxSize()) {
        ToolbarIsland(title = Declarations.nav.section(sectionId)?.label.orEmpty(), subtitle = null, actions = { TerminalIslandAction(actions, ::say) })
        Box(Modifier.weight(1f)) { body(::say) }
        SnackbarHost(snackbar)
    }
}

/** The terminal owns the shared store: start it (process, service, store folder), then open it. One island action. */
@Composable
fun TerminalIslandAction(actions: DriveActions, say: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    IslandAction(Icons.Filled.Terminal, stringResource(R.string.sync_open_terminal)) {
        scope.launch {
            val why = withContext(Dispatchers.IO) { StoreReady.wake(ctx.applicationContext) }
            if (!actions.launchApp(StoreAccess.ownerPackage(), "")) say(ctx.getString(R.string.chrome_not_installed, ctx.getString(R.string.sync_terminal_name)))
            else if (why != null) say(why)
        }
    }
}
