package com.diegonmarcos.superapp.bottomnav

/**
 * The five bottom-navigation items of the mail home bar (#465), in display order, Home in the
 * centre. Icons only, no labels, so the ordering and the content descriptions are the only thing a
 * screen reader has to navigate by — both live here, where a test can pin them.
 *
 * Two of the five are LAUNCHES of other apps, not destinations of this NavHost. Tapping them must
 * not move the selected indicator, so they carry no route; [BottomNavItem.packageName] is where the
 * interim Telegram / WhatsApp Business link is declared, so swapping "Chat" for a real in-app
 * screen later is a change to this one table and nothing else.
 *
 * THE ONE DECLARATION (#493). The item list, the routes, the launch targets and the selected-destination
 * contract all live here, once, in the shared module. The consuming app adds this module BY
 * REFERENCE and renders [BottomNavBar]; it does not restate any item. This file is package-visible
 * so apps + screened tests see the model, and there is exactly one copy in the constellation.
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

/** The five items, left to right. Order is load-bearing: Home is deliberately the centre item. */
public val bottomNavItems: List<BottomNavItem> = listOf(
    BottomNavItem(id = "mail", action = BottomNavAction.DESTINATION, route = "inbox"),
    BottomNavItem(id = "chat", action = BottomNavAction.LAUNCH, packageName = "org.telegram.messenger"),
    BottomNavItem(id = "home", action = BottomNavAction.DESTINATION, route = "home"),
    BottomNavItem(id = "rss", action = BottomNavAction.DESTINATION, route = "rss"),
    BottomNavItem(id = "video", action = BottomNavAction.LAUNCH, packageName = "com.whatsapp.w4b"),
)

/** The destinations the bar owns: only these routes draw the bar. Everything else is a full-size
 *  screen (compose, read, settings) that the island would only get in the way of. One place, so
 *  the verdict for a new route is a single line here rather than a scattered boolean per site. */
public fun bottomNavOwns(route: String): Boolean =
    bottomNavItems.any { it.action == BottomNavAction.DESTINATION && it.route == route }