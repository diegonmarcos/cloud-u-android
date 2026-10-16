package com.diegonmarcos.cloudnav

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.superapp.uikit.CloudKitTheme

/**
 * "Space View" -- renamed from the earlier "Constellations"/MilkyWayActivity
 * screen and refocused onto the Solar System (planets, moons, orbits) as
 * the primary navigable 3D content, with star dust and constellation
 * stars as toggleable backdrop layers. A three.js WebGL scene (bundled
 * locally, no network needed). See assets/space/CREDITS.md and
 * assets/space/space_view.html for the full technical writeup, data
 * provenance, and scale disclosures.
 *
 * Served via WebViewAssetLoader (https://appassets.androidplatform.net/...)
 * instead of loadUrl("file:///android_asset/...") -- required because
 * Chromium's fetch() API refuses file:// requests by CORS policy, and
 * this screen's data (constellation lines, planetary elements) loads via
 * fetch(). This is Google's own documented fix for that bug class.
 */
class SpaceViewActivity : AppCompatActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { CloudKitTheme(TrackerInk.palette()) { AndroidView(factory = { ctx -> createWebView(ctx) }, modifier = Modifier.fillMaxSize()) } }
        })
    }

    /** The scene itself stays a WebView (three.js); Compose only hosts it. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: android.content.Context): WebView = WebView(ctx).also { wv ->
        webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
        }
        wv.loadUrl("https://appassets.androidplatform.net/assets/space/space_view.html")
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
