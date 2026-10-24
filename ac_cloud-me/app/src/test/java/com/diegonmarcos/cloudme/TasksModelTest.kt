package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/** #1150 the three views of Profile > Tasks, as arithmetic over the engine's rows (events: start; tasks: due, projectId). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TasksModelTest {

    private fun at(y: Int, m: Int, d: Int, h: Int = 10): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0, 0) }.timeInMillis

    private val today = at(2026, 10, 24)

    private fun event(title: String, start: Long) = JSONObject().put("title", title).put("start", start).put("allDay", false)
    private fun todo(title: String, due: Long?, project: String, status: String = "NEEDS-ACTION") =
        JSONObject().put("summary", title).put("due", due?.toString() ?: "").put("projectId", project).put("status", status)

    private val items = TasksModel.items(
        JSONArray().put(event("Standup", at(2026, 10, 24, 9))).put(event("Dentist", at(2026, 10, 26, 15))),
        JSONArray().put(todo("File taxes", at(2026, 10, 24), "Buro"))
            .put(todo("Old thing", at(2026, 10, 1), "Buro"))
            .put(todo("Plan trip", null, "Trips"))
            .put(todo("Loose end", at(2026, 11, 3), ""))
            .put(todo("Done already", at(2026, 10, 24), "Buro", "COMPLETED")),
    )

    @Test
    fun `the Day view shows the day's events and tasks, with overdue and undated tasks on today`() {
        val titles = TasksModel.day(items, today, today).map { it.title }.toSet()
        assertEquals(setOf("Standup", "File taxes", "Old thing", "Plan trip"), titles)
        assertEquals(listOf("Dentist"), TasksModel.day(items, at(2026, 10, 26), today).map { it.title })
        assertTrue("a completed task is history", "Done already" !in items.map { it.title })
        assertTrue(TasksModel.day(items, at(2026, 10, 27), today).isEmpty())
    }

    @Test
    fun `the Subject view groups open tasks by subject with No subject last`() {
        val groups = TasksModel.bySubject(items)
        assertEquals(listOf("Buro", "Trips", TasksModel.NO_SUBJECT), groups.map { it.first })
        assertEquals(setOf("File taxes", "Old thing"), groups[0].second.map { it.title }.toSet())
        assertTrue("an event is not a task", groups.flatMap { it.second }.none { !it.isTask })
    }

    @Test
    fun `the Calendar view lays the month out Monday first and marks the days with something`() {
        val cells = TasksModel.month(items, today)
        // 1 Oct 2026 is a Thursday: three padding cells, then 31 days
        assertEquals(3, cells.takeWhile { it.first == null }.size)
        assertEquals(31, cells.count { it.first != null })
        val busy = cells.filter { it.second }.mapNotNull { it.first }.map { Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.DAY_OF_MONTH) }
        assertEquals(listOf(1, 24, 26), busy)
    }

    @Test
    fun `month stepping lands on the first of the adjacent month`() {
        val next = Calendar.getInstance().apply { timeInMillis = TasksModel.addMonths(at(2026, 10, 31), 1) }
        assertEquals(10, next.get(Calendar.MONTH)) // November (0-based)
        assertEquals(1, next.get(Calendar.DAY_OF_MONTH))
    }
}
