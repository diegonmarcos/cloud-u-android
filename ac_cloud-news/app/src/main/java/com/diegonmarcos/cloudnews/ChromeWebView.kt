package com.diegonmarcos.cloudnews

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.bottomnav.ChromeWebScroll
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse

/**
 * The WebView inside the shared Compose scaffold: its scroll (the WebView's own and the page's inner scroller, which
 * news.html reports to `NavChrome`) and its finger state feed the scaffold's one collapse connection through the shared
 * [ChromeWebScroll], so a held drag hides every bar and the release shrinks them.
 */
@Composable
fun ChromeWebView(webView: WebView, modifier: Modifier = Modifier) {
    val collapse = LocalNavChromeCollapse.current
    AndroidView(factory = { webView }, modifier = modifier.fillMaxSize(), update = { ChromeWebScroll.attach(it, collapse) })
}
