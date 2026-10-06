package com.diegonmarcos.superapp.browser

/**
 * #893 what a WebView must do for Google sign-in (and any OAuth / form-POST login) to finish, as pure
 * rules a JVM test can pin. Free of android.* on purpose.
 *
 * Three real causes lived here:
 *  - ERR_CACHE_MISS on accounts.google.com/gsi/transform: that page is the RESULT OF A POST. #886 stored
 *    it as the tab's url and saved it in the tab's WebView state; the next rebuild of the WebView (leaving
 *    for the grid, pause/stop, a tab switch) restored or re-loaded it, which asks the cache for a POST
 *    response that was never cached. [isFormPostResult] pages are never committed, never saved and never
 *    restored; the tab falls back to the last page that can be loaded again.
 *  - the user agent: without a configured one WebView sends `; wv)` and `Version/x.x`, which Google answers
 *    with disallowed_useragent. [cleanUserAgent] strips both.
 *  - popups: accounts.google.com / OAuth open a child window and post back through window.opener, which
 *    needs setSupportMultipleWindows + onCreateWindow; links the page hands to another app (intent:,
 *    market:) must not leave the browser ([decide]).
 */
object BrowserNavPolicy {

    /** Pages that exist only as the response to a POST (or a one-shot token exchange): reloading them cannot work. */
    fun isFormPostResult(url: String?): Boolean {
        val u = url?.trim()?.lowercase().orEmpty()
        if (!u.startsWith("http")) return false
        val rest = u.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore('?')
        val path = "/" + rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        if (host == "accounts.google.com" || host.endsWith(".accounts.google.com")) {
            return path.startsWith("/gsi/") || path.startsWith("/signin/") || path.startsWith("/v3/signin/") ||
                path.startsWith("/o/oauth2/") || path.startsWith("/accountchooser") || path.contains("servicelogin")
        }
        return false
    }

    /** The user agent Google accepts: WebView's own minus the `; wv` token and the `Version/x.x` marker. */
    fun cleanUserAgent(ua: String): String =
        ua.replace(Regex(";\\s*wv\\b"), "").replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "").replace(Regex("\\s{2,}"), " ").trim()

    fun hasWebViewToken(ua: String): Boolean = Regex(";\\s*wv\\b").containsMatchIn(ua) || Regex("Version/\\d").containsMatchIn(ua)

    sealed class Decision {
        /** Let WebView load it. */
        object Load : Decision()
        /** Load [url] instead (an intent: link's browser_fallback_url). */
        data class Redirect(val url: String) : Decision()
        /** Swallow it: the page asked for another app or the Store; the browser stays where it is. */
        object Block : Decision()
    }

    /** What shouldOverrideUrlLoading does with [url]: web pages load (accounts.google.com included), app links never leave. */
    fun decide(url: String?): Decision {
        val u = url?.trim().orEmpty()
        val low = u.lowercase()
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("about:") ||
            low.startsWith("file:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("javascript:")) return Decision.Load
        if (low.startsWith("intent:")) {
            val fb = Regex("S\\.browser_fallback_url=([^;]+)").find(u)?.groupValues?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            return if (fb != null && fb.lowercase().startsWith("http")) Decision.Redirect(fb) else Decision.Block
        }
        return Decision.Block
    }
}
