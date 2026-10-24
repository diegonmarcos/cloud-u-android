package com.diegonmarcos.cloudnav

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.maps.MapsPageStrip
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * A section of the nav island that is a strip of pages (build.json `ui.sections[<section>].pages`, drawn by libs:maps'
 * [MapsPageStrip]) over one child page. [ExploredFragment] and [TrackerFragment] (#1156) are the two uses. A page id maps to the
 * child fragment [pageFragment] returns; [containerId] is the child host's view id.
 */
abstract class PagedSectionFragment : KitComposeFragment() {
    protected abstract val section: String
    protected abstract val fallbackPages: List<NavPage>
    protected abstract val containerId: Int
    protected abstract fun pageFragment(id: String): Fragment

    private var current by mutableStateOf("")
    private var nonce by mutableIntStateOf(0)

    override fun palette(): KitPalette = TrackerInk.palette()

    private fun pages(): List<NavPage> = NavConfig.decl.section(section)?.pages.orEmpty().ifEmpty { fallbackPages }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = arguments?.getString(ARG_PAGE)?.takeIf { id -> pages().any { it.id == id } } ?: pages().first().id
    }

    @Composable
    override fun Content() {
        val pages = remember { pages() }
        Column(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { c -> MapsPageStrip.create(c, pages, current) { page -> current = page.id; nonce++ } },
                update = { tabs -> if (tabs.selectedId != current) tabs.selectedId = current },
            )
            FragmentHost(childFragmentManager, containerId, current to nonce, Modifier.weight(1f)) { pageFragment(current) }
        }
    }

    companion object { const val ARG_PAGE = "page" }
}
