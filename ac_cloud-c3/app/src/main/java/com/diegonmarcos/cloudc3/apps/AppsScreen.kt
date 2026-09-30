package com.diegonmarcos.cloudc3.apps

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.cloudc3.ui.C3Metrics
import com.diegonmarcos.cloudc3.ui.IconCatalog

/**
 * #648 THE APPS TAB — Watchdog · Morpheus · WatchTower.
 *
 * All three are ALREADY standalone fleet members with their own directory, build.json, ship
 * workflow and applicationId, so this tab LAUNCHES them and declares no page of its own.
 * That is the same `extapp:` idiom aa_cloud-superapp's ui.sections[c3] used for exactly
 * these three tiles; it moved here with the section rather than being re-implemented, which
 * is why nothing in this app knows what any of the three DOES.
 *
 * Each tile RESOLVES its package against the device instead of assuming it is installed
 * (AndroidManifest declares all three under <queries>, without which API 30+ reports an
 * uninstalled-looking null for an app that is present). A missing app therefore reads
 * "Not installed" in words, instead of a tap that silently does nothing — the failure the
 * ac_c3-watchtower application-id note calls "a tab that opens nothing".
 */
@Composable
fun AppsScreen(reselectTick: Int) {
    val ctx = LocalContext.current
    val tiles = Declarations.externalApps
    // Keyed on reselectTick so re-tapping the Apps tab re-resolves what is installed:
    // the user's reason for tapping again is usually that they just installed one.
    val installed = remember(reselectTick) { tiles.associate { it.id to isInstalled(ctx, it.packageName) } }

    LazyColumn(
        Modifier.fillMaxSize().testTag(TAG_APPS),
        contentPadding = PaddingValues(C3Metrics.gutter),
        verticalArrangement = Arrangement.spacedBy(C3Metrics.gap),
    ) {
        items(tiles, key = { it.id }) { tile ->
            val present = installed[tile.id] == true
            AppTile(
                label = tile.label,
                icon = tile.icon,
                present = present,
                onOpen = { launch(ctx, tile.packageName, tile.label) },
            )
        }
    }
}

internal const val TAG_APPS: String = "c3_apps_list"

@Composable
private fun AppTile(label: String, icon: String, present: Boolean, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(C3Metrics.corner))
            .background(MaterialTheme.colorScheme.surface)
            .border(C3Metrics.hairline, MaterialTheme.colorScheme.outline, RoundedCornerShape(C3Metrics.corner))
            .then(if (present) Modifier.clickable { onOpen() } else Modifier)
            .padding(C3Metrics.cardPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = IconCatalog.painter(icon),
            contentDescription = null,
            modifier = Modifier.size(C3Metrics.iconSize),
            tint = if (present) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(C3Metrics.inner))
        Text(label, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(C3Metrics.tight))
        Text(
            stringResource(if (present) R.string.apps_open else R.string.apps_not_installed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Whether [pkg] is on this device. Resolved through the launch intent rather than
 * getPackageInfo: an app with no launcher activity could not be opened by this tile even if
 * it were installed, so "installed" here means precisely "openable", which is what the tile
 * offers. Returns false rather than throwing when the package is invisible or absent.
 */
internal fun isInstalled(ctx: Context, pkg: String): Boolean =
    runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)

/** Opens [pkg], and says so when it cannot rather than failing silently. */
internal fun launch(ctx: Context, pkg: String, label: String) {
    val intent = runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
    if (intent == null) {
        Toast.makeText(ctx, ctx.getString(R.string.apps_launch_failed, label), Toast.LENGTH_SHORT).show()
        return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }.onFailure {
        Toast.makeText(ctx, ctx.getString(R.string.apps_launch_failed, label), Toast.LENGTH_SHORT).show()
    }
}
