package com.diegonmarcos.clouddrive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.clouddrive.configs.DriveAuthApply
import com.diegonmarcos.clouddrive.configs.DriveGitChain
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.cloudlib.gitsync.GitAuth
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
 * DECLARED-PUBLIC ONLY, WITH THE ONE CREDENTIAL ANYWAY (#683). The seed set stays the
 * manifest's public repositories (a declared-private one stays declared-not-cloned on the
 * Git page), but the DECLARATION and the PROVIDER can drift apart: front-diegonmarcos was
 * measured 2026-09-30 answering "Authentication is required" to the anonymous seed clone —
 * declared public, held private upstream. So every seed clone now carries the SAME
 * credential the Git page's own clone carries, resolved through the SAME declared path —
 * the vault-delivered token first ([DriveAuthApply.vaultGitToken]), then the declared chain
 * (`ab_cloud-libs-shared/build.json::auth.git_chain`, walked by [DriveGitChain.resolve]) —
 * never a second credential mechanism. And a clone the provider refuses for want of a
 * credential the device does not hold is a STRUCTURAL failure, not a transient one: it
 * becomes the terminal [SeedOutcome.NEEDS_CREDENTIAL], because retrying cannot mint a
 * credential and "will retry" on it would burn battery and quota against a clone that can
 * never succeed. The vault import or a fleet sign-in is the way out, and the report says so.
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
        // #730 THE FRESH-PHONE GATE, before anything touches the store (the migration included):
        // this pass is scheduled by the same onCreate that sends the user to the all-files grant,
        // so on a fresh install it runs before the toggle is flipped. Every repository is then
        // reported as needing storage — logged, persisted for the Git page — and the pass is
        // retried; MainActivity.onResume re-kicks it the moment the grant arrives.
        SeedReport.withoutStorage(Declarations.seedRepos.map { it.name }, Places.hasAllFilesAccess(applicationContext))?.let { blocked ->
            blocked.lines().forEach { DriveDebugLog.i(applicationContext, TAG, it) }
            runCatching { reportFile(applicationContext).writeText(blocked.text()) }
            return Result.retry()
        }
        val family = Declarations.gitFamily
        val registry = RepoRegistry(File(applicationContext.filesDir, GitSyncWorker.REGISTRY_FILE))
        // #606/#629 the one-time migration runs BEFORE the seed loop, so the seed then finds a
        // migrated repository present and does not clone a second copy of it. Manifest-driven
        // (every declared name), never a hardcoded repository, and it decides by COMPLETENESS
        // rather than by position — StoreMigration says why that matters. Every decision is
        // logged, the ones that changed nothing included: no silent skip.
        // #731 then the declared upstream RENAMES (`renamed_from`): git/<old> settles into
        // git/<new> under the same completeness rule, so a renamed repository is never seeded a
        // second time beside its stray old-name clone.
        val moves = StoreMigration.migrate(SharedStore.root(), BuildConfig.GIT_SUBDIR, family.repos.map { it.name }.toSet()) +
            StoreMigration.migrateRenames(SharedStore.gitRoot(), family.renames)
        moves.forEach { move ->
            DriveDebugLog.i(applicationContext, TAG, "migration ${move.name}: ${move.decision}")
            if (!StoreMigration.isComplete(move.to)) return@forEach
            val decl = family.repos.firstOrNull { it.name == move.name }
            if (move.removed) registry.remove(RepoRegistry.idFor(move.from.absolutePath))
            // A renamed clone still points origin at the OLD name; the provider's redirect is not
            // a design, so origin is repointed at the declared URL of the new name.
            val url = decl?.let { family.cloneUrl(it) }
            if (move.moved && move.from.name != move.to.name && url != null) {
                runCatching { GitEngine(move.to).use { it.setRemoteUrl("origin", url) } }
                    .onFailure { DriveDebugLog.i(applicationContext, TAG, "migration ${move.name}: origin not repointed: ${it.message}") }
            }
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
        // #683 THE SEED'S CREDENTIAL IS THE PAGE'S CREDENTIAL: the vault-delivered token under
        // the ONE declared id first (the primary path needs no negotiation), then the declared
        // chain — the SAME resolver the Sync ▸ Git page uses, so there is no second mechanism to
        // drift. With no interactive fleet session the fleet rungs fall through instantly and
        // cheaply; blank simply means "this device holds none", which the clone arm then reports
        // as the terminal needs-credential outcome instead of an unwinnable retry.
        val token = DriveAuthApply.vaultGitToken(applicationContext)
            .ifBlank { runCatching { DriveGitChain.resolve(applicationContext).token.orEmpty() }.getOrDefault("") }
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
                    val auth = if (token.isBlank()) GitAuth.None else GitAuth.Https(decl.githubOwner, token)
                    val cloned = runCatching { GitEngine.clone(url, dir, auth = auth, depth = BuildConfig.SEED_DEPTH).close() }
                    if (cloned.isFailure) {
                        val why = cloned.exceptionOrNull()?.message ?: cloned.exceptionOrNull()?.javaClass?.simpleName ?: "clone failed"
                        // #683 STRUCTURAL, NOT TRANSIENT: the provider demanded a credential and
                        // this device holds none the declared path can answer with. A retry
                        // cannot mint one, so this is TERMINAL — never the retried FAILED. A
                        // refusal WITH a credential in hand stays FAILED: the next pass
                        // re-resolves the chain and a fresh vault import can change the answer.
                        if (token.isBlank() && authDemanded(why))
                            SeedOutcome(decl.name, SeedOutcome.NEEDS_CREDENTIAL, "$why — the vault import or a fleet sign-in delivers one")
                        else
                            SeedOutcome(decl.name, SeedOutcome.FAILED, why)
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

        /**
         * #683 Was this clone refusal the transport DEMANDING a credential? JGit's wording for
         * the anonymous case is "Authentication is required but no CredentialsProvider has been
         * registered" (measured on the device, 2026-09-30, against front-diegonmarcos); the
         * refused-credential and raw-HTTP shapes ("not authorized", 401/403) are matched too.
         * Pure text, so the JVM suite exercises it against the measured message.
         */
        internal fun authDemanded(why: String): Boolean =
            listOf("authentication", "not authorized", "401", "403").any { why.contains(it, ignoreCase = true) }
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
        fun schedule(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

        /**
         * #730 the all-files grant just arrived: REPLACE the queued pass, because a pass that
         * ended needs-storage sits in WorkManager's exponential back-off and KEEP would leave the
         * store empty for that whole delay. Nothing useful is lost — a pass without the grant
         * cannot write a byte.
         */
        fun kick(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            if (!BuildConfig.SEED_ENABLED) return
            // #730 the network constraint is DECLARED (build.json::storage.seed.require_unmetered_network).
            val network = if (BuildConfig.SEED_REQUIRE_UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<StoreSeedWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
            // Before any pass has run the Git page would show nothing at all — exactly what a
            // phone waiting for Wi-Fi looked like. The queued state gets the report's own words.
            if (lastReport(context).isBlank()) {
                val pending = SeedReport.pending(Declarations.seedRepos.size, BuildConfig.SEED_REQUIRE_UNMETERED)
                DriveDebugLog.i(context, TAG, pending)
                runCatching { reportFile(context).writeText(pending) }
            }
        }
    }
}
