package com.diegonmarcos.cloudme

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 */
@Composable
fun WebPage(block: JSONObject, modifier: Modifier = Modifier) {
    val url = block.optString("url")
    /** The page's WebView state (history, scroll), refreshed whenever a page finishes loading. */
    val saved = rememberSaveable { Bundle() }
    // Back walks the page's own history before it leaves the section — the
    // profiles have internal navigation and losing it would strand you.
    var canGoBack by mutableStateOf(false)
    val web = remember { mutableStateOf<WebView?>(null) }
    BackHandler(enabled = canGoBack) { web.value?.goBack() }

    // #1164 the page's scroll feeds the shell's one chrome collapse (first row, second row, bottom nav).
    val collapse = com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse.current
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
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.let { com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.inject(it) }
                        view?.saveState(saved)
                        canGoBack = view?.canGoBack() == true
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        canGoBack = view?.canGoBack() == true
                    }
                }
                setBackgroundColor(0xFF0B0414.toInt())
                web.value = this
                if (!saved.isEmpty) restoreState(saved) else loadUrl("file:///android_asset/$url")
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}
