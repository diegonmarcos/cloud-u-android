package com.diegonmarcos.cloudlib.diskscan

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** One regular file found by [Walk]: its absolute path, its length and its mtime. */
data class Entry(val path: String, val bytes: Long, val modified: Long)

/** The outcome of a walk: the files, and whether the walk stopped at its cap. */
data class Tree(val root: String, val entries: List<Entry>, val truncated: Boolean) {
    val bytes: Long get() = entries.sumOf { it.bytes }
}

/**
 * The walk of a tree into [Entry]s. Iterative (a deep tree cannot overflow the stack), it never
 * follows a symbolic link (a link back up the tree would loop, and a link out of it would count
 * another volume's bytes as this one's), skips what [skip] names, and stops at [maxFiles] so a
 * phone with millions of thumbnails answers instead of hanging; [Tree.truncated] says so.
 */
object Walk {
    const val DEFAULT_MAX_FILES = 400_000

    fun tree(root: File, maxFiles: Int = DEFAULT_MAX_FILES, skip: (File) -> Boolean = { false }): Tree {
        val out = ArrayList<Entry>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (isLink(f) || skip(f)) continue
            if (f.isDirectory) {
                f.listFiles()?.forEach { stack.addLast(it) }
            } else if (f.isFile) {
                if (out.size >= maxFiles) return Tree(root.absolutePath, out, truncated = true)
                out += Entry(f.absolutePath, f.length(), f.lastModified())
            }
        }
        return Tree(root.absolutePath, out, truncated = false)
    }

    private fun isLink(f: File): Boolean = Files.isSymbolicLink(f.toPath()) ||
        !Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
}
