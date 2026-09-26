package com.diegonmarcos.clouddrive.sync

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
 * #609 SYNC: the strip build.json::ui.sync.pages moved back out of Configs — Git ·
 * Rclone (Rsync) · Mounts, the mirror of #603's move the other way. The strip is
 * tabs inside the tab, not a stack — back leaves the app. Every one of the three is
 * a screen that already existed (#567): this file routes, it does not draw a card.
 *
 * The dispatch on a page id is the ONE place Kotlin names them; test-drive-shell.sh
 * diffs it against the declaration in both directions.
 *
 * [page] is a DECLARED page id another screen asked for (an Apps tile's route); it is
 * applied once and [onPageConsumed] clears the request.
 */
@Composable
fun SyncScreen(
    git: GitSyncCoordinator,
    rclone: RcloneCoordinator,
    prefs: DrivePrefs,
    actions: DriveActions,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val pages = Declarations.sync.pages
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
        ToolbarIsland(title = stringResource(R.string.sync_tab_title), subtitle = pages.firstOrNull { it.id == current }?.label)
        Row(
            Modifier.fillMaxWidth().testTag(DriveTags.SYNC_STRIP).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            pages.forEach { p -> Pill(p.label, { current = p.id }, icon = IconCatalog.vectorOrDefault(p.icon), filled = current == p.id) }
        }
        AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "sync_page", modifier = Modifier.weight(1f)) { id ->
            when (id) {
                "git" -> GitReposScreen(git, actions, nextRun)
                "rclone" -> RcloneSyncScreen(rclone, prefs, actions, ::say)
                "mounts" -> MountsSyncScreen(rclone, prefs, actions, ::say)
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
        SnackbarHost(snackbar)
    }
}
