package com.diegonmarcos.clouddrive

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import java.io.File

/**
 * #603 SEEDING THE ONE SHARED STORE, from the ONE declaration. build.json::storage.seed
 * says whether and how deep; data/drive-git-repos.json says which repositories (`seed`
 * on the PUBLIC ones). Nothing is seeded into the APK — an APK cannot carry a working
 * clone and would ship it stale — so the store is filled on FIRST RUN instead: for every
 * declared seed repository whose `<shared_root>/<name>` does not exist yet, this worker
 * clones it SHALLOW through libs:git-sync's own [GitEngine] and registers it in the SAME
 * [RepoRegistry] the Git sub-page and [GitSyncWorker] read. One git, one registry, one store.
 *
 * PUBLIC ONLY: an anonymous clone of a private repository fails every time, so seeding one
 * would bake a permanent red. Those stay declared-not-cloned on Configs ▸ Git, where the
 * user can type the token the credential store then holds.
 *
 * Idempotent by construction (an existing folder is skipped) and unique by work name, so a
 * relaunch mid-seed neither duplicates a clone nor restarts a finished one.
 */
class StoreSeedWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        if (!BuildConfig.SEED_ENABLED) return Result.success()
        val family = Declarations.gitFamily
        val registry = RepoRegistry(File(applicationContext.filesDir, GitSyncWorker.REGISTRY_FILE))
        Declarations.seedRepos.forEach { decl ->
            if (isStopped) return Result.retry()
            val dir = SharedStore.repoDir(decl.name)
            if (GitEngine.isRepository(dir)) return@forEach
            val url = family.cloneUrl(decl) ?: return@forEach
            val ok = runCatching { GitEngine.clone(url, dir, depth = BuildConfig.SEED_DEPTH).close() }
            if (ok.isFailure) {
                // A failed seed is not fatal and is not retried into a storm: the repository
                // simply stays declared-not-cloned on the Git page, which says so.
                Log.w(TAG, "seed ${decl.name}: ${ok.exceptionOrNull()?.message}")
                return@forEach
            }
            registry.upsert(
                ManagedRepo(
                    id = RepoRegistry.idFor(dir.absolutePath),
                    name = decl.name,
                    path = dir.absolutePath,
                    remoteUrl = url,
                ),
            )
            Log.i(TAG, "seeded ${decl.name} into ${dir.absolutePath}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "StoreSeed"
        const val WORK_NAME = "cloud-drive-store-seed"

        /**
         * Enqueue the first-run seed. KEEP, not UPDATE: this work is a one-off whose whole
         * point is "only if the store is empty", and replacing a running clone with an
         * identical one would restart a download the user is already waiting on.
         */
        fun schedule(context: Context) {
            if (!BuildConfig.SEED_ENABLED) return
            val request = OneTimeWorkRequestBuilder<StoreSeedWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
