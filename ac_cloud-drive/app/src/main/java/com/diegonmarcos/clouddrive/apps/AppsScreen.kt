package com.diegonmarcos.clouddrive.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.ToolbarIsland
import com.diegonmarcos.superapp.bottomnav.bottomNavPillShape
import kotlinx.coroutines.launch

/**
 * #579 APPS (cloud-drive-redesign.md §6): a 3-column grid of the fleet's data apps,
 * from data/drive-apps.json resolved against the constellation manifest at build time.
 * A tile is the declared glyph in a raised disc plus its label; a tile whose package
 * is not on this device carries the ○ badge and says so on tap.
 *
 * #603 a tile that declares a `route` (GitSync, RSync) does not leave the app: it hands
 * [onRoute] the DECLARED tab and sub-page ids and the shell selects them. Those two are
 * shortcuts into Configs ▸ Git and Configs ▸ Backups, the screens #567 and #330 built —
 * never a second copy, and never a package this device cannot have.
 */
@Composable
fun AppsScreen(actions: DriveActions, onRoute: (tab: String, page: String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val apps = Declarations.apps
    val installed = remember(apps) {
        apps.associate { a -> a.label to (a.routeTab.isNotBlank() || (a.packageName.isNotBlank() && runCatching { ctx.packageManager.getLaunchIntentForPackage(a.packageName) }.getOrNull() != null)) }
    }
    Column(modifier.fillMaxSize()) {
        ToolbarIsland(title = stringResource(R.string.apps_title), subtitle = stringResource(R.string.apps_hint))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag(DriveTags.APPS_GRID),
            contentPadding = PaddingValues(DriveMetrics.gutter),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(apps, key = { it.label }) { app ->
                val present = installed[app.label] == true
                Column(
                    Modifier.clip(RoundedCornerShape(DriveMetrics.cardRadius)).background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            if (app.routeTab.isNotBlank()) {
                                onRoute(app.routeTab, app.routePage)
                            } else {
                                val ok = actions.launchApp(app.packageName, app.fallbackUrl)
                                if (!ok) scope.launch { snackbar.showSnackbar(ctx.getString(R.string.chrome_not_installed, app.label)) }
                            }
                        }
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(56.dp).clip(bottomNavPillShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Text(app.icon, fontSize = 26.sp)
                        if (!present) Text(StatusLight.glyph(StatusLight.State.OFF), Modifier.align(Alignment.BottomEnd).padding(4.dp), color = colorResource(StatusLight.colourRes(StatusLight.State.OFF)), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(app.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                }
            }
        }
        SnackbarHost(snackbar)
    }
}
