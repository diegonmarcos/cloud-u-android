package com.diegonmarcos.superapp.launcher

import android.os.Bundle
import android.view.View
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backToPreviousPage)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val url = arguments?.getString(ARG_URL).orEmpty()
        val p = LocalKitPalette.current
        // A page declared with `embed=1` is a site that knows it is drawn INSIDE the SuperApp (the cloud-mobile replica): it
        // is told so by the query AND by a user-agent marker, and it is a tab pane, so it carries no floating Back control
        // (the system Back steps through its history) and fills exactly the pane it is given.
        val embed = isEmbed(url)
        var refreshing by remember { mutableStateOf(false) }
        // The page that failed, so a pull reloads IT and not the error page that replaced it.
        var failedUrl by remember { mutableStateOf<String?>(null) }
        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(isRefreshing = refreshing, modifier = Modifier.fillMaxSize(), onRefresh = {
                refreshing = true
                val w = web
                val retry = failedUrl
                failedUrl = null
                if (w == null) refreshing = false
                else if (retry != null) w.loadUrl(retry) else w.reload()
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
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
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
                            override fun onReceivedError(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                                error: android.webkit.WebResourceError,
                            ) {
                                if (!request.isForMainFrame) return
                                refreshing = false
                                failedUrl = request.url.toString()
                                val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
                                view.loadDataWithBaseURL(null, errorHtml(host, error.description?.toString()),
                                    "text/html", "utf-8", null)
                            }
                        }
                        if (url.isNotBlank()) loadUrl(url)
                        web = this
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
        const val UA_MARKER = " CloudSuperApp-Embed/1"

        fun isEmbed(url: String): Boolean = Regex("[?&]embed=1(&|#|$)").containsMatchIn(url)

        /** Public hosts answer off the mesh; the rest of the fleet's sites do not. */
        internal fun isPublicHost(host: String): Boolean = host.endsWith("github.io")

        /** Same origin as the page that was declared (scheme + host + port). */
        internal fun sameSite(declared: String, target: String): Boolean = runCatching {
            val a = java.net.URI(declared); val b = java.net.URI(target)
            a.scheme == b.scheme && a.host == b.host && a.port == b.port
        }.getOrDefault(false)

        fun newInstance(url: String) = WebPageFragment().apply {
            arguments = Bundle().apply { putString(ARG_URL, url) }
        }
    }
}
