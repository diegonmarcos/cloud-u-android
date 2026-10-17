package com.diegonmarcos.cloudagenda.data

/** One project as the SuperApp's Boards ▸ Tasks shows it: counts over its top-level tasks and the next dated open one. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val open: Int,
    val done: Int,
    val nextDueName: String?,
    val nextDue: String?,
)

/** Pure over a [Snapshot], so a JVM test holds what projects/list answers. */
object ProjectSummaries {
    private val DONE = setOf(TaskStatus.COMPLETED, TaskStatus.ARCHIVED)

    /** Every declared project, in the app's own order. Open = not completed/archived; next due = the earliest
     *  deadline, else target date, among open tasks (ISO dates sort as text). */
    fun of(s: Snapshot): List<ProjectSummary> = s.projects.sortedBy { it.position }.map { p ->
        val listIds = s.listsOf(p.id).map { it.id }.toSet()
        val tasks = s.tasks.filter { it.listId in listIds && it.parentId == null }
        val open = tasks.filter { it.status !in DONE }
        val next = open.mapNotNull { t -> (t.dateDeadline ?: t.dateTarget)?.let { t to it } }.minByOrNull { it.second }
        ProjectSummary(p.id, p.name, open.size, tasks.size - open.size, next?.first?.name, next?.second)
    }

    /** The list the app opens for [projectId]: its first list, or null when it has none. */
    fun firstList(s: Snapshot, projectId: String): String? = s.listsOf(projectId).minByOrNull { it.position }?.id
}
