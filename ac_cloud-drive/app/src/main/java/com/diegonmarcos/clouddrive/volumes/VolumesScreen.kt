package com.diegonmarcos.clouddrive.volumes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.sync.MountsSyncScreen
import com.diegonmarcos.clouddrive.sync.RcloneCoordinator
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.ToolbarIsland
import kotlinx.coroutines.launch

/**
 * #604/#613 VOLUMES — FOUR DECLARED CLASSES over one body, regrouped into TWO SECTIONS.
 * build.json::ui.volumes.classes names Cloud-Constellation (our same-signature fleet apps),
 * Cloud-Containers (the fleet's container mounts), Cloud-Machines (VMs, PCs, phones) and S3 (OCI
 * buckets and Google Drive); #613's ui.volumes.sections reparents those four class pills under
 * two headers — Cloud Constellation (the mesh/cloud side) and Personal Mounts (Machines,
 * Containers, S3, carrying a sign-in affordance) — and a class no section claims falls under an
 * 'Others' header rather than vanishing (the #292 rule). The regroup is a pure reparent of the
 * strip: the `when` below is still the ONE place Kotlin names a class id, unchanged, and
 * test/test-drive-shell.sh diffs it against the declaration in both directions.
 *
 * The Personal Mounts sign-in is a LINK, not an auth surface: it deep-links to cloud-sa's
 * Profile ▸ Connect (libs:auth, the fleet's ONE sign-in that holds the SSH keys and the Authelia
 * web-auth flow) via [DriveActions.openAuthProfile]. build.json::ui.volumes.personal_auth
 * declares the target; nothing here reimplements auth.
 *
 * The device's own removable volumes stay a header ABOVE the strip: they are DISCOVERED
 * (Places.discovered), never declared, and #603's capability is not lost by the redesign.
 *
 * This file is a HOST: it lists and it routes. Cloud-Containers is the very same
 * [MountsSyncScreen] Configs ▸ Mounts draws, scoped to the container connections, because
 * libs:mounts (#567) already owns the connections, the credentials and the browse-through.
 */
@Composable
fun VolumesScreen(rclone: RcloneCoordinator, prefs: DrivePrefs, actions: DriveActions, onOpenPath: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val snap by prefs.snapshot.collectAsState()
    val hasAccess = remember { Places.hasAllFilesAccess(ctx) }
    val volumes = remember(snap, hasAccess) { Places.discovered(ctx, snap, hasAccess).filter { it.kind == Places.Kind.PATH } }
    val classes = Declarations.volumes.classes
    var current by rememberSaveable { mutableStateOf(classes.firstOrNull()?.id ?: "") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    Column(modifier.fillMaxSize()) {
        ToolbarIsland(title = stringResource(R.string.volumes_title), subtitle = classes.firstOrNull { it.id == current }?.label)
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
        val auth = Declarations.volumes.personalAuth
        Column(Modifier.fillMaxWidth().testTag(DriveTags.VOLUMES_STRIP)) {
            Declarations.volumes.sections.forEach { sec ->
                ClassStrip(sec.label, sec.classIds.mapNotNull { id -> classes.firstOrNull { it.id == id } }, current, { current = it }) {
                    if (sec.auth && auth != null) Pill(
                        stringResource(R.string.volumes_personal_auth),
                        { if (!actions.openAuthProfile(auth.pkg, auth.target, auth.extra)) say(ctx.getString(R.string.volumes_personal_auth_unavailable)) },
                        icon = IconCatalog.vectorOrDefault(auth.icon),
                    )
                }
            }
            val others = Declarations.volumes.unsectioned()
            if (others.isNotEmpty()) ClassStrip(stringResource(R.string.volumes_others), others, current, { current = it })
        }
        AnimatedContent(targetState = current, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "volumes_class", modifier = Modifier.weight(1f)) { id ->
            when (id) {
                "constellation" -> ConstellationVolumes(onOpenPath)
                "containers" -> MountsSyncScreen(rclone, prefs, actions, ::say, connections = Declarations.connections.filter { it.machine == Declarations.MACHINE_CONTAINER })
                "machines" -> MachineVolumes()
                "s3" -> S3Volumes(rclone)
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
        SnackbarHost(snackbar)
    }
}

/**
 * #613 one section's header and the horizontally-scrolling row of its class pills, plus any
 * [trailing] affordance (the Personal Mounts sign-in). The pill idiom is unchanged from #604;
 * only which pills sit under which header moved.
 */
@Composable
private fun ClassStrip(
    label: String,
    classes: List<Declarations.VolumeClassDecl>,
    current: String,
    onPick: (String) -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    SectionHeader(label, count = classes.size)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        classes.forEach { c -> Pill(c.label, { onPick(c.id) }, icon = IconCatalog.vectorOrDefault(c.icon), filled = current == c.id) }
        trailing()
    }
}
