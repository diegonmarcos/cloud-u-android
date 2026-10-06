package app.sterna.ui

import app.sterna.BuildConfig
import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * cloud-mail's five bottom-navigation items (#465), in display order, with Home in the centre.
 * This is the item TABLE of one app. The bar that draws it is the fleet-wide [com.diegonmarcos.superapp.bottomnav.BottomNavIsland]
 * (#565), which takes its items injected as [com.diegonmarcos.superapp.bottomnav.BottomNavEntry]. [BottomNavBar] maps this table
 * onto it. Each item's accessible name (strings.xml nav_bar_*) is also its visible label.
 *
 * Two of the five LAUNCH other apps and are not destinations of mail's NavHost. Tapping them
 * must not move the selected pill, so they carry no route. [BottomNavItem.packageName] is where
 * the interim Telegram / WhatsApp Business link is declared, so swapping "Chat" for a real
 * in-app screen later is a change to this one table and nothing else.
 *
 * The table is DERIVED from build.json::ui (below). It lived in libs:bottomnav while mail was the
 * module's only consumer; #868 moved it into the app so the lib holds no app's menu, and then into the declaration.
 */

/** What a tap on a bottom-nav item does: move inside this app, or leave it for another app. */
public enum class BottomNavAction { DESTINATION, LAUNCH }

/** One bottom-nav item: what kind of action it is and what it acts on. */
public data class BottomNavItem(
    /** Stable identifier, also the suffix of its accessible content description (`nav_bar_<id>`). */
    public val id: String,
    public val action: BottomNavAction,
    /** The NavHost destination for [BottomNavAction.DESTINATION]; null for a launch. */
    public val route: String? = null,
    /** The app to launch for [BottomNavAction.LAUNCH]: resolved against the device rather than
     *  assumed installed, and named here once so the interim links are one declaration each. */
    public val packageName: String? = null,
)

/**
 * The five items, left to right: build.json::ui (#868) read through libs:bottomnav's [NavDecl], so the
 * order, the Home centre and every hand-off live in ONE declaration and this file holds none of them.
 * A section's first page is what its item does: no `action` = a destination whose page id is the
 * NavHost route; `launch:<package>` = a hand-off to that app.
 */
internal val mailNav: NavDecl by lazy {
    NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
}

public val bottomNavItems: List<BottomNavItem> by lazy {
    mailNav.bottomSections().mapNotNull { section ->
        val page = section.pages.firstOrNull() ?: return@mapNotNull null
        if (page.action.startsWith(LAUNCH_PREFIX)) {
            BottomNavItem(section.id, BottomNavAction.LAUNCH, packageName = page.action.removePrefix(LAUNCH_PREFIX))
        } else {
            BottomNavItem(section.id, BottomNavAction.DESTINATION, route = page.id)
        }
    }
}

private const val LAUNCH_PREFIX = "launch:"

/** The destinations the bar owns: only these routes draw the bar. Everything else is a full-size
 *  screen (compose, read, settings) that the island would only get in the way of. One place, so
 *  the verdict for a new route is a single line here rather than a scattered boolean per site. */
public fun bottomNavOwns(route: String): Boolean =
    bottomNavItems.any { it.action == BottomNavAction.DESTINATION && it.route == route }