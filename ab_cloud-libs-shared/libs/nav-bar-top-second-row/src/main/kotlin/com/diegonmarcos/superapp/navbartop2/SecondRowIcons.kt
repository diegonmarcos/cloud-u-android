package com.diegonmarcos.superapp.navbartop2

import androidx.compose.ui.graphics.vector.ImageVector
import com.diegonmarcos.superapp.uiicons.UiIcons

/**
 * The icon vocabulary of the fleet's nav items (#1157): a declared icon name to its glyph. Since #1160 the table lives ONCE, in
 * libs:ui-icons ([UiIcons], the catalog every nav bar draws from); this is its view for the sub-page strip. A page ALWAYS draws
 * one: a name the catalog does not hold draws [FALLBACK] (never a letter or nothing), and [has] is what the guard tests hold
 * every declaration to.
 */
public object SecondRowIcons {
    public val FALLBACK: ImageVector get() = UiIcons.FALLBACK
    public fun has(name: String): Boolean = UiIcons.has(name)
    public fun vector(name: String): ImageVector = UiIcons.vector(name)
    public fun names(): Set<String> = UiIcons.names()
}
