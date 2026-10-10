package com.diegonmarcos.superapp.launcher

import android.content.Intent
import android.content.pm.PackageManager
import com.diegonmarcos.superapp.fleetconfig.CloudSearchQuery

/**
 * #937 Cloud > Apps search with no match: the row Search "<text>" in Cloud Search hands the typed
 * text over. The intent is CloudSearchQuery's (libs:fleetconfig-model, the contract Cloud Search
 * reads it with): ACTION_SEARCH, explicit to the Cloud Search package, SearchManager.QUERY plus
 * CloudSearchQuery.EXTRA_QUERY. When no candidate package answers it (not installed, or a Cloud
 * Search built before it took a query) [intent] is null and [GroupedTilesFragment] opens the app
 * through its tile target instead (launched, or offered for install, as the AGI tile is).
 */
object CloudSearchHandoff {

    /** The packages a Cloud Search may be installed as, from the external_apps row, hub first. */
    fun packages(app: Sections.ExternalApp?): List<String> =
        listOfNotNull(app?.hubPackage, app?.altPackage).filter { it.isNotBlank() }.distinct()

    /** The intent for the first of [packages] that takes a search intent, or null when none does. */
    fun intent(pm: PackageManager, packages: List<String>, query: String): Intent? =
        packages.asSequence()
            .map { CloudSearchQuery.intent(it, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .firstOrNull { pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
}
