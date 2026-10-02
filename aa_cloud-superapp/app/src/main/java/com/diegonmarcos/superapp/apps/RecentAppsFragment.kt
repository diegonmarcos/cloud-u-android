package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.datamanager.AppUsageProvider
import com.diegonmarcos.superapp.launcher.AppLongPressMenu
import com.diegonmarcos.superapp.ui.LauncherPalette

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * Full-screen grid of the last 24 recently-opened Android apps, 3 per
 * row, icon + label. Data source: AppUsageProvider.recentUsed (ranked
 * most-recent first). Own package filtered out; packages that cannot be
 * resolved to a launchable activity are skipped silently.
 *
 * Reachable via tile target "page:recentapps/grid".
 *
 * Compose since #773: a LazyVerticalGrid of three columns (the trailing row
 * stays left-aligned by construction, which the View version needed weighted
 * spacers for). A tap launches, a long-press opens the shared app menu, and
 * the labels take the palette's primary ink instead of a white literal.
 */
class RecentAppsFragment : KitComposeFragment() {

    internal data class AppInfo(val pkg: String, val label: String, val icon: Drawable)

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val recent = remember { recentApps(ctx) }
        if (recent.isEmpty()) {
            KitEmptyState(title = null, caption = "") // MUTANT M7
            return
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 96.dp),
        ) {
            items(recent, key = { it.pkg }) { AppTile(ctx, it) }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AppTile(ctx: Context, a: AppInfo) {
        val p = LocalKitPalette.current
        val icon = remember(a.pkg) { a.icon.toBitmap().asImageBitmap() }
        Column(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        runCatching {
                            val intent = ctx.packageManager.getLaunchIntentForPackage(a.pkg)
                            if (intent != null) ctx.startActivity(intent)
                        }
                    },
                    onLongClick = { AppLongPressMenu.show(ctx, a.pkg) },
                )
                .padding(6.dp)
                .testTag(tileTag(a.pkg)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Image(icon, contentDescription = null, modifier = Modifier.size(52.dp))
            Text(a.label, color = p.textPrimary, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
    }

    companion object {
        fun newInstance() = RecentAppsFragment()

        internal fun tileTag(pkg: String): String = "recent:app:$pkg"

        /** The last 24 launchable apps, most recent first, this app excluded. */
        internal fun recentApps(ctx: Context): List<AppInfo> {
            // Usage first: with no usage access there is nothing to resolve, and the launcher
            // service is not asked for every activity on the device for nothing.
            val used = AppUsageProvider.recentUsed(ctx).filter { it != ctx.packageName }
            if (used.isEmpty()) return emptyList()
            val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val byPkg = launcher.getActivityList(null, Process.myUserHandle())
                .groupBy { it.applicationInfo.packageName }
            fun resolve(pkg: String): AppInfo? {
                val info = byPkg[pkg]?.firstOrNull() ?: return null
                return AppInfo(pkg, info.label.toString(), info.getIcon(ctx.resources.displayMetrics.densityDpi))
            }
            return used.asSequence()
                .mapNotNull { resolve(it) }
                .take(24)
                .toList()
        }
    }
}
