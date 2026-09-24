package com.diegonmarcos.cloudlib.rclone

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One transfer the engine can run. `op` is the rclone verb (copy, sync, move,
 * check, bisync); `source`/`destination` are rclone paths (`remote:dir` or a
 * local absolute path); `flags` are extra arguments, split on whitespace.
 * `declared` marks a job the host app handed in from its own build-time data
 * (cloud-drive's data/drive-rclone-jobs.json) as opposed to one the user
 * created here; declared jobs are re-declared on every launch and cannot be
 * deleted from this screen, only from that file.
 */
@Serializable
data class RcloneJob(
    val id: String,
    val name: String,
    val op: String,
    val source: String,
    val destination: String,
    val flags: List<String> = emptyList(),
    val declared: Boolean = false,
    val lastRunEpochSeconds: Long = 0,
    val lastRunSummary: String = "",
) {
    /** The argv after the binary: verb, paths, flags, plus what live progress needs. */
    fun arguments(): List<String> = listOf(op, source, destination) + flags

    companion object {
        val OPS = listOf("copy", "sync", "move", "check", "bisync")
    }
}

/** The job list as one JSON file (pure Kotlin; the host hands in `File(filesDir, "rclone/jobs.json")`). */
class RcloneJobStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val serializer = ListSerializer(RcloneJob.serializer())

    fun load(): List<RcloneJob> = if (!file.isFile) emptyList() else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { emptyList() }

    fun save(jobs: List<RcloneJob>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, jobs))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    fun upsert(job: RcloneJob): List<RcloneJob> { val next = load().filterNot { it.id == job.id } + job; save(next); return next }

    fun remove(id: String): List<RcloneJob> { val next = load().filterNot { it.id == id }; save(next); return next }

    /**
     * Replace every declared job with [jobs] (the host's current declaration),
     * keeping user-created ones and the last-run record of declared ones whose
     * id survived. Called by the host before it opens the screen.
     */
    fun declare(jobs: List<RcloneJob>): List<RcloneJob> {
        val current = load()
        val userJobs = current.filterNot { it.declared }
        val history = current.filter { it.declared }.associateBy { it.id }
        val declared = jobs.map { j ->
            val h = history[j.id]
            j.copy(declared = true, lastRunEpochSeconds = h?.lastRunEpochSeconds ?: 0, lastRunSummary = h?.lastRunSummary ?: "")
        }
        val next = declared + userJobs
        save(next); return next
    }
}
