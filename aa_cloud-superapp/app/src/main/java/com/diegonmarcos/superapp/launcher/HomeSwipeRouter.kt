package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.apptabs.AppTabPrefs

/** Which declared action a Home gesture runs; pure so it is unit-tested without an activity. */
object HomeSwipeRouter {
    /** A swipe down is split by the half of the screen it STARTS in: left half -> [left], right half -> [right]. */
    fun downAction(startX: Float, screenWidth: Int, left: String, right: String): String =
        if (startX < screenWidth / 2f) left else right

    /** The newest launched app that is a fleet/constellation app ([fleetPackages]); null when none was launched yet. */
    fun lastCloudApp(entries: List<AppTabPrefs.Entry>, fleetPackages: Set<String>): AppTabPrefs.Entry.ExternalAppEntry? =
        entries.filterIsInstance<AppTabPrefs.Entry.ExternalAppEntry>().firstOrNull { it.packageName in fleetPackages }

    /** Every package a fleet app can be installed as: hub, stock-resigned alt, install package and forks. */
    fun fleetPackages(apps: List<Sections.ExternalApp>): Set<String> =
        apps.flatMap { listOf(it.hubPackage, it.altPackage, it.installPackage) + it.forks.values }.filter { it.isNotBlank() }.toSet()
}
