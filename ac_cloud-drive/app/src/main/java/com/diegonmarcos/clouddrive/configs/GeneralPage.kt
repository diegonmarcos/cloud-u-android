package com.diegonmarcos.clouddrive.configs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.StatusLight

/**
 * #603 CONFIGS ▸ GENERAL — #579's Configs page, unchanged in substance and now one of the
 * six declared sub-pages: storage access (a StatusLight that is a LOOK at the grant, with
 * the Grant pill), Sign in (#587, the fleet's shared libs:auth surface, applied by
 * DriveAuthApply), the Files defaults, and the declared sync schedule (read-only, it is
 * build.json's). Removable storage and About moved to ▸ Others.
 */
@Composable
fun GeneralPage(prefs: DrivePrefs, actions: DriveActions, hasAccess: Boolean, modifier: Modifier = Modifier) {
    val snap by prefs.snapshot.collectAsState()
    LazyColumn(modifier.fillMaxSize()) {
        item {
            DriveCard(stringResource(R.string.configs_storage_access), light = StatusLight.of(hasAccess), summary = stringResource(R.string.configs_storage_access_body), tag = DriveTags.CONFIGS_CARD) {
                if (!hasAccess) PillRow { Pill(stringResource(R.string.files_grant_access), { actions.requestStorageAccess() }, filled = true) }
            }
        }
        // #587 THE fleet sign-in (libs:auth), in this app's card: what it yields is applied by DriveAuthApply.
        item { SignInCard() }
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
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
