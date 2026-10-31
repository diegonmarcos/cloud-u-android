package com.diegonmarcos.superapp.navbartop1

import com.diegonmarcos.superapp.bottomnav.DropdownEntryRole
import com.diegonmarcos.superapp.bottomnav.NavTokens
import com.diegonmarcos.superapp.themebg.ThemeBackground
import org.junit.Assert.assertEquals
import org.junit.Test

/** The title dropdown paints its entries from NavTokens' on-surface ink, on the island surface the tokens' contrast check uses. */
class DropdownEntryInkTest {
    private fun e(depth: Int, selected: Boolean = false) = HierarchyEntry("p$depth", "P", depth, "s", selected)

    @Test fun `an indented entry is a child, the open page is selected, the rest are parents`() {
        assertEquals(DropdownEntryRole.Child, dropdownEntryRole(e(1), currentDepth = 0))
        assertEquals(DropdownEntryRole.Selected, dropdownEntryRole(e(1, selected = true), currentDepth = 0))
        assertEquals(DropdownEntryRole.Parent, dropdownEntryRole(e(0), currentDepth = 0))
        assertEquals(DropdownEntryRole.Parent, dropdownEntryRole(e(1), currentDepth = 1))
    }

    @Test fun `the tokens' dropdown surfaces are the islands the dropdown is drawn on`() {
        val t = NavTokens()
        assertEquals(ThemeBackground.colors(dark = true).island, t.dropdownSurfaceDark)
        assertEquals(ThemeBackground.colors(dark = false).island, t.dropdownSurfaceLight)
        assertEquals(ThemeBackground.colors(dark = true).onIsland, t.dropdownOnSurface)
    }
}
