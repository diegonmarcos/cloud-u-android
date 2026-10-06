package com.diegonmarcos.superapp.ui

import android.content.Context
import android.view.ViewGroup
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.BottomNavViewItem
import com.diegonmarcos.superapp.launcher.Sections

/**
 * The shell's bottom nav: libs:bottomnav's island, fed from build.json (#531).
 *
 * The private CloudBottomNavView and its menu, drawables and colour selector are gone; the bar is
 * the fleet's one Compose island. This file is the only superapp-specific part of it, which is one
 * fact: WHICH sections (ui.bottom_nav). The look is the lib's (FleetChrome), the same in every app.
 * ShellActivity and the bottom-nav tests both call [configure], so the tests measure the island the shell actually shows.
 */
object ShellBottomNav {

    /** ui.bottom_nav in bar order, each item labelled and iconed by its section for [mode]. */
    fun items(ctx: Context, mode: String): List<BottomNavViewItem> = Sections.bottomNav().map {
        BottomNavViewItem(it.id, it.label, Sections.iconResFor(ctx, it.iconForMode(mode)))
    }

    fun configure(nav: BottomNavIslandView, mode: String, content: ViewGroup) {
        nav.items = items(nav.context, mode)
        // ShellActivity.applyEdgeToEdgeInsets pads shell_linear by the system-bar inset, and the
        // island sits inside it; the island measures that room itself, so it is lifted once (#477).
        // #673 scroll-collapse. [content] is the shell's content host, so scrolling any page
        // collapses the bar to icons. Required rather than optional: #532's collapse was inert in
        // this shell for as long as nothing here drove it, and an argument that can be omitted is
        // the same defect waiting to happen again. Re-configuring replaces the driver, never
        // stacks another one.
        nav.collapseOnScrollIn(content)
    }
}
