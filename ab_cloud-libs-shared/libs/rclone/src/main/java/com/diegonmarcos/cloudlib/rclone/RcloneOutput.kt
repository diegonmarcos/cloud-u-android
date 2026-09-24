package com.diegonmarcos.cloudlib.rclone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One `stats` object from rclone's JSON log (`--use-json-log --stats 1s`). */
data class RcloneStats(
    val bytes: Long,
    val totalBytes: Long,
    val transfers: Long,
    val totalTransfers: Long,
    val checks: Long,
    val errors: Long,
    val speedBytesPerSecond: Double,
    val etaSeconds: Long?,
    val elapsedSeconds: Double,
) {
    val fraction: Float get() = if (totalBytes > 0) (bytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

/** One entry of `rclone lsjson remote:path`. */
data class RcloneEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modTime: String, val mimeType: String)

/**
 * Parsers for what the rclone binary prints. Pure Kotlin, exercised by the
 * JVM suite against captured fixture lines, so a format change in a new
 * rclone shows up as a red test rather than a progress bar stuck at 0 %.
 */
object RcloneOutput {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The stats inside one JSON log line, or null for any line that is not one (plain text, other levels, malformed). */
    fun parseStatsLine(line: String): RcloneStats? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
        val stats = obj["stats"] as? JsonObject ?: return null
        fun long(k: String) = (stats[k] as? JsonPrimitive)?.longOrNull ?: (stats[k] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L
        fun dbl(k: String) = (stats[k] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val eta = (stats["eta"] as? JsonPrimitive)?.longOrNull
        return RcloneStats(
            bytes = long("bytes"), totalBytes = long("totalBytes"), transfers = long("transfers"), totalTransfers = long("totalTransfers"),
            checks = long("checks"), errors = long("errors"), speedBytesPerSecond = dbl("speed"), etaSeconds = eta, elapsedSeconds = dbl("elapsedTime"),
        )
    }

    /** The human message of a JSON log line (`msg`), with its level; null when the line is not JSON. */
    fun parseMessage(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
        val msg = (obj["msg"] as? JsonPrimitive)?.contentOrNull ?: return null
        val level = (obj["level"] as? JsonPrimitive)?.contentOrNull ?: "info"
        return level to msg
    }

    /** `rclone lsjson` output → entries, directories first, then by name. */
    fun parseLsjson(text: String): List<RcloneEntry> {
        val arr = runCatching { json.parseToJsonElement(text.trim()) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
            RcloneEntry(
                name = str("Name"), path = str("Path"), isDir = (o["IsDir"] as? JsonPrimitive)?.booleanOrNull ?: false,
                size = (o["Size"] as? JsonPrimitive)?.longOrNull ?: -1L, modTime = str("ModTime"), mimeType = str("MimeType"),
            )
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** First line of `rclone version`: "rclone v1.75.1" → "v1.75.1". */
    fun parseVersion(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("rclone v") }?.removePrefix("rclone ")?.trim()

    fun humanBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b.toDouble(); var i = -1
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format("%.1f %s", v, units[i])
    }

    fun humanEta(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "–"
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}
