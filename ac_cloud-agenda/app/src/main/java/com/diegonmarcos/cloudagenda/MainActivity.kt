package com.diegonmarcos.cloudagenda

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.diegonmarcos.superapp.updater.Updater

class MainActivity : AppCompatActivity() {

    /**
     * The last measured inset script. Kept because the window hands us insets
     * before the page exists, so the same script has to be replayed once
     * agenda.html has finished loading.
     */
    private var insetScript = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // agenda.html paints a full-bleed shell and reserves room for the system
        // bars through the --sat/--sab/--sal/--sar custom properties. Chromium on
        // Android only ever reports the display cutout through
        // env(safe-area-inset-*), never the status bar, so those properties
        // resolved to 0 and the sticky panel header drew underneath the clock and
        // the battery icon. Measure the real insets here and publish them as the
        // very properties the stylesheet already reads.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // The page background is white top and bottom, so the system bar
            // glyphs have to be drawn dark or they vanish into it.
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val webView = WebView(this)
        setContentView(webView)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                publishInsets(view)
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, windowInsets ->
            insetScript = insetScriptFor(
                windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            )
            publishInsets(view as WebView)
            windowInsets
        }
        webView.addJavascriptInterface(CalBridge(this), "CalBridge")
        webView.loadUrl("file:///android_asset/agenda.html")
        Updater.start(this)
    }

    /** Android insets arrive in device pixels; CSS wants density-independent ones. */
    private fun insetScriptFor(insets: Insets): String {
        val density = resources.displayMetrics.density
        fun cssPixels(devicePixels: Int) = (devicePixels / density).toInt()
        return "var s = document.documentElement.style;" +
            "s.setProperty('--sat', '" + cssPixels(insets.top) + "px');" +
            "s.setProperty('--sab', '" + cssPixels(insets.bottom) + "px');" +
            "s.setProperty('--sal', '" + cssPixels(insets.left) + "px');" +
            "s.setProperty('--sar', '" + cssPixels(insets.right) + "px');"
    }

    private fun publishInsets(webView: WebView) {
        if (insetScript.isNotEmpty()) webView.evaluateJavascript(insetScript, null)
    }
}
