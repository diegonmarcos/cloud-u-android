package com.diegonmarcos.superapp.bottomnav

import android.os.Handler
import android.os.Looper
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

    private class Feed : View.OnScrollChangeListener {
        @Volatile var target: BottomNavCollapse? = null
        private val main = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun onScroll(delta: Double) { main.post { target?.onScrollDelta(delta.toFloat()) } }

        override fun onScrollChange(v: View, x: Int, y: Int, oldX: Int, oldY: Int) {
            if (y != oldY) target?.onScrollDelta((y - oldY).toFloat())
        }
    }

    private val feeds: MutableMap<WebView, Feed> = Collections.synchronizedMap(WeakHashMap())

    /** Wire [webView]'s scroll to [collapse] (null detaches). Idempotent; safe to call from every composition. */
    public fun attach(webView: WebView, collapse: BottomNavCollapse?) {
        val feed = feeds.getOrPut(webView) {
            Feed().also {
                webView.setOnScrollChangeListener(it)
                webView.addJavascriptInterface(it, BRIDGE)
            }
        }
        feed.target = collapse
    }

    /** Install the inner-scroller reporter in the loaded page (call from onPageFinished). */
    public fun inject(webView: WebView) { webView.evaluateJavascript(SCRIPT, null) }
}
