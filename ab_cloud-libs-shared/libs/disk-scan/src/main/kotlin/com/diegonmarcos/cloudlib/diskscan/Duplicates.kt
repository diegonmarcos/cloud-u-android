package com.diegonmarcos.cloudlib.diskscan

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Duplicate files, found the way that reads the fewest bytes: group by SIZE (free, it is in the
 * entry), then by a hash of the first [PARTIAL_BYTES] (cheap, splits most same-size strangers),
 * then by the FULL hash. Only a full-hash match is a duplicate — two files that merely share a
 * size and a first block are not, and offering to delete one of them would lose data.
 * Empty files are never grouped: they hold no bytes to reclaim and are often markers.
 */
object Duplicates {
    const val PARTIAL_BYTES = 64L * 1024

    data class Group(val hash: String, val bytes: Long, val paths: List<String>) {
        /** What keeping one copy frees. */
        val reclaimable: Long get() = bytes * (paths.size - 1)
    }

    /** [hash] (path, limit or null for the whole file) is injectable so a test can count reads. */
    fun groups(
        entries: List<Entry>,
        minBytes: Long = 1,
        hash: (String, Long?) -> String? = ::sha256,
    ): List<Group> {
        val out = ArrayList<Group>()
        val bySize = entries.filter { it.bytes >= maxOf(1L, minBytes) }.groupBy { it.bytes }
        for ((size, sameSize) in bySize) {
            if (sameSize.size < 2) continue
            val byPartial = sameSize.groupBy { hash(it.path, PARTIAL_BYTES) }
            for ((partial, samePartial) in byPartial) {
                if (partial == null || samePartial.size < 2) continue
                // A file no bigger than the partial window was already hashed whole.
                val byFull = if (size <= PARTIAL_BYTES) mapOf(partial to samePartial)
                else samePartial.groupBy { hash(it.path, null) }
                for ((full, same) in byFull) {
                    if (full == null || same.size < 2) continue
                    out += Group(full, size, same.map { it.path }.sorted())
                }
            }
        }
        return out.sortedWith(compareByDescending<Group> { it.reclaimable }.thenBy { it.paths.first() })
    }

    /** The copies to delete when [keep] is the one kept; [keep] must be in the group. */
    fun keepOne(group: Group, keep: String): List<String> {
        require(keep in group.paths) { "$keep is not a copy in this group" }
        return group.paths.filter { it != keep }
    }

    fun sha256(path: String, limit: Long?): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(File(path)).use { input ->
            val buf = ByteArray(64 * 1024)
            var left = limit ?: Long.MAX_VALUE
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                md.update(buf, 0, n)
                left -= n
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
