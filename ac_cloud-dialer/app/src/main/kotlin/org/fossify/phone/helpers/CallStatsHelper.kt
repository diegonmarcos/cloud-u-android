package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CallLog.Calls
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.ensureBackgroundThread
import java.time.ZoneId

/**
 * Cloud Dialer: reads android.provider.CallLog.Calls for the Home page's
 * statistics.
 *
 * This is the same provider, the same content URI and the same
 * PERMISSION_READ_CALL_LOG contract RecentsHelper already works under — the
 * Home page opens no second path to the call log, it asks the one provider a
 * different question. RecentsHelper answers "which calls happened, with names
 * and photos and SIM colours", a per-row question it answers a page at a time.
 * This asks "what do a year of calls add up to", and the two cannot share a
 * query: the recents query is deliberately LIMITed and joins against contacts
 * row by row, both of which are wrong for a total.
 *
 * What the provider will and will not do:
 *  - It will narrow. Only the five columns a total needs are projected, and
 *    the DATE selection means rows older than the window are never read.
 *  - It will NOT aggregate. CallLogProvider validates the projection against a
 *    fixed column map, so COUNT/SUM/GROUP BY cannot be pushed into the query
 *    the way they could against a database we owned. The rows are therefore
 *    folded here instead, one at a time and never held, by
 *    CallStatisticsAggregator.
 */
class CallStatsHelper(private val context: Context) {
    companion object {
        private val PROJECTION = arrayOf(
            Calls.DATE,
            Calls.DURATION,
            Calls.TYPE,
            Calls.NUMBER,
            Calls.CACHED_NAME,
        )
    }

    /**
     * Computes the statistics off the main thread and hands them back on the
     * background thread the query ran on.
     *
     * A denied READ_CALL_LOG permission yields null rather than an error: the
     * Home page has an honest thing to say in that case, and nothing to blame
     * the owner for. A granted permission over an empty log yields statistics
     * whose every total is zero, which the page says just as plainly.
     */
    fun getCallStatistics(callback: (CallStatistics?) -> Unit) {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(null)
            return
        }

        ensureBackgroundThread {
            callback(aggregateCallLog())
        }
    }

    @SuppressLint("MissingPermission")
    private fun aggregateCallLog(): CallStatistics {
        // The device timezone, read now rather than cached, so a month boundary
        // is the one the owner's own calendar shows even after he flies.
        val aggregator = CallStatisticsAggregator(
            zone = ZoneId.systemDefault(),
            nowMillis = System.currentTimeMillis(),
        )

        context.contentResolver.query(
            Calls.CONTENT_URI,
            PROJECTION,
            "${Calls.DATE} >= ?",
            arrayOf(aggregator.windowStartMillis.toString()),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return@use
            }

            // Column indices resolved once. Looking them up per row costs a
            // string comparison per column per row, which on a log of tens of
            // thousands of rows is the difference between snappy and janky.
            val dateIndex = cursor.getColumnIndexOrThrow(Calls.DATE)
            val durationIndex = cursor.getColumnIndexOrThrow(Calls.DURATION)
            val typeIndex = cursor.getColumnIndexOrThrow(Calls.TYPE)
            val numberIndex = cursor.getColumnIndexOrThrow(Calls.NUMBER)
            val cachedNameIndex = cursor.getColumnIndexOrThrow(Calls.CACHED_NAME)

            do {
                aggregator.add(
                    startTimestampMillis = cursor.getLong(dateIndex),
                    durationSeconds = cursor.getLong(durationIndex),
                    direction = toCallDirection(cursor.getInt(typeIndex)),
                    cachedName = cursor.getString(cachedNameIndex),
                    phoneNumber = cursor.getString(numberIndex),
                )
            } while (cursor.moveToNext())
        }

        return aggregator.build()
    }

    private fun toCallDirection(type: Int) = when (type) {
        Calls.INCOMING_TYPE -> CallDirection.INCOMING
        Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
        Calls.MISSED_TYPE -> CallDirection.MISSED
        else -> CallDirection.OTHER
    }
}
