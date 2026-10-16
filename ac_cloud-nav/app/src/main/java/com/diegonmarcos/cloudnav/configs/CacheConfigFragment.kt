package com.diegonmarcos.cloudnav.configs

import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.cloudnav.tracker.TrackerPage
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.io.File

/**
 * Cache tab: reports real on-disk size for the app's actual cache
 * mechanisms and offers a real clear action for each, rather than one
 * vague "clear cache" button. There are exactly three, per what this app
 * actually uses (verified against the code, not assumed):
 *
 *  1. MapLibre Native's own offline/ambient tile cache (`mbgl-offline.db*`
 *     in [Context.getFilesDir]) -- used by the main native map.
 *  2. Android WebView's HTTP cache -- shared by every WebView-hosted
 *     screen (Terrain, Galaxy). Android does not expose a way to separate
 *     this by which page/style filled it (it's one per-app Chromium
 *     cache, not per-Activity), so this is reported and cleared as one
 *     bucket -- not faked as a per-map breakdown the platform doesn't
 *     actually provide.
 *  3. The app's general cache directory ([Context.getCacheDir]), which
 *     the WebView cache technically lives inside of on current Android
 *     versions, plus anything else that lands there. Reported separately
 *     from (2) since it also can be cleared as a whole via the OS-level
 *     cache-dir convention (same directory Android itself may purge under
 *     storage pressure).
 *
 * Compose (KitComposeFragment): the sizes are state, re-read on resume and after every clear.
 */
class CacheConfigFragment : KitComposeFragment() {

    private var sizes by mutableStateOf(CacheSizes())

    override fun palette(): KitPalette = TrackerInk.palette()

    override fun onResume() {
        super.onResume()
        sizes = CacheDisk.read(requireContext())
    }

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        fun cleared(n: Long) {
            Toast.makeText(ctx, if (n > 0) "Cleared" else "Already empty", Toast.LENGTH_SHORT).show()
            sizes = CacheDisk.read(ctx)
        }
        TrackerPage {
            KitSectionHeader("Cache", "Real on-disk sizes for this app's actual cache mechanisms — not an estimate.")
            CacheSection(CacheTags.MAPLIBRE, "Native map (MapLibre)", "Vector/raster tiles cached by the main map screen.",
                sizes.mapLibre, "Clear native map cache") { cleared(CacheDisk.clearMapLibre(ctx)) }
            CacheSection(CacheTags.WEBVIEW, "WebView maps (Terrain, Galaxy)",
                "Terrain's map/satellite tiles are fetched over the network and land here; the Galaxy view is fully bundled and barely touches this. Android caches this per-app, not per-screen, so it can't be split further than this.",
                sizes.webView, "Clear WebView cache") { CacheDisk.clearWebView(ctx); cleared(1) }
            CacheSection(CacheTags.APP, "App cache directory",
                "Everything under this app's general cache dir (includes the WebView cache above, plus anything else cached there). Same directory the OS itself may purge under low storage.",
                sizes.appCache, "Clear app cache directory") { cleared(CacheDisk.clearAppCache(ctx)) }
        }
    }
}

object CacheTags {
    const val MAPLIBRE = "cache:maplibre"
    const val WEBVIEW = "cache:webview"
    const val APP = "cache:app"
    fun value(tag: String) = "$tag:value"
    fun clear(tag: String) = "$tag:clear"
}

@Composable
private fun CacheSection(tag: String, title: String, caption: String, bytes: Long, clearLabel: String, onClear: () -> Unit) {
    val p = LocalKitPalette.current
    KitSectionHeader(title, caption)
    KitCard {
        Text(CacheFormat.size(bytes), Modifier.testTag(CacheTags.value(tag)), color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onClear, modifier = Modifier.testTag(CacheTags.clear(tag))) { Text(clearLabel) }
    }
}

data class CacheSizes(val mapLibre: Long = 0L, val webView: Long = 0L, val appCache: Long = 0L)

object CacheFormat {
    fun size(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GiB".format(java.util.Locale.US, bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.2f MiB".format(java.util.Locale.US, bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KiB".format(java.util.Locale.US, bytes / 1024.0)
        else -> "$bytes B"
    }
}

/** The real cache mechanisms: sizes and clears. */
object CacheDisk {
    fun read(ctx: Context) = CacheSizes(
        mapLibre = mapLibreSize(ctx),
        webView = dirSize(File(ctx.applicationInfo.dataDir, "app_webview")),
        appCache = dirSize(ctx.cacheDir),
    )

    // MapLibre Native's offline/ambient cache DB: verified against the pinned
    // org.maplibre.gl:android-sdk:11.7.0's own documented default (mbgl-offline.db in
    // Context.getFilesDir(), plus SQLite WAL/SHM sidecar files while a connection is open).
    private fun mapLibreFiles(ctx: Context): List<File> =
        (ctx.filesDir.listFiles() ?: emptyArray()).filter { it.name.startsWith("mbgl-offline.db") }

    fun mapLibreSize(ctx: Context): Long = mapLibreFiles(ctx).sumOf { it.length() }

    fun clearMapLibre(ctx: Context): Long {
        val before = mapLibreSize(ctx)
        mapLibreFiles(ctx).forEach { it.delete() }
        return before
    }

    // WebView.clearCache(true) is the documented API; it clears the whole app's WebView cache
    // (one per-app Chromium cache, not per instance), so a throwaway instance works.
    fun clearWebView(ctx: Context) {
        val wv = WebView(ctx)
        wv.clearCache(true)
        wv.destroy()
    }

    fun clearAppCache(ctx: Context): Long {
        val n = dirSize(ctx.cacheDir)
        ctx.cacheDir.deleteRecursively()
        return n
    }

    fun dirSize(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)
}
