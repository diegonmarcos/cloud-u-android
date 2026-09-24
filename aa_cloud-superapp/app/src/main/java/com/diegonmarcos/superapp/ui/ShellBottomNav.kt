package com.diegonmarcos.superapp.ui

import android.content.Context
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.BottomNavViewItem
import com.diegonmarcos.superapp.launcher.Sections
import com.google.android.material.color.MaterialColors

/**
 * The shell's bottom nav: libs:bottomnav's island, fed from build.json (#531).
 *
 * The private CloudBottomNavView and its menu, drawables and colour selector are gone; the bar is
 * the fleet's one Compose island. This file is the only superapp-specific part of it, which is
 * three facts: WHICH sections (ui.bottom_nav), how the launcher theme colours the pill, and that
 * the shell already pads for the system bars. ShellActivity and the bottom-nav tests both call
 * [configure], so the tests measure the island the shell actually shows.
 */
object ShellBottomNav {

    /** ui.bottom_nav in bar order, each item labelled and iconed by its section for [mode]. */
    fun items(ctx: Context, mode: String): List<BottomNavViewItem> = Sections.bottomNav().reversed().map {
        BottomNavViewItem(it.id, it.label, Sections.iconResFor(ctx, it.iconForMode(mode)))
    }

    /** The launcher theme's inverse pair, carried into the island: the selected pill is the
     *  theme's light capsule (colorSurfaceInverse) with colorOnSurfaceInverse ink, and the other
     *  items are colorOnSurfaceVariant. The same three attrs the deleted bottom_nav_content
     *  selector and bg_bottom_nav_item_checked read, so each theme keeps its own capsule. */
    fun colorScheme(ctx: Context): ColorScheme {
        fun attr(id: Int) = Color(MaterialColors.getColor(ctx, id, "ShellBottomNav"))
        return darkColorScheme(
            inverseSurface = attr(com.google.android.material.R.attr.colorOnSurfaceVariant),
            inverseOnSurface = attr(com.google.android.material.R.attr.colorOnSurfaceInverse),
            onSurfaceVariant = attr(com.google.android.material.R.attr.colorOnSurfaceVariant),
        )
    }

    fun configure(nav: BottomNavIslandView, mode: String) {
        nav.items = items(nav.context, mode)
        nav.colorScheme = colorScheme(nav.context)
        // ShellActivity.applyEdgeToEdgeInsets pads shell_linear by the system-bar inset, and the
        // island sits inside it. Reading the live inset again would lift the bar twice (#477).
        nav.insets = null
    }
}
