package com.diegonmarcos.superapp.search

import android.content.ComponentName
import android.content.Intent
import android.os.UserHandle
import android.util.Base64
import org.json.JSONArray

/**
 * The vocabulary of the search: what an app's [SearchSource] hands over and what [SearchEngine]
 * returns. No UI here — each app draws the results its own way (the SuperApp's search panel);
 * the index is testable data, the panel is only a renderer.
 */

/** One chip. `kind` is the app's own label for "which builder fills this";
 *  the engine never interprets it, it only groups and filters by [SearchScope.id].
 *  [section] titles the scope's group of results ("Browser favourites"), [label] its chip. */
data class SearchScope(val id: String, val label: String, val kind: String, val section: String = label)

/**
 * One result row. Exactly one payload is meant to be set — the sheet branches
 * on whichever is non-null, so a hit carries its own dispatch and the builders
 * stay free of UI.
 *
 *  - [phoneApp]          launch through LauncherApps
 *  - [settingsComponent] start that Android Settings activity
 *  - [copyValue]         copy to the clipboard
 *  - [target]            hand back to the host's own target grammar
 *  - [intent]            start this activity (a page in Cloud Browser, a web search)
 */
data class SearchHit(
    val label: String,
    val crumb: String,
    val source: String,
    val phoneApp: PhoneAppRef? = null,
    val settingsComponent: ComponentName? = null,
    val copyValue: String? = null,
    val target: String? = null,
    val intent: Intent? = null,
)

/** Just enough to launch an installed app through LauncherApps. A component
 *  plus a user, rather than a LauncherActivityInfo, so an app can build hits
 *  from whatever it already enumerated — including a filtered list. */
data class PhoneAppRef(val component: ComponentName, val user: UserHandle)

/**
 * One `:`-command. [alias] is what the user types after the colon; it is
 * matched case-insensitively and is expected to be kebab-case, because that is
 * what reads well on a phone keyboard (`:update-all`, not `:update_all`).
 * [target] is opaque to the lib — it goes straight back to the app, which
 * runs it the way it runs a tile target.
 */
data class SearchCommand(val alias: String, val label: String, val target: String, val crumb: String = "")

/** Scope chips declared in the consuming app's `build.json::ui.search_scopes`,
 *  baked into this library's own BuildConfig at build time. */
object SearchScopes {
    fun fromBuildConfig(): List<SearchScope> = runCatching {
        val arr = JSONArray(String(Base64.decode(BuildConfig.UI_SEARCH_SCOPES_B64, Base64.NO_WRAP)))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val label = o.optString("label")
            SearchScope(o.optString("id"), label, o.optString("kind"), o.optString("section", label).ifBlank { label })
        }
    }.getOrDefault(emptyList())
}

/** The scope kinds the SuperApp's builders fill (build.json::ui.search_scopes[].kind). */
object SearchKinds {
    const val CLOUD_APPS = "cloud_apps"
    const val PHONE_APPS = "phone_apps"
    const val CLOUD_CONFIGS = "cloud_configs"
    const val PHONE_CONFIGS = "phone_configs"
    /** Cloud Browser's favourites (bookmarks), its history, and a "search the web" row. */
    const val BROWSER_FAV = "browser_fav"
    const val BROWSER_HISTORY = "browser_history"
    const val BROWSER_WEB = "browser_web"

    /** Kinds answered per query rather than indexed once: their data is another app's. */
    val LIVE = setOf(BROWSER_FAV, BROWSER_HISTORY, BROWSER_WEB)
}

/** What a [SearchSource.live] lookup answered: the hits, and at most a few lines to say what is
 *  missing (e.g. Cloud Browser not installed), each shown once at the end of the results. */
data class LiveResult(val hits: List<SearchHit>, val notices: List<String> = emptyList()) {
    companion object { val EMPTY = LiveResult(emptyList()) }
}

/**
 * Where an app's results come from — the only app-shaped part of the search. [hitsFor] is the
 * index, built once per opening; [live] answers the [SearchKinds.LIVE] scopes for one query, OFF
 * the main thread (it may cross into another app). Every hit carries `source = scope.id`.
 */
interface SearchSource {
    fun hitsFor(scope: SearchScope): List<SearchHit>
    fun live(scopes: List<SearchScope>, query: String): LiveResult = LiveResult.EMPTY
    fun commands(): List<SearchCommand> = emptyList()
}
