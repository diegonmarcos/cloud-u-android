package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.ui.Declarations
import com.diegonmarcos.cloudagenda.ui.Ids
import com.diegonmarcos.superapp.bottomnav.TabSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The declared navigation (build.json::ui, baked into BuildConfig) against the ids Kotlin dispatches on. */
class NavDeclTest {
    private val nav = Declarations.nav

    @Test fun `the declaration is valid and opens on Tasks`() {
        assertEquals(emptyList<String>(), nav.problems())
        assertEquals(listOf("tasks", "calendar"), nav.bottomNav)
        assertEquals(Ids.TASKS, nav.default()?.id)
    }

    @Test fun `every id Kotlin dispatches on is declared and every declared section is dispatched`() {
        assertEquals(setOf(Ids.TASKS, Ids.CALENDAR, Ids.SETTINGS), nav.sections.map { it.id }.toSet())
        assertEquals(listOf(Ids.PAGE_EVENTS, Ids.PAGE_CALENDAR, Ids.PAGE_INVITES), nav.section(Ids.CALENDAR)!!.pages.map { it.id })
        assertEquals(listOf(Ids.PAGE_APPEARANCE, Ids.PAGE_AGENT, Ids.PAGE_INBOX, Ids.PAGE_FEEDS), nav.section(Ids.SETTINGS)!!.pages.map { it.id })
        assertEquals(listOf(Ids.PAGE_TASKS), nav.section(Ids.TASKS)!!.pages.map { it.id })
    }

    @Test fun `Settings is declared but not on the island`() {
        assertTrue(Ids.SETTINGS !in nav.bottomNav)
        assertTrue(nav.bottomSections().none { it.id == Ids.SETTINGS })
    }

    @Test fun `strips follow the app's light and dark mode`() {
        assertEquals(TabSurface.Dark, nav.stripSurface(Ids.CALENDAR, darkTheme = true))
        assertEquals(TabSurface.Light, nav.stripSurface(Ids.CALENDAR, darkTheme = false))
        assertEquals(TabSurface.Light, nav.stripSurface(Ids.SETTINGS, darkTheme = false, pageId = Ids.PAGE_AGENT))
    }
}
