package com.diegonmarcos.clouddrive

import android.content.Context
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import com.diegonmarcos.cloudlib.rclone.RcloneJobStore
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import java.io.File

/**
 * cloud-shared-store: the first-launch rewrite of every SAVED sync and clone path from the old
 * location (<shared storage>/CloudDrive/..., and the terminals' old link name, the compat link) to the store.
 * Covers the Git registry (repos.json: path, and the id derived from it, with its credential
 * re-keyed) and the rclone jobs (jobs.json: both legs). IDEMPOTENT by construction: the rule
 * (StorePaths.migrateLegacy) returns null for a path already in the store, so a second run
 * rewrites nothing. Runs on every launch and before every worker pass; it costs two small
 * file reads when there is nothing to do.
 */
object JobPathMigration {

    data class Report(val repos: Int, val jobs: Int)

    fun run(ctx: Context): Report {
        val u = StoreAccess.userId()
        val rule = { p: String -> StoreAccess.paths.migrateLegacy(p, u) }
        var repos = 0
        val registry = RepoRegistry(File(ctx.filesDir, GitSyncWorker.REGISTRY_FILE))
        val loaded = registry.load()
        val moved = loaded.map { r ->
            val to = rule(r.path) ?: return@map r
            repos++
            val id = RepoRegistry.idFor(to)
            runCatching { GitCredentialStore(ctx).rekey(r.id, id) }
            r.copy(id = id, path = to)
        }
        if (repos > 0) registry.save(moved)
        var jobs = 0
        val store = RcloneJobStore(File(ctx.filesDir, "rclone/jobs.json"))
        val js = store.load()
        val nj = js.map { j ->
            val s = rule(j.source); val d = rule(j.destination)
            if (s == null && d == null) j else { jobs++; j.copy(source = s ?: j.source, destination = d ?: j.destination) }
        }
        if (jobs > 0) store.save(nj)
        if (repos + jobs > 0) DriveDebugLog.i(ctx, "JobPathMigration", "rewrote $repos repo path(s) and $jobs rclone job(s) to ${StoreAccess.root()}")
        return Report(repos, jobs)
    }
}
