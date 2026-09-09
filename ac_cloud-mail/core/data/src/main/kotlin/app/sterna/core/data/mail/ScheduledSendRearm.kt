package app.sterna.core.data.mail

import app.sterna.core.data.db.ScheduledSendEntity

data class ScheduledSendJob(val id: Long, val delayMillis: Long)

/** How long from [nowMillis] until [sendAtMillis] — never negative, so an overdue row goes at once. */
fun scheduledSendDelayMillis(sendAtMillis: Long, nowMillis: Long): Long =
    (sendAtMillis - nowMillis).coerceAtLeast(0)

/** The work a startup has to book for the [rows] of `scheduled_sends`. Every row gets one, overdue
 *  or not: the row is the only copy of the message and nothing else comes asking about it, so an
 *  overdue row goes out with its delay coerced to zero — late rather than never. */
fun scheduledSendJobs(rows: List<ScheduledSendEntity>, nowMillis: Long): List<ScheduledSendJob> =
    rows.map { ScheduledSendJob(it.id, scheduledSendDelayMillis(it.sendAtMillis, nowMillis)) }
