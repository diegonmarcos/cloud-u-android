package com.diegonmarcos.clouddrive

import android.content.Context
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
 * declared seed repository whose `<shared_root>/<git_subdir>/<name>` does not exist yet, this
 * worker clones it SHALLOW through libs:git-sync's own [GitEngine] and registers it in the SAME
 * [RepoRegistry] the Git sub-page and [GitSyncWorker] read. One git, one registry, one store.
 *
 * PUBLIC ONLY: an anonymous clone of a private repository fails every time, so seeding one
 * would bake a permanent red. Those stay declared-not-cloned on Configs ▸ Git, where the
 * user can type the token the credential store then holds.
 *
 * #629 COMPLETE, RESUMABLE AND PER-REPO REPORTED. The first draft was none of the three: a
 * clone failure was swallowed (`Log.w` then `return@forEach`) and the pass returned
 * `Result.success()` regardless, so the ONE realistic failure mode — WorkManager stopping the
 * worker at its ten-minute execution window, with twelve shallow clones queued behind it —
 * turned into a permanently truncated store reporting itself finished. Nine of twelve, missing
 * the alphabetical tail, is exactly what the owner's device held. Now: every declared repository
 * is attempted, a failure stops nothing, EVERY outcome lands in a [SeedReport] that is logged and
 * persisted, and an incomplete pass returns `Result.retry()` so the tail is resumed.
 */
class StoreSeedWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        if (!BuildConfig.SEED_ENABLED) return Result.success()
        val family = Declarations.gitFamily
        val registry = RepoRegistry(File(applicationContext.filesDir, GitSyncWorker.REGISTRY_FILE))
        // #606/#629 the one-time migration runs BEFORE the seed loop, so the seed then finds a
        // migrated repository present and does not clone a second copy of it. Manifest-driven
        // (every declared name), never a hardcoded repository, and it decides by COMPLETENESS
        // rather than by position — StoreMigration says why that matters. Every decision is
        // logged, the ones that changed nothing included: no silent skip.
        StoreMigration.migrate(SharedStore.root(), BuildConfig.GIT_SUBDIR, family.repos.map { it.name }.toSet()).forEach { move ->
            DriveDebugLog.i(applicationContext, TAG, "migration ${move.name}: ${move.decision}")
            if (!StoreMigration.isComplete(move.to)) return@forEach
            val decl = family.repos.firstOrNull { it.name == move.name }
            registry.upsert(
                ManagedRepo(
                    id = RepoRegistry.idFor(move.to.absolutePath),
                    name = move.name,
                    path = move.to.absolutePath,
                    remoteUrl = decl?.let { family.cloneUrl(it) } ?: "",
                ),
            )
        }
        val declared = Declarations.seedRepos
        val outcomes = declared.map { decl ->
            val dir = SharedStore.repoDir(decl.name)
            val url = family.cloneUrl(decl).orEmpty()
            when {
                GitEngine.isRepository(dir) -> SeedOutcome(decl.name, SeedOutcome.PRESENT, dir.absolutePath)
                url.isBlank() -> SeedOutcome(decl.name, SeedOutcome.UNDECLARED, "no upstream instance declares a clone URL")
                // Stopped: recorded, not thrown away. The remaining repositories are DEFERRED rather
                // than attempted against a torn-down thread and logged as if they were broken.
                isStopped -> SeedOutcome(decl.name, SeedOutcome.DEFERRED, "the worker was stopped before this repository was reached")
                else -> {
                    val cloned = runCatching { GitEngine.clone(url, dir, depth = BuildConfig.SEED_DEPTH).close() }
                    if (cloned.isFailure) {
                        SeedOutcome(decl.name, SeedOutcome.FAILED, cloned.exceptionOrNull()?.message ?: cloned.exceptionOrNull()?.javaClass?.simpleName ?: "clone failed")
                    } else {
                        registry.upsert(
                            ManagedRepo(
                                id = RepoRegistry.idFor(dir.absolutePath),
                                name = decl.name,
                                path = dir.absolutePath,
                                remoteUrl = url,
                            ),
                        )
                        SeedOutcome(decl.name, SeedOutcome.SEEDED, dir.absolutePath)
                    }
                }
            }
        }
        val report = SeedReport(declared.size, outcomes)
        // Every per-repository outcome ALSO lands in the on-device debug log
        // (Download/drive-debug/), so an incomplete or failing pass is readable off the
        // phone with no adb — the outcome text carries a reason, never a credential.
        report.lines().forEach { DriveDebugLog.i(applicationContext, TAG, it) }
        // Persisted next to the registry so the Sync ▸ Git page can show "9/12, will retry: …"
        // on the device instead of the user inferring it from a folder listing.
        runCatching { reportFile(applicationContext).writeText(report.text()) }
        // THE LOAD-BEARING LINE. retry, not success, on an incomplete pass: success is terminal for
        // unique work, and a terminal success is how three repositories went missing for good.
        return if (report.complete) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "StoreSeed"
        const val WORK_NAME = "cloud-drive-store-seed"
        const val REPORT_FILE = "store-seed-report.txt"

        /** The last pass's per-repository report, in this app's own files dir. */
        fun reportFile(context: Context): File = File(context.filesDir, REPORT_FILE)

        /** What the last pass reported, or blank before any — the Git page's honest "no pass yet". */
        fun lastReport(context: Context): String =
            runCatching { reportFile(context).takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")

        /**
         * Enqueue the first-run seed. KEEP, not UPDATE: this work is a one-off whose whole
         * point is "only if the store is empty", and replacing a running clone with an
         * identical one would restart a download the user is already waiting on. A pass that
         * ended incomplete is still in RETRY state, so KEEP resumes it rather than dropping it.
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
