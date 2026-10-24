package com.diegonmarcos.cloudagenda.ui

import com.diegonmarcos.cloudagenda.BuildConfig
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDecl

/**
 * The ids Kotlin dispatches on. build.json::ui is the declaration; test/test-tasks-nav.sh diffs this object against
 * it in both directions, so an id declared and never drawn (or drawn and never declared) fails the build.
 */
object Ids {
    const val TASKS = "tasks"
    const val CALENDAR = "calendar"
    const val SETTINGS = "settings"

    const val PAGE_TASKS = "tasks"
    const val PAGE_EVENTS = "events"
    const val PAGE_CALENDAR = "calendar"
    const val PAGE_INVITES = "invites"
    const val PAGE_APPEARANCE = "appearance"
    const val PAGE_AGENT = "agent"
    const val PAGE_INBOX = "inbox"
    const val PAGE_FEEDS = "feeds"
}

/** The navigation declaration baked from build.json::ui (ui.bottom_nav, ui.sections, ui.default_section). */
object Declarations {
    /** build.json::ui.top_bar (the fleet's first row); null = the app declares none. */
    val topBar: TopBarDecl? by lazy { TopBarDecl.fromBuildConfig(BuildConfig.UI_TOP_BAR_B64) }

    val nav: NavDecl by lazy { NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION) }
}
