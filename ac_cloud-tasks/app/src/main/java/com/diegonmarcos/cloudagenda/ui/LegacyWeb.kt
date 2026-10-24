package com.diegonmarcos.cloudagenda.ui

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.cloudagenda.CalBridge

/**
 * The legacy calendar pages (Events, Calendar, Invites, and Settings > Feeds = the old Configs tab): still the
 * tasks.html WebView with its CalBridge, hosted INSIDE the Compose shell. The old page drew its own five-tab bottom
 * bar and measured the system bars into --sat/--sab; both are the shell's job now, so the bar is hidden, the insets
 * are zeroed (the shell already clears them) and the tab the shell selected is shown.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LegacyWeb(tab: String, modifier: Modifier = Modifier) {
    val wanted = rememberUpdatedState(tab)
    // #1164 the page's scroll (its .app-main container included) feeds the shell's one chrome collapse.
    val collapse = com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                addJavascriptInterface(CalBridge(ctx), "CalBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.tag = true
                        com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.inject(view)
                        show(view, wanted.value)
                    }
                }
                loadUrl("file:///android_asset/tasks.html")
            }
        },
        update = { view -> com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(view, collapse); if (view.tag == true) show(view, tab) },
    )
}

private fun show(view: WebView, tab: String) {
    view.evaluateJavascript(
        "(function(){var n=document.getElementById('bottom-nav');if(n)n.style.display='none';" +
            "var s=document.documentElement.style;s.setProperty('--sat','0px');s.setProperty('--sab','0px');" +
            "s.setProperty('--sal','0px');s.setProperty('--sar','0px');" +
            "if(typeof switchTab==='function')switchTab('" + tab + "');})();",
        null,
    )
}
