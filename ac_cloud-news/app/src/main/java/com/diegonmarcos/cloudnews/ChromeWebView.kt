package com.diegonmarcos.cloudnews

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.bottomnav.BottomNavCollapse
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse

/**
 * #1164 the page's scroll, fed into the shared chrome collapse: the WebView's own scroll listener and the page's scroller
 * (news.html scrolls inside its own container, which the WebView never sees) both end in BottomNavCollapse.onScrollDelta,
 * so reading down hides the first row and compacts the second row and the bottom nav, and reading up restores them.
 */
class ChromeScrollFeed {
    @Volatile var target: BottomNavCollapse? = null
    private val main = Handler(Looper.getMainLooper())

    fun onDelta(delta: Float) { target?.onScrollDelta(delta) }

    /** Exposed to the page as `NavChrome` (a local asset only, see MainActivity). */
    @JavascriptInterface fun onScroll(delta: Double) { main.post { onDelta(delta.toFloat()) } }
}

/** The WebView inside the shared Compose scaffold: its scroll drives the scaffold's one collapse connection. */
@Composable
fun ChromeWebView(webView: WebView, feed: ChromeScrollFeed, modifier: Modifier = Modifier) {
    val collapse = LocalNavChromeCollapse.current
    SideEffect { feed.target = collapse }
    AndroidView(factory = { webView }, modifier = modifier.fillMaxSize())
}
