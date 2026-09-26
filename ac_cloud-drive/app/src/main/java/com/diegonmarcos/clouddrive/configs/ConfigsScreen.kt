package com.diegonmarcos.clouddrive.configs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.GitSyncWorker
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.backups.BackupsScreen
import com.diegonmarcos.clouddrive.backups.MirrorRunner
import com.diegonmarcos.clouddrive.sync.GitReposScreen
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import com.diegonmarcos.clouddrive.sync.MountsSyncScreen
import com.diegonmarcos.clouddrive.sync.RcloneCoordinator
import com.diegonmarcos.clouddrive.sync.RcloneSyncScreen
import com.diegonmarcos.clouddrive.sync.SyncSchedule
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.ToolbarIsland
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #603 CONFIGS: ONE island whose strip is build.json::ui.configs.pages — Git · Rclone ·
 * Mounts (moved in from the old Sync tab), Backups (moved in from its own tab), General
 * (#579's Configs cards) and Others. The strip is tabs inside the tab, not a stack — back
 * leaves the app. Every one of the six is a screen that already existed: this file routes,
 * it does not draw a card.
 *
 * The dispatch on a page id is the ONE place Kotlin names them; test-drive-shell.sh diffs
 * it against the declaration in both directions.
 *
 * [page] is a DECLARED page id another screen asked for (an Apps tile's route); it is
 * applied once and [onPageConsumed] clears the request.
 */
@Composable
fun ConfigsScreen(
    git: GitSyncCoordinator,
    rclone: RcloneCoordinator,
    mirrors: MirrorRunner,
    prefs: DrivePrefs,
    actions: DriveActions,
    hasAccess: Boolean,
    rcloneVersion: String?,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val pages = Declarations.configs.pages
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
        ToolbarIsland(title = stringResource(R.string.configs_title), subtitle = pages.firstOrNull { it.id == current }?.label)
        Row(
            Modifier.fillMaxWidth().testTag(DriveTags.CONFIGS_STRIP).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pages.forEach { p -> Pill(p.label, { current = p.id }, icon = IconCatalog.vectorOrDefault(p.icon), filled = current == p.id) }
        }
        AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "configs_page", modifier = Modifier.weight(1f)) { id ->
            when (id) {
                "git" -> GitReposScreen(git, actions, nextRun)
                "rclone" -> RcloneSyncScreen(rclone, prefs, actions, ::say)
                "mounts" -> MountsSyncScreen(rclone, prefs, actions, ::say)
                "backups" -> BackupsScreen(mirrors, prefs)
                "general" -> GeneralPage(prefs, actions, hasAccess)
                "others" -> OthersPage(prefs, actions, rcloneVersion)
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
        SnackbarHost(snackbar)
    }
}
