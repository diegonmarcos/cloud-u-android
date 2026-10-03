package com.diegonmarcos.clouddrive.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.superapp.bottomnav.bottomNavPillShape
import kotlinx.coroutines.launch

/**
 * #579 APPS (cloud-drive-redesign.md §6): a 3-column grid of the fleet's data apps,
 * from data/drive-apps.json resolved against the constellation manifest at build time.
 * A tile is the declared glyph in a raised disc plus its label; a tile whose package
 * is not on this device carries the ○ badge and says so on tap.
 *
 * #603 a tile that declares a `route` (GitSync, RSync) does not leave the app: it hands
 * [onRoute] the DECLARED tab and sub-page ids and the shell selects them. #609 those two
 * targets moved from Configs to the new Sync tab — never a second copy, and never a
 * package this device cannot have.
 *
 * #609 this used to be its own tab (AppsScreen, with a [ToolbarIsland][com.diegonmarcos.clouddrive.ui.ToolbarIsland]);
 * the tab is gone and [AppsGrid] is now embedded in the Home tab's Apps section, so it
 * draws only the grid — its host supplies the title.
 */
@Composable
fun AppsGrid(actions: DriveActions, onRoute: (tab: String, page: String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val apps = Declarations.apps
    val installed = remember(apps) {
        apps.associate { a -> a.label to (a.routeTab.isNotBlank() || (a.packageName.isNotBlank() && runCatching { ctx.packageManager.getLaunchIntentForPackage(a.packageName) }.getOrNull() != null)) }
    }
    // #813 the grid is laid out by its CONTENT, never by a height computed from a row count:
    // the old LazyVerticalGrid was given `appTile * rows`, which left out its own padding and
    // row spacing and a tile taller than appTile (glyph + label + padding), so the last row was
    // clipped -- and, being lazy, never even composed. Inside Home's LazyColumn the grid does
    // not scroll anyway, so plain rows measure exactly what they draw and the list scrolls the
    // last one above the bottom-nav island (DriveShell consumes the island's inset once).
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().testTag(DriveTags.APPS_GRID).padding(DriveMetrics.gutter),
            verticalArrangement = Arrangement.spacedBy(DriveMetrics.pad),
        ) {
            apps.chunked(COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.pad)) {
                    row.forEach { app ->
                        val present = installed[app.label] == true
                        Column(
                            Modifier.weight(1f).testTag(DriveTags.appTile(app.label)).clip(RoundedCornerShape(DriveMetrics.cardRadius)).background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    if (app.routeTab.isNotBlank()) {
                                        onRoute(app.routeTab, app.routePage)
                                    } else {
                                        val ok = actions.launchApp(app.packageName, app.fallbackUrl)
                                        if (!ok) scope.launch { snackbar.showSnackbar(ctx.getString(R.string.chrome_not_installed, app.label)) }
                                    }
                                }
                                .padding(vertical = DriveMetrics.padWide),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(DriveMetrics.appGlyph).clip(bottomNavPillShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                Text(app.icon, fontSize = DriveMetrics.tileGlyphText)
                                if (!present) Text(StatusLight.glyph(StatusLight.State.OFF), Modifier.align(Alignment.BottomEnd).padding(DriveMetrics.gap), color = colorResource(StatusLight.colourRes(StatusLight.State.OFF)), fontSize = DriveMetrics.statusGlyphText)
                            }
                            Spacer(Modifier.height(DriveMetrics.pad))
                            Text(app.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = DriveMetrics.gapWide))
                        }
                    }
                    // A short last row keeps its tiles the width of the others.
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        SnackbarHost(snackbar)
    }
}

private const val COLUMNS = 3
