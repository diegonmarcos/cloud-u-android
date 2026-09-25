package com.diegonmarcos.clouddrive

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * #575 the GitSync app's scheduled sync, natively: every repository the user
 * opted in (ManagedRepo.autoSync) gets the engine's one-tap sync — stage,
 * commit, pull, push — on the period build.json::storage.git_sync declares.
 * Lives in the app, not the library: the library owns the engine and the
 * screen, the app owns when they run (lib → app is forbidden; app → lib is how
 * the chrome uses an engine). The registry and the credential store are the
 * SAME files the screen uses, so what the user sees as "last sync" is what this
 * worker did.
 */
class GitSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val registry = RepoRegistry(File(applicationContext.filesDir, REGISTRY_FILE))
        val credentials = GitCredentialStore(applicationContext)
        var failures = 0
        registry.load().filter { it.autoSync }.forEach { repo ->
            if (isStopped) return Result.retry()
            val result = runCatching {
                GitEngine(File(repo.path)).use { engine ->
                    engine.sync(
                        repo.syncMessage,
                        repo.authorName.ifBlank { DEFAULT_AUTHOR },
                        repo.authorEmail.ifBlank { DEFAULT_EMAIL },
                        rebase = repo.pullRebase,
                        auth = credentials.authFor(repo),
                    )
                }
            }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }
            if (!result.ok) failures++
            Log.i(TAG, "${repo.name}: ${result.summary}")
            registry.upsert(repo.copy(lastSyncEpochSeconds = System.currentTimeMillis() / 1000, lastSyncSummary = "scheduled: " + result.summary))
        }
        // A conflict or a rejected push is the user's to look at, not something a
        // retry storm fixes: report success so the period stays regular.
        return Result.success()
    }

    companion object {
        private const val TAG = "GitSyncWorker"
        private const val WORK_NAME = "cloud-drive-git-sync"
        /** The registry file the git manager screen writes (libs:git-sync names it in GitSyncScreen). */
        const val REGISTRY_FILE = "git-sync/repos.json"
        private const val DEFAULT_AUTHOR = "cloud-drive"
        private const val DEFAULT_EMAIL = "cloud-drive@localhost"

        /**
         * Enqueue (or re-apply) the periodic sync from the baked declaration. UPDATE,
         * not KEEP, for the reason libs:updater gives: a KEEP'd request pins the first
         * interval a phone ever saw and a later build.json edit never reaches it.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (BuildConfig.GIT_SYNC_REQUIRE_UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<GitSyncWorker>(BuildConfig.GIT_SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
