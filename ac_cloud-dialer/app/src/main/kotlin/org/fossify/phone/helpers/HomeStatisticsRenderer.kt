package org.fossify.phone.helpers

import android.content.res.ColorStateList
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.formatSecondsToShortTimeString
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.FragmentHomeBinding
import org.fossify.phone.databinding.ItemHomeChartBarBinding
import org.fossify.phone.databinding.ItemHomeTopContactBinding
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Cloud Dialer: paints a [CallStatistics] onto the Home page.
 *
 * Kept apart from HomeFragment because the fragment's job is asking the call
 * log a question and this one's is answering it on screen — and because the
 * answer has to be repainted whenever the owner changes his accent colour,
 * which is a redraw and not a reload.
 */
class HomeStatisticsRenderer(
    private val activity: SimpleActivity,
    private val binding: FragmentHomeBinding,
    private val onContactClick: (ContactTotals) -> Unit,
) {
    companion object {
        /** Hours the time-of-day chart labels, so the strip reads without 24 numbers. */
        private const val LABELLED_HOUR_INTERVAL = 6

        private const val NO_TALK_TIME = 0L
        private const val NO_CALLS = 0
    }

    fun render(statistics: CallStatistics, accentColor: Int) {
        renderTotals(statistics)
        renderMix(statistics)
        renderCharts(statistics, accentColor)
        renderTopContacts(statistics)
    }

    private fun renderTotals(statistics: CallStatistics) {
        val months = statistics.monthlyTotals
        val currentMonth = statistics.currentMonth

        binding.homeMissedCount.text = (currentMonth?.missedCount ?: NO_CALLS).toString()
        binding.homeMissedInWindow.text = activity.getString(
            R.string.home_missed_in_window, months.sumOf { it.missedCount }, months.size
        )

        binding.homeTalkTimeThisMonth.text = formatTalkTime(currentMonth?.talkSeconds ?: NO_TALK_TIME)
        binding.homeTalkTimeTrend.text = trendText(currentMonth, statistics.previousMonth)
        binding.homeTalkTimeTotal.text = activity.getString(
            R.string.home_total_in_window, formatTalkTime(months.sumOf { it.talkSeconds }), months.size
        )
        binding.homeWindowCaption.text = activity.getString(R.string.home_window_caption, months.size)
    }

    private fun renderMix(statistics: CallStatistics) {
        binding.homeIncomingCount.text = statistics.mix.incoming.toString()
        binding.homeOutgoingCount.text = statistics.mix.outgoing.toString()
        binding.homeMissedMixCount.text = statistics.mix.missed.toString()

        // Rejected, blocked and voicemail calls are real calls with no honest
        // direction, so they are named rather than quietly folded into a column.
        binding.homeOtherCalls.beVisibleIf(statistics.mix.other > NO_CALLS)
        binding.homeOtherCalls.text = activity.getString(R.string.home_other_calls, statistics.mix.other)

        binding.homeAverageCall.text =
            activity.formatSecondsToShortTimeString(statistics.talkTime.averageSeconds.toInt())

        val busiestHour = statistics.busiestHourOfDay
        binding.homeBusiestHour.beGoneIf(busiestHour == null)
        if (busiestHour != null) {
            val time = LocalTime.of(busiestHour, 0)
                .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            binding.homeBusiestHour.text = activity.getString(R.string.home_busiest_hour, time)
        }
    }

    private fun renderCharts(statistics: CallStatistics, accentColor: Int) {
        val months = statistics.monthlyTotals
        binding.homeMonthBars.removeAllViews()
        val busiestMonth = months.maxOfOrNull { it.talkSeconds } ?: NO_TALK_TIME
        months.forEach { month ->
            val initial = Month.of(month.month).getDisplayName(TextStyle.NARROW, Locale.getDefault())
            addChartBar(binding.homeMonthBars, month.talkSeconds, busiestMonth, initial, accentColor)
        }

        val hours = statistics.callsByHourOfDay
        binding.homeHourBars.removeAllViews()
        val busiestHourCount = hours.maxOrNull() ?: NO_CALLS
        hours.forEachIndexed { hour, count ->
            val label = if (hour % LABELLED_HOUR_INTERVAL == 0) hour.toString() else ""
            addChartBar(binding.homeHourBars, count.toLong(), busiestHourCount.toLong(), label, accentColor)
        }
    }

    private fun renderTopContacts(statistics: CallStatistics) {
        val byTalkTime = statistics.topContactsByTalkTime
        val byCallCount = statistics.topContactsByCallCount
        binding.homeTopContactsCard.beGoneIf(byTalkTime.isEmpty() && byCallCount.isEmpty())

        binding.homeTopByTalkTime.removeAllViews()
        byTalkTime.forEach { contact ->
            val calls = activity.resources.getQuantityString(R.plurals.home_calls, contact.callCount, contact.callCount)
            contactRow(binding.homeTopByTalkTime, contact, formatTalkTime(contact.talkSeconds), calls)
        }

        binding.homeTopByCallCount.removeAllViews()
        byCallCount.forEach { contact ->
            val calls = activity.resources.getQuantityString(R.plurals.home_calls, contact.callCount, contact.callCount)
            contactRow(binding.homeTopByCallCount, contact, calls, formatTalkTime(contact.talkSeconds))
        }
    }

    private fun contactRow(holder: LinearLayout, contact: ContactTotals, value: String, detail: String) {
        val row = ItemHomeTopContactBinding.inflate(activity.layoutInflater, holder, true)
        row.topContactName.text = contact.displayName.ifEmpty { activity.getString(R.string.unknown) }
        row.topContactValue.text = value
        row.topContactDetail.text = activity.getString(
            R.string.home_contact_detail, contact.phoneNumber.formatPhoneNumber(), detail
        )

        // A hidden number has nothing to call back, so it is shown but not offered.
        if (contact.phoneNumber.isEmpty()) {
            row.topContactHolder.isClickable = false
        } else {
            row.topContactHolder.setOnClickListener { onContactClick(contact) }
        }
    }

    /**
     * Adds one bar to a chart, its height being [value] as a share of the
     * largest bar in the same chart. An empty chart draws as a row of floors
     * rather than as nothing, so the axis stays legible.
     */
    private fun addChartBar(holder: LinearLayout, value: Long, maximum: Long, label: String, accentColor: Int) {
        val bar = ItemHomeChartBarBinding.inflate(activity.layoutInflater, holder, true)
        bar.chartBarLabel.text = label

        val fullHeight = activity.resources.getDimensionPixelSize(R.dimen.home_chart_height)
        val floorHeight = activity.resources.getDimensionPixelSize(R.dimen.home_bar_minimum_height)
        val barHeight = if (maximum <= NO_TALK_TIME) {
            floorHeight
        } else {
            (fullHeight * value / maximum).toInt().coerceAtLeast(floorHeight)
        }

        bar.chartBarFill.updateLayoutParams { height = barHeight }
        bar.chartBarFill.backgroundTintList = ColorStateList.valueOf(accentColor)
    }

    private fun formatTalkTime(seconds: Long): String {
        val (hours, minutes) = CallStatisticsAggregator.splitToHoursAndMinutes(seconds)
        return if (hours > NO_TALK_TIME) {
            activity.getString(R.string.home_duration_hours_minutes, hours.toInt(), minutes.toInt())
        } else {
            activity.getString(R.string.home_duration_minutes, minutes.toInt())
        }
    }

    /**
     * How this month compares with the one before it, in the same units the
     * headline is in. A first month with nothing behind it says so rather than
     * claiming an infinite rise.
     */
    private fun trendText(currentMonth: MonthlyCallTotals?, previousMonth: MonthlyCallTotals?): String {
        if (currentMonth == null || previousMonth == null || previousMonth.talkSeconds == NO_TALK_TIME) {
            return activity.getString(R.string.home_trend_no_previous)
        }

        val difference = currentMonth.talkSeconds - previousMonth.talkSeconds
        return when {
            difference > NO_TALK_TIME -> activity.getString(R.string.home_trend_more, formatTalkTime(difference))
            difference < NO_TALK_TIME -> activity.getString(R.string.home_trend_less, formatTalkTime(-difference))
            else -> activity.getString(R.string.home_trend_same)
        }
    }
}
