package com.diegonmarcos.cgomniverse.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader

/** The app's WebView settings and its two kinds of page: bundled assets (the Avatar scene) and a world from the Store. */
object Web {
    /** The bundled Avatar scene, served by [assetLoader] so its ES modules load (a file:// page may not import modules). */
    val AVATAR_URL: String = Uri.Builder().scheme("https").authority(WebViewAssetLoader.DEFAULT_DOMAIN)
        .path("/assets/avatar/index.html").build().toString()

    fun assetLoader(ctx: Context): WebViewAssetLoader =
        WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx)).build()

    /** WebGL on, hardware-accelerated, media without a gesture, and a page may load http parts (some worlds are http). */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun create(ctx: Context): WebView = WebView(ctx).apply {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        setBackgroundColor(0)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
        }
        // a 3D scene owns its gestures: the shell must not take a drag or a pinch as a scroll
        setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    fun openInBrowser(ctx: Context, url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
