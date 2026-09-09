package org.fossify.phone.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Cloud Dialer: tester for the Home page's call statistics.
 *
 * Pure JVM, because the two rules that decide whether the numbers on that page
 * are trustworthy cannot be checked on a device without a year of real call
 * history: that a month is a real calendar month in the device timezone, and
 * that a missed call — which the provider stores with a duration of zero — is
 * counted as a call but never averaged as a conversation of length zero.
 */
class CallStatisticsTest {

    private val madrid: ZoneId = ZoneId.of("Europe/Madrid")
    private val auckland: ZoneId = ZoneId.of("Pacific/Auckland")
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun millisAt(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun aggregatorAtMarch2025(zone: ZoneId) = CallStatisticsAggregator(
        zone = zone,
        nowMillis = millisAt(zone, 2025, 3, 15, 12, 0),
    )

    private fun monthOf(statistics: CallStatistics, year: Int, month: Int): MonthlyCallTotals =
        statistics.monthlyTotals.single { it.year == year && it.month == month }

    @Test
    fun windowCoversTwelveWholeCalendarMonths() {
        val aggregator = aggregatorAtMarch2025(madrid)
        assertEquals(millisAt(madrid, 2024, 4, 1, 0, 0), aggregator.windowStartMillis)

        val statistics = aggregator.build()
        assertEquals(CallStatisticsAggregator.DEFAULT_MONTHS_OF_HISTORY, statistics.monthlyTotals.size)
        assertEquals(2024 to 4, statistics.monthlyTotals.first().year to statistics.monthlyTotals.first().month)
        assertEquals(2025 to 3, statistics.monthlyTotals.last().year to statistics.monthlyTotals.last().month)
    }

    @Test
    fun callsEitherSideOfMidnightLandInTheRightMonth() {
        val aggregator = aggregatorAtMarch2025(madrid)
        aggregator.add(millisAt(madrid, 2025, 1, 31, 23, 59), 60, CallDirection.OUTGOING, "Ana Ruiz", "+34600111222")
        aggregator.add(millisAt(madrid, 2025, 2, 1, 0, 1), 120, CallDirection.OUTGOING, "Ana Ruiz", "+34600111222")

        val statistics = aggregator.build()
        assertEquals(1, monthOf(statistics, 2025, 1).callCount)
        assertEquals(60L, monthOf(statistics, 2025, 1).talkSeconds)
        assertEquals(1, monthOf(statistics, 2025, 2).callCount)
        assertEquals(120L, monthOf(statistics, 2025, 2).talkSeconds)
    }

    @Test
    fun monthBoundariesFollowTheDeviceTimezone() {
        // One and the same instant: still January in London, already February
        // in Auckland. A UTC-only bucketing would put both in January.
        val instant = millisAt(utc, 2025, 1, 31, 23, 30)

        val inUtc = aggregatorAtMarch2025(utc)
        inUtc.add(instant, 300, CallDirection.INCOMING, "Ana Ruiz", "+34600111222")
        assertEquals(1, monthOf(inUtc.build(), 2025, 1).callCount)
        assertEquals(0, monthOf(inUtc.build(), 2025, 2).callCount)

        val inAuckland = aggregatorAtMarch2025(auckland)
        inAuckland.add(instant, 300, CallDirection.INCOMING, "Ana Ruiz", "+34600111222")
        assertEquals(0, monthOf(inAuckland.build(), 2025, 1).callCount)
        assertEquals(1, monthOf(inAuckland.build(), 2025, 2).callCount)
    }

    @Test
    fun missedCallsAreCountedButNeverAveraged() {
        val aggregator = aggregatorAtMarch2025(madrid)
        aggregator.add(millisAt(madrid, 2025, 3, 1, 10, 0), 600, CallDirection.INCOMING, "Ana Ruiz", "+34600111222")
        aggregator.add(millisAt(madrid, 2025, 3, 2, 10, 0), 0, CallDirection.MISSED, null, "+34911222333")
        aggregator.add(millisAt(madrid, 2025, 3, 3, 10, 0), 0, CallDirection.MISSED, null, "+34911222333")

        val statistics = aggregator.build()
        assertEquals(3, statistics.mix.total)
        assertEquals(2, statistics.mix.missed)
        // Ten minutes of talking stays ten minutes, not three minutes twenty.
        assertEquals(1, statistics.talkTime.connectedCallCount)
        assertEquals(600L, statistics.talkTime.connectedSeconds)
        assertEquals(600L, statistics.talkTime.averageSeconds)
        assertEquals(2, monthOf(statistics, 2025, 3).missedCount)
        assertEquals(600L, monthOf(statistics, 2025, 3).talkSeconds)
    }

    @Test
    fun oneContactOnSeveralNumbersAddsUpToOneRow() {
        val aggregator = aggregatorAtMarch2025(madrid)
        aggregator.add(millisAt(madrid, 2025, 3, 1, 10, 0), 300, CallDirection.OUTGOING, "Ana Ruiz", "+34600111222")
        aggregator.add(millisAt(madrid, 2025, 3, 2, 10, 0), 200, CallDirection.INCOMING, "Ana Ruiz", "+34911222333")
        aggregator.add(millisAt(madrid, 2025, 3, 3, 10, 0), 100, CallDirection.INCOMING, "Bea Soler", "+34677000111")

        val statistics = aggregator.build()
        val top = statistics.topContactsByTalkTime
        assertEquals(2, top.size)
        assertEquals("Ana Ruiz", top.first().displayName)
        assertEquals(500L, top.first().talkSeconds)
        assertEquals(2, top.first().callCount)
        // The number kept is the one she last called from, so calling back works.
        assertEquals("+34911222333", top.first().phoneNumber)
    }

    @Test
    fun unknownCallersGroupByTheirNormalisedNumber() {
        val aggregator = aggregatorAtMarch2025(madrid)
        aggregator.add(millisAt(madrid, 2025, 3, 1, 10, 0), 30, CallDirection.INCOMING, null, "+34 600 111 222")
        aggregator.add(millisAt(madrid, 2025, 3, 2, 10, 0), 40, CallDirection.INCOMING, "", "+34-600-111-222")

        val statistics = aggregator.build()
        assertEquals(1, statistics.topContactsByCallCount.size)
        assertEquals(2, statistics.topContactsByCallCount.first().callCount)
        assertEquals(70L, statistics.topContactsByCallCount.first().talkSeconds)
    }

    @Test
    fun busiestHourIsReadInTheDeviceTimezone() {
        val aggregator = aggregatorAtMarch2025(madrid)
        repeat(3) { day ->
            val evening = millisAt(madrid, 2025, 3, day + 1, 21, 0)
            aggregator.add(evening, 60, CallDirection.OUTGOING, "Ana Ruiz", "+34600111222")
        }
        val morning = millisAt(madrid, 2025, 3, 4, 9, 0)
        aggregator.add(morning, 60, CallDirection.OUTGOING, "Ana Ruiz", "+34600111222")

        val statistics = aggregator.build()
        assertEquals(21, statistics.busiestHourOfDay)
        assertEquals(3, statistics.callsByHourOfDay[21])
        assertEquals(1, statistics.callsByHourOfDay[9])
    }

    @Test
    fun callsOlderThanTheWindowAreIgnored() {
        val aggregator = aggregatorAtMarch2025(madrid)
        aggregator.add(millisAt(madrid, 2024, 3, 31, 23, 0), 900, CallDirection.OUTGOING, "Ana Ruiz", "+34600111222")

        val statistics = aggregator.build()
        assertEquals(0, statistics.mix.total)
        assertEquals(0L, statistics.talkTime.connectedSeconds)
    }

    @Test
    fun anEmptyLogIsAllZeroesRatherThanAnError() {
        val statistics = aggregatorAtMarch2025(madrid).build()
        assertEquals(0, statistics.mix.total)
        assertEquals(false, statistics.hasAnyCalls)
        assertEquals(0L, statistics.talkTime.averageSeconds)
        assertNull(statistics.busiestHourOfDay)
        assertEquals(emptyList<ContactTotals>(), statistics.topContactsByTalkTime)
        assertEquals(0L, statistics.monthlyTotals.sumOf { it.talkSeconds })
    }

    @Test
    fun talkTimeSplitsIntoHoursAndMinutes() {
        assertEquals(0L to 0L, CallStatisticsAggregator.splitToHoursAndMinutes(59))
        assertEquals(0L to 1L, CallStatisticsAggregator.splitToHoursAndMinutes(60))
        assertEquals(2L to 5L, CallStatisticsAggregator.splitToHoursAndMinutes(7500))
    }
}
