package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabsView

/**
 * #648 a content tab: a strip over that tab's DECLARED pages, and one container the
 * selected page's fragment is swapped into.
 *
 * ONE implementation for every paged tab, so adding a page is a build.json edit. The strip
 * is libs:bottomnav's PageTabsView over the section's declared pages (#868) — the labels are
 * never spelled here.
 *
 * THE RULE THIS CLASS ENFORCES AT RUNTIME, and the tester at build time: a declared page
 * MUST resolve to a real fragment. [pageFragment] returning null is treated as a
 * programming error and says so on screen, rather than drawing an empty pane that looks
 * like a page with nothing in it. There is deliberately no "not built yet" state to fall
 * back into: a tab the declaration lists is a tab that works, and the only way to keep
 * that true is to declare a page when it is implemented and not before.
 */
abstract class PagedFragment : Fragment(R.layout.fragment_paged) {

    /** That tab's declared pages, from build.json::ui.<tab>.pages. */
    abstract fun pages(): List<NavPage>

    /** The real fragment for a declared page id. Null means the declaration is a lie. */
    abstract fun pageFragment(pageId: String): Fragment?

    private var selected: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val strip = view.findViewById<PageTabsView>(R.id.page_strip)
        val declared = pages()
        val restored = savedInstanceState?.getString(STATE_PAGE)
        val initial = restored?.takeIf { id -> declared.any { it.id == id } }
            ?: declared.firstOrNull()?.id

        // A single declared page needs no strip: the tab IS that page, and a one-item
        // picker is chrome that cannot be used.
        strip.visibility = if (declared.size > 1) View.VISIBLE else View.GONE
        // #868 the strip is libs:bottomnav's PageTabsView; the section sits under the shell's
        // own inset padding, so the strip adds none.
        strip.underTopChrome = false
        strip.pages = declared
        strip.onSelect = { open(it.id, strip) }
        initial?.let { open(it, strip) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, selected)
    }

    private fun open(pageId: String, strip: PageTabsView) {
        if (pageId == selected) return
        val fragment = pageFragment(pageId)
        if (fragment == null) {
            // A declared page with no fragment. Stated, not drawn blank.
            childFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.page_container, UndeclaredPageFragment.newInstance(pageId))
            }
            selected = pageId
            strip.selectedId = pageId
            return
        }
        selected = pageId
        strip.selectedId = pageId
        childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.page_container, fragment, pageId)
        }
    }

    private companion object {
        const val STATE_PAGE = "c3_selected_page"
    }
}
