package com.diegonmarcos.cloudlib.gitsync

import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The list of repositories the manager shows, as one JSON file. Pure Kotlin
 * over a [File] so the JVM test suite exercises the real persistence rather
 * than a mock — the host hands in `File(filesDir, "git-sync/repos.json")`.
 *
 * A malformed file is not fatal: it reads as empty and is overwritten on the
 * next save. Losing the list costs a re-add; crashing on launch costs the app.
 */
class RepoRegistry(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val serializer = ListSerializer(ManagedRepo.serializer())

    fun load(): List<ManagedRepo> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(repos: List<ManagedRepo>) {
        file.parentFile?.mkdirs()
        // Write beside, then rename: a crash mid-write must not leave a
        // half-file that reads as "no repositories".
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, repos))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun upsert(repo: ManagedRepo): List<ManagedRepo> {
        val current = load().filterNot { it.id == repo.id }
        val next = current + repo
        save(next)
        return next
    }

    fun remove(id: String): List<ManagedRepo> {
        val next = load().filterNot { it.id == id }
        save(next)
        return next
    }

    companion object {
        /** A stable id from the path, so re-adding the same folder updates rather than duplicates. */
        fun idFor(path: String): String {
            val canonical = File(path).absolutePath.trimEnd('/')
            var h = 1125899906842597L
            for (c in canonical) h = 31 * h + c.code
            return "repo-" + java.lang.Long.toHexString(h)
        }
    }
}
