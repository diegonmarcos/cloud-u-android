package com.diegonmarcos.superapp.cloud

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the calendar bubble (status strip date tap) in Compose, was a PopupWindow of TextViews.
 *
 *   C1 it opens on the Agenda: seven day rows, today first
 *   C2 the Calendar tab shows this month's grid with today marked
 *   C3 the stopwatch toggles, and the formatting is m:ss.t
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalendarAgendaPopupComposeTest : KitPageHarness() {

    @Before fun fresh() = CalendarAgendaPopup.resetStopwatchForTest()

    @Test
    fun `C1 agenda opens first with today on top`() {
        showKit { CalendarAgendaPopup.CalendarBubble() }
        compose.onNodeWithTag("cal:agenda").assertExists()
        compose.onNodeWithText("Today", substring = true).assertExists()
        compose.onNodeWithTag("cal:month").assertDoesNotExist()
    }

    @Test
    fun `C2 the Calendar tab draws the month with today`() {
        showKit { CalendarAgendaPopup.CalendarBubble() }
        compose.onNodeWithTag("cal:tab:Calendar").performClick()
        compose.onNodeWithTag("cal:month").assertExists()
        compose.onNodeWithTag("cal:today").assertExists()
        compose.onNodeWithTag("cal:agenda").assertDoesNotExist()
    }

    @Test
    fun `C3 the stopwatch starts from the toggle and formats m ss t`() {
        assertEquals("0:00.0", CalendarAgendaPopup.formatStopwatch(0))
        assertEquals("1:05.3", CalendarAgendaPopup.formatStopwatch(65_300))
        showKit { CalendarAgendaPopup.CalendarBubble() }
        compose.onNodeWithText("▶").assertExists()
        compose.onNodeWithTag("cal:stopwatch:toggle").performClick()
        compose.onNodeWithText("■").assertExists()
        assertTrue(true)
    }
}
