package com.diegonmarcos.cloudagenda.data

/**
 * The top filter bar's state. Empty set = that dimension does not filter. Within a dimension the choices are
 * OR (status Action or Waiting; any of the picked tags); the two dimensions are AND. Pure, so it is tested
 * without a device, and it round-trips through [encode]/[decode] so the filter survives a restart.
 */
data class TaskFilter(
    val statuses: Set<TaskStatus> = emptySet(),
    val tagIds: Set<String> = emptySet(),
) {
    val active: Boolean get() = statuses.isNotEmpty() || tagIds.isNotEmpty()

    fun matches(t: Task): Boolean =
        (statuses.isEmpty() || t.status in statuses) &&
            (tagIds.isEmpty() || t.tagIds.any { it in tagIds })

    fun toggle(s: TaskStatus): TaskFilter = copy(statuses = if (s in statuses) statuses - s else statuses + s)

    fun toggleTag(id: String): TaskFilter = copy(tagIds = if (id in tagIds) tagIds - id else tagIds + id)

    fun clear(): TaskFilter = TaskFilter()

    /** Drops tags that no longer exist (deleted since the filter was saved). */
    fun prune(existingTagIds: Set<String>): TaskFilter = copy(tagIds = tagIds.filter { it in existingTagIds }.toSet())

    fun encode(): String =
        "s=" + statuses.map { it.id }.sorted().joinToString(",") + ";t=" + tagIds.sorted().joinToString(",")

    companion object {
        fun decode(text: String?): TaskFilter {
            if (text.isNullOrBlank()) return TaskFilter()
            var statuses = emptySet<TaskStatus>()
            var tags = emptySet<String>()
            for (part in text.split(';')) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val values = part.substring(eq + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                when (part.substring(0, eq)) {
                    "s" -> statuses = values.mapNotNull { v -> TaskStatus.entries.firstOrNull { it.id == v } }.toSet()
                    "t" -> tags = values.toSet()
                }
            }
            return TaskFilter(statuses, tags)
        }
    }
}

object TaskFiltering {
    /**
     * What a list shows under [filter]. A top-level task is shown when it matches, or when one of its subtasks
     * does (so a matching subtask is never hidden behind a parent that does not match). Its subtasks are the
     * matching ones; with no filter active that is all of them. Order is the stored position.
     */
    fun visible(snapshot: Snapshot, listId: String, filter: TaskFilter): List<TaskNode> =
        snapshot.topLevel(listId).sortedBy { it.position }.mapNotNull { task ->
            val subs = snapshot.subtasksOf(task.id).sortedBy { it.position }
            val shownSubs = if (filter.active) subs.filter { filter.matches(it) } else subs
            if (!filter.active || filter.matches(task) || shownSubs.isNotEmpty()) TaskNode(task, shownSubs) else null
        }
}
