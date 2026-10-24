package app.cloudnotes.pro

import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.diegonmarcos.superapp.bottomnav.ChromeWebScroll
import com.getcapacitor.Bridge
import com.getcapacitor.BridgeWebViewClient

internal class CloudNotesWebViewClient(
    bridge: Bridge,
    private val trustedOrigin: String?,
) : BridgeWebViewClient(bridge) {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val shouldOverride = super.shouldOverrideUrlLoading(view, request)
        if (!shouldOverride && request.isForMainFrame) {
            (view as? CloudNotesEditorWebView)?.updateAndroidIMEBridge(
                request.url.toString(),
                trustedOrigin,
            )
        }
        return shouldOverride
    }

    // #1164 the page's inner scrollers feed the chrome's one scroll-collapse (the WebView's own scroll is hooked by ChromeWebScroll.attach).
    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        ChromeWebScroll.inject(view)
    }
}
