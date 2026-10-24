package com.diegonmarcos.cloudme

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** One row of the Tasks page: an event or a task, on a day, filed under a subject. */
data class TaskItem(val title: String, val whenMs: Long, val allDay: Boolean, val isTask: Boolean, val subject: String, val detail: String, val percent: Int)

/**
 * The pure half of the Tasks page (ticket #1150): the cal engine's rows (CalEngineClient: events with `start`, tasks with
 * `due` and `projectId`) as [TaskItem]s, and the three views over them: a Day, a Subject and a Calendar month.
 * No Android and no clock of its own: every function takes the day it is about, so it is testable as arithmetic.
 */
object TasksModel {
    const val NO_SUBJECT = "No subject"

    fun items(events: JSONArray, todos: JSONArray): List<TaskItem> {
        val ev = (0 until events.length()).mapNotNull { events.optJSONObject(it) }.map {
            TaskItem(it.optString("title").ifBlank { "(untitled)" }, it.optLong("start"), it.optBoolean("allDay"), false,
                NO_SUBJECT, it.optString("location"), -1)
        }
        val open = (0 until todos.length()).mapNotNull { todos.optJSONObject(it) }
            .filter { it.optString("status") != "COMPLETED" && it.optString("status") != "CANCELLED" }
            .map { t ->
                TaskItem(t.optString("summary").ifBlank { "(untitled)" }, due(t) ?: 0L, true, true,
                    t.optString("projectId").ifBlank { NO_SUBJECT }, t.optString("description").lineSequence().firstOrNull().orEmpty(),
                    t.optInt("percentComplete", -1))
            }
        return (ev + open).sortedWith(compareBy({ it.whenMs == 0L }, { it.whenMs }, { it.title }))
    }

    private fun due(t: JSONObject): Long? = t.optString("due").toLongOrNull()

    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun addDays(ms: Long, days: Int): Long = Calendar.getInstance().apply { timeInMillis = ms; add(Calendar.DAY_OF_YEAR, days) }.timeInMillis

    /** The first day of the month [n] months from [ms]'s. */
    fun addMonths(ms: Long, n: Int): Long = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, n) }.timeInMillis

    /** Day view: what falls on [day]; tasks with no due date and overdue tasks are shown on [today]. */
    fun day(items: List<TaskItem>, day: Long, today: Long): List<TaskItem> {
        val from = startOfDay(day)
        val to = addDays(from, 1)
        return items.filter { i ->
            when {
                i.isTask && i.whenMs == 0L -> startOfDay(day) == startOfDay(today)
                i.isTask && i.whenMs < startOfDay(today) -> startOfDay(day) == startOfDay(today)
                else -> i.whenMs in from until to
            }
        }
    }

    /** Subject view: open tasks grouped by subject (the engine's collection), subjects in name order, "No subject" last. */
    fun bySubject(items: List<TaskItem>): List<Pair<String, List<TaskItem>>> =
        items.filter { it.isTask }.groupBy { it.subject }.toList()
            .sortedWith(compareBy({ it.first == NO_SUBJECT }, { it.first.lowercase() }))

    /** Calendar view: the days of the month of [month] (a cell is the start of that day, null = padding before the 1st) and which of them have items. */
    fun month(items: List<TaskItem>, month: Long, firstDayOfWeek: Int = Calendar.MONDAY): List<Pair<Long?, Boolean>> {
        val first = Calendar.getInstance().apply { timeInMillis = month; set(Calendar.DAY_OF_MONTH, 1) }
        val lead = (first.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
        val days = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val busy = items.filter { !(it.isTask && it.whenMs == 0L) }.map { startOfDay(it.whenMs) }.toSet()
        val cells = MutableList<Pair<Long?, Boolean>>(lead) { null to false }
        for (d in 0 until days) {
            val start = startOfDay(addDays(startOfDay(first.timeInMillis), d))
            cells += start to (start in busy)
        }
        return cells
    }
}
