package com.diegonmarcos.clouddrive.configs

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.StatusLight

/**
 * #603 CONFIGS ▸ OTHERS — what is neither an engine's configuration nor a daily setting:
 * the removable-storage SAF grant (the volumes themselves are browsed from the Volumes
 * tab) and About. Both came out of #579's single Configs page when it became six.
 */
@Composable
fun OthersPage(prefs: DrivePrefs, actions: DriveActions, rcloneVersion: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val snap by prefs.snapshot.collectAsState()
    val hasAccess = remember { Places.hasAllFilesAccess(ctx) }
    val volumes = remember(snap, hasAccess) { Places.discovered(ctx, snap, hasAccess).filter { it.kind == Places.Kind.PATH } }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            val granted = snap.treeGrantUri != null
            DriveCard(
                stringResource(R.string.configs_removable), light = StatusLight.of(granted, observed = false),
                summary = if (granted) stringResource(R.string.files_place_granted, snap.treeGrantName ?: "") else stringResource(R.string.configs_removable_none),
                tag = DriveTags.CONFIGS_CARD,
            ) {
                Text(
                    stringResource(R.string.configs_removable_volumes, volumes.size),
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
