package com.diegonmarcos.clouddrive.volumes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.sync.MountsSyncScreen
import com.diegonmarcos.clouddrive.sync.RcloneCoordinator
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.ToolbarIsland
import kotlinx.coroutines.launch

/**
 * #603 VOLUMES — the FULL FLEET's volumes in one tab: the device's own removable volumes
 * (discovered at runtime, never declared — Places.discovered) above, and every mesh mount
 * and declared fleet connection below, drawn by the SAME [MountsSyncScreen] the Configs ▸
 * Mounts sub-page draws, because libs:mounts (#567) already owns the connections, the
 * credentials and the browse-through. This file is a HOST: it lists and it routes, it
 * implements no mount logic and no second connection list.
 */
@Composable
fun VolumesScreen(rclone: RcloneCoordinator, prefs: DrivePrefs, actions: DriveActions, onOpenPath: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val snap by prefs.snapshot.collectAsState()
    val hasAccess = remember { Places.hasAllFilesAccess(ctx) }
    val volumes = remember(snap, hasAccess) { Places.discovered(ctx, snap, hasAccess).filter { it.kind == Places.Kind.PATH } }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    Column(modifier.fillMaxSize()) {
        ToolbarIsland(title = stringResource(R.string.volumes_title), subtitle = stringResource(R.string.volumes_hint))
        SectionHeader(stringResource(R.string.volumes_device_section), count = volumes.size)
        if (volumes.isEmpty()) {
            DriveCard(stringResource(R.string.volumes_device_none), light = StatusLight.State.UNKNOWN, summary = stringResource(R.string.volumes_device_none_hint), tag = DriveTags.VOLUMES_CARD) {
                PillRow { Pill(stringResource(R.string.configs_grant_tree), { actions.requestTreeGrant() }) }
            }
        }
        volumes.forEach { v ->
            DriveCard(v.label, light = StatusLight.of(true), summary = v.location?.path, summaryMonospace = true, tag = DriveTags.VOLUMES_CARD) {
                PillRow { Pill(stringResource(R.string.volumes_open), { v.location?.let { onOpenPath(it.path) } }, filled = true) }
            }
        }
        MountsSyncScreen(rclone, prefs, actions, ::say, Modifier.weight(1f).fillMaxWidth())
        SnackbarHost(snackbar)
    }
}
