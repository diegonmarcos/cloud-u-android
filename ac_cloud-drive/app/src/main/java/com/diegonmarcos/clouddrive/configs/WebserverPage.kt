package com.diegonmarcos.clouddrive.configs

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.StatusLight

/**
 * How Configs ▸ Webserver opens cloud-c3-webserver: an EXPLICIT component intent at the app's own launcher
 * activity (ui.webserver: package from the fleet manifest, activity and fallback URL declared). Not installed
 * or refused is a false return, never a crash.
 */
object WebserverLaunch {
    fun component(d: Declarations.WebserverDecl): ComponentName =
        ComponentName(d.packageName, if (d.activity.startsWith(".")) d.packageName + d.activity else d.activity)

    fun intent(d: Declarations.WebserverDecl): Intent =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component(d)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installed(ctx: Context, d: Declarations.WebserverDecl): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(d.packageName, 0); true }.getOrDefault(false)

    fun open(ctx: Context, d: Declarations.WebserverDecl): Boolean = runCatching { ctx.startActivity(intent(d)); true }.getOrDefault(false)
}

/**
 * CONFIGS ▸ WEBSERVER: one card. Open launches cloud-c3-webserver; when it is not installed the card says
 * "Install c3-webserver" and offers the declared localhost URL instead.
 */
@Composable
fun WebserverPage(actions: DriveActions, modifier: Modifier = Modifier) {
    val decl = Declarations.webserver
    val ctx = LocalContext.current
    var installed by remember(decl) { mutableStateOf(decl != null && WebserverLaunch.installed(ctx, decl)) }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            DriveCard(
                stringResource(R.string.configs_webserver), light = StatusLight.of(installed),
                summary = stringResource(R.string.configs_webserver_body, decl?.fallbackUrl.orEmpty()), tag = DriveTags.CONFIGS_CARD,
            ) {
                if (decl != null) PillRow {
                    Pill(stringResource(R.string.configs_webserver_open), { installed = WebserverLaunch.open(ctx, decl) }, Modifier.testTag(DriveTags.WEBSERVER_OPEN), filled = installed)
                    if (!installed && decl.fallbackUrl.isNotBlank()) Pill(stringResource(R.string.configs_webserver_localhost), { actions.openUrl(decl.fallbackUrl) }, Modifier.testTag(DriveTags.WEBSERVER_LOCALHOST))
                }
                if (!installed) Text(
                    stringResource(R.string.configs_webserver_install), Modifier.padding(top = DriveMetrics.gap).testTag(DriveTags.WEBSERVER_INSTALL),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
