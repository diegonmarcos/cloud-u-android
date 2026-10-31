package com.diegonmarcos.watchdog

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsMenu
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.watchdog.WatchdogBridge

/**
 * A Compose host (CloudKitTheme) around a WebView showing the shipped dashboard, filled from
 * envelopes the env pushes into this app. Nothing here measures anything or reaches any host.
 */
class MainActivity : AppCompatActivity() {

    private var bridge: WatchdogBridge? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun c(id: Int) = ContextCompat.getColor(this, id)
        val palette = KitPalette.fromArgb(
            surface = c(R.color.kit_surface),
            surfaceSelected = c(R.color.kit_surface_selected),
            textPrimary = c(R.color.kit_text_primary),
            textSecondary = c(R.color.kit_text_secondary),
            accent = c(R.color.kit_accent),
            hairline = c(R.color.kit_hairline),
            tileInk = c(R.color.kit_tile_ink),
        )
        setContent {
            CloudKitTheme(palette) {
              androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().background(LocalKitPalette.current.surface),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = false
                            settings.loadWithOverviewMode = false
                            setBackgroundColor(c(R.color.kit_surface))
                            val b = WatchdogBridge(this@MainActivity, this)
                            bridge = b
                            addJavascriptInterface(b, "AndroidWatchdog")
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(v: WebView?, url: String?) = b.renderLast()
                            }
                            loadUrl("file:///android_asset/watchdog-app.html")
                        }
                    },
                )
                // #1352 Configs ▸ General / ▸ About (libs:ui-nav-bars), over the dashboard's top-right corner
                FleetConfigsMenu(Modifier.align(androidx.compose.ui.Alignment.TopEnd), tint = LocalKitPalette.current.accent)
              }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bridge?.renderLast()
    }
}
