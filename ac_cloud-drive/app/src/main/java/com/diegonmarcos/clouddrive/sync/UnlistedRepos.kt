package com.diegonmarcos.clouddrive.sync

import java.io.File

/**
 * #1255 THE UNLISTED GIT FOLDERS of the device: every folder under the DECLARED roots (build.json ui.sync.git.unlisted)
 * that holds a `.git` and is NOT a repository of the registry — matched by its origin URL with the renamed_from chains
 * folded in ([BulkDepth.identity]); a folder with no remote is always unlisted. The walk is bounded (depth, folder count,
 * deadline), never enters a repository, a hidden folder or Android/, and runs off the main thread. Pure over java.io,
 * JVM-tested (UnlistedReposTest).
 */
object UnlistedRepos {

    data class Folder(val path: String, val origin: String)

    /** The root templates with their {placeholders} expanded; a template whose placeholder has no value is dropped. */
    fun expandRoots(templates: List<String>, vars: Map<String, List<String>>): List<File> =
        templates.flatMap { t ->
            val ph = Regex("""\{([a-z_]+)\}""").find(t)?.groupValues?.get(1)
            if (ph == null) listOf(t) else vars[ph].orEmpty().map { v -> t.replace("{$ph}", v) }
        }.filter { it.isNotBlank() }.map { File(it) }.distinctBy { it.absolutePath }

    // #1315 the walk and the registry match moved to the ONE inventory (sync/inventory: InventoryScript.walkIo + RepoInventory.classify).

    /** #1317 the list without the folders a command just moved, archived or deleted (their rows must vanish at once, the rest stay). */
    fun without(l: List<Folder>, gone: Set<String>): List<Folder> = l.filter { it.path !in gone }

    /** The status key of an unlisted folder in the ONE status reader (and so in StatusCache). */
    fun statusKey(path: String): String = KEY_PREFIX + path
    const val KEY_PREFIX = "unlisted:"

    /** The found list as one prefs string (path TAB origin, one per line) and back. */
    fun encode(l: List<Folder>): String = l.joinToString("\n") { it.path + "\t" + it.origin }
    fun decode(s: String?): List<Folder> = s.orEmpty().lineSequence().filter { it.isNotBlank() }.map { val p = it.split('\t', limit = 2); Folder(p[0], p.getOrElse(1) { "" }) }.toList()
}
