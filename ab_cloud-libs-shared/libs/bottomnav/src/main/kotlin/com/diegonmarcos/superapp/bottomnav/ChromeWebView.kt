package com.diegonmarcos.superapp.bottomnav

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A WebView (built by libs:web-core's CloudWeb) inside the shared Compose scaffold: its scroll (the WebView's own and
 * the page's inner scroller, which the page reports to `NavChrome`) and its finger state feed the scaffold's one
 * collapse connection through [ChromeWebScroll], so a held drag hides every bar and the release shrinks them.
 * Was a byte-identical copy in Cloud News and Cloud Contacts (#1371).
 */
@Composable
fun ChromeWebView(webView: WebView, modifier: Modifier = Modifier) {
    val collapse = LocalNavChromeCollapse.current
    AndroidView(factory = { webView }, modifier = modifier.fillMaxSize(), update = { ChromeWebScroll.attach(it, collapse) })
}
