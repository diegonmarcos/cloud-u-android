package com.diegonmarcos.cloudnav

import android.os.Bundle
import android.webkit.WebView
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
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

    /** The scene itself stays a WebView (three.js, WEB_WORLD); Compose only hosts it. */
    private fun createWebView(ctx: android.content.Context): WebView = CloudWeb.create(ctx, WebProfile.WEB_WORLD).also { wv ->
        webView = wv
        wv.webViewClient = object : CloudWebClient(WebProfile.WEB_WORLD, CloudWeb.assetLoader(this)) {
            override fun onRecovered(dead: WebView, fresh: WebView?) { webView = fresh }
        }
        wv.loadUrl(CloudWeb.assetUrl("space/space_view.html"))
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
