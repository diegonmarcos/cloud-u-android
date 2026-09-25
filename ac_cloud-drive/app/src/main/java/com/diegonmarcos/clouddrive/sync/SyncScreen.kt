package com.diegonmarcos.clouddrive.sync

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #579 the Sync tab: ONE island whose strip is build.json::ui.sync.pages (git · rclone ·
 * mounts), each page a card list in the same language. The strip is tabs inside the
 * tab, not a stack — back leaves the app.
 */
@Composable
fun SyncScreen(git: GitSyncCoordinator, rclone: RcloneCoordinator, prefs: DrivePrefs, actions: DriveActions, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val pages = Declarations.sync.pages
    var page by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val prefsSnap by prefs.snapshot.collectAsState()
    var nextRun by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        // WorkManager's own word on when the base period fires next; null when it will not say.
        nextRun = withContext(Dispatchers.IO) {
            runCatching {
                val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(GitSyncWorker.WORK_NAME).get()
                val next = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis
                SyncSchedule.minutesUntilNext(next, System.currentTimeMillis())
            }.getOrNull()
        }
    }
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    Column(modifier.fillMaxSize()) {
        ToolbarIsland(title = stringResource(R.string.sync_title), subtitle = pages.firstOrNull { it.id == page }?.label)
        Row(Modifier.fillMaxWidth().testTag(DriveTags.SYNC_STRIP).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pages.forEach { p -> Pill(p.label, { page = p.id }, icon = IconCatalog.vectorOrDefault(p.icon), filled = page == p.id) }
        }
        AnimatedContent(targetState = page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "sync_page", modifier = Modifier.weight(1f)) { id ->
            when (id) {
                "git" -> GitReposScreen(git, actions, nextRun)
                "rclone" -> RcloneSyncScreen(rclone, prefsSnap, actions, ::say)
                "mounts" -> MountsSyncScreen(rclone, prefsSnap, actions, ::say)
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
        SnackbarHost(snackbar)
    }
}
