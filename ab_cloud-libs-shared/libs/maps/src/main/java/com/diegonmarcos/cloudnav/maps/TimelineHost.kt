package com.diegonmarcos.cloudnav.maps

/**
 * What a Timeline page needs from whatever hosts it (libs:maps' own [MapsTimelineTabsFragment], or an
 * app's host that adds pages of its own): Daily's row tap asks the host to show Stops for one day.
 */
interface TimelineHost {
    fun openStopsForDay(dayMs: Long)

    companion object {
        /** Daily -> Stops drill-down: true when [parent] is a host and was asked, false when nothing handled it. */
        fun openStops(parent: Any?, dayMs: Long): Boolean =
            (parent as? TimelineHost)?.also { it.openStopsForDay(dayMs) } != null
    }
}
