package com.diegonmarcos.clouddrive.agents

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The contract's files under [root] (= <shared_root>/.agents/screenshots/v1): records/<id>.json,
 * the append-only index.jsonl and state.json. Every write goes to a .tmp file that is then renamed,
 * so a reader in another app never sees half a record. Plain java.io, JVM-tested.
 */
class ScreenshotStore(val root: File) {
    data class State(val lastScanMs: Long, val processed: Int, val failed: Int, val cursorMtimeMs: Long)

    private val json = Json { prettyPrint = false }
    private val records get() = File(root, "records")
    private val index get() = File(root, "index.jsonl")
    private val stateFile get() = File(root, "state.json")

    fun recordFile(id: String) = File(records, "$id.json")
    fun has(id: String) = recordFile(id).isFile

    fun write(r: ScreenshotRecord) {
        records.mkdirs()
        atomic(recordFile(r.id), json.encodeToString(JsonObject.serializer(), r.toJson()))
        index.appendText(r.indexLine() + "\n")
    }

    fun read(id: String): ScreenshotRecord? = runCatching { ScreenshotRecord.fromJson(json.parseToJsonElement(recordFile(id).readText()).jsonObject) }.getOrNull()

    /** Every record, newest processed first. */
    fun all(): List<ScreenshotRecord> = records.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> read(f.name.removeSuffix(".json")) }.sortedByDescending { it.processedAtMs }

    /** The newest record whose source is [path], whatever its size and mtime became. */
    fun forSource(path: String): ScreenshotRecord? = all().firstOrNull { it.sourcePath == path }

    fun remove(id: String) {
        if (recordFile(id).delete()) index.appendText(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("id", id); put("deleted", true) }) + "\n")
    }

    fun state(): State = runCatching {
        val o = json.parseToJsonElement(stateFile.readText()).jsonObject
        fun l(k: String) = o[k]?.toString()?.toLongOrNull() ?: 0L
        State(l("last_scan_ms"), l("processed").toInt(), l("failed").toInt(), l("cursor_mtime_ms"))
    }.getOrDefault(State(0, 0, 0, 0))

    fun writeState(s: State) {
        root.mkdirs()
        atomic(stateFile, json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("version", 1); put("last_scan_ms", s.lastScanMs); put("processed", s.processed); put("failed", s.failed); put("cursor_mtime_ms", s.cursorMtimeMs)
        }))
    }

    private fun atomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    companion object {
        /** The contract's directory under the shared store's root. */
        const val RELATIVE = ".agents/screenshots/v1"
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
