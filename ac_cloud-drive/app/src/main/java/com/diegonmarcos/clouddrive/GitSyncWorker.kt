package com.diegonmarcos.clouddrive

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.clouddrive.sync.SyncEvent
import com.diegonmarcos.clouddrive.sync.SyncHistory
import com.diegonmarcos.clouddrive.sync.SyncSchedule
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * #575 the GitSync app's scheduled sync, natively; #579 per repository. ONE periodic
 * request ticks at the BASE period build.json::storage.git_sync declares (WorkManager's
 * floor); on each tick every repository that opted in (ManagedRepo.autoSync) is
 * checked by [SyncSchedule.isDue] against its OWN period (0 = the base) and its own
 * network rule, and the due ones get the engine's one-tap sync. Every run is written
 * to the sync history (trigger `scheduled`), so the Sync ▸ Git card and its History
 * show exactly what this worker did. The registry and the credential store are the
 * SAME files the screen uses.
 *
 * Lives in the app, not the library: the library owns the engine and the screen, the
 * app owns when they run (lib → app is forbidden; app → lib is how the chrome uses
 * an engine).
 */
class GitSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val registry = RepoRegistry(File(applicationContext.filesDir, REGISTRY_FILE))
        val credentials = GitCredentialStore(applicationContext)
        val history = SyncHistory(File(applicationContext.filesDir, SyncHistory.FILE))
        val unmetered = networkUnmetered(applicationContext)
        val now = System.currentTimeMillis() / 1000
        registry.load().filter { it.autoSync }.forEach { repo ->
            if (isStopped) return Result.retry()
            val due = SyncSchedule.isDue(
                autoSync = repo.autoSync, repoIntervalMinutes = repo.syncIntervalMinutes, baseIntervalMinutes = BuildConfig.GIT_SYNC_INTERVAL_MINUTES,
                lastSyncEpochSeconds = repo.lastSyncEpochSeconds, nowEpochSeconds = now,
                requireUnmetered = repo.syncRequireUnmetered, networkUnmetered = unmetered,
            )
            if (!due) return@forEach
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
            Log.i(TAG, "${repo.name}: ${result.summary}")
            val at = System.currentTimeMillis() / 1000
            registry.upsert(repo.copy(lastSyncEpochSeconds = at, lastSyncSummary = "scheduled: " + result.summary))
            history.append(SyncEvent(at, repo.id, repo.name, SyncHistory.TRIGGER_SCHEDULED, result.ok, result.summary, result.details))
        }
        // A conflict or a rejected push is the user's to look at (the card goes red and says
        // so), not something a retry storm fixes: report success so the period stays regular.
        return Result.success()
    }

    companion object {
        private const val TAG = "GitSyncWorker"
        const val WORK_NAME = "cloud-drive-git-sync"
        /** The registry file the git manager screen writes (libs:git-sync names it in GitSyncScreen). */
        const val REGISTRY_FILE = "git-sync/repos.json"
        private const val DEFAULT_AUTHOR = "cloud-drive"
        private const val DEFAULT_EMAIL = "cloud-drive@localhost"

        /** null when the platform will not say (no active network, no capabilities). */
        fun networkUnmetered(context: Context): Boolean? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return null
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        /**
         * Enqueue (or re-apply) the periodic sync from the baked declaration. UPDATE, not
         * KEEP, for the reason libs:updater gives: a KEEP'd request pins the first interval a
         * phone ever saw and a later build.json edit never reaches it. The request's network
         * constraint is CONNECTED (a repository declaring "any network" must get its tick);
         * the base declaration's unmetered rule is what a repository inherits when it says
         * nothing, applied per repository in doWork.
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
