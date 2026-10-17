package com.diegonmarcos.superapp.launcher

import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * THE embedded browser. Two ways in, one implementation:
 *
 *  • a section page that IS a web page — declare `url` on the page in
 *    build.json and [SectionPages.factoryFor] renders it here instead of
 *    needing a Kotlin screen (Cloud ▸ Linktree, Projects ▸ C3);
 *  • any plain http(s) target in the tile/action grammar — MySocials,
 *    PM Boards, an RSS item — routed here by ShellActivity.launchUri.
 *
 * Cloud ▸ Linktree is the reason it exists: the linktree is already a
 * maintained site, and porting it to tiles meant keeping two copies of the
 * same link list in step forever. One field in build.json is the whole
 * integration; the same fragment then costs nothing to reuse for every
 * other plain web URL, which is why launchUri opens it rather than
 * shipping the user out to a browser app.
 *
 * Deliberately chrome-less apart from Back: no address bar, no tabs. This
 * is a page of the app that happens to be rendered from the web — a real
 * browsing session is what ac_cloud-browser (extapp:cloud-browser) is for.
 *
 * Compose since #773: the WebView is hosted in an AndroidView (a WebView has no Compose equivalent) and the Back
 * control is a composable floating over it.
 */
class WebPageFragment : KitComposeFragment() {

    private var web: WebView? = null

