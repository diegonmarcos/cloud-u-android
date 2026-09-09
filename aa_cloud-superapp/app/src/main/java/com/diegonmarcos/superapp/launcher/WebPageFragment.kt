package com.diegonmarcos.superapp.launcher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R

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
 */
class WebPageFragment : Fragment() {

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val url = arguments?.getString(ARG_URL).orEmpty()
        val wv = WebView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // HONOUR <meta name="viewport">. Off by default in a WebView —
            // and with it off the tag is ignored entirely, so the WebView
            // lays the page out at a width of its own choosing. The linktree
            // is mobile-first (18 of its rules are @media(max-width: 768px))
            // and declares `width=device-width`, so the moment that tag is
            // ignored the layout viewport lands above 768, every mobile rule
            // is skipped and the desktop layout renders on a phone. Chrome
            // sets these two; a bare WebView does not, which is the whole
            // difference between the site in the browser and the site here.
            settings.useWideViewPort = true
            // Fit content to the view when a page IS wider than the WebView.
            // A no-op for a page that sizes to device-width — it is the net
            // under the next embedded page that does not.
            settings.loadWithOverviewMode = true
            // The page asks for user-scalable=yes (0.3–2.0). Pinch-zoom needs
            // the built-in controls enabled; displayZoomControls=false keeps
            // the gesture and drops the deprecated on-screen ± overlay.
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // Keep navigation INSIDE the page. Without a WebViewClient the
            // platform hands every link to an ACTION_VIEW chooser, so the
            // first tap leaves the app — which is the one thing an embedded
            // page must not do.
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    backToPreviousPage.isEnabled = view?.canGoBack() == true
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
                    val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
                    view.loadDataWithBaseURL(null, errorHtml(host, error.description?.toString()),
                        "text/html", "utf-8", null)
                }
            }
            if (url.isNotBlank()) loadUrl(url)
        }
        requireActivity().onBackPressedDispatcher
            .addCallback(viewLifecycleOwner, backToPreviousPage)
        web = wv
        return FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(wv)
            addView(backControl())
        }
    }

    /**
     * The on-screen Back control. An embedded page reached from a tile has
     * no toolbar affordance of its own and the system gesture is a swipe
     * most phones also give to the site itself, so without a visible
     * control a user who follows a link two pages deep has no way out that
     * does not gamble on the gesture landing.
     *
     * Floats over the content rather than taking a bar of its own: the
     * embedded page IS the screen, and a chrome bar would be the address
     * bar this fragment deliberately does not have.
     */
    private fun backControl(): View = ImageButton(requireContext()).apply {
        setImageResource(R.drawable.ic_back)
        contentDescription = getString(R.string.action_back)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xCC1A0033.toInt())
        }
        // Bottom-start: out of the thumb's way for scrolling, and clear of
        // the bottom-end corner where sites park their own cookie and chat
        // widgets.
        layoutParams = FrameLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            setMargins(dp(16), dp(16), dp(16), dp(16))
        }
        setOnClickListener { goBackOrClose() }
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        // A WebView outliving its fragment leaks the whole view hierarchy.
        web?.let { it.stopLoading(); it.destroy() }
        web = null
        super.onDestroyView()
    }

    /** The blank-page replacement. Names the host, the likely cause and the
     *  one thing the user can do about it, because "mesh-only" is the answer
     *  for most of what this fragment embeds. */
    private fun errorHtml(host: String, detail: String?): String = """
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
        <body style="margin:0;padding:24px;background:#12101a;color:#e9d8fd;
                     font-family:monospace;line-height:1.6">
          <h3 style="margin:0 0 12px">Could not load $host</h3>
          <p style="color:#b9a8d0;margin:0 0 12px">${detail.orEmpty()}</p>
          <p style="color:#b9a8d0;margin:0">This page is served on the WireGuard
          mesh only — it answers nothing at the public edge. Check the tunnel is
          up (Configs &rarr; WireGuard) and reopen.</p>
        </body></html>
    """.trimIndent()

    companion object {
        private const val ARG_URL = "url"

        fun newInstance(url: String) = WebPageFragment().apply {
            arguments = Bundle().apply { putString(ARG_URL, url) }
        }
    }
}
