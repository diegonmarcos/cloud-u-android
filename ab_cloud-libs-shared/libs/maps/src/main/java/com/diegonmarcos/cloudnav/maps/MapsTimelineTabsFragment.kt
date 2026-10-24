package com.diegonmarcos.cloudnav.maps

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
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * Maps → Timeline page — top-level wrapper that puts a `Daily | Stops |
 * Explored` tab row above a single fragment host. Tab change replaces the
 * host's child fragment.
 *
 *   • Daily     — one row per calendar day showing where the user slept
 *                 that night (see [MapsDailyFragment]). Tapping a row calls
 *                 [openStopsForDay] on this host, switching to Stops filtered
 *                 to that one day.
 *   • Stops     — flat reverse-chrono list of Stops (existing
 *                 [MapsStopsFragment]); all-time by default, or scoped to a
 *                 single day when arrived at via [openStopsForDay]. Tapping a
 *                 row opens the full-screen day map ([MapsDayMapFragment]).
 *   • Explored  — every CITY ever visited (sourced from Daily's per-day
 *                 picks, not raw Stops — keeps the map from being polluted by
 *                 same-city noise), one pin each (all-time, no window) — tap
 *                 a pin for its full day-by-day visit history (see
 *                 [MapsExploredFragment]).
 *
 * #868 The strip is libs:bottomnav's PageTabsView, made by [MapsPageStrip.create]. Its pages are
 * the host app's declared `ui.sections[timeline].pages` (ids explored | daily | stops), handed in
 * through [newInstance]; a host that passes none gets [DEFAULT_PAGES].
 */
class MapsTimelineTabsFragment : KitComposeFragment(), TimelineHost {

    /** Which page is up; [nonce] changes on every pick so a re-tap of the same tab rebuilds it. */
    private var current by mutableStateOf(PAGE_EXPLORED)
    private var nonce by mutableIntStateOf(0)

    /** One-shot day filter consumed by the next Stops-tab render — set by
     *  [openStopsForDay], cleared once the Stops page is built, so a later manual
     *  tap on the Stops tab header shows the unfiltered all-time list again. */
    private var pendingStopsDayMs: Long? = null

    override fun palette(): KitPalette = MapsInk.palette()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = MapsPageStrip.fromArgs(arguments, DEFAULT_PAGES).first().id
    }

    @Composable
    override fun Content() {
        val pages = remember { MapsPageStrip.fromArgs(arguments, DEFAULT_PAGES) }
        Column(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { c ->
                    MapsPageStrip.create(c, pages, current) { page ->
                        pendingStopsDayMs = null
                        current = page.id
                        nonce++
                    }
                },
                update = { tabs -> if (tabs.selectedId != current) tabs.selectedId = current },
            )
            ChildFragmentHost(
                fm = childFragmentManager,
                containerId = R.id.maps_host_timeline,
                key = current to nonce,
                modifier = Modifier.weight(1f),
            ) { pageFragment(current) }
        }
    }

    /** Called by a Daily row's tap — switches to Stops, scoped to [dayMs]. */
    override fun openStopsForDay(dayMs: Long) {
        pendingStopsDayMs = dayMs
        current = PAGE_STOPS
        nonce++
    }

    private fun pageFragment(id: String): Fragment = when (id) {
        PAGE_DAILY -> MapsDailyFragment.newInstance()
        PAGE_STOPS -> {
            val dayMs = pendingStopsDayMs
            pendingStopsDayMs = null
            MapsStopsFragment.newInstance(dayMs)
        }
        else -> MapsExploredFragment.newInstance()  // explored, the default
    }

    companion object {
        const val PAGE_EXPLORED = "explored"
        const val PAGE_DAILY = "daily"
        const val PAGE_STOPS = "stops"

        /** What a host that declares no pages shows: the three views, in order. */
        val DEFAULT_PAGES = listOf(
            NavPage(PAGE_EXPLORED, "Explored", "explore"),
            NavPage(PAGE_DAILY, "Daily", "schedule"),
            NavPage(PAGE_STOPS, "Stops", "place"),
        )

        fun newInstance(pages: List<NavPage> = DEFAULT_PAGES) =
            MapsTimelineTabsFragment().apply { arguments = MapsPageStrip.toArgs(pages) }
    }
}
