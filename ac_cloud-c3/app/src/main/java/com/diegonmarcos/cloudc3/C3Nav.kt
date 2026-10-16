package com.diegonmarcos.cloudc3

import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage

/**
 * Where the app is and where an id points, as pure functions of the DECLARATION ([NavDecl] + ui.aliases) so the JVM
 * suite runs them against this repository's own build.json. No section or page id is spelled here.
 */
object C3Nav {

    /** A place in the app: a section, and the page inside it (null = the section's first page). */
    data class Target(val section: String, val page: String? = null)

    /**
     * Resolve an id from outside (a deep link's `page:c3/<id>`, a restored tab, an old tester) to where it lives now:
     * a moved page id (ui.aliases.pages) first, then a declared section, then a renamed section (ui.aliases.sections),
     * then a page id that some section declares. Null when nothing owns it.
     */
    fun resolve(nav: NavDecl, aliases: Declarations.Aliases, id: String?): Target? {
        val key = id?.takeIf { it.isNotBlank() } ?: return null
        aliases.pages[key]?.let { dest ->
            val sec = dest.substringBefore('/')
            val page = dest.substringAfter('/', "").takeIf { it.isNotBlank() }
            if (nav.section(sec) != null) return Target(sec, page)
        }
        if (nav.section(key) != null) return Target(key)
        aliases.sections[key]?.takeIf { nav.section(it) != null }?.let { return Target(it) }
        nav.sections.firstOrNull { s -> s.allPages().any { it.id == key } }?.let { return Target(it.id, key) }
        return null
    }

    /** The page the app opens on: a restored place that still resolves, else ui.default_section (Home). */
    fun start(nav: NavDecl, aliases: Declarations.Aliases, restored: String? = null): Target? =
        resolve(nav, aliases, restored) ?: nav.default()?.let { Target(it.id) }

    /** One line of the drawer tree: a section header (page == null) or one of its pages, [depth] 1 and down. */
    data class DrawerRow(val section: String, val page: String?, val label: String, val depth: Int)

    /** The drawer's tree: EVERY declared section (bar or not) in declared order, each followed by its pages. */
    fun drawerRows(nav: NavDecl): List<DrawerRow> = nav.sections.flatMap { s ->
        listOf(DrawerRow(s.id, null, s.label, 0)) + s.pages.flatMap { pageRows(s.id, it, 1) }
    }

    private fun pageRows(section: String, p: NavPage, depth: Int): List<DrawerRow> =
        listOf(DrawerRow(section, p.id, p.label, depth)) + p.pages.flatMap { pageRows(section, it, depth + 1) }
}
