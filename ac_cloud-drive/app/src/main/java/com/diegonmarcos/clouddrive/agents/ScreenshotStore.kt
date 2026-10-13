package com.diegonmarcos.clouddrive.agents

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The contract's files under [root] (= <shared_root>/.agents/screenshots/v1): records/<id>.json,
 * the append-only index.jsonl and state.json (data/agent-screenshots-contract.json). Every
 * record and the state go to a .tmp file that is then renamed, so a reader in another app never
 * sees half a record; index.jsonl is appended one whole line per write. Plain java.io, so the
 * suite (ScreenshotStoreTest) exercises the real files.
 *
 * Knowing what is done costs a directory listing, not a parse: a record's file name IS its id,
 * and the id already folds in path, size and mtime.
 */
class ScreenshotStore(val root: File) {
    data class State(val lastScanMs: Long = 0, val processed: Int = 0, val failed: Int = 0, val cursorMtimeMs: Long = 0, val total: Int = 0, val pending: Int = 0)

    private val json = Json { prettyPrint = false }
    private val records get() = File(root, "records")
    val index get() = File(root, "index.jsonl")
    private val stateFile get() = File(root, "state.json")

    fun recordFile(id: String) = File(records, "$id.json")
    fun has(id: String) = recordFile(id).isFile

    /** Every record id on disk (no parse). */
    fun ids(): Set<String> = records.list { _, n -> n.endsWith(".json") }.orEmpty().map { it.removeSuffix(".json") }.toSet()

    @Synchronized
    fun write(r: ScreenshotRecord) {
        records.mkdirs()
        atomic(recordFile(r.id), json.encodeToString(JsonObject.serializer(), r.toJson()))
        index.appendText(r.indexLine() + "\n")
    }

    fun read(id: String): ScreenshotRecord? = runCatching { ScreenshotRecord.parse(recordFile(id).readText()) }.getOrNull()

    /** The record of the file at [path] as it is now (same size and mtime), else null. */
    fun forFile(file: File): ScreenshotRecord? = read(ScreenshotRecord.idOf(file.absolutePath, file.length(), file.lastModified()))

    /** The [n] most recently written records, newest first; reads only those files. */
    fun recent(n: Int): List<ScreenshotRecord> = records.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .sortedByDescending { it.lastModified() }.take(n).mapNotNull { read(it.name.removeSuffix(".json")) }

    /** Every record that carries an error, for Retry failed (parses every record; user-triggered only). */
    fun failedIds(): List<String> = ids().filter { id -> read(id)?.error != null }

    @Synchronized
    fun remove(id: String) {
        if (recordFile(id).delete()) index.appendText(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("id", id); put("deleted", true) }) + "\n")
    }

    fun state(): State = runCatching {
        val o = json.parseToJsonElement(stateFile.readText()).jsonObject
        fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
        State(l("last_scan_ms"), l("processed").toInt(), l("failed").toInt(), l("cursor_mtime_ms"), l("total").toInt(), l("pending").toInt())
    }.getOrDefault(State())

    @Synchronized
    fun writeState(s: State) {
        root.mkdirs()
        atomic(stateFile, json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("version", 1); put("last_scan_ms", s.lastScanMs); put("processed", s.processed); put("failed", s.failed); put("cursor_mtime_ms", s.cursorMtimeMs)
            put("total", s.total); put("pending", s.pending)
        }))
    }

    private fun atomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "cannot write ${target.path}" } }
    }

    companion object {
        /** The contract's directory under the shared store's root. */
        const val RELATIVE = ".agents/screenshots/v1"
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
