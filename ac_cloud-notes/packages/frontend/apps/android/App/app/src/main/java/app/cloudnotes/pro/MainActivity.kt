package app.cloudnotes.pro

import android.content.ComponentCallbacks2
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.cloudnotes.pro.plugin.CloudNotesThemePlugin
import app.cloudnotes.pro.plugin.ExternalFilePlugin
import app.cloudnotes.pro.plugin.LocalAIButtonPlugin
import app.cloudnotes.pro.plugin.LocalAuthPlugin
import app.cloudnotes.pro.plugin.LocalHashCashPlugin
import app.cloudnotes.pro.plugin.NbStorePlugin
import app.cloudnotes.pro.plugin.MobileBackPlugin
import app.cloudnotes.pro.plugin.PreviewPlugin
import app.cloudnotes.pro.utils.dp2px
import app.cloudnotes.pro.utils.px2dp
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDecl
import com.getcapacitor.BridgeActivity
import com.getcapacitor.BridgeWebChromeClient
import com.getcapacitor.WebViewListener
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.updateMargins
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

// DE-CLOUDED (#469): upstream wired this activity to the Cloud Notes: AI chat
// (FAB + AIActivity), cloud auth (AuthPlugin/HashCashPlugin), GraphQL and SSE
// services, and log upload. None of that exists in this local-first build.
// What remains is the editor WebView, the Android IME bridge and the local
// storage plugins.
//
// #543: the de-clouding deleted those plugins' NATIVE side but not the web code
// that calls them, and an unregistered Capacitor plugin throws rather than
// degrades — which killed local workspace creation. They are back as local-only
// implementations (DeCloudedPlugins.kt); still no server, but the bridge
// resolves. This list is the ONE registration site for app-local plugins and
// `cap sync` does not rewrite it, so the fix survives a re-vendor.
@AndroidEntryPoint
class MainActivity : BridgeActivity(), CloudNotesThemePlugin.Callback {

    init {
        registerPlugins(
            listOf(
                CloudNotesThemePlugin::class.java,
                NbStorePlugin::class.java,
                MobileBackPlugin::class.java,
                PreviewPlugin::class.java,
                ExternalFilePlugin::class.java,
                LocalAuthPlugin::class.java,
                LocalHashCashPlugin::class.java,
                LocalAIButtonPlugin::class.java,
            )
        )
    }

    private var navHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            navHeight = px2dp(insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            ViewCompat.onApplyWindowInsets(v, insets)
        }
        setupOpenFileFab()
        setupNativeNav()
    }

    // #868: the fleet bottom island + page tabs (libs:bottomnav) replace the editor's own
    // tab bar; the web half hides its tabs once it sees cloudNotesNativeNav (setup.ts).
    private fun setupNativeNav() {
        val decl = NavDecl.fromBuildConfig(
            BuildConfig.UI_SECTIONS_B64,
            BuildConfig.UI_BOTTOM_NAV,
            BuildConfig.UI_DEFAULT_SECTION,
        )
        val parent = bridge.webView.parent as CoordinatorLayout
        val run: (String) -> Unit = { js -> bridge.webView.post { bridge.webView.evaluateJavascript(js, null) } }
        val nav = NativeNav(
            decl = decl,
            webView = bridge.webView,
            gatewayUrl = BuildConfig.AI_GATEWAY_URL,
            icon = { name ->
                @Suppress("DiscouragedApi")
                resources.getIdentifier("ic_nav_$name", "drawable", packageName)
            },
            run = run,
        )
        nav.attach(parent)
        // The fleet's first row (libs:bottomnav AppTopBar, build.json::ui.top_bar). It reports its
        // height, status bar included, and NativeNav places the strip and the WebView below it.
        TopBarDecl.fromBuildConfig(BuildConfig.UI_TOP_BAR_B64)?.let { topDecl ->
            val topBar = NativeTopBar(
                decl = topDecl,
                title = applicationInfo.loadLabel(packageManager).toString(),
                nav = nav,
                navDecl = decl,
                gatewayUrl = BuildConfig.AI_GATEWAY_URL,
                run = run,
            )
            bridge.webView.addJavascriptInterface(topBar.host(), "CloudNotesHost")
            topBar.attach(parent) { px -> nav.setTopInset(px) }
        }
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
                ContextCompat.getColor(context, R.color.cloud_notes_primary)
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
        persistWebConsoleErrors()
    }

    // #522: THE ONLY PLACE THE REAL CAUSE IS EVER WRITTEN.
    //
    // This app is a web application in a WebView. When it fails to boot, the
    // thing that actually failed is a JavaScript exception, and the web layer
    // does what web layers do: it catches it, writes it to console.error and
    // renders a fixed string — "An internal error occurred." (the
    // error.INTERNAL_SERVER_ERROR i18n key, which is itself a lie: this build
    // has no server). See core/src/desktop/pages/index/index.tsx, where the
    // first-workspace bootstrap is caught and discarded.
    //
    // Capacitor's BridgeWebChromeClient already forwards that console line to
    // LOGCAT, but nothing persisted it: FileTree logs Kotlin only, and logcat
    // is a ring buffer that is empty by the time anyone is asked to look. So
    // two tickets in a row (#513, #522) had to reason about a web-layer boot
    // failure from source, with the exception sitting unreadable on the phone.
    //
    // Console ERRORs now go through Timber, which means FileTree writes them to
    // filesDir/logs/<date>.log and they survive the process, the reboot and the
    // question "what did it actually say?". super() is still called, so
    // Capacitor's own console handling is unchanged.
    private fun persistWebConsoleErrors() {
        bridge.webView.webChromeClient = object : BridgeWebChromeClient(bridge) {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Timber.e(
                        "web console error: %s (%s:%d)",
                        consoleMessage.message(),
                        consoleMessage.sourceId(),
                        consoleMessage.lineNumber(),
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun configureAndroidIMEBridge() {
        val trustedOrigin = normalizeCloudNotesOrigin(bridge.localUrl)
        bridge.setWebViewClient(CloudNotesWebViewClient(bridge, trustedOrigin))
        bridge.addWebViewListener(object : WebViewListener() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                (view as? CloudNotesEditorWebView)?.updateAndroidIMEBridge(url, trustedOrigin)
            }
        })

        (bridge.webView as? CloudNotesEditorWebView)?.updateAndroidIMEBridge(
            bridge.webView.url ?: bridge.localUrl,
            trustedOrigin,
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            bridge.webView.evaluateJavascript(
                "window.dispatchEvent(new Event('cloud-notes:memory-pressure'))",
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
        // The Cloud Notes Theme plugin's only callback; the editor sets the system
        // bar appearance through it. Nothing else to do in the de-clouded build.
    }

    override fun getSystemNavBarHeight(): Int {
        return navHeight
    }
}
