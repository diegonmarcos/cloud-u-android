package com.diegonmarcos.clouddrive.sync

/** #1138 the Sync ▸ Git repo table's columns, in display order. */
enum class RepoColumn { NAME, PROVIDER, VISIBILITY, SIZE_MAIN, SIZE_TOTAL, BRANCHES, DIRTY, AHEAD_BEHIND }

/**
 * One table row's facts. A fact the page could not read (not cloned, not fetched yet, no upstream) is
 * NULL and prints [RepoTable.DASH]: a zero would claim "empty / clean / in sync", which is not known.
 */
data class RepoTableRow(
    val name: String,
    val isPrivate: Boolean?,
    val sizeMainBytes: Long?,
    val sizeTotalBytes: Long?,
    val branches: Int?,
    /** Local uncommitted changes: the changed-file count (0 = clean); null = unknown / not cloned. */
    val dirtyFiles: Int?,
    val ahead: Int?,
    val behind: Int?,
    /** The forge's printed word (GitHub / GitLab / Gitea / Other) from the remote URL's host; null = no remote URL known. */
    val provider: String? = null,
)

/** #1138 the table's pure rules: ranking, sorting, how each cell prints and where its facts come from. */
object RepoTable {
    const val DASH = "—"
    val DEFAULT_SORT = RepoColumn.NAME

    /** The sort after a header tap: the same column flips direction, another column starts ascending. */
    fun tap(column: RepoColumn, current: RepoColumn, ascending: Boolean): Pair<RepoColumn, Boolean> =
        if (column == current) column to !ascending else column to true

    private fun key(r: RepoTableRow, c: RepoColumn): Comparable<*>? = when (c) {
        RepoColumn.NAME -> r.name.lowercase()
        RepoColumn.PROVIDER -> r.provider?.lowercase()
        RepoColumn.VISIBILITY -> r.isPrivate?.let { if (it) 1 else 0 }
        RepoColumn.SIZE_MAIN -> r.sizeMainBytes
        RepoColumn.SIZE_TOTAL -> r.sizeTotalBytes
        RepoColumn.BRANCHES -> r.branches
        RepoColumn.DIRTY -> r.dirtyFiles
        // Ahead first, then behind: one number that orders by ahead and breaks ties by behind.
        RepoColumn.AHEAD_BEHIND -> if (r.ahead == null || r.behind == null) null else r.ahead.toLong() * 1_000_000L + r.behind
    }

    /** [items] ordered by [column]; unknown values always sink to the bottom whichever the direction; ties break by name. */
    fun <T> sort(items: List<T>, column: RepoColumn, ascending: Boolean, row: (T) -> RepoTableRow): List<T> {
        val known = items.filter { key(row(it), column) != null }
        val unknown = items.filter { key(row(it), column) == null }.sortedBy { row(it).name.lowercase() }
        @Suppress("UNCHECKED_CAST")
        val cmp = compareBy<T> { key(row(it), column) as Comparable<Any> }.let { if (ascending) it else it.reversed() }.thenBy { row(it).name.lowercase() }.thenBy { row(it).isPrivate?.let { p -> if (p) 1 else 0 } ?: -1 }
        return known.sortedWith(cmp) + unknown
    }

    /** Which repository's menu is open after a tap on [tapped]: a second tap on the same repo closes it ("" = none). */
    fun toggle(open: String, tapped: String): String = if (open == tapped) "" else tapped

    /** The status line over the table: one pass over the rows, [cloned] says whether a row has a local clone. */
    data class Summary(val total: Int, val cloned: Int, val notCloned: Int, val dirty: Int, val ahead: Int, val behind: Int)

    fun summarize(rows: List<Pair<Boolean, RepoTableRow>>): Summary = Summary(
        total = rows.size,
        cloned = rows.count { it.first },
        notCloned = rows.count { !it.first },
        dirty = rows.count { (it.second.dirtyFiles ?: 0) > 0 },
        ahead = rows.count { (it.second.ahead ?: 0) > 0 },
        behind = rows.count { (it.second.behind ?: 0) > 0 },
    )

    fun size(bytes: Long?): String = if (bytes == null) DASH else GitRepoScan.humanBytes(bytes)
    fun count(n: Int?): String = n?.toString() ?: DASH
    fun visibility(isPrivate: Boolean?, pvt: String, pub: String): String = when (isPrivate) { null -> DASH; true -> pvt; false -> pub }
    fun dirty(files: Int?, yes: String, no: String): String = when {
        files == null -> DASH
        files == 0 -> no
        else -> "$yes · $files"
    }
    fun aheadBehind(ahead: Int?, behind: Int?): String = if (ahead == null || behind == null) DASH else "↑$ahead ↓$behind"

    /**
     * The row of one repository from what the page holds: the remote facts (cached REST), the local
     * glance (the git engine's status, no network) and whether the clone exists. [declaredPrivate] is the
     * listing's flag when there is one; the REST answer fills it for a repo no listing named.
     */
    fun rowOf(name: String, declaredPrivate: Boolean?, cloned: Boolean, glance: GitSyncCoordinator.Glance?, remote: RepoRemoteStats.Stats?, provider: String? = null): RepoTableRow {
        val local = cloned && glance != null && glance.read && !glance.gone && glance.error == null
        return RepoTableRow(
            name = name,
            isPrivate = declaredPrivate ?: remote?.isPrivate,
            sizeMainBytes = remote?.sizeMainBytes,
            sizeTotalBytes = remote?.let { it.sizeTotalKb * 1024 },
            branches = remote?.branches,
            dirtyFiles = if (local) glance!!.changed + glance.conflicts else null,
            ahead = if (local && glance!!.upstream != null) glance.ahead else null,
            behind = if (local && glance!!.upstream != null) glance.behind else null,
            provider = provider,
        )
    }
}
