package com.diegonmarcos.cloudnav

import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.tracker.DayFragment
import com.diegonmarcos.cloudnav.tracker.ExploredMapsFragment
import com.diegonmarcos.superapp.bottomnav.NavPage

/**
 * Explored (the old Timeline section, #1156): Trips | Maps. Trips is the dashboards [DayFragment] draws (its own chips: Day first,
 * then Trips, Places, Cities, World, Insights); Maps shows the same trip data on an OSM map.
 */
class ExploredFragment : PagedSectionFragment() {
    override val section = "explored"
    override val containerId = R.id.nav_host_timeline
    override val fallbackPages = listOf(NavPage(PAGE_TRIPS, "Trips"), NavPage(PAGE_MAPS, "Maps"))

    override fun pageFragment(id: String): Fragment = when (id) {
        PAGE_MAPS -> ExploredMapsFragment()
        else -> DayFragment()
    }

    companion object {
        const val PAGE_TRIPS = "trips"
        const val PAGE_MAPS = "maps"
    }
}
