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
import com.diegonmarcos.cloudnav.maps.MapsDailyFragment
import com.diegonmarcos.cloudnav.maps.MapsExploredFragment
import com.diegonmarcos.cloudnav.maps.MapsPageStrip
import com.diegonmarcos.cloudnav.maps.MapsStopsFragment
import com.diegonmarcos.cloudnav.tracker.StepsFragment
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * Timeline section: the strip of build.json's `ui.sections[timeline].pages` (Explored | Daily |
 * Stops | Steps) over one child page. Explored / Daily / Stops are libs:maps' pages; Steps is this
 * app's ([StepsFragment]) — which is why the host lives here and not in libs:maps' own Timeline host.
 */
class TimelineFragment : KitComposeFragment() {
    private var current by mutableStateOf(PAGE_EXPLORED)
    private var nonce by mutableIntStateOf(0)

    override fun palette(): KitPalette = TrackerInk.palette()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = pages().first().id
    }

    private fun pages(): List<NavPage> = NavConfig.decl.section("timeline")?.pages.orEmpty().ifEmpty { DEFAULT_PAGES }

    @Composable
    override fun Content() {
        val pages = remember { pages() }
        Column(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { c -> MapsPageStrip.create(c, pages, current) { page -> current = page.id; nonce++ } },
                update = { tabs -> if (tabs.selectedId != current) tabs.selectedId = current },
            )
            FragmentHost(childFragmentManager, R.id.nav_host_timeline, current to nonce, Modifier.weight(1f)) { pageFragment(current) }
        }
    }

    private fun pageFragment(id: String): Fragment = when (id) {
        PAGE_DAILY -> MapsDailyFragment.newInstance()
        PAGE_STOPS -> MapsStopsFragment.newInstance(null)
        PAGE_STEPS -> StepsFragment()
        else -> MapsExploredFragment.newInstance()
    }

    companion object {
        const val PAGE_EXPLORED = "explored"
        const val PAGE_DAILY = "daily"
        const val PAGE_STOPS = "stops"
        const val PAGE_STEPS = "steps"
        val DEFAULT_PAGES = listOf(NavPage(PAGE_EXPLORED, "Explored"), NavPage(PAGE_DAILY, "Daily"), NavPage(PAGE_STOPS, "Stops"), NavPage(PAGE_STEPS, "Steps"))
    }
}
