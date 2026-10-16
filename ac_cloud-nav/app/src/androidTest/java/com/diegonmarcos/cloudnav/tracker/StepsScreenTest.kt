package com.diegonmarcos.cloudnav.tracker

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPartTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Timeline > Steps under the fleet palette: source label, totals, period switch + chart, day cards (places, events). */
@RunWith(AndroidJUnit4::class)
class StepsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 10, 14)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun ui(status: SourceStatus = SourceStatus.READY, source: StepsSource = StepsSource.HEALTH_CONNECT) =
        StepsModel.build(
            source, status, mapOf(day to 3_000L, day.minusDays(1) to 8_000L),
            listOf(StepSlice(at(9), at(9, 30), 3_000)),
            listOf(PlaceFix(at(8), 47.0, 8.0, false, 10.0), PlaceFix(at(13, 5), 47.1, 8.1, true, 120.0)),
            zone, at(22), 0.7,
        ) { "Place@%.1f".format(it.lat) }

    private var period: Period? = null
    private var granted = false

    private fun show(u: StepsUi, p: Period = Period.DAY) {
        period = null; granted = false
        rule.setContent {
            CloudKitTheme(TrackerInk.palette()) { StepsScreen(u, p, { period = it }, onGrant = { granted = true }, zone = zone) }
        }
    }

    private fun scrollTo(tag: String) = rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))

    @Test fun healthConnectSourceIsLabelled() {
        show(ui())
        rule.onNodeWithTag(StepsTags.SOURCE).assertIsDisplayed()
        rule.onNodeWithText("Source: Health Connect").assertIsDisplayed()
    }

    @Test fun fallbackIsLabelledAndOffersThePermission() {
        show(ui(SourceStatus.NEEDS_PERMISSION, StepsSource.DEVICE))
        rule.onNodeWithText("on-device fallback", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(StepsTags.GRANT).performClick()
        assertEquals(true, granted)
    }

    @Test fun showsDayWeekMonthTotals() {
        show(ui())
        rule.onNodeWithText("3,000").assertIsDisplayed()                 // today
        rule.onAllNodesWithText("11,000").assertCountEquals(2)           // this week (Tue 13 + Wed 14) and this month
        rule.onNodeWithText("Today").assertIsDisplayed()
        rule.onNodeWithText("This week").assertIsDisplayed()
        rule.onNodeWithText("This month").assertIsDisplayed()
    }

    @Test fun periodSwitchReports() {
        show(ui())
        rule.onNodeWithTag(KitPartTags.segment(StepsTags.PERIOD, "WEEK")).performClick()
        assertEquals(Period.WEEK, period)
        rule.onNodeWithTag(KitPartTags.segment(StepsTags.PERIOD, "MONTH")).performClick()
        assertEquals(Period.MONTH, period)
    }

    private fun chartHas(p: Period, n: Int) {
        show(ui(), p)
        rule.onNodeWithTag(StepsTags.CHART).assertIsDisplayed()
        rule.onNodeWithTag(StepsTags.bar(n - 1)).assertExists()
        rule.onNodeWithTag(StepsTags.bar(n)).assertDoesNotExist()
    }
    @Test fun dayChartHasFourteenBars() = chartHas(Period.DAY, 14)
    @Test fun weekChartHasTwelveBars() = chartHas(Period.WEEK, 12)
    @Test fun monthChartHasTwelveBars() = chartHas(Period.MONTH, 12)

    @Test fun dayCardShowsMorningNoonNightAndMarksPredicted() {
        show(ui())
        scrollTo(StepsTags.day(day))
        rule.onNodeWithTag(StepsTags.place(day, DayPart.MORNING)).assertTextContains("Place@47.0", substring = true)
        rule.onNodeWithTag(StepsTags.place(day, DayPart.NOON)).assertTextContains("predicted", substring = true)
        rule.onNodeWithTag(StepsTags.place(day, DayPart.NIGHT)).assertTextContains("no fix", substring = true)
    }

    @Test fun dayCardListsStopAndMovingEvents() {
        show(ui())
        scrollTo(StepsTags.day(day))
        rule.onNodeWithTag(StepsTags.seg(day, 0)).assertTextContains("Stop", substring = true)
        rule.onNodeWithTag(StepsTags.seg(day, 1)).assertTextContains("Moving", substring = true)
        rule.onNodeWithTag(StepsTags.seg(day, 1)).assertTextContains("3,000 steps", substring = true)
        rule.onNodeWithTag(StepsTags.seg(day, 1)).assertTextContains("2.1 km", substring = true)
        rule.onNodeWithTag(StepsTags.seg(day, 1)).assertTextContains("30 min", substring = true)
    }
}
