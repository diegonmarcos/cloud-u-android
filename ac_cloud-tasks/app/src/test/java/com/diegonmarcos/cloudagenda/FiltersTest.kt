package com.diegonmarcos.cloudagenda

import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.Task
import com.diegonmarcos.cloudagenda.data.TaskFilter
import com.diegonmarcos.cloudagenda.data.TaskFiltering
import com.diegonmarcos.cloudagenda.data.TaskStatus
import com.diegonmarcos.cloudagenda.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The filter bar's logic: status and tags are multi-select, ANDed across, ORed within, and the choice survives a restart. */
class FiltersTest {

    private fun t(id: String, status: TaskStatus = TaskStatus.ACTION, tags: Set<String> = emptySet(), parent: String? = null, pos: Int = 0) =
        Task(id = id, listId = "L", parentId = parent, name = id, status = status, tagIds = tags, position = pos)

    private val snapshot = Snapshot(
        tasks = listOf(
            t("a", TaskStatus.ACTION, setOf("red"), pos = 0),
            t("a1", TaskStatus.WAITING, setOf("blue"), parent = "a", pos = 0),
            t("a2", TaskStatus.COMPLETED, parent = "a", pos = 1),
            t("b", TaskStatus.HOLD, setOf("blue"), pos = 1),
            t("c", TaskStatus.COMPLETED, pos = 2),
            t("d", TaskStatus.ARCHIVED, setOf("red", "blue"), pos = 3),
        ),
    )

    private fun ids(f: TaskFilter) = TaskFiltering.visible(snapshot, "L", f).map { it.task.id }

    @Test fun `no filter shows every top-level task and all subtasks`() {
        val all = TaskFiltering.visible(snapshot, "L", TaskFilter())
        assertEquals(listOf("a", "b", "c", "d"), all.map { it.task.id })
        assertEquals(listOf("a1", "a2"), all.first().subtasks.map { it.id })
    }

    @Test fun `status is multi-select and ORed`() {
        // a is shown too: its subtask a2 is Completed, so the parent stays visible above it
        assertEquals(listOf("a", "b", "c"), ids(TaskFilter(statuses = setOf(TaskStatus.HOLD, TaskStatus.COMPLETED))))
    }

    @Test fun `tags are multi-select and a task needs any of them`() {
        assertEquals(listOf("a", "b", "d"), ids(TaskFilter(tagIds = setOf("red", "blue"))))
        assertEquals(listOf("a", "d"), ids(TaskFilter(tagIds = setOf("red"))))
    }

    @Test fun `status and tags are ANDed`() {
        assertEquals(listOf("b"), ids(TaskFilter(statuses = setOf(TaskStatus.HOLD), tagIds = setOf("blue"))))
        assertEquals(emptyList<String>(), ids(TaskFilter(statuses = setOf(TaskStatus.COMPLETED), tagIds = setOf("red"))))
    }

    @Test fun `a matching subtask keeps its parent visible and only matching subtasks show`() {
        val nodes = TaskFiltering.visible(snapshot, "L", TaskFilter(statuses = setOf(TaskStatus.WAITING)))
        assertEquals(listOf("a"), nodes.map { it.task.id })
        assertEquals(listOf("a1"), nodes.single().subtasks.map { it.id })
    }

    @Test fun `clear removes both dimensions`() {
        val f = TaskFilter(setOf(TaskStatus.HOLD), setOf("x"))
        assertTrue(f.active)
        assertFalse(f.clear().active)
    }

    @Test fun `toggle adds then removes`() {
        val f = TaskFilter().toggle(TaskStatus.ACTION).toggleTag("x")
        assertEquals(setOf(TaskStatus.ACTION), f.statuses)
        assertEquals(setOf("x"), f.tagIds)
        assertEquals(TaskFilter(), f.toggle(TaskStatus.ACTION).toggleTag("x"))
    }

    @Test fun `the filter round-trips through its persisted text`() {
        val f = TaskFilter(setOf(TaskStatus.WAITING, TaskStatus.HOLD), setOf("t1", "t2"))
        assertEquals(f, TaskFilter.decode(f.encode()))
        assertEquals(TaskFilter(), TaskFilter.decode(TaskFilter().encode()))
        assertEquals(TaskFilter(), TaskFilter.decode(null))
        assertEquals(TaskFilter(), TaskFilter.decode("garbage"))
    }

    @Test fun `a deleted tag is pruned from a restored filter`() {
        val f = TaskFilter(tagIds = setOf("keep", "gone")).prune(setOf("keep"))
        assertEquals(setOf("keep"), f.tagIds)
    }

    @Test fun `theme mode resolves light dark and system`() {
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of("nonsense"))
    }
}
