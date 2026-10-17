package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.data.Project
import com.diegonmarcos.cloudagenda.data.ProjectSummaries
import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.Task
import com.diegonmarcos.cloudagenda.data.TaskList
import com.diegonmarcos.cloudagenda.data.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What projects/list answers the SuperApp's Boards ▸ Tasks: one row per declared project, counted from its tasks. */
class ProjectSummariesTest {
    private val snap = Snapshot(
        projects = listOf(Project("p2", "Home", 1), Project("p1", "Work", 0), Project("p3", "Empty", 2)),
        lists = listOf(TaskList("l1", "p1", "Inbox", 0), TaskList("l1b", "p1", "Later", 1), TaskList("l2", "p2", "Chores", 0)),
        tasks = listOf(
            Task("t1", "l1", name = "Ship boards", dateDeadline = "2026-10-20"),
            Task("t2", "l1b", name = "Review", dateTarget = "2026-10-18"),
            Task("t3", "l1", name = "Old", status = TaskStatus.COMPLETED, dateDeadline = "2026-01-01"),
            Task("t4", "l1", parentId = "t1", name = "subtask", dateDeadline = "2026-10-01"),
            Task("t5", "l2", name = "Laundry", status = TaskStatus.ARCHIVED),
        ),
    )

    @Test fun `every declared project in order with open, done and next due`() {
        val rows = ProjectSummaries.of(snap)
        assertEquals(listOf("Work", "Home", "Empty"), rows.map { it.name })
        val work = rows[0]
        assertEquals(2, work.open); assertEquals(1, work.done)
        assertEquals("Review", work.nextDueName); assertEquals("2026-10-18", work.nextDue)
        val home = rows[1]
        assertEquals(0, home.open); assertEquals(1, home.done); assertNull(home.nextDueName)
        assertEquals(0, rows[2].open + rows[2].done)
    }

    @Test fun `a project opens on its first list`() {
        assertEquals("l1", ProjectSummaries.firstList(snap, "p1"))
        assertNull(ProjectSummaries.firstList(snap, "p3"))
    }
}
