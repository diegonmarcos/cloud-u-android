package com.diegonmarcos.cloudlib.diskscan

/**
 * The cache/temp clean, as a PLAN first and a run second, so the dry run cannot promise one
 * number and the run reclaim another. A [Plan] is the list of files the run will delete with the
 * bytes each held when planned; [run] deletes exactly those, and only a file that still has the
 * planned size (a file that changed since the preview is not the file the user agreed to lose:
 * it is skipped and reported). The reclaimed total is the sum of what was really deleted, so for
 * an unchanged tree it equals [Plan.bytes] — the property the suite pins.
 *
 * [protect] is the veto every caller passes: a path it answers true for never enters a plan
 * (pending installs in an APK cache, a file another engine holds open).
 */
object CleanPlan {
    data class Item(val path: String, val bytes: Long, val source: String)

    data class Plan(val items: List<Item>) {
        val bytes: Long get() = items.sumOf { it.bytes }
        fun bySource(): Map<String, Long> = items.groupBy { it.source }.mapValues { (_, v) -> v.sumOf { it.bytes } }
    }

    data class Result(val reclaimed: Long, val deleted: List<String>, val skipped: List<String>)

    /** Names a temp file wears: partial downloads, editor locks and swap files, *.tmp. */
    private val TEMP_SUFFIXES = listOf(".tmp", ".temp", ".part", ".partial", ".crdownload", ".swp")
    private val TEMP_PREFIXES = listOf("~$", ".~lock.")

    fun isTemp(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return TEMP_SUFFIXES.any { name.endsWith(it) } || TEMP_PREFIXES.any { name.startsWith(it) }
    }

    fun temp(entries: List<Entry>, source: String = "temp"): List<Item> =
        entries.filter { isTemp(it.path) }.map { Item(it.path, it.bytes, source) }

    fun everything(entries: List<Entry>, source: String): List<Item> = entries.map { Item(it.path, it.bytes, source) }

    fun plan(items: List<Item>, protect: (String) -> Boolean = { false }): Plan =
        Plan(items.filter { !protect(it.path) }.distinctBy { it.path })

    /** [size] is the file's length now, null when it is gone; [delete] answers whether it went. */
    fun run(plan: Plan, size: (String) -> Long?, delete: (String) -> Boolean): Result {
        var reclaimed = 0L
        val deleted = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (item in plan.items) {
            if (size(item.path) != item.bytes || !delete(item.path)) {
                skipped += item.path
                continue
            }
            reclaimed += item.bytes
            deleted += item.path
        }
        return Result(reclaimed, deleted, skipped)
    }
}
