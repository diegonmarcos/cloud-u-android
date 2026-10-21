package com.diegonmarcos.cloudlib.rclone.unison

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * #1084 what the Sync tab remembers per pair between runs, so a conflict found by a scheduled run is
 * still there when the user opens the app, and a run's log survives leaving the tab. One JSON file in
 * the app's private files dir, written atomically (tmp + rename) so a kill mid-write keeps the old
 * file. The archives themselves (Unison's own state) are NOT here: they live in the engine's private
 * dir with their own snapshot and rollback.
 */
class UnisonStateStore(private val file: File) {
    data class PairState(
        val pairId: String,
        val lastRunEpochSeconds: Long,
        val trigger: String,
        val headline: String,
        val rc: Int,
        val conflicts: List<UnisonOutput.Conflict>,
        val failures: List<UnisonOutput.Failure>,
        val log: List<String>,
    )

    private val lock = Any()

    fun load(): Map<String, PairState> = synchronized(lock) {
        if (!file.isFile) return emptyMap()
        runCatching {
            val root = JSONObject(file.readText())
            root.keys().asSequence().associateWith { id ->
                val o = root.getJSONObject(id)
                PairState(
                    id, o.optLong("at"), o.optString("trigger"), o.optString("headline"), o.optInt("rc", -1),
                    list(o.optJSONArray("conflicts")).map { UnisonOutput.Conflict(it.getString("path"), it.optString("reason")) },
                    list(o.optJSONArray("failures")).map { UnisonOutput.Failure(it.getString("path"), it.optString("reason")) },
                    (0 until (o.optJSONArray("log")?.length() ?: 0)).map { o.getJSONArray("log").getString(it) },
                )
            }
        }.getOrElse { emptyMap() }
    }

    fun get(id: String): PairState? = load()[id]

    fun put(s: PairState) {
        synchronized(lock) {
            val all = load().toMutableMap()
            all[s.pairId] = s.copy(log = s.log.takeLast(MAX_LOG))
            write(all)
        }
    }

    /** A resolved conflict leaves the list at once; the next run re-derives the truth. */
    fun resolved(id: String, path: String) {
        synchronized(lock) {
            val all = load().toMutableMap()
            val cur = all[id] ?: return
            all[id] = cur.copy(conflicts = cur.conflicts.filterNot { it.path == path })
            write(all)
        }
    }

    /** Pairs no longer declared lose their remembered state. */
    fun retain(ids: Set<String>) {
        synchronized(lock) {
            val all = load()
            if (all.keys.all { it in ids }) return
            write(all.filterKeys { it in ids })
        }
    }

    private fun write(all: Map<String, PairState>) {
        val root = JSONObject()
        for ((id, s) in all) root.put(id, JSONObject()
            .put("at", s.lastRunEpochSeconds).put("trigger", s.trigger).put("headline", s.headline).put("rc", s.rc)
            .put("conflicts", JSONArray(s.conflicts.map { JSONObject().put("path", it.path).put("reason", it.reason) }))
            .put("failures", JSONArray(s.failures.map { JSONObject().put("path", it.path).put("reason", it.reason) }))
            .put("log", JSONArray(s.log)))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    private fun list(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }

    companion object {
        const val FILE = "unison/state.json"
        const val MAX_LOG = 150
    }
}
