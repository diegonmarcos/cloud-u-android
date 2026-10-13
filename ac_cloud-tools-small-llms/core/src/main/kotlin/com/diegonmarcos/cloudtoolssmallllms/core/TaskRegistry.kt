package com.diegonmarcos.cloudtoolssmallllms.core

/**
 * "What I can do": every declared task with its worker (the declared one, or the user's override
 * from Settings when the catalogue prices it), that worker's $/1M from the shared catalogue and the
 * estimated cost of the typical call (build.json::tools.typical).
 */
class TaskRegistry(private val cfg: ToolsConfig, private val prices: PriceBook, private val overrides: Map<String, String> = emptyMap()) {
    data class Entry(val task: ToolsConfig.Task, val worker: String, val price: Price?, val estimate: Double?)

    /** The worker a task runs on: an override only when the catalogue prices it, else the declared worker. */
    fun worker(task: ToolsConfig.Task): String = overrides[task.id]?.takeIf { prices.price(it) != null } ?: task.worker

    fun entry(task: ToolsConfig.Task): Entry {
        val w = worker(task)
        val p = prices.price(w)
        return Entry(task, w, p, p?.let { Costs.estimate(it, cfg.typicalIn, cfg.typicalOut) })
    }

    val entries: List<Entry> get() = cfg.tasks.map(::entry)

    fun entry(id: String): Entry? = cfg.task(id)?.let(::entry)

    /** Why the registry is not whole: a task with no prompt, or a worker / chat router the catalogue does not price. Empty = whole. */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        for (t in cfg.tasks) {
            if (t.system.isBlank()) out += "${t.id}: no system prompt"
            if (prices.price(t.worker) == null) out += "${t.id}: worker ${t.worker} is not priced by the catalogue"
        }
        for (m in cfg.router.chatModels) if (prices.price(m) == null) out += "router: ${m} is not priced by the catalogue"
        return out
    }
}
