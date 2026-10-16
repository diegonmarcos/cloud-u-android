package app.sterna.ui.message

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext

/** A page around an already-sanitised [fragment], in the app's colours. No script, no network. */
internal fun htmlFragmentDocument(fragment: String, foreground: String, background: String): String =
    "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'\">" +
        "<style>body{margin:0;color:$foreground;background:$background;font:15px/1.4 sans-serif;overflow-wrap:anywhere}" +
        "img{max-width:100%;height:auto}pre.plain{white-space:pre-wrap;font-family:inherit;margin:0}" +
        "blockquote{margin:0 0 0 .8ex;padding-left:1ex;border-left:2px solid #888}</style></head>" +
        "<body>$fragment</body></html>"

/**
 * A message body that is HTML, shown as HTML, where the full reader is not the surface (the sheet of an
 * attached message). Static: JavaScript off, every request refused, links inert - the reader owns
 * opening links and loading pictures.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlFragmentView(fragment: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val document = remember(fragment, scheme.onSurface, scheme.surface) {
        htmlFragmentDocument(
            fragment,
            "#%06X".format(scheme.onSurface.toArgb() and 0xFFFFFF),
            "#%06X".format(scheme.surface.toArgb() and 0xFFFFFF),
        )
    }
    AndroidView(
        modifier = modifier,
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                setBackgroundColor(AndroidColor.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }
        },
        update = { it.loadDataWithBaseURL(null, document, "text/html", "utf-8", null) },
        onRelease = { it.destroy() },
    )
}
