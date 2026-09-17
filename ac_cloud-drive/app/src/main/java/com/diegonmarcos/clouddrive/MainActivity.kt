package com.diegonmarcos.clouddrive

import android.content.Intent
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
     * because two delivery channels arrive OUTSIDE the WebView and have to reach
     * the SAME instance the page holds: the SAF tree-grant result on the Activity's
     * result channel (#457a), and the PDF-handler intent on onCreate/onNewIntent
     * (#458).
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

    /** The WebView, kept for [onNewIntent]'s nudge to an already-loaded page. */
    private lateinit var webView: WebView

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

        webView = WebView(this)
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
        // A cold start that arrived as an "Open with Cloud Drive" hand-off carries
        // the URI in this initial intent; the page drains it once it exists.
        handlePdfIntent(intent)
        Updater.start(this)
    }

    /**
     * A second "Open with Cloud Drive" tap must NOT stack a second copy of the
     * activity: singleTask in the manifest routes it here instead. The incoming
     * URI (content:// from a modern app, file:// from a legacy one) is handed
     * to the bridge, which is the component that may read it — the activity
     * never mints a path from it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePdfIntent(intent)
        // The page drained its bootstrap hand-off long ago (or will, if this
        // relaunch raced the first load); wake it so the new URI is picked up
        // without waiting for another launch.
        webView.evaluateJavascript("window.handlePdfHandoff && window.handlePdfHandoff()", null)
    }

    /** Parks a PDF hand-off URI on the bridge, whichever route delivered it. */
    private fun handlePdfIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            filesBridge.setIncomingPdf(intent.data)
        }
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
