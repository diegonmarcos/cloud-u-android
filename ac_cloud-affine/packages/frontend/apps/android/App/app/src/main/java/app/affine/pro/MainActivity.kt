package app.affine.pro

import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.affine.pro.plugin.AFFiNEThemePlugin
import app.affine.pro.plugin.ExternalFilePlugin
import app.affine.pro.plugin.NbStorePlugin
import app.affine.pro.plugin.MobileBackPlugin
import app.affine.pro.plugin.PreviewPlugin
import app.affine.pro.utils.dp2px
import app.affine.pro.utils.px2dp
import com.getcapacitor.BridgeActivity
import com.getcapacitor.WebViewListener
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.updateMargins
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint

// DE-CLOUDED (#469): upstream wired this activity to the AFFiNE cloud: AI chat
// (FAB + AIActivity), cloud auth (AuthPlugin/HashCashPlugin), GraphQL and SSE
// services, and log upload. None of that exists in this local-first build.
// What remains is the editor WebView, the Android IME bridge and the local
// storage plugins.
@AndroidEntryPoint
class MainActivity : BridgeActivity(), AFFiNEThemePlugin.Callback {

    init {
        registerPlugins(
            listOf(
                AFFiNEThemePlugin::class.java,
                NbStorePlugin::class.java,
                MobileBackPlugin::class.java,
                PreviewPlugin::class.java,
                ExternalFilePlugin::class.java,
            )
        )
    }

    private var navHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            navHeight = px2dp(insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            ViewCompat.onApplyWindowInsets(v, insets)
        }
        setupOpenFileFab()
    }

    // #469: the ONE feature over upstream — open a file from emulated storage.
    // The FAB asks the editor for a path; the editor reads it through the
    // ExternalFilePlugin and imports the markdown into the local workspace.
    private fun setupOpenFileFab() {
        val fab = FloatingActionButton(this).apply {
            layoutParams = CoordinatorLayout.LayoutParams(dp2px(52), dp2px(52)).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                updateMargins(0, 0, dp2px(24), dp2px(86))
            }
            customSize = dp2px(52)
            setImageResource(R.drawable.ic_open_file)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.affine_primary)
            )
            setOnClickListener {
                bridge.webView.post {
                    bridge.webView.evaluateJavascript(
                        "window.dispatchEvent(new Event('cloud-notes:open-file'))",
                        null,
                    )
                }
            }
            val parent = bridge.webView.parent as CoordinatorLayout
            parent.addView(this)
        }
    }

    override fun load() {
        super.load()
        configureAndroidIMEBridge()
        configureEditorWebView()
    }

    private fun configureAndroidIMEBridge() {
        val trustedOrigin = normalizeAffineOrigin(bridge.localUrl)
        bridge.setWebViewClient(AffineWebViewClient(bridge, trustedOrigin))
        bridge.addWebViewListener(object : WebViewListener() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                (view as? AffineEditorWebView)?.updateAndroidIMEBridge(url, trustedOrigin)
            }
        })

        (bridge.webView as? AffineEditorWebView)?.updateAndroidIMEBridge(
            bridge.webView.url ?: bridge.localUrl,
            trustedOrigin,
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            bridge.webView.evaluateJavascript(
                "window.dispatchEvent(new Event('affine:memory-pressure'))",
                null,
            )
        }
    }

    private fun configureEditorWebView() {
        bridge.webView.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            settings.apply {
                // Debug builds may point CAP_SERVER_URL at an HTTP dev server; release builds
                // should keep mixed content blocked.
                mixedContentMode = if (BuildConfig.DEBUG) {
                    WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                } else {
                    WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
        }
    }

    override fun onThemeChanged(darkMode: Boolean) {
        // The AFFiNE Theme plugin's only callback; the editor sets the system
        // bar appearance through it. Nothing else to do in the de-clouded build.
    }

    override fun getSystemNavBarHeight(): Int {
        return navHeight
    }
}
