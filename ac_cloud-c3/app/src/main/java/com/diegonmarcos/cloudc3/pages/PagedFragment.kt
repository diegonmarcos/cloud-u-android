package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabs
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * #648 a content tab: a strip over that tab's DECLARED pages, and one container the
 * selected page's fragment is swapped into.
 *
 * ONE implementation for every paged tab, so adding a page is a build.json edit. The strip
 * is libs:bottomnav's PageTabs over the section's declared pages (#868) — the labels are
 * never spelled here.
 *
 * THE RULE THIS CLASS ENFORCES AT RUNTIME, and the tester at build time: a declared page
 * MUST resolve to a real fragment. [pageFragment] returning null is treated as a
 * programming error and says so on screen, rather than drawing an empty pane that looks
 * like a page with nothing in it. There is deliberately no "not built yet" state to fall
 * back into: a tab the declaration lists is a tab that works, and the only way to keep
 * that true is to declare a page when it is implemented and not before.
 *
 * 2026-10-16 owner decision: all apps must be Compose — the tab is a [KitComposeFragment].
 */
abstract class PagedFragment : KitComposeFragment() {

    /** That tab's declared pages, from build.json::ui.<tab>.pages. */
    abstract fun pages(): List<NavPage>

    /** The real fragment for a declared page id. Null means the declaration is a lie. */
    abstract fun pageFragment(pageId: String): Fragment?

    private var selected by mutableStateOf<String?>(null)

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val declared = pages()
        val restored = savedInstanceState?.getString(STATE_PAGE)
        selected = restored?.takeIf { id -> declared.any { it.id == id } } ?: declared.firstOrNull()?.id
    }

    @Composable
    override fun Content() {
        val declared = remember { pages() }
        Column(Modifier.fillMaxSize()) {
            // A single declared page needs no strip: the tab IS that page, and a one-item
            // picker is chrome that cannot be used. #868 the strip is libs:bottomnav's; the
            // section sits under the shell's own inset padding, so the strip adds none.
            if (declared.size > 1) {
                PageTabs(
                    pages = declared,
                    selectedId = selected,
                    onSelect = { selected = it.id },
                    underTopChrome = false,
                )
            }
            val id = selected
            if (id != null) {
                C3FragmentHost(
                    fm = childFragmentManager,
                    containerId = R.id.page_container,
                    key = id,
                    modifier = Modifier.weight(1f),
                ) {
                    // A declared page with no fragment. Stated, not drawn blank.
                    pageFragment(id) ?: UndeclaredPageFragment.newInstance(id)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, selected)
    }

    private companion object {
        const val STATE_PAGE = "c3_selected_page"
    }
}
