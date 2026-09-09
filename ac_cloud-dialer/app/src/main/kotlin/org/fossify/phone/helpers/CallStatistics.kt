package org.fossify.phone.helpers

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** No talk time at all: what a missed call, and an empty log, both add up to. */
private const val NO_TALK_TIME = 0L

/**
 * Cloud Dialer: the direction of a call, as the Home statistics count it.
 *
 * These are deliberately NOT android.provider.CallLog.Calls constants. Keeping
 * the aggregator free of every Android type is the only reason
 * CallStatisticsTest can run on a plain JVM, and the month-boundary and
 * average-talk-time rules below are precisely the ones that cannot be proven
 * on a device without a year of real call history. CallStatsHelper maps the
 * provider's TYPE column onto these values at the cursor boundary.
 */
object CallDirection {
    const val INCOMING = 1
    const val OUTGOING = 2
    const val MISSED = 3

    /**
     * Voicemail, rejected and blocked calls. Counted in the total, but not
     * attributed to a direction: "rejected" is the owner declining a call he
     * did see, which is not the same event as a missed call, and folding the
     * two together would inflate the missed number the Home page calls out.
     */
    const val OTHER = 0
}

/** How many calls of each direction happened inside the window. */
data class CallMix(
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    val other: Int,
) {
    val total: Int get() = incoming + outgoing + missed + other
}

/**
 * Talk time over calls that actually connected. A missed call is stored by the
 * provider with a duration of zero seconds; counting those as calls of length
 * zero would drag the average towards nothing and make the number meaningless,
 * so only calls with a duration above zero are averaged here.
 */
data class TalkTime(
    val connectedCallCount: Int,
    val connectedSeconds: Long,
) {
    val averageSeconds: Long
        get() = if (connectedCallCount == 0) NO_TALK_TIME else connectedSeconds / connectedCallCount
}

/**
 * One real calendar month in the device timezone — never a rolling window of
 * thirty days, which would silently mix February into January.
 */
data class MonthlyCallTotals(
    val year: Int,
    val month: Int,
    val talkSeconds: Long,
    val callCount: Int,
    val missedCount: Int,
)

/** Everything the call log knows about one conversation partner. */
data class ContactTotals(
    val contactKey: String,
    val displayName: String,
    val phoneNumber: String,
    val talkSeconds: Long,
    val callCount: Int,
)

/**
 * The complete picture the Home page draws. Every field is derivable from the
 * columns android.provider.CallLog.Calls actually stores — nothing here is
 * estimated, and nothing that the provider does not record has a placeholder.
 */
data class CallStatistics(
    val mix: CallMix,
    val talkTime: TalkTime,
    val monthlyTotals: List<MonthlyCallTotals>,
    val topContactsByTalkTime: List<ContactTotals>,
    val topContactsByCallCount: List<ContactTotals>,
    val callsByHourOfDay: List<Int>,
) {
    val hasAnyCalls: Boolean get() = mix.total > 0

    /** The month the device is in right now, which is always the last entry. */
    val currentMonth: MonthlyCallTotals? get() = monthlyTotals.lastOrNull()

    /** The month before the current one, for the "up or down" comparison. */
    val previousMonth: MonthlyCallTotals?
        get() = monthlyTotals.getOrNull(monthlyTotals.size - 2)

    /** The hour of day the owner is most often on the phone, or null when he never is. */
    val busiestHourOfDay: Int?
        get() {
            val busiest = callsByHourOfDay.withIndex().maxByOrNull { it.value } ?: return null
            return if (busiest.value == 0) null else busiest.index
        }
}

/**
 * Folds call log rows into [CallStatistics] one row at a time.
 *
 * A fold rather than a list transformation on purpose: the call log on a phone
 * that has been in use for years holds tens of thousands of rows, and reading
 * them into a list of objects first would cost memory proportional to the log.
 * Here the cost is proportional to the number of distinct conversation
 * partners and to the length of the window instead, so CallStatsHelper can
 * stream a cursor straight into [add] and never hold more than one row.
 */
