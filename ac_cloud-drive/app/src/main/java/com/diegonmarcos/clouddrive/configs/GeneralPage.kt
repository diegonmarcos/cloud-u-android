package com.diegonmarcos.clouddrive.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.ui.StatusLight
import kotlinx.coroutines.launch

/**
 * #1216 CONFIGS ▸ GENERAL in four parts: Explorer (storage access, Sign in #587 via libs:auth applied by DriveAuthApply,
 * the Files defaults), Manage (the Data scan choice), Agents (the screenshot agent switch), then removable storage,
 * the privileged shell and About (the former Others page, folded in). The declared sync schedule rides under Explorer
 * (read-only, it is build.json's). #1333 the sections are drawn from ui.general.anchors (an icon row pinned on top scrolls to each
 * one) and a fifth, Perms, lists every permission the app needs with its live status (GeneralPerms).
 */
@Composable
fun GeneralPage(prefs: DrivePrefs, actions: DriveActions, hasAccess: Boolean, rcloneVersion: String? = null, modifier: Modifier = Modifier) {
    val snap by prefs.snapshot.collectAsState()
    val general = Declarations.general
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize()) {
        // #1333 the anchor row stays above the list (so it is always visible); a tap scrolls to that section's item.
        GeneralAnchorRow(general.anchors, { index -> scope.launch { list.animateScrollToItem(index) } })
        // One item per declared anchor, IN the declared order: the item index IS the anchor index. Kotlin names a section only
        // to draw it; which sections exist and in which order is ui.general.anchors (test-drive-general-perms.sh diffs both ways).
        LazyColumn(Modifier.weight(1f).fillMaxSize(), state = list) {
            items(general.anchors, key = { it.id }) { a ->
                Column {
                    when (a.id) {
                        "explorer" -> ExplorerSection(prefs, actions, hasAccess, snap)
                        "manage" -> ManageSection(prefs, snap)
                        "agents" -> AgentsSection(prefs, snap)
                        "perms" -> { SectionHeader(stringResource(R.string.configs_section_perms)); GeneralPermsSection(general.perms) }
                        "device" -> { SectionHeader(stringResource(R.string.configs_section_device)); OthersSection(prefs, actions, rcloneVersion) }
                    }
                }
            }
        }
    }
}

// #1216 General is three sections named after the tabs they configure, then what used to be Others.
@Composable
private fun ExplorerSection(prefs: DrivePrefs, actions: DriveActions, hasAccess: Boolean, snap: DrivePrefs.Snapshot) {
    SectionHeader(stringResource(R.string.configs_section_explorer))
    DriveCard(stringResource(R.string.configs_storage_access), light = StatusLight.of(hasAccess), summary = stringResource(R.string.configs_storage_access_body), tag = DriveTags.CONFIGS_CARD) {
        if (!hasAccess) PillRow { Pill(stringResource(R.string.files_grant_access), { actions.requestStorageAccess() }, filled = true) }
    }
    // #587 THE fleet sign-in (libs:auth), in this app's card: what it yields is applied by DriveAuthApply.
    SignInCard()
    DriveCard(stringResource(R.string.configs_files), tag = DriveTags.CONFIGS_CARD) {
        Text(stringResource(R.string.configs_default_sort), Modifier.padding(top = DriveMetrics.pad), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.padding(top = DriveMetrics.gapWide), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
            Declarations.files.sortKeys.forEach { key -> Pill(sortLabel(key), { prefs.setDefaultSort(key) }, filled = snap.defaultSort == key) }
        }
        ToggleRow(stringResource(R.string.configs_show_hidden), snap.showHidden) { prefs.setShowHidden(it) }
        ToggleRow(stringResource(R.string.configs_dual_pane), snap.dualPane) { prefs.setDualPane(it) }
        ToggleRow(stringResource(R.string.configs_thumbnails), snap.thumbnails) { prefs.setThumbnails(it) }
    }
}

@Composable
private fun ManageSection(prefs: DrivePrefs, snap: DrivePrefs.Snapshot) {
    SectionHeader(stringResource(R.string.configs_section_manage))
    DriveCard(stringResource(R.string.configs_manage_data), tag = DriveTags.CONFIGS_CARD) {
        Text(stringResource(R.string.configs_manage_data_body), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow(stringResource(R.string.configs_manage_folders), snap.let { prefs.dataFolders() }) { prefs.setDataFolders(it) }
    }
}

@Composable
private fun AgentsSection(prefs: DrivePrefs, snap: DrivePrefs.Snapshot) {
    SectionHeader(stringResource(R.string.configs_section_agents))
    DriveCard(stringResource(R.string.configs_agent_screenshots), tag = DriveTags.CONFIGS_CARD) {
        Text(stringResource(R.string.configs_agent_screenshots_body), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleRow(stringResource(R.string.configs_agent_screenshots_on), snap.let { prefs.screenshotAgentEnabled() }) { prefs.setScreenshotAgentEnabled(it) }
    }
    DriveCard(
        stringResource(R.string.configs_sync_schedule), light = StatusLight.State.UNVERIFIABLE,
        summary = stringResource(R.string.sync_schedule, BuildConfig.GIT_SYNC_INTERVAL_MINUTES, stringResource(if (BuildConfig.GIT_SYNC_REQUIRE_UNMETERED) R.string.sync_network_unmetered else R.string.sync_network_any)),
        tag = DriveTags.CONFIGS_CARD,
    ) { Text(stringResource(R.string.configs_sync_schedule_body), Modifier.padding(top = DriveMetrics.gap), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = DriveMetrics.gapWide), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun sortLabel(key: String): String = when (key) {
    "size" -> stringResource(R.string.files_sort_size)
    "modified" -> stringResource(R.string.files_sort_modified)
    "type" -> stringResource(R.string.files_sort_type)
    else -> stringResource(R.string.files_sort_name)
}
