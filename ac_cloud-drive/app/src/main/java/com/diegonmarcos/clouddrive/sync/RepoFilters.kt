package com.diegonmarcos.clouddrive.sync

/**
 * #1256 THE FILTER ROW of the Git table: the filters and their options are DECLARED (build.json ui.sync.git.filters);
 * this is the one place each declared filter id is given its meaning ([HANDLED], diffed against the declaration by
 * test-drive-git-filters.sh). Every filter's first option is "all"; filters combine (AND). The selection is persisted
 * as one prefs string ([encode]/[decode]). Pure, JVM-tested (RepoFiltersTest).
 */
object RepoFilters {
    const val ALL = "all"

    /** What a filter can look at for one row: only facts the table already holds. */
    data class Facts(val cloned: Boolean, val status: RepoStatus.Kind?, val lfs: Boolean?, val isPrivate: Boolean?, val hub: Boolean?, val unlisted: Boolean = false)

    val HANDLED = setOf("cloned", "status", "lfs", "visibility", "hub", "unlisted")

    fun matches(filter: String, option: String, f: Facts): Boolean = option == ALL || when (filter) {
        "cloned" -> when (option) { "cloned" -> f.cloned; "not_cloned" -> !f.cloned; else -> true }
        "status" -> f.status?.name?.lowercase() == option
        "lfs" -> when (option) { "lfs" -> f.lfs == true; "normal" -> f.lfs == false; else -> true }
        "visibility" -> when (option) { "public" -> f.isPrivate == false; "private" -> f.isPrivate == true; else -> true }
        "hub" -> when (option) { "hub" -> f.hub == true; "non_hub" -> f.hub != true; else -> true }
        "unlisted" -> when (option) { "unlisted" -> f.unlisted; "listed" -> !f.unlisted; else -> true }
        else -> true
    }

    fun <T> apply(items: List<T>, selection: Map<String, String>, facts: (T) -> Facts): List<T> =
        items.filter { item -> val f = facts(item); selection.all { (k, v) -> matches(k, v, f) } }

    /** The unlisted section is shown unless the owner asked for listed rows only; "unlisted" hides the registry rows instead. */
    fun showUnlisted(selection: Map<String, String>): Boolean = selection["unlisted"] != "listed"

    fun encode(sel: Map<String, String>): String = sel.filterValues { it != ALL }.entries.joinToString(";") { "${it.key}=${it.value}" }
    fun decode(s: String?): Map<String, String> = s.orEmpty().split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1] } }.toMap()

    /** The persisted sort: "COLUMN:asc|desc"; an unknown column falls back to the default. */
    fun encodeSort(c: RepoColumn, asc: Boolean): String = c.name + ":" + (if (asc) "asc" else "desc")
    fun decodeSort(s: String?): Pair<RepoColumn, Boolean> {
        val p = s.orEmpty().split(':')
        val c = RepoColumn.entries.firstOrNull { it.name == p.getOrNull(0) } ?: return RepoTable.DEFAULT_SORT to true
        return c to (p.getOrNull(1) != "desc")
    }
}
