package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.GitSyncWorker
import com.diegonmarcos.clouddrive.JobPathMigration
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import com.diegonmarcos.cloudlib.rclone.RcloneJobStore
import java.io.File

/**
 * #1149 after [LocalRepos.rename] moved a folder, every place this app remembered the old path follows it: the Git
 * registry (path, the id derived from it, the stored credential re-keyed to that id), both legs of the saved rclone
 * jobs, and the Files bookmarks. The rule is [LocalRepos.repointPath]; the stores are the ones [JobPathMigration]
 * already rewrites on a store move, so there is one way a saved path is rewritten.
 */
object LocalRepoRefs {

    data class Report(val repos: Int, val jobs: Int, val bookmarks: Int)

    /** The registry half, over a [RepoRegistry] and a re-key callback so the JVM suite runs it without a Context. Returns how many entries moved. */
    fun repointRegistry(registry: RepoRegistry, rekey: (from: String, to: String) -> Unit, old: String, new: String): Int {
        var moved = 0
        val next = registry.load().map { r ->
            val to = LocalRepos.repointPath(r.path, old, new) ?: return@map r
            moved++
            val id = RepoRegistry.idFor(to)
            if (id != r.id) rekey(r.id, id)
            // The display name follows when it was the old folder's name (a name the owner chose stays).
            r.copy(id = id, path = to, name = if (r.name == File(old).name) File(new).name else r.name)
        }
        if (moved > 0) registry.save(next)
        return moved
    }

    fun repoint(ctx: Context, prefs: DrivePrefs, old: File, new: File): Report {
        val o = old.absolutePath
        val n = new.absolutePath
        val rule = { p: String -> LocalRepos.repointPath(p, o, n) }
        val repos = repointRegistry(
            RepoRegistry(File(ctx.filesDir, GitSyncWorker.REGISTRY_FILE)),
            { from, to -> runCatching { GitCredentialStore(ctx).rekey(from, to) } },
            o, n,
        )
        val jobs = JobPathMigration.migrateJobs(RcloneJobStore(File(ctx.filesDir, "rclone/jobs.json")), rule)
        val marks = prefs.repointBookmarks(rule)
        return Report(repos, jobs, marks)
    }
}
