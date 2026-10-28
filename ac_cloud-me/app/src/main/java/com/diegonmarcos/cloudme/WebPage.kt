package com.diegonmarcos.cloudme

import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * A page that IS the web page.
 *
 * Profile > Professional and Profile > Personal are the LinkedIn and Instagram
 * layouts from front-diegonmarcos/b-Media/mySocials, rendered by loading those
 * exact files out of assets. They were reimplemented with this app's blocks
 * first, and that was wrong: a reimplementation is a second design to keep in
 * step, and it was visibly not the same page. The HTML and CSS already exist
 * and are already the design — so ship them.
 *
 * data/regen-web.py copies each page plus everything it references, so the
 * bundle is what the browser would have fetched. Images and fonts still come
 * from the CDN the pages already use, which is why this needs the network the
 * app already declares.
 *
 * 2026-10-16 owner decision: all apps must be Compose. The WebView is the one thing on the page
 * and stays a WebView, hosted by AndroidView; Back walks its own history first and the page's
 * state survives a recreation.
 * `{"kind":"fragment","id":"web","url":"mysocials/linkedin.html"}`
 *
 * Profile > Social, Health and DD are the same kind of page (cloud-me-profiles b_Profiles/mySocials). Health and DD
 * add `"inject":"health"|"dd"`: the bundled page is the public SCHEMA only and reads "Locked — sign in";
 * once the page has loaded, the owner's values from the on-device store (imported from the private repo
 * cloud-me-pvt-data, profiles/<id>.data.json) are handed to `window.CloudMeProfile.inject(...)`. The values
 * never ship in assets. `CloudMeBridge.importData()` lets the page ask for that import.
 */
@Composable
fun WebPage(block: JSONObject, modifier: Modifier = Modifier) {
    val url = block.optString("url")
    val inject = block.optString("inject")
    val ctx = LocalContext.current
    /** The page's WebView state (history, scroll), refreshed whenever a page finishes loading. */
    val saved = rememberSaveable { Bundle() }
    // Back walks the page's own history before it leaves the section — the
    // profiles have internal navigation and losing it would strand you.
    var canGoBack by mutableStateOf(false)
    val web = remember { mutableStateOf<WebView?>(null) }
    BackHandler(enabled = canGoBack) { web.value?.goBack() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || inject.isBlank()) return@rememberLauncherForActivityResult
        runCatching {
            val schema = loadSchema(ctx, inject)!!
            val data = JSONObject(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            if (ProfileRecord.validate(schema, data).isEmpty()) {
                ProfileStore.save(ctx, if (inject == "health") ProfileStore.HEALTH else ProfileStore.DD, data)
                web.value?.let { pushProfile(it, ctx, inject) }
            }
        }
    }

    // #1164 the page's scroll feeds the shell's one chrome collapse (first row, second row, bottom nav).
    val collapse = com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse.current
    // #1198 a remote page (Lnktree) can lose its renderer process; an unhandled loss takes the whole app down
    // (WebViewClient.onRenderProcessGone returns false -> the process is killed). Handle it and build a fresh WebView.
    var generation by remember { mutableStateOf(0) }
    androidx.compose.runtime.key(generation) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        update = { com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(it, collapse) },
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true          // the pages hydrate from PORTAL_DATA
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.mediaPlaybackRequiresUserGesture = false
                // Same-origin file access, so a page in assets can pull its own
                // stylesheets and data scripts. Nothing outside assets is reachable.
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                        saved.clear()
                        canGoBack = false
                        web.value = null
                        generation++   // drops this WebView (onRelease destroys it) and creates a new one
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.let { com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.inject(it) }
                        view?.let { if (inject.isNotBlank()) pushProfile(it, ctx, inject) }
                        view?.saveState(saved)
                        canGoBack = view?.canGoBack() == true
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        canGoBack = view?.canGoBack() == true
                    }
                }
                if (inject.isNotBlank()) addJavascriptInterface(object {
                    @JavascriptInterface fun importData() { post { picker.launch(arrayOf("application/json", "text/plain", "*/*")) } }
                }, "CloudMeBridge")
                setBackgroundColor(0xFF0B0414.toInt())
                web.value = this
                // the scroll bridge must exist BEFORE the first load: a JavaScript interface added later only reaches pages loaded after it
                com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(this, collapse)
                if (!saved.isEmpty) restoreState(saved) else loadUrl(if (url.startsWith("https://")) url else "file:///android_asset/$url")
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
    }
}

/** Hands the stored values of Health/DD to the loaded page; a page with nothing stored stays locked. */
private fun pushProfile(view: WebView, ctx: android.content.Context, id: String) {
    val doc = runCatching { ProfileStore.load(ctx, if (id == "health") ProfileStore.HEALTH else ProfileStore.DD) }.getOrNull() ?: return
    view.evaluateJavascript("window.CloudMeProfile&&window.CloudMeProfile.inject(${JSONObject.quote(doc.toString())})", null)
}