    /** Disabled until the WebView actually has history, so a back press on
     *  the first page falls through to the FragmentManager and pops this
     *  page like every other one — which IS "close the browser". Keeping it
     *  enabled with an internal close call would have to re-implement the
     *  pop the dispatcher already does. */
    private val backToPreviousPage = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            web?.takeIf { it.canGoBack() }?.goBack()
        }
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    /** A WebView scrolls as a View and the shell's View driver already sees it; no Compose scroll wrapper around it. */
    override val reportsScroll: Boolean get() = false

    /** The page this fragment is for, and the last time a load was started (so a resume right after the first load is no second load). */
    private val declaredUrl: String get() = arguments?.getString(ARG_URL).orEmpty()
    internal var lastLoadAt = 0L
    /** The page that failed, so a pull or a re-entry reloads IT and not the error page that replaced it. */
    private var failed: String? = null

    /**
     * Load [target] from the network, past the HTTP cache. Every ENTRY into the page does this (first show, tab re-selected, back
     * to it, the app coming back to it), so it never shows a stale or blank cached view. Only the HTTP cache is bypassed: the site's
     * own service worker is separate and keeps working. The cache mode goes back to normal when the page finishes.
     */
    private fun loadFresh(w: WebView, target: String) {
        lastLoadAt = android.os.SystemClock.elapsedRealtime()
        w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        w.loadUrl(target)
    }

    /** [loadFresh] as soon as [w] is laid out with a non-zero size (now, if it already is). */
    private fun loadWhenSized(w: WebView, target: String) {
        if (w.width > 0 && w.height > 0) { loadFresh(w, target); return }
        w.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (r - l <= 0 || b - t <= 0) return
                v.removeOnLayoutChangeListener(this)
                // Posted: the renderer takes the new size on this frame; the load starts on the next.
                v.post { if (web === w) loadFresh(w, target) }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Re-entry: the WebView is already there (the page was left and is back). The first show loads from the factory below, and
        // a resume that lands right behind it must not load twice.
        val w = web ?: return
        if (android.os.SystemClock.elapsedRealtime() - lastLoadAt < REENTRY_DEBOUNCE_MS) return
        loadWhenSized(w, failed ?: declaredUrl.ifBlank { return })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToPreviousPage)
    }

    @Composable
    override fun Content() {
        val url = arguments?.getString(ARG_URL).orEmpty()
        val p = LocalKitPalette.current
        // A page declared with `embed=1` is a site that knows it is drawn INSIDE the SuperApp (the cloud-mobile replica): it
        // is told so by the query AND by a user-agent marker, and it is a tab pane, so it carries no floating Back control
        // (the system Back steps through its history) and fills exactly the pane it is given.
        val embed = isEmbed(url)
        var refreshing by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            PullDownToRefresh(modifier = Modifier.fillMaxSize(), refreshing = refreshing, onRefresh = {
                refreshing = true
                val w = web
                val retry = failed
                failed = null
                if (w == null) refreshing = false
                else loadFresh(w, retry ?: w.url?.takeIf { it.startsWith("http") } ?: declaredUrl)
            }) {
            AndroidView(
                modifier = Modifier.fillMaxSize().testTag(TAG_WEB),
                factory = { ctx ->
                    WebView(ctx).apply {
                        if (embed) settings.userAgentString = settings.userAgentString + UA_MARKER
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        // Every console line of the page reaches logcat (tag WebPage) and [probe]: a page that renders blank on a
                        // phone is a JS error nobody can see without this.
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                report("console ${m.messageLevel()} ${m.sourceId()}:${m.lineNumber()} ${m.message()}")
                                return super.onConsoleMessage(m)
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                                backToPreviousPage.isEnabled = view?.canGoBack() == true
                                refreshing = false
                            }

                            /** Same site stays in the page; any other http(s) link goes through the app's link rules
                             *  (fleet hosts open in the embedded browser, the rest in the system's), never inside this
                             *  pane where the user could not tell they had left the site. */
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                val target = request.url
                                if (!embed || !request.isForMainFrame) return false
                                val scheme = target.scheme.orEmpty()
                                if (scheme != "http" && scheme != "https") return true
                                if (sameSite(url, target.toString())) return false
                                (activity as? com.diegonmarcos.superapp.ShellActivity)?.launchUri(target.toString())
                                    ?: runCatching {
                                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, target)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                return true
                            }

                            /**
                             * SAY SO when the page does not load. Every site embedded here
                             * is ours, and the ones that matter — Paca among them — are
                             * mesh-only: paca.diegonmarcos.com serves no certificate at the
                             * public edge and simply does not answer off the WireGuard
                             * mesh. The platform's reaction to that is a blank white view,
                             * which is indistinguishable from a feature that was never
                             * built, and this page IS the whole board.
                             *
                             * Main frame only: a failed favicon or analytics sub-request
                             * must not blank a page that rendered.
                             */
                            override fun onReceivedHttpError(
                                view: WebView, request: android.webkit.WebResourceRequest,
                                response: android.webkit.WebResourceResponse,
                            ) {
                                report("httpError ${response.statusCode} main=${request.isForMainFrame} ${request.url}")
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                                error: android.webkit.WebResourceError,
                            ) {
                                report("error ${error.errorCode} ${error.description} main=${request.isForMainFrame} ${request.url}")
                                if (!request.isForMainFrame) return
                                refreshing = false
                                failed = request.url.toString()
                                val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
                                view.loadDataWithBaseURL(null, errorHtml(host, error.description?.toString()),
                                    "text/html", "utf-8", null)
                            }
                        }
                        web = this
                        // Load only once the WebView has a real size. Loaded from this factory it starts with a 0x0 view, and in
                        // this host (Compose pane > FragmentContainerView > ComposeView > AndroidView) the renderer kept that
                        // zero-height viewport: on Android 16 the CI emulator showed innerHeight=362 but <html>/.shell 0 px tall
                        // and a black, never-painted pane (the owner's "WebApps fully blank"). WebPagesRenderTest guards it.
                        if (url.isNotBlank()) loadWhenSized(this, url)
                    }
                },
                onRelease = { it.stopLoading(); it.destroy(); if (web === it) web = null },
            )
            }
            // The on-screen Back control. An embedded page reached from a tile has no toolbar affordance of its own
            // and the system gesture is a swipe most phones also give to the site itself, so without a visible
            // control a user who follows a link two pages deep has no way out that does not gamble on the gesture.
            // It floats over the content rather than taking a bar of its own: the embedded page IS the screen.
            if (!embed) Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back),
                tint = p.textPrimary,
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).size(44.dp)
                    .clip(CircleShape).background(p.surface.copy(alpha = 0.8f))
                    .clickable { goBackOrClose() }.padding(10.dp).testTag(TAG_BACK))
        }
    }

    /** One meaning of Back, shared by the control and the gesture: step back
     *  through the page's own history while it has one, otherwise leave the
     *  browser. The dispatcher does the leaving — [backToPreviousPage] is
     *  disabled in exactly that case, so this cannot recurse into itself. */
    private fun goBackOrClose() {
        val wv = web
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    /** The blank-page replacement. Names the host, the likely cause and the
     *  one thing the user can do about it, because "mesh-only" is the answer
     *  for most of what this fragment embeds. */
    internal fun errorHtml(host: String, detail: String?): String = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="margin:0;padding:24px;background:#12101a;color:#e9d8fd;
                     font-family:monospace;line-height:1.6">
          <h3 style="margin:0 0 12px">Could not load $host</h3>
          <p style="color:#b9a8d0;margin:0 0 12px">${detail.orEmpty()}</p>
          <p style="color:#b9a8d0;margin:0">${if (isPublicHost(host))
              "You look to be offline, or the site did not answer. Pull down to try again."
          else
              """This page is served on the WireGuard
          mesh only — it answers nothing at the public edge. Check the tunnel is
          up (Configs &rarr; WireGuard) and reopen."""}</p>
        </body></html>
    """.trimIndent()

    companion object {
        private const val ARG_URL = "url"
        const val TAG_WEB = "webpage:web"
        const val TAG_BACK = "webpage:back"

        /** Appended to the WebView's user agent on an `embed=1` page, so the site can tell the SuperApp is hosting it. */
        /** A resume this soon after a load started is the same entry, not a new one. */
        internal const val REENTRY_DEBOUNCE_MS = 1_500L

        const val UA_MARKER = " CloudSuperApp-Embed/1"

        fun isEmbed(url: String): Boolean = Regex("[?&]embed=1(&|#|$)").containsMatchIn(url)

        /** Public hosts answer off the mesh; the rest of the fleet's sites do not. */
        internal fun isPublicHost(host: String): Boolean = host.endsWith("github.io")

        /** Same origin as the page that was declared (scheme + host + port). */
        internal fun sameSite(declared: String, target: String): Boolean = runCatching {
            val a = java.net.URI(declared); val b = java.net.URI(target)
            a.scheme == b.scheme && a.host == b.host && a.port == b.port
        }.getOrDefault(false)

        /** Diagnostics sink (console lines, load errors): logcat always, plus an instrumented test when one listens. */
        @Volatile var probe: ((String) -> Unit)? = null
        internal fun report(line: String) {
            android.util.Log.i("WebPage", line)
            probe?.invoke(line)
        }

        fun newInstance(url: String) = WebPageFragment().apply {
            arguments = Bundle().apply { putString(ARG_URL, url) }
        }
    }
}

/**
 * Pull down at the top of the content to refresh. Written here, not M3's PullToRefreshBox: this page hosts a WebView, which has no
 * business under a layer-wrapping container, and all it needs is "the content is at its top and the finger keeps pulling".
 * The content's own nested scrolling (the WebView's) reports the pull it could not use; once it adds up to [PULL_DP] the refresh fires
 * and a spinner shows until the caller clears [refreshing].
 */
@Composable
internal fun PullDownToRefresh(
    modifier: Modifier, refreshing: Boolean, onRefresh: () -> Unit, content: @Composable () -> Unit,
) {
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { PULL_DP.dp.toPx() }
    val onRefreshNow by androidx.compose.runtime.rememberUpdatedState(onRefresh)
    val busy by androidx.compose.runtime.rememberUpdatedState(refreshing)
    val connection = remember(threshold) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (available.y > 0f) {
                    pulled += available.y
                    if (pulled >= threshold && !busy) { pulled = 0f; onRefreshNow() }
                } else if (available.y < 0f || consumed.y != 0f) pulled = 0f
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                pulled = 0f
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    Box(modifier.nestedScroll(connection)) {
        content()
        if (refreshing) androidx.compose.material3.CircularProgressIndicator(
            Modifier.align(Alignment.TopCenter).padding(top = 12.dp).size(28.dp).testTag(TAG_REFRESHING), strokeWidth = 3.dp,
        )
    }
}

private const val PULL_DP = 72
internal const val TAG_REFRESHING = "webpage:refreshing"
