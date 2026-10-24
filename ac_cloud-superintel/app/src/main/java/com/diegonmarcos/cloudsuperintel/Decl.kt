package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * The navigation declaration app/build.gradle bakes from build.json::ui (bottom_nav, sections, default_section),
 * decoded once through libs:bottomnav's NavDecl. Kotlin names no section or page label: the bottom nav, the second
 * row and the title dropdown all come from it.
 */
object Decl {
    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** The page ids Kotlin dispatches on; test-superintel-app.sh diffs them against build.json::ui in both directions. */
    const val SECTION_CHAT = "chat"
    const val SECTION_SETTINGS = "settings"
    const val PAGE_CHAT = "chat"
    const val PAGE_MODELS = "models"
    const val PAGE_BACKEND = "backend"
    const val PAGE_ABOUT = "about"
}
