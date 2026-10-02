package com.diegonmarcos.superapp.cloud

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the agenda page, Compose since its rows were hand-built Views.
 *
 *   A1 seven rows, today first and labelled as today, each day after it once
 *   A2 every row still says it has no events (CalDAV lands with libs:cal slice D)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalendarAgendaComposeTest : KitPageHarness() {

    @Test
    fun `A1 seven rows, today first`() {
        show(CalendarAgendaFragment.newInstance())
        val days = CalendarAgendaFragment.days()
        assertEquals(7, days.size)
        assertEquals("the days repeat — the calendar is not advancing", 7, days.toSet().size)
        assertNotEquals(days[0], days[1])
        compose.onNodeWithTag(CalendarAgendaFragment.dayTag(0)).assert(hasText("Today · ${days[0]}"))
        for (i in 1 until 7) {
            compose.onNodeWithTag(CalendarAgendaFragment.dayTag(i)).assert(hasText(days[i]))
        }
    }

    @Test
    fun `A2 every row says it has no events`() {
        show(CalendarAgendaFragment.newInstance())
        for (i in 0 until 7) {
            compose.onNodeWithTag(CalendarAgendaFragment.dayTag(i)).assert(hasText("no events"))
        }
    }
}
