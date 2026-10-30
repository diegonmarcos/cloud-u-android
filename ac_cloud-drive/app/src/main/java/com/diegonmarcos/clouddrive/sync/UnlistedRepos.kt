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

    /** Breadth-first, bounded: every git working folder under [roots] (a root that is itself a repository counts). */
    fun walk(roots: List<File>, maxDepth: Int, maxDirs: Int, deadlineMs: Long, now: () -> Long = System::currentTimeMillis, skip: (File) -> Boolean = { false }): List<Folder> {
        val out = LinkedHashMap<String, Folder>()
        var seen = 0
        val queue = ArrayDeque<Pair<File, Int>>()
        roots.filter { it.isDirectory }.forEach { queue.add(it to 0) }
        while (queue.isNotEmpty() && seen < maxDirs && now() < deadlineMs) {
            val (d, depth) = queue.removeFirst(); seen++
            if (skip(d) || LocalRepos.isIncoming(d)) continue
            if (File(d, ".git").exists()) { out.putIfAbsent(d.absolutePath, Folder(d.absolutePath, LocalRepos.originOf(d))); continue }
            if (depth >= maxDepth) continue
            d.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }.sortedBy { it.name.lowercase() }
                .forEach { queue.add(it to depth + 1) }
        }
        return out.values.toList()
    }

    /** The folders whose origin names no registry repository ([registry] = identities); no origin = unlisted. */
    fun unlisted(found: List<Folder>, registry: Set<String>, canonical: (String) -> String): List<Folder> =
        found.filter { f -> f.origin.isBlank() || BulkDepth.identity(f.origin, canonical) !in registry }

    /** #1317 the list without the folders a command just moved, archived or deleted (their rows must vanish at once, the rest stay). */
    fun without(l: List<Folder>, gone: Set<String>): List<Folder> = l.filter { it.path !in gone }

    /** The status key of an unlisted folder in the ONE status reader (and so in StatusCache). */
    fun statusKey(path: String): String = KEY_PREFIX + path
    const val KEY_PREFIX = "unlisted:"

    /** The found list as one prefs string (path TAB origin, one per line) and back. */
    fun encode(l: List<Folder>): String = l.joinToString("\n") { it.path + "\t" + it.origin }
    fun decode(s: String?): List<Folder> = s.orEmpty().lineSequence().filter { it.isNotBlank() }.map { val p = it.split('\t', limit = 2); Folder(p[0], p.getOrElse(1) { "" }) }.toList()
}
