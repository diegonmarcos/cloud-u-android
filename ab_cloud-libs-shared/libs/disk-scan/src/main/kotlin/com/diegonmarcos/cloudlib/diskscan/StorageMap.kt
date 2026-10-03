package com.diegonmarcos.cloudlib.diskscan

/**
 * The storage map: bytes per top-level folder of the scanned root, largest first. A file that
 * sits directly in the root is counted under [ROOT_FILES], so the rows always add up to the
 * tree's total — a map whose slices do not sum to the whole would be lying about one of them.
 */
object StorageMap {
    const val ROOT_FILES = "(files in this folder)"

    data class Slice(val name: String, val bytes: Long, val files: Int)

    fun slices(tree: Tree): List<Slice> {
        val prefix = tree.root.trimEnd('/') + "/"
        val acc = LinkedHashMap<String, LongArray>()
        for (e in tree.entries) {
            if (!e.path.startsWith(prefix)) continue
            val rel = e.path.substring(prefix.length)
            val key = if ('/' in rel) rel.substringBefore('/') else ROOT_FILES
            val a = acc.getOrPut(key) { LongArray(2) }
            a[0] += e.bytes
            a[1] += 1
        }
        return acc.map { (k, v) -> Slice(k, v[0], v[1].toInt()) }
            .sortedWith(compareByDescending<Slice> { it.bytes }.thenBy { it.name })
    }
}
