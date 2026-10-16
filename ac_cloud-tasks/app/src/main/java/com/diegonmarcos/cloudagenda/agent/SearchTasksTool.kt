package com.diegonmarcos.cloudagenda.agent

import com.diegonmarcos.cloudagenda.data.Snapshot
import com.diegonmarcos.cloudagenda.data.Task
import com.diegonmarcos.cloudagenda.data.TasksRepository
import com.diegonmarcos.superapp.ai.AiTool
import org.json.JSONObject

/** Searching the tasks: pure over a snapshot, so the one AI box can answer "what is due this week" from real data. */
object TaskSearch {
    /** A task matches when every word of [query] appears in its name, details, location, status, tags or attachment labels. */
    fun search(snapshot: Snapshot, query: String, limit: Int = 20): List<Task> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return snapshot.tasks.filter { t ->
            val hay = buildString {
                append(t.name).append(' ').append(t.details).append(' ').append(t.location).append(' ').append(t.status.label).append(' ')
                append(t.dateTarget.orEmpty()).append(' ').append(t.dateDeadline.orEmpty()).append(' ')
                t.tagIds.forEach { id -> snapshot.tag(id)?.let { append(it.name).append(' ') } }
                t.attachments.forEach { append(it.label).append(' ') }
            }.lowercase()
            words.all { it in hay }
        }.take(limit)
    }

    /** One line per task: where it is, its status and dates. What the model reads back. */
    fun describe(snapshot: Snapshot, t: Task): String {
        val list = snapshot.list(t.listId)
        val where = listOfNotNull(snapshot.project(list?.projectId)?.name, list?.name, snapshot.task(t.parentId)?.name).joinToString(" > ")
        val dates = listOfNotNull(t.dateTarget?.let { "target $it" }, t.dateDeadline?.let { "deadline $it" }).joinToString(", ")
        return "- ${t.name} [${t.status.label}]" + (if (dates.isNotEmpty()) " ($dates)" else "") + (if (where.isNotEmpty()) " in $where" else "")
    }
}

/** The AI box's read-only tool: the model may look through the tasks. It changes nothing, so it asks no consent. */
class SearchTasksTool(private val repo: TasksRepository) : AiTool {
    override val name = "search_tasks"
    override val description = "Search the user's tasks and subtasks by words in the name, details, location, status, tags or dates (yyyy-MM-dd). Read only."
    override val schema = """{"type":"object","properties":{"query":{"type":"string","description":"words to look for"}},"required":["query"]}"""

    override fun run(args: JSONObject): String {
        val snapshot = repo.snapshot()
        val found = TaskSearch.search(snapshot, args.optString("query"))
        return if (found.isEmpty()) "no task matches" else found.joinToString("\n") { TaskSearch.describe(snapshot, it) }
    }
}
