package com.diegonmarcos.cgomniverse.ui

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.cgomniverse.Decl
import com.diegonmarcos.cgomniverse.Offline
import com.diegonmarcos.cgomniverse.R
import com.diegonmarcos.cgomniverse.World
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.delay

/** Galaxy: the world loaded now (enter it again, or open it in the browser) and every selectable world of the Store's data. */
@Composable
fun GalaxyPage(loaded: World?, onEnter: (World) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    LazyColumn(
        Modifier.fillMaxSize().testTag(Tags.GALAXY),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "loaded") {
            KitCard(Modifier.testTag(Tags.GALAXY_LOADED)) {
                Text(stringResource(R.string.galaxy_loaded), style = MaterialTheme.typography.labelMedium, color = p.accent)
                if (loaded == null) {
                    Text(stringResource(R.string.galaxy_none), style = MaterialTheme.typography.bodyMedium, color = p.textSecondary,
                        modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text(loaded.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = p.textPrimary,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                    WorldBadges(loaded)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KitButton(onClick = { onEnter(loaded) }, modifier = Modifier.testTag(Tags.GALAXY_ENTER)) {
                            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.galaxy_enter))
                        }
                        KitOutlinedButton(onClick = { Web.openInBrowser(ctx, loaded.url) }) {
                            Icon(Icons.Filled.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open_browser))
                        }
                    }
                }
            }
        }
        item(key = "title") {
            Text(stringResource(R.string.galaxy_worlds, Decl.worlds.size), style = MaterialTheme.typography.labelMedium, color = p.textSecondary,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }
        items(Decl.worlds, key = { it.id }) { w ->
            val current = w.id == loaded?.id
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (current) p.surfaceSelected else p.surface)
                    .then(if (current) Modifier.border(1.dp, p.accent, RoundedCornerShape(12.dp)) else Modifier)
                    .clickable { onEnter(w) }.padding(horizontal = 14.dp, vertical = 12.dp).testTag(Tags.galaxyWorld(w.id)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(w.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = p.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(w.framework, style = MaterialTheme.typography.labelSmall, color = p.textSecondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                    WorldBadges(w)
                }
                Icon(Icons.Filled.PlayArrow, null, tint = if (current) p.accent else p.textSecondary)
            }
        }
    }
}

/**
 * One world, full screen: a WebView with WebGL on and hardware acceleration, a bar with close, reload and open-in-browser, and
 * for a VR entry the WebXR state this WebView reports (Android's WebView has no immersive WebXR today, so a VR world offers the
 * browser for it). Back walks the page's history, then closes. A main-frame error (an unreachable host, a blocked scheme) or a
 * crashed renderer replaces the page with a fallback card: Open in browser, or Reload. A world that is offline ([Offline]: no page
 * within the load watchdog, the host never answering, or HTTP 404/410) gets the offline card instead: Open in browser, its Wayback
 * Machine copy, or Reload.
 */
