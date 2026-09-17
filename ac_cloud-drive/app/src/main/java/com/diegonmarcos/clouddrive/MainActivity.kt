package com.diegonmarcos.clouddrive

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
     * drive.html has finished loading.
     */
    private var insetScript = ""

    /**
     * The bridge the page talks to. Kept as a field rather than constructed inline
     * because the SAF tree-grant result arrives on the Activity's result channel,
     * outside the WebView, and has to reach the SAME instance the page holds.
     */
    private lateinit var filesBridge: FilesBridge

    /**
     * The SAF "open a document tree" launcher. The system picker returns a content://
     * tree URI when the user grants a whole volume; persisting that grant (and the
     * permission Android ties to it) is what makes an SD card reachable enough to be a
     * place on the next launch. The result contract needs an Activity, so it lives here
     * and the callback hands the URI to the bridge instance above.
     */
    private val openTreeLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        filesBridge.persistTreeGrant(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // drive.html paints a full-bleed shell and reserves room for the system
        // bars through the --sat/--sab/--sal/--sar custom properties. Chromium on
        // Android only ever reports the display cutout through
        // env(safe-area-inset-*), never the status bar, so those properties
        // resolved to 0 and the sticky panel header drew underneath the clock and
        // the battery icon. Measure the real insets here and publish them as the
        // very properties the stylesheet already reads.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // The page background is dark top and bottom (the fleet-wide default,
            // set in values/themes.xml and drive.html), so the system bar glyphs
            // have to be drawn light or they vanish into it.
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        filesBridge = FilesBridge(this) { openTreeLauncher.launch(null) }

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
        webView.addJavascriptInterface(filesBridge, "FilesBridge")
        webView.loadUrl("file:///android_asset/drive.html")
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
