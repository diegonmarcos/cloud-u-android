package org.fossify.phone.nav

import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavSection
import org.fossify.phone.BuildConfig
import org.fossify.phone.R

/**
 * Cloud Dialer's navigation (#868): build.json::ui, baked into BuildConfig, decoded once. The
 * sections are the island's items; which of them are on screen is the pager's business
 * (Settings > Manage shown tabs), so [shown] takes the ids the pager shows.
 */
object DialerNav {
    val decl: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    /** The drawable a declared icon name stands for. */
    fun icon(name: String): Int = when (name) {
        "home" -> R.drawable.ic_home_vector
        "person" -> R.drawable.ic_person_outline_vector
        "star" -> R.drawable.ic_star_outline_vector
        "history" -> R.drawable.ic_clock_vector
        "sms" -> R.drawable.ic_sms_vector
        else -> R.drawable.ic_sms_vector
    }

    /** The bottom nav's sections, in declared order, limited to the sections whose page is on screen. */
    fun shown(visibleSections: Collection<String>): List<NavSection> =
        decl.bottomSections().filter { it.id in visibleSections }
}
