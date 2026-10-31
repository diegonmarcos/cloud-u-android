package com.diegonmarcos.clouddrive.files

import com.diegonmarcos.clouddrive.Declarations
import java.io.File

/**
 * The explorer's inline search (ui.files.search). Pure: no Compose, no Android, so the JVM suite
 * runs it against real temp folders.
 *
 * A non-recursive scope ([Declarations.SearchScopeDecl.recursive] false) filters the listing the
 * pane already holds, in place ([filterHere]). A recursive scope walks from the current folder or
 * from the storage root ([startOf]) and streams hits through [walk], which the controller runs on
 * Dispatchers.IO and cancels whenever the query or the scope changes. The walk is bounded three
 * ways (hits, entries scanned, depth) so a huge storage cannot hold the UI.
 */
object ExplorerSearch {

    fun needle(query: String): String = query.trim().lowercase()

    fun matches(name: String, needle: String): Boolean = needle.isNotEmpty() && name.lowercase().contains(needle)

    /** The live filter over the current listing: name contains the query, folders only when the scope says so. A blank query leaves the listing whole. */
    fun filterHere(entries: List<FileOps.Entry>, query: String, scope: Declarations.SearchScopeDecl): List<FileOps.Entry> {
        val n = needle(query)
        if (n.isEmpty()) return entries
        return entries.filter { e -> (!scope.foldersOnly || e.isDirectory) && matches(e.name, n) }
    }

    /** The storage root that holds [path]: the longest of [roots] that is [path] or a parent of it, else "/". */
    fun storageRoot(path: String, roots: List<String>): String =
        roots.map { it.trimEnd('/') }.filter { it.isNotEmpty() && (path == it || path.startsWith("$it/")) }.maxByOrNull { it.length } ?: "/"

    fun startOf(scope: Declarations.SearchScopeDecl, here: String, roots: List<String>): String =
        if (scope.fromRoot) storageRoot(here, roots) else here

    data class Bounds(val results: Int, val scanned: Int, val depth: Int)

    /** Why a walk stopped early, or [NONE] when it covered everything. */
    enum class Stop { NONE, RESULTS, SCANNED, CANCELLED }

    /**
     * Breadth-first from [start] (not reported itself): every entry whose name matches, folders only when
     * [Declarations.SearchScopeDecl.foldersOnly]. [onHit] is called as hits are found (streaming).
     */
    fun walk(
        start: File,
        query: String,
        scope: Declarations.SearchScopeDecl,
        bounds: Bounds,
        mimes: FileOps.MimeResolver,
        cancelled: () -> Boolean,
        onScanned: (Int) -> Unit = {},
        onHit: (FileOps.Entry) -> Unit,
    ): Stop {
        val n = needle(query)
        if (n.isEmpty()) return Stop.NONE
        var scanned = 0
        var found = 0
        var level = listOf(start)
        var depth = 0
        while (level.isNotEmpty() && depth < bounds.depth) {
            val next = ArrayList<File>()
            for (dir in level) {
                if (cancelled()) return Stop.CANCELLED
                val children = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
                for (c in children) {
                    if (cancelled()) return Stop.CANCELLED
                    if (++scanned >= bounds.scanned) { onScanned(scanned); return Stop.SCANNED }
                    if (scanned % 200 == 0) onScanned(scanned)
                    val isDir = c.isDirectory
                    if (isDir) next += c
                    if ((!scope.foldersOnly || isDir) && matches(c.name, n)) {
                        onHit(FileOps.describe(c, mimes, countChildren = false))
                        if (++found >= bounds.results) { onScanned(scanned); return Stop.RESULTS }
                    }
                }
            }
            level = next
            depth++
        }
        onScanned(scanned)
        return Stop.NONE
    }
}
