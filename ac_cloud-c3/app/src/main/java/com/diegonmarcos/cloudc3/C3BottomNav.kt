package com.diegonmarcos.cloudc3

import android.content.Context
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.BottomNavViewItem

/**
 * #648 cloud-c3's bottom nav: libs:bottomnav's island fed from build.json::ui.bottom_nav (#868, through NavDecl).
 *
 * The five items come from the ONE tab declaration, in declared order, so reordering that
 * array reorders the bar and nothing else. An icon name in the declaration is resolved to a
 * DRAWABLE by name — the cloud-me contract — which is why each declared icon has a drawable
 * of that exact name in res/drawable and why test-c3-shell.sh asserts the pair exists in
 * both directions: a name with no drawable resolves to 0 and draws a blank square.
 *
 * Every tap is a DESTINATION here. Unlike cloud-mail's table and cloud-me's sections, no
 * cloud-c3 tab launches another app: the three sibling APKs are rows INSIDE the Apps tab,
 * not tabs of their own, so the pill always moves and never has to stay behind.
 */
object C3BottomNav {

    /**
     * The declared tabs as island items. A declared icon that names no drawable would
     * resolve to 0 and draw nothing, so it is reported rather than silently blank —
     * the tester fails the build on it, and this keeps the runtime honest too.
     */
    fun items(ctx: Context): List<BottomNavViewItem> = Declarations.nav.viewItems { name ->
        @Suppress("DiscouragedApi")
        ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
    }

    /** True when every declared tab icon resolves to a real drawable in this APK. */
    fun iconsResolve(ctx: Context): Boolean = items(ctx).all { it.icon != 0 }

    /**
     * [onOpen] shows a tab's page in the shell's one container. The pill always moves,
     * because every tab is a destination.
     */
    fun configure(nav: BottomNavIslandView, onOpen: (String) -> Unit) {
        val ctx = nav.context
        nav.items = items(ctx)
        nav.onSelect = { id -> nav.selectedId = id; onOpen(id) }
        // Re-tapping the tab already shown rebuilds nothing; the pill is already there.
        nav.onReselect = {}
    }

    /** Keep the pill honest when a tab was reached from restored state rather than a tap. */
    fun sync(nav: BottomNavIslandView, tabId: String?) {
        nav.selectedId = tabId?.takeIf { id -> Declarations.tabs.any { it.id == id } }
    }
}
