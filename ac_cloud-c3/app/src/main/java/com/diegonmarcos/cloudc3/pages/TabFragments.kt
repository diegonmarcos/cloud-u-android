package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.cloudc3.cloud.C3HealthFragment

/**
 * #648 the three paged content tabs. Each one is only two things: WHICH declaration it
 * reads, and WHICH fragment each of its declared page ids resolves to. Everything else —
 * the strip, the selection, the swap, the state restore — is [PagedFragment].
 *
 * Every page id below resolves to a real, working fragment. That is not a coincidence, it
 * is the rule this ticket added: a page is DECLARED when it is implemented and not before,
 * so there is no "not built yet" body anywhere in this app and no flag that could excuse
 * one. The pages still to arrive (the GHA / Dagu / commit feeds, the ntfy centre, the
 * container dashboards) are absent from build.json until their fragments exist.
 */

/**
 * TOPOLOGY — the address estate, the SuperApp's two `c3_public` / `c3_private` cards that
 * sat under its Addresses heading. The SAME [C3HealthFragment] renders both, scoped, which
 * is exactly how the SuperApp embedded it.
 */
class TopologyFragment : PagedFragment() {
    override fun pages(): List<Declarations.PageDecl> = Declarations.topologyPages
    override fun pageFragment(pageId: String): Fragment? = when (pageId) {
        "public" -> C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PUBLIC)
        "private" -> C3HealthFragment.newInstance(C3HealthFragment.SCOPE_PRIVATE)
        else -> null
    }
}

/**
 * OBSERV — what the estate is doing. Today that is Health, the SuperApp's own
 * `page:c3/health`: both tables with their counts, the full declared estate in one view.
 */
class ObservFragment : PagedFragment() {
    override fun pages(): List<Declarations.PageDecl> = Declarations.observPages
    override fun pageFragment(pageId: String): Fragment? = when (pageId) {
        "health" -> C3HealthFragment.newInstance(C3HealthFragment.SCOPE_ALL)
        else -> null
    }
}

/** CONFIGS — this app's own settings. About is real because everything it shows is baked. */
class ConfigsFragment : PagedFragment() {
    override fun pages(): List<Declarations.PageDecl> = Declarations.configsPages
    override fun pageFragment(pageId: String): Fragment? = when (pageId) {
        "about" -> AboutFragment()
        else -> null
    }
}

/**
 * Drawn ONLY when a declared page id resolves to no fragment — a declaration that is not
 * true. It names the id and says what is wrong, because the alternative (an empty pane) is
 * indistinguishable from a page that legitimately has nothing to show. It is not a
 * placeholder to ship behind: test-c3-shell.sh fails the build if any declared page reaches
 * it, so on a shipped APK this class is unreachable by construction.
 */
class UndeclaredPageFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = TextView(requireContext()).apply {
        val d = resources.displayMetrics.density
        setPadding((16 * d).toInt(), (24 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        gravity = Gravity.CENTER_HORIZONTAL
        setTextColor(ContextCompat.getColor(requireContext(), R.color.c3_text_secondary))
        text = getString(R.string.page_has_no_body, arguments?.getString(ARG_PAGE) ?: "")
    }

    companion object {
        private const val ARG_PAGE = "page"
        fun newInstance(pageId: String) = UndeclaredPageFragment().apply {
            arguments = Bundle().apply { putString(ARG_PAGE, pageId) }
        }
    }
}
