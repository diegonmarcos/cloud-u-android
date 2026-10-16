package app.sterna.ui.settings

import android.annotation.SuppressLint
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** The visual pane's remote control: the toolbar asks the page to run an editing command through it. */
internal class VisualHandle {
    internal var web: WebView? = null

    /** `document.execCommand` in the page; [command] is a fixed name, [value] is quoted here. */
    fun exec(command: String, value: String? = null) {
        val arg = value?.let { "'" + it.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'" } ?: "null"
        web?.evaluateJavascript("window.sigExec&&window.sigExec('$command',$arg)", null)
    }
}

/**
 * What the preview renders: [document] from [SignaturePreview.document]. In [visual] mode the page runs
 * ONE script - its own, allowed by nonce - which reports the edited signature back through [onVisualHtml];
 * otherwise JavaScript is off, as it always was for the signature preview.
 *
 * Software layer, no file / content / storage access, links swallowed, and every request refused but the
 * pictures the owner pointed at: the same containment as the reader's WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SignatureWebPreview(
    document: String,
    visual: Boolean,
    handle: VisualHandle,
    onVisualHtml: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = rememberUpdatedState(onVisualHtml)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                settings.domStorageEnabled = false
                settings.setGeolocationEnabled(false)
                settings.setSupportZoom(false)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = PreviewClient
                handle.web = this
            }
        },
        update = { view ->
            handle.web = view
            view.settings.javaScriptEnabled = visual
            view.removeJavascriptInterface(SignaturePreview.BRIDGE)
            if (visual) {
                view.addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onHtml(html: String) {
                            view.post { report.value.invoke(html) }
                        }
                    },
                    SignaturePreview.BRIDGE,
                )
            }
            if (view.tag != document) {
                view.tag = document
                view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            view.removeJavascriptInterface(SignaturePreview.BRIDGE)
            handle.web = null
            view.destroy()
        },
    )
}

/** Default-deny: only pictures over http(s) and inline data leave the page; a tapped link goes nowhere. */
internal object PreviewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val scheme = request?.url?.scheme?.lowercase()
        return if (scheme == "https" || scheme == "http" || scheme == "data" || scheme == "about") {
            null
        } else {
            WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
        }
    }
}
