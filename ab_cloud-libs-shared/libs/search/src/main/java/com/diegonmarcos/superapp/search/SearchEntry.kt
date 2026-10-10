package com.diegonmarcos.superapp.search

import com.diegonmarcos.superapp.search.SearchKinds.BROWSER_FAV
import com.diegonmarcos.superapp.search.SearchKinds.BROWSER_HISTORY
import com.diegonmarcos.superapp.search.SearchKinds.BROWSER_WEB
import com.diegonmarcos.superapp.search.SearchKinds.CLOUD_APPS
import com.diegonmarcos.superapp.search.SearchKinds.CLOUD_CONFIGS
import com.diegonmarcos.superapp.search.SearchKinds.PHONE_APPS
import com.diegonmarcos.superapp.search.SearchKinds.PHONE_CONFIGS

/**
 * Where a search was opened from. Each place starts on its own scopes and remembers the user's
 * own choice separately ([id] keys it), so turning Web on from the Home star does not turn it on
 * in Cloud ▸ Apps.
 *
 *  - [CLOUD_APPS_PAGE] and [HOME_SHEET] (the swipe sheet, whose Cloud tab is that same page)
 *    find things to open on the phone: the apps and the configs, nothing from the browser;
 *  - [HOME_STAR] (Polaris, the launcher's Search shortcut, action:open_search) is the opposite:
 *    the browser's favourites, history and a web search, plus the configs; the apps stay off.
 *
 * Defaults are by scope KIND, so a scope renamed in build.json keeps its default.
 */
enum class SearchEntry(val id: String, private val defaultKinds: Set<String>) {
    CLOUD_APPS_PAGE("cloud_apps_page", setOf(CLOUD_APPS, PHONE_APPS, CLOUD_CONFIGS, PHONE_CONFIGS)),
    HOME_SHEET("home_sheet", setOf(CLOUD_APPS, PHONE_APPS, CLOUD_CONFIGS, PHONE_CONFIGS)),
    HOME_STAR("home_star", setOf(BROWSER_FAV, BROWSER_HISTORY, BROWSER_WEB, CLOUD_CONFIGS, PHONE_CONFIGS));

    /** The scopes this entry starts on. A taxonomy with none of its kinds starts on all of them. */
    fun defaults(scopes: List<SearchScope>): Set<String> =
        scopes.filter { it.kind in defaultKinds }.map { it.id }.toSet()
            .ifEmpty { scopes.map { it.id }.toSet() }

    /**
     * What to show: the user's [saved] choice for this entry, kept to scopes that still exist;
     * the [defaults] when nothing was ever saved, or when nothing saved still exists.
     */
    fun selection(scopes: List<SearchScope>, saved: Set<String>?): Set<String> {
        val ids = scopes.map { it.id }.toSet()
        val kept = saved.orEmpty().filter { it in ids }.toSet()
        return kept.ifEmpty { defaults(scopes) }
    }
}