class CallStatisticsAggregator(
    private val zone: ZoneId,
    nowMillis: Long,
    private val monthsOfHistory: Int = DEFAULT_MONTHS_OF_HISTORY,
    private val topContactCount: Int = DEFAULT_TOP_CONTACT_COUNT,
) {
    companion object {
        const val DEFAULT_MONTHS_OF_HISTORY = 12
        const val DEFAULT_TOP_CONTACT_COUNT = 5
        const val HOURS_PER_DAY = 24
        const val SECONDS_PER_MINUTE = 60L
        const val MINUTES_PER_HOUR = 60L

        /** A call the provider recorded as never having connected. */
        private const val NO_DURATION = 0L

        /** Months are counted inclusively, so a twelve month window reaches back eleven. */
        private const val ONE_MONTH = 1L

        /**
         * The identity two calls share when they belong to the same person.
         *
         * The call log itself caches the contact's display name at the moment
         * of the call, and that cached name is the only cross-number identity
         * the provider carries — it is what makes a work number and a mobile
         * number of the same contact add up to one row on the Home page. When
         * the name is absent (the number was not in contacts when it rang) the
         * key falls back to the number reduced to its dialable characters, so
         * the same unknown caller still groups with itself.
         */
        fun contactKey(cachedName: String?, phoneNumber: String?): String {
            val name = cachedName?.trim().orEmpty()
            val number = normalizeNumber(phoneNumber)
            return if (name.isNotEmpty() && name != phoneNumber?.trim()) {
                "name:${name.lowercase()}"
            } else {
                "number:$number"
            }
        }

        /**
         * Reduces a number to the characters that identify it: its digits, plus
         * a leading plus sign when the number was stored in international form.
         *
         * ponytail: a national and an international spelling of the same number
         * stay two keys. Upgrade path is comparing the trailing digits the way
         * RecentsHelper does for name lookup, which needs a region hint this
         * pure aggregator deliberately does not have.
         */
        fun normalizeNumber(phoneNumber: String?): String {
            val raw = phoneNumber?.trim().orEmpty()
            val digits = raw.filter { it.isDigit() }
            return if (raw.startsWith("+")) "+$digits" else digits
        }

        /** Splits a duration into whole hours and the remaining whole minutes. */
        fun splitToHoursAndMinutes(seconds: Long): Pair<Long, Long> {
            val totalMinutes = seconds / SECONDS_PER_MINUTE
            return totalMinutes / MINUTES_PER_HOUR to totalMinutes % MINUTES_PER_HOUR
        }
    }

    private class MutableContactTotals(var displayName: String, var phoneNumber: String) {
        var talkSeconds = 0L
        var callCount = 0
        var lastSeenMillis = 0L
    }

    private class MutableMonthTotals {
        var talkSeconds = 0L
        var callCount = 0
        var missedCount = 0
    }

    /** The first month the window covers, in the device timezone. */
    private val firstMonth: YearMonth =
        YearMonth.from(Instant.ofEpochMilli(nowMillis).atZone(zone)).minusMonths(monthsOfHistory - ONE_MONTH)

    /**
     * The instant the window opens: midnight on the first day of [firstMonth]
     * in the device timezone. CallStatsHelper hands this to the provider as a
     * selection so rows older than the window are never read at all.
     */
    val windowStartMillis: Long = firstMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private val monthTotals = LinkedHashMap<YearMonth, MutableMonthTotals>()
    private val contactTotals = HashMap<String, MutableContactTotals>()
    private val hourCounts = IntArray(HOURS_PER_DAY)

    private var incoming = 0
    private var outgoing = 0
    private var missed = 0
    private var other = 0
    private var connectedCallCount = 0
    private var connectedSeconds = 0L

    init {
        // Pre-create every month in the window so a month with no calls at all
        // still draws as a gap in the trend rather than disappearing from it.
        repeat(monthsOfHistory) { offset ->
            monthTotals[firstMonth.plusMonths(offset.toLong())] = MutableMonthTotals()
        }
    }

    /**
     * Folds in one call log row. Rows older than the window are ignored, so the
     * aggregator is correct even if the caller widens the query.
     */
    fun add(
        startTimestampMillis: Long,
        durationSeconds: Long,
        direction: Int,
        cachedName: String?,
        phoneNumber: String?,
    ) {
        if (startTimestampMillis < windowStartMillis) {
            return
        }

        val zonedStart = Instant.ofEpochMilli(startTimestampMillis).atZone(zone)
        val month = monthTotals[YearMonth.from(zonedStart)] ?: return
        hourCounts[zonedStart.hour]++

        when (direction) {
            CallDirection.INCOMING -> incoming++
            CallDirection.OUTGOING -> outgoing++
            CallDirection.MISSED -> missed++
            else -> other++
        }

        month.callCount++
        if (direction == CallDirection.MISSED) {
            month.missedCount++
        }

        if (durationSeconds > NO_DURATION) {
            connectedCallCount++
            connectedSeconds += durationSeconds
            month.talkSeconds += durationSeconds
        }

        accumulateContact(startTimestampMillis, durationSeconds, cachedName, phoneNumber)
    }

    private fun accumulateContact(
        startTimestampMillis: Long,
        durationSeconds: Long,
        cachedName: String?,
        phoneNumber: String?,
    ) {
        val key = contactKey(cachedName, phoneNumber)
        val displayName = cachedName?.trim().orEmpty().ifEmpty { phoneNumber?.trim().orEmpty() }
        val totals = contactTotals.getOrPut(key) { MutableContactTotals(displayName, phoneNumber?.trim().orEmpty()) }
        totals.callCount++
        totals.talkSeconds += maxOf(durationSeconds, NO_DURATION)

        // Keep the most recent spelling of the name and number, so a contact
        // renamed last week is not listed under the name he had last year.
        if (startTimestampMillis >= totals.lastSeenMillis) {
            totals.lastSeenMillis = startTimestampMillis
            if (displayName.isNotEmpty()) {
                totals.displayName = displayName
            }
            if (!phoneNumber.isNullOrBlank()) {
                totals.phoneNumber = phoneNumber.trim()
            }
        }
    }

    fun build(): CallStatistics {
        val contacts = contactTotals.map { (key, totals) ->
            ContactTotals(
                contactKey = key,
                displayName = totals.displayName,
                phoneNumber = totals.phoneNumber,
                talkSeconds = totals.talkSeconds,
                callCount = totals.callCount,
            )
        }

        return CallStatistics(
            mix = CallMix(incoming = incoming, outgoing = outgoing, missed = missed, other = other),
            talkTime = TalkTime(connectedCallCount = connectedCallCount, connectedSeconds = connectedSeconds),
            monthlyTotals = monthTotals.map { (month, totals) ->
                MonthlyCallTotals(
                    year = month.year,
                    month = month.monthValue,
                    talkSeconds = totals.talkSeconds,
                    callCount = totals.callCount,
                    missedCount = totals.missedCount,
                )
            },
            topContactsByTalkTime = contacts
                .filter { it.talkSeconds > NO_DURATION }
                .sortedWith(compareByDescending<ContactTotals> { it.talkSeconds }.thenBy { it.displayName })
                .take(topContactCount),
            topContactsByCallCount = contacts
                .sortedWith(compareByDescending<ContactTotals> { it.callCount }.thenBy { it.displayName })
                .take(topContactCount),
            callsByHourOfDay = hourCounts.toList(),
        )
    }
}
