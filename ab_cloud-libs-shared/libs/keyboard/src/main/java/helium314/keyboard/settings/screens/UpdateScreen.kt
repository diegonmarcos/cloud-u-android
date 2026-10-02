// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.PackageInfoCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.KeyboardUpdate
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference

/** Config ▸ Update (#776): installed version, the Store's update management for it, Restart. */
@Composable
fun UpdateScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val installed = remember {
        runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("?")
    }
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.keyboard_update),
        settings = emptyList(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Preference(
                name = stringResource(R.string.keyboard_update_installed),
                description = installed,
                onClick = { },
                icon = R.drawable.ic_settings_about,
            )
            Preference(
                name = stringResource(R.string.keyboard_update_store),
                description = stringResource(R.string.keyboard_update_store_summary),
                onClick = { KeyboardUpdate.openStore(ctx) },
                icon = R.drawable.ic_settings_about,
            )
            Preference(
                name = stringResource(R.string.keyboard_restart),
                description = stringResource(R.string.keyboard_restart_summary),
                onClick = { ctx.getActivity()?.let { KeyboardUpdate.restart(it) } },
                icon = R.drawable.ic_settings_advanced,
            )
        }
    }
}
