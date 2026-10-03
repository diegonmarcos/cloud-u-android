package com.diegonmarcos.superapp.shell

/**
 * Marker for fragments that host collapsable content (stack panels,
 * accordion sections, etc.). MainActivity asks the active fragment
 * to toggle collapse-all when the user re-taps a bottom-nav slot
 * they're already on.
 *
 * #825 moved here from libs:core: the SuperApp is its one reader (ShellActivity) and
 * its only implementers (AggregatorStackFragment, SectionTabsFragment); libs:browser
 * stopped implementing it, so an edit no longer queues every app for fleet-refresh.
 */
interface Collapsible {
    /** Collapse all panels if any are expanded; expand all if all are
     *  collapsed. Returns true if the fragment handled the toggle. */
    fun toggleAllCollapsed(): Boolean
}
