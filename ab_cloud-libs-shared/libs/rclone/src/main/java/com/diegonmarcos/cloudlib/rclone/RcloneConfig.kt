package com.diegonmarcos.cloudlib.rclone

import java.io.File

/** One `[section]` of rclone.conf: the remote's name, its `type`, and every other key. */
data class RcloneRemote(val name: String, val type: String, val options: Map<String, String> = emptyMap())

/**
 * rclone.conf, read and written by this library: the INI shape rclone itself
 * uses — `[name]` sections, `key = value` lines, `#`/`;` comments. Pure Kotlin;
 * the JVM suite round-trips it. Values are written back verbatim (rclone
 * stores obscured passwords as opaque strings, never quoted).
 */
object RcloneConfig {

    fun parse(text: String): List<RcloneRemote> {
        val out = ArrayList<RcloneRemote>()
        var name: String? = null
        var opts = LinkedHashMap<String, String>()
        fun flush() {
            val n = name ?: return
            out += RcloneRemote(n, opts["type"] ?: "", opts.filterKeys { it != "type" })
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                flush(); name = line.substring(1, line.length - 1).trim(); opts = LinkedHashMap(); continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0 || name == null) continue
            opts[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        flush()
        return out
    }

    fun serialize(remotes: List<RcloneRemote>): String = buildString {
        for (r in remotes) {
            append('[').append(r.name).append("]\n")
            append("type = ").append(r.type).append('\n')
            for ((k, v) in r.options) append(k).append(" = ").append(v).append('\n')
            append('\n')
        }
    }

    fun read(file: File): List<RcloneRemote> = if (file.isFile) parse(file.readText()) else emptyList()

    fun write(file: File, remotes: List<RcloneRemote>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(serialize(remotes))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    fun upsert(file: File, remote: RcloneRemote): List<RcloneRemote> {
        val next = read(file).filterNot { it.name == remote.name } + remote
        write(file, next); return next
    }

    fun remove(file: File, name: String): List<RcloneRemote> {
        val next = read(file).filterNot { it.name == name }
        write(file, next); return next
    }

    /**
     * #575 the host's declared remotes (its build-time data), as skeletons: a name
     * the file does not have yet is ADDED with the given type and options; a name
     * it already has is left exactly as it is — that entry may carry the key the
     * user typed on the device, and a declaration must never wipe it. Returns the
     * resulting list.
     */
    fun declare(file: File, remotes: List<RcloneRemote>): List<RcloneRemote> {
        val current = read(file)
        val have = current.map { it.name }.toSet()
        val added = remotes.filter { it.name !in have && isValidName(it.name) }
        if (added.isEmpty()) return current
        val next = current + added
        write(file, next); return next
    }

    /** rclone remote names: letters, digits, `_`, `-`, `.`, space — and never a colon, which is the remote:path separator. */
    fun isValidName(name: String): Boolean = name.isNotBlank() && name.none { it == ':' || it == '[' || it == ']' || it == '/' || it == '\n' }
}
