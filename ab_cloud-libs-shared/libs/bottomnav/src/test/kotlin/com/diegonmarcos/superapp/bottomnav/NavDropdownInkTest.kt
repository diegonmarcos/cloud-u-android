package com.diegonmarcos.superapp.bottomnav

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page-list dropdown's entries draw in the on-surface token. Indented children once drew a dark ink on the dark dropdown and
 * nearly vanished. The nav-mutation run `dropdown-ink` turns the child ink dark and must turn this red.
 */
class NavDropdownInkTest {
    private val t = NavTokens()

    private fun contrast(ink: Color, surface: Color): Double {
        val a = ink.compositeOver(surface).luminance() + 0.05
        val b = surface.luminance() + 0.05
        return maxOf(a, b) / minOf(a, b)
    }

    @Test
    fun `a child entry uses the on-surface token`() {
        val child = t.dropdownInk(DropdownEntryRole.Child)
        assertEquals(t.dropdownOnSurface.copy(alpha = 1f), child.copy(alpha = 1f))
        assertEquals(t.dropdownChildAlpha, child.alpha, 0.01f)
    }

    @Test
    fun `every entry role is the on-surface ink`() {
        for (role in DropdownEntryRole.values()) {
            assertEquals(role.name, t.dropdownOnSurface.copy(alpha = 1f), t.dropdownInk(role).copy(alpha = 1f))
        }
        assertEquals(t.dropdownOnSurface, t.dropdownInk(DropdownEntryRole.Parent))
        assertEquals(t.dropdownOnSurface, t.dropdownInk(DropdownEntryRole.Selected))
    }

    @Test
    fun `every entry reads at least 4_5 to 1 on the dropdown surface in dark and light`() {
        for (surface in listOf(t.dropdownSurfaceDark, t.dropdownSurfaceLight)) {
            for (role in DropdownEntryRole.values()) {
                val r = contrast(t.dropdownInk(role), surface)
                assertTrue("$role on $surface is $r:1", r >= 4.5)
            }
        }
    }
}
