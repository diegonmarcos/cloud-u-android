package com.diegonmarcos.cloudnav.maps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabsView

/**
 * Maps → Timeline page — top-level wrapper that puts a `Daily | Stops |
 * Explored` tab row above a single FrameLayout host. Tab change replaces the
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
 * #868 The strip is libs:bottomnav's [PageTabsView]. Its pages are the host app's declared
 * `ui.sections[timeline].pages` (ids explored | daily | stops), handed in through
 * [newInstance]; a host that passes none gets [DEFAULT_PAGES].
 */
class MapsTimelineTabsFragment : Fragment() {

    private var hostId: Int = 0
    private lateinit var tabs: PageTabsView
    private var pages: List<NavPage> = DEFAULT_PAGES

    /** One-shot day filter consumed by the next Stops-tab render — set by
     *  [openStopsForDay], cleared once [showPage] reads it, so a later manual
     *  tap on the Stops tab header shows the unfiltered all-time list again. */
    private var pendingStopsDayMs: Long? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        pages = MapsPageStrip.fromArgs(arguments, DEFAULT_PAGES)
        tabs = MapsPageStrip.create(ctx, pages, pages.first().id) { showPage(it.id) }
        tabs.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val host = FrameLayout(ctx).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f,
            )
        }
        hostId = host.id
        root.addView(tabs)
        root.addView(host)

        if (s == null) showPage(pages.first().id)
        return root
    }

    /** Called by a Daily row's tap — switches to Stops, scoped to [dayMs]. */
    fun openStopsForDay(dayMs: Long) {
        pendingStopsDayMs = dayMs
        tabs.selectedId = PAGE_STOPS
        showPage(PAGE_STOPS)
    }

    private fun showPage(id: String) {
        val frag: Fragment = when (id) {
            PAGE_DAILY -> MapsDailyFragment.newInstance()
            PAGE_STOPS -> {
                val dayMs = pendingStopsDayMs
                pendingStopsDayMs = null
                MapsStopsFragment.newInstance(dayMs)
            }
            else -> MapsExploredFragment.newInstance()  // explored, the default
        }
        childFragmentManager.beginTransaction()
            .replace(hostId, frag)
            .commitAllowingStateLoss()
    }

    companion object {
        const val PAGE_EXPLORED = "explored"
        const val PAGE_DAILY = "daily"
        const val PAGE_STOPS = "stops"

        /** What a host that declares no pages shows: the three views, in order. */
        val DEFAULT_PAGES = listOf(
            NavPage(PAGE_EXPLORED, "Explored"),
            NavPage(PAGE_DAILY, "Daily"),
            NavPage(PAGE_STOPS, "Stops"),
        )

        fun newInstance(pages: List<NavPage> = DEFAULT_PAGES) =
            MapsTimelineTabsFragment().apply { arguments = MapsPageStrip.toArgs(pages) }
    }
}