@Composable
fun WorldView(w: World, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember(w.id) { mutableIntStateOf(0) }
    var error by remember(w.id) { mutableStateOf<String?>(null) }
    var xr by remember(w.id) { mutableStateOf<Boolean?>(null) }
    var generation by remember(w.id) { mutableIntStateOf(0) }
    // every load attempt (first load, reload, re-create) restarts the watchdog; finished = the main frame loaded a page
    var attempt by remember(w.id) { mutableIntStateOf(0) }
    var finished by remember(w.id) { mutableStateOf(false) }
    var offline by remember(w.id) { mutableStateOf(false) }
    val reload = { error = null; offline = false; finished = false; attempt++; if (web == null) generation++ else web?.reload(); Unit }
    LaunchedEffect(w.id, attempt) {
        val start = System.currentTimeMillis()
        delay(Offline.LOAD_TIMEOUT_MS)
        if (error == null && Offline.timedOut(System.currentTimeMillis() - start, finished)) { offline = true; web?.stopLoading() }
    }
    val crashed = stringResource(R.string.world_crashed)

    BackHandler { val v = web; if (v != null && v.canGoBack()) v.goBack() else onClose() }

    Column(Modifier.fillMaxSize().background(Color.Black).testTag(Tags.WORLD)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag(Tags.WORLD_CLOSE)) { Icon(Icons.Filled.Close, stringResource(R.string.world_close), tint = p.textPrimary) }
            Column(Modifier.weight(1f)) {
                Text(w.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Uri.parse(w.url).host.orEmpty(), style = MaterialTheme.typography.labelSmall, color = p.textSecondary, maxLines = 1)
            }
            if (w.vr) {
                IconButton(
                    onClick = { if (xr != true) Web.openInBrowser(ctx, w.url) },
                    modifier = Modifier.testTag(Tags.WORLD_VR),
                ) { Icon(Icons.Filled.ViewInAr, stringResource(if (xr == true) R.string.world_xr_ready else R.string.world_xr_browser), tint = if (xr == true) p.ok else p.accent) }
            }
            IconButton(onClick = reload, modifier = Modifier.testTag(Tags.WORLD_RELOAD)) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.world_reload), tint = p.textPrimary)
            }
            IconButton(onClick = { Web.openInBrowser(ctx, w.url) }, modifier = Modifier.testTag(Tags.WORLD_BROWSER)) {
                Icon(Icons.Filled.OpenInBrowser, stringResource(R.string.open_browser), tint = p.textPrimary)
            }
        }
        if (progress in 1..99 && !offline) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = p.accent)
        if (w.vr && xr == false) Text(stringResource(R.string.world_xr_note), style = MaterialTheme.typography.labelSmall, color = p.textSecondary,
            modifier = Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 14.dp, vertical = 4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(w.id, generation) {
                AndroidView(factory = { c ->
                    Web.create(c).also { wv ->
                        wv.webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                            // protected media (some worlds stream DRM video) is the one resource granted; nothing else is asked of the user
                            override fun onPermissionRequest(request: PermissionRequest) {
                                val ok = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                                if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
                            }
                        }
                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val s = request.url.scheme.orEmpty()
                                if (s == "http" || s == "https") return false
                                Web.openInBrowser(c, request.url.toString()); return true
                            }
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { error = null }
                            override fun onPageFinished(view: WebView, url: String) {
                                if (error == null && !offline) finished = true
                                if (w.vr) view.evaluateJavascript("(function(){return !!(navigator.xr&&navigator.xr.isSessionSupported)})()") { xr = it == "true" }
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                if (!request.isForMainFrame) return
                                if (Offline.isOfflineError(err.errorCode)) offline = true else error = err.description?.toString()
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame && Offline.isOfflineHttp(response.statusCode)) offline = true
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                if (web === view) web = null
                                view.destroy(); error = crashed; return true
                            }
                        }
                        wv.loadUrl(w.url)
                        web = wv
                    }
                }, modifier = Modifier.fillMaxSize().testTag(Tags.WORLD_WEB))
            }
            // a world that cannot load here gets a clear card, never the WebView's raw error page
            if (offline) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0D16)).padding(20.dp), contentAlignment = Alignment.Center) {
                    KitCard(Modifier.testTag(Tags.WORLD_OFFLINE)) {
                        Text(stringResource(R.string.world_offline_title), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = p.textPrimary)
                        Text(stringResource(R.string.world_offline_body, w.name), style = MaterialTheme.typography.bodyMedium,
                            color = p.textSecondary, modifier = Modifier.padding(top = 6.dp))
                        if (w.checked.isNotEmpty()) Text(stringResource(R.string.world_offline_checked, w.status.name.lowercase(), w.checked),
                            style = MaterialTheme.typography.labelSmall, color = p.textSecondary, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KitButton(onClick = { Web.openInBrowser(ctx, w.url) }, modifier = Modifier.testTag(Tags.WORLD_OFFLINE_BROWSER)) {
                                Icon(Icons.Filled.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open_in_browser))
                            }
                            if (w.wayback.isNotEmpty()) KitOutlinedButton(onClick = { Web.openInBrowser(ctx, w.wayback) }, modifier = Modifier.testTag(Tags.WORLD_OFFLINE_WAYBACK)) {
                                Icon(Icons.Filled.History, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open_wayback))
                            }
                        }
                        KitOutlinedButton(onClick = reload, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.world_reload))
                        }
                    }
                }
            }
            if (!offline) error?.let { msg ->
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0D16)).padding(20.dp), contentAlignment = Alignment.Center) {
                    KitCard(Modifier.testTag(Tags.WORLD_FALLBACK)) {
                        Text(stringResource(R.string.world_fallback_title), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = p.textPrimary)
                        Text(stringResource(R.string.world_fallback_body, w.name), style = MaterialTheme.typography.bodyMedium,
                            color = p.textSecondary, modifier = Modifier.padding(top = 6.dp))
                        Text(msg, style = MaterialTheme.typography.labelSmall, color = p.textSecondary, modifier = Modifier.padding(top = 6.dp))
                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KitButton(onClick = { Web.openInBrowser(ctx, w.url) }, modifier = Modifier.testTag(Tags.WORLD_FALLBACK_BROWSER)) {
                                Icon(Icons.Filled.OpenInBrowser, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open_in_browser))
                            }
                            KitOutlinedButton(onClick = reload) {
                                Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.world_reload))
                            }
                        }
                    }
                }
            }
        }
    }
    DisposableEffect(w.id) { onDispose { web?.destroy(); web = null } }
}
