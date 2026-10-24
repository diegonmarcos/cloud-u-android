package com.diegonmarcos.cloudnav

import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.tracker.TrackerSleepFragment
import com.diegonmarcos.cloudnav.tracker.TrackerStepsFragment
import com.diegonmarcos.cloudnav.tracker.TrackerTimelineFragment
import com.diegonmarcos.superapp.bottomnav.NavPage

/** Tracker (#1156, replaces Configs in the island): the raw data. Timeline | Steps | Sleep, all read through the tracker provider. */
class TrackerFragment : PagedSectionFragment() {
    override val section = "tracker"
    override val containerId = R.id.nav_host_tracker
    override val fallbackPages = listOf(NavPage(PAGE_TIMELINE, "Timeline", "timeline"), NavPage(PAGE_STEPS, "Steps", "directions_walk"), NavPage(PAGE_SLEEP, "Sleep", "bedtime"))

    override fun pageFragment(id: String): Fragment = when (id) {
        PAGE_STEPS -> TrackerStepsFragment()
        PAGE_SLEEP -> TrackerSleepFragment()
        else -> TrackerTimelineFragment()
    }

    companion object {
        const val PAGE_TIMELINE = "timeline"
        const val PAGE_STEPS = "steps"
        const val PAGE_SLEEP = "sleep"
    }
}
