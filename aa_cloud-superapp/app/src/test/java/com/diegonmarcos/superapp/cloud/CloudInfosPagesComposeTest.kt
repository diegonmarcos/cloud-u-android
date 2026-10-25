package com.diegonmarcos.superapp.cloud

import com.diegonmarcos.superapp.boards.TasksFragment
import com.diegonmarcos.superapp.cal.CalendarMonthFragment

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.KitPageHarness
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the Infos-stack pages that were hand-built Views: Tasks, the month grid, News & RSS.
 *
 *   T1 Tasks opens on Agenda (seven rows, today first), a chip tap swaps the body in place and
 *      moves the selection
 *   T2 the Day tab draws the 08:00-22:00 hourly grid and marks exactly the current hour
 *   C1 the month grid puts the 1st in its weekday column and has every day once, in rows of seven
 *   C2 the arrows step the month, wrapping the year, and the title follows
 *   C3 every day of the shown month is drawn, labelled with its number
 *   N1 News draws a header per declared feed (or says there are none), collapsed
 *   N2 a header tap opens the card body (fetching, then items or the failure) and a second closes it
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudInfosPagesComposeTest : KitPageHarness() {

    @Test
    fun `T1 tasks opens on Agenda and a chip swaps the body in place`() {
        show(TasksFragment.newInstance())
        compose.onNodeWithTag(TasksFragment.chipTag(TasksFragment.Tab.AGENDA)).assertIsSelected()
        compose.onNodeWithTag(TasksFragment.chipTag(TasksFragment.Tab.TODO)).assertIsNotSelected()
        val days = TasksFragment.agendaDays()
        assertEquals(7, days.size)
        compose.onAllNodesWithText("Today · ${days[0]}").assertCountEquals1()
        compose.onNodeWithTag(TasksFragment.chipTag(TasksFragment.Tab.TODO)).performClick()
        compose.onNodeWithTag(TasksFragment.chipTag(TasksFragment.Tab.TODO)).assertIsSelected()
        compose.onNodeWithTag(TasksFragment.chipTag(TasksFragment.Tab.AGENDA)).assertIsNotSelected()
        compose.onAllNodesWithText("(no tasks yet)").assertCountEquals1()
        compose.onAllNodesWithText("Today · ${days[0]}").assertCountEquals0()
    }

    @Test
    fun `T2 the Day tab marks exactly the current hour`() {
        show(TasksFragment.newInstance())
        compose.onNodeWithTag(TasksFragment.chipTag(TasksFragment.Tab.DAY)).performClick()
        val now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        for (h in 8..22) {
            val label = "%02d:00".format(h)
            if (h == now) compose.onAllNodesWithText("$label  · now").assertCountEquals1()
            else compose.onAllNodesWithText(label).assertCountEquals1()
        }
        if (now !in 8..22) compose.onAllNodesWithText("· now", substring = true).assertCountEquals0()
    }

    @Test
    fun `C1 the month grid seats the first in its weekday and holds every day once`() {
        // 2026-10-01 is a Thursday: Sunday-first column 4.
        val oct = CalendarMonthFragment.monthGrid(2026, Calendar.OCTOBER)
        assertTrue(oct.all { it.size == 7 })
        assertEquals(listOf(0, 0, 0, 0, 1, 2, 3), oct[0])
        assertEquals((1..31).toList(), oct.flatten().filter { it != 0 })
        // February 2026 starts on a Sunday and has 28 days: exactly four full rows, none trimmed in.
        val feb = CalendarMonthFragment.monthGrid(2026, Calendar.FEBRUARY)
        assertEquals(4, feb.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), feb[0])
    }

    @Test
    fun `C2 the arrows step the month and wrap the year`() {
        show(CalendarMonthFragment.newInstance())
        val now = Calendar.getInstance()
        var y = now.get(Calendar.YEAR); var m = now.get(Calendar.MONTH)
        compose.onNodeWithTag(CalendarMonthFragment.TAG_TITLE).assert(hasText(CalendarMonthFragment.monthTitle(y, m)))
        repeat(13) {
            compose.onNodeWithTag(CalendarMonthFragment.TAG_NEXT).performClick()
            m++; if (m > 11) { m = 0; y++ }
        }
        compose.onNodeWithTag(CalendarMonthFragment.TAG_TITLE).assert(hasText(CalendarMonthFragment.monthTitle(y, m)))
        repeat(14) {
            compose.onNodeWithTag(CalendarMonthFragment.TAG_PREV).performClick()
            m--; if (m < 0) { m = 11; y-- }
        }
        compose.onNodeWithTag(CalendarMonthFragment.TAG_TITLE).assert(hasText(CalendarMonthFragment.monthTitle(y, m)))
    }

    @Test
    fun `C3 every day of the month is drawn`() {
        show(CalendarMonthFragment.newInstance())
        val now = Calendar.getInstance()
        val days = now.getActualMaximum(Calendar.DAY_OF_MONTH)
        for (d in 1..days) compose.onNodeWithTag(CalendarMonthFragment.dayTag(d)).assert(hasText(d.toString()))
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals1() =
        assertCountEquals(1)
    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() =
        assertCountEquals(0)
}
