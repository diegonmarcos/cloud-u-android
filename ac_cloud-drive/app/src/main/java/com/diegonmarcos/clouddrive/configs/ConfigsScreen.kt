package com.diegonmarcos.clouddrive.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.ToolbarIsland

/**
 * #579 CONFIGS (cloud-drive-redesign.md §6): cards — storage access (a StatusLight
 * that is a LOOK at the grant, with the Grant pill), Files defaults, the declared sync
 * schedule (read-only, it is build.json's), removable storage (the SAF grant), About.
 */
@Composable
fun ConfigsScreen(prefs: DrivePrefs, actions: DriveActions, hasAccess: Boolean, rcloneVersion: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val snap by prefs.snapshot.collectAsState()
    Column(modifier.fillMaxSize()) {
        ToolbarIsland(title = stringResource(R.string.configs_title), subtitle = BuildConfig.APPLICATION_ID)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                DriveCard(stringResource(R.string.configs_storage_access), light = StatusLight.of(hasAccess), summary = stringResource(R.string.configs_storage_access_body), tag = DriveTags.CONFIGS_CARD) {
                    if (!hasAccess) PillRow { Pill(stringResource(R.string.files_grant_access), { actions.requestStorageAccess() }, filled = true) }
                }
            }
            item {
                DriveCard(stringResource(R.string.configs_files), tag = DriveTags.CONFIGS_CARD) {
                    Text(stringResource(R.string.configs_default_sort), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Declarations.files.sortKeys.forEach { key -> Pill(sortLabel(key), { prefs.setDefaultSort(key) }, filled = snap.defaultSort == key) }
                    }
                    ToggleRow(stringResource(R.string.configs_show_hidden), snap.showHidden) { prefs.setShowHidden(it) }
                    ToggleRow(stringResource(R.string.configs_dual_pane), snap.dualPane) { prefs.setDualPane(it) }
                    ToggleRow(stringResource(R.string.configs_thumbnails), snap.thumbnails) { prefs.setThumbnails(it) }
                }
            }
            item {
                DriveCard(
                    stringResource(R.string.configs_sync_schedule), light = StatusLight.State.UNVERIFIABLE,
                    summary = stringResource(R.string.sync_schedule, BuildConfig.GIT_SYNC_INTERVAL_MINUTES, stringResource(if (BuildConfig.GIT_SYNC_REQUIRE_UNMETERED) R.string.sync_network_unmetered else R.string.sync_network_any)),
                    tag = DriveTags.CONFIGS_CARD,
                ) { Text(stringResource(R.string.configs_sync_schedule_body), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                val granted = snap.treeGrantUri != null
                DriveCard(stringResource(R.string.configs_removable), light = StatusLight.of(granted, observed = false), summary = if (granted) stringResource(R.string.files_place_granted, snap.treeGrantName ?: "") else stringResource(R.string.configs_removable_none), tag = DriveTags.CONFIGS_CARD) {
                    PillRow {
                        Pill(stringResource(R.string.configs_grant_tree), { actions.requestTreeGrant() }, filled = !granted)
                        if (granted) Pill(stringResource(R.string.configs_forget_grant), { prefs.forgetTreeGrant() })
                    }
                }
            }
            item {
                DriveCard(stringResource(R.string.configs_about), summary = BuildConfig.VERSION_NAME, tag = DriveTags.CONFIGS_CARD) {
                    AboutRow(stringResource(R.string.configs_version), BuildConfig.VERSION_NAME + " · " + BuildConfig.VERSION_CODE)
                    AboutRow(stringResource(R.string.configs_built), BuildConfig.BUILD_TIMESTAMP + " · sha-" + BuildConfig.GIT_SHORT_SHA)
                    AboutRow(stringResource(R.string.configs_engines), stringResource(R.string.configs_engines_value, rcloneVersion ?: "?"))
                    AboutRow(stringResource(R.string.sync_store), Places.initialLocations().first.path)
                }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = ctx
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun sortLabel(key: String): String = when (key) {
    "size" -> stringResource(R.string.files_sort_size)
    "modified" -> stringResource(R.string.files_sort_modified)
    "type" -> stringResource(R.string.files_sort_type)
    else -> stringResource(R.string.files_sort_name)
}
