package com.diegonmarcos.superapp.bottomnav

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.Collections
import java.util.WeakHashMap

/**
 * Feeds a WebView page's scroll into the chrome's ONE collapse connection ([BottomNavCollapse.onScrollDelta]): the WebView's own
 * scroll offset, plus any inner scroller of the page (a container with overflow:auto never moves the WebView's scrollY) through
 * [SCRIPT]. Reading down hides the first row and compacts the second row and the bottom nav; reading up restores them.
 * Call [attach] with the scaffold's [LocalNavChromeCollapse] value on every composition, and [inject] when a page finishes loading.
 */
public object ChromeWebScroll {
    public const val BRIDGE: String = "NavChrome"

    /** Reports every scroll (window or inner container, capture phase) to the native bridge as a signed delta. */
    public const val SCRIPT: String =
        "(function(){if(window.__navChromeFeed)return;window.__navChromeFeed=1;" +
            "window.addEventListener('scroll',function(ev){var t=ev.target,y=(t===document||t===document.documentElement)?window.scrollY:t.scrollTop;" +
            "var d=y-(t.__navLast||0);t.__navLast=y;if(d!==0&&window.NavChrome)window.NavChrome.onScroll(d);},true);})();"

    private class Feed : View.OnScrollChangeListener, View.OnTouchListener {
        @Volatile var target: BottomNavCollapse? = null
        private val main = Handler(Looper.getMainLooper())
        /** The finger is on the page: a downward read is a DRAG (everything hidden), else a settle (shrunk). */
        private var fingerDown = false
        private var lastY = 0f
        private var downY = 0f

        private fun scrolled(delta: Float) {
            val t = target ?: return
            // A finger that has travelled UP the screen reads down the page: a negative scroll offset meanwhile is the page re-laying
            // itself out (images loading, an inner scroller clamping), never the reader scrolling back, so it must not restore the bars.
            if (fingerDown && delta < 0f && lastY < downY - SLOP) return
            if (fingerDown) t.onDragDelta(delta) else t.onScrollDelta(delta)
        }

        @JavascriptInterface
        fun onScroll(delta: Double) { main.post { scrolled(delta.toFloat()) } }

        override fun onScrollChange(v: View, x: Int, y: Int, oldX: Int, oldY: Int) {
            if (y != oldY) scrolled((y - oldY).toFloat())
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { fingerDown = true; lastY = e.y; downY = e.y }
                // A finger dragging DOWN reads UP the page: the chrome comes back even when the page cannot scroll further
                // (it sits at its top, or the scroller is an inner container the bridge cannot see).
                MotionEvent.ACTION_MOVE -> { if (e.y - lastY > SLOP) target?.onScrollDelta(-1f); lastY = e.y }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { fingerDown = false; target?.onRelease() }
            }
            return false
        }
    }

    private const val SLOP = 12f

    private val feeds: MutableMap<WebView, Feed> = Collections.synchronizedMap(WeakHashMap())

    /** Wire [webView]'s scroll to [collapse] (null detaches). Idempotent; safe to call from every composition. */
    public fun attach(webView: WebView, collapse: BottomNavCollapse?) {
        val feed = feeds.getOrPut(webView) {
            Feed().also {
                webView.setOnScrollChangeListener(it)
                webView.setOnTouchListener(it)
                webView.addJavascriptInterface(it, BRIDGE)
            }
        }
        feed.target = collapse
    }

    /** Install the inner-scroller reporter in the loaded page (call from onPageFinished). */
    public fun inject(webView: WebView) { webView.evaluateJavascript(SCRIPT, null) }
}
