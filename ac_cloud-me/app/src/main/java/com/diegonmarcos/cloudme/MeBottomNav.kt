package com.diegonmarcos.cloudme

import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.BottomNavViewItem
import com.google.android.material.color.MaterialColors

/**
 * Cloud Me's bottom nav: libs:bottomnav's island fed from [Sections.bottom] (#531).
 *
 * The stock Material BottomNavigationView is gone; the bar is the fleet's one Compose island.
 * What stays Cloud Me's own is small: WHICH sections (the bar sections of build.json, in
 * `order`), what a tap on a launch section does (it leaves the app and the pill stays on the page
 * you are still on), the theme's inverse pair for the pill, and that DrawerLayout already
 * clears the system bars for its content. MainActivity and MeBottomNavTest both call [configure],
 * so the test measures the bar the app shows.
 */
object MeBottomNav {

    fun items(ctx: Context): List<BottomNavViewItem> = Sections.bottom().map {
        @Suppress("DiscouragedApi")
        val icon = ctx.resources.getIdentifier(it.icon, "drawable", ctx.packageName)
        BottomNavViewItem(it.id, it.label, icon)
    }

    /**
     * [onOpen] shows a section inside the app, [onTarget] launches a section's `target`.
     * Opening moves the pill; launching does not, so the bar keeps pointing at the page the
     * user comes back to.
     */
    fun configure(nav: BottomNavIslandView, onOpen: (String) -> Unit, onTarget: (String) -> Unit) {
        val ctx = nav.context
        fun attr(id: Int) = Color(MaterialColors.getColor(ctx, id, "MeBottomNav"))
        nav.items = items(ctx)
        nav.colorScheme = darkColorScheme(
            inverseSurface = attr(com.google.android.material.R.attr.colorSurfaceInverse),
            inverseOnSurface = attr(com.google.android.material.R.attr.colorOnSurfaceInverse),
            onSurfaceVariant = attr(com.google.android.material.R.attr.colorOnSurfaceVariant),
        )
        // activity_main's DrawerLayout (fitsSystemWindows) already margins its content by the
        // system-bar inset, and the island sits inside that content. Reading the live inset
        // again would lift the bar twice (#477).
        nav.insets = null
        nav.onSelect = { id ->
            val section = Sections.byId(id)
            when {
                section == null -> Unit
                else -> { nav.selectedId = id; onOpen(id) }
            }
        }
        // A re-tap on the page already shown reloads nothing; the pill is already there.
        nav.onReselect = {}
    }

    /** Keep the pill honest when a section was reached from the drawer, a tile or restored
     *  state: a bar section lights its item, an off-bar section leaves none lit. */
    fun sync(nav: BottomNavIslandView, sectionId: String?) {
        nav.selectedId = sectionId?.takeIf { id -> Sections.bottom().any { it.id == id } }
    }
}
