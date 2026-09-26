package com.diegonmarcos.clouddrive.configs

import android.content.Context
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.GitSyncWorker
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import com.diegonmarcos.cloudlib.gitsync.RepoRegistry
import com.diegonmarcos.cloudlib.rclone.RcloneConfig
import com.diegonmarcos.cloudlib.rclone.RcloneRemote
import com.diegonmarcos.cloudlib.rclone.RcloneRunner
import java.io.File
import org.json.JSONObject

/**
 * #587 What a drive sign-in YIELDS: the DRIVE-relevant sections of the
 * consolidated artifact, applied to this app's own stores and nothing else.
 *
 * The sections are DATA — build.json::auth.applies, baked and decoded once by
 * [Declarations.authApplies] — and this object dispatches on the section id
 * alone (test-drive-configs-sign-in.sh holds the two lists to each other).
 * [plan] is pure and JVM-tested: from an artifact and the declaration it says,
 * per declared section, what WOULD be written and why not when it cannot.
 * [apply] is the only writer, and it writes through the engines' own stores:
 * libs:git-sync's keystore-backed [GitCredentialStore] and libs:rclone's
 * [RcloneConfig]. It never writes a WireGuard key, a mail password or an AI
 * token: those are other apps' to apply.
 */
object DriveAuthApply {

    /** One declared section's fate. [ok] = written (or would be); the line says what. */
    data class Step(val section: String, val ok: Boolean, val line: String)

    data class Report(val steps: List<Step>) {
        val ok: Boolean get() = steps.any { it.ok }
        fun text(): String = steps.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.line }
    }

    /** build.json::auth.applies → section id → the key inside it, off the ONE reader. */
    val applies: Map<String, String> get() = Declarations.authApplies

    /** The artifact's [section] — bare artifact or `{schema, bundle}` envelope alike. */
    private fun section(artifact: JSONObject, id: String): JSONObject? =
        (artifact.optJSONObject("bundle") ?: artifact).optJSONObject(id)

    /**
     * The token the git section carries, or null with the reason. A pending
     * placeholder (`{"pending": true, …}`) is "declared, not yet emitted", not a token.
     */
    fun gitToken(artifact: JSONObject, key: String): Pair<String?, String> {
        val git = section(artifact, "git") ?: return null to "no git section in the artifact"
        val v = git.opt(key)
        return when {
            v is String && v.isNotBlank() -> v.trim() to "git.$key"
            v is JSONObject && v.optBoolean("pending") -> null to "git.$key is pending on the server side (${v.optString("reason")})"
            else -> null to "git section carries no $key"
        }
    }

    /** `rclone.remotes`: `{ "<name>": { "type": …, "options": {…} } }` → remotes, or empty with the reason. */
    fun rcloneRemotes(artifact: JSONObject, key: String): Pair<List<RcloneRemote>, String> {
        val rc = section(artifact, "rclone") ?: return emptyList<RcloneRemote>() to "no rclone section in the artifact"
        val remotes = rc.optJSONObject(key) ?: return emptyList<RcloneRemote>() to "rclone section carries no $key"
        val out = remotes.keys().asSequence().mapNotNull { name ->
            val r = remotes.optJSONObject(name) ?: return@mapNotNull null
            val type = r.optString("type"); if (type.isBlank() || !RcloneConfig.isValidName(name)) return@mapNotNull null
            val opts = r.optJSONObject("options") ?: JSONObject()
            RcloneRemote(name, type, opts.keys().asSequence().associateWith { opts.optString(it) })
        }.toList()
        return out to (if (out.isEmpty()) "rclone.$key names no usable remote" else "rclone.$key")
    }

    /**
     * What a sign-in would write, per declared section, given the repositories
     * this phone manages. Pure: no store is touched.
     */
    fun plan(artifact: JSONObject, applies: Map<String, String>, repos: List<ManagedRepo>): Report {
        val steps = applies.map { (id, key) ->
            when (id) {
                "git" -> {
                    val (token, why) = gitToken(artifact, key)
                    val targets = repos.filter { it.authKind == "https" }
                    when {
                        token == null -> Step(id, false, "git: $why")
                        targets.isEmpty() -> Step(id, false, "git: $why found, but no managed repository uses https auth yet — clone into the store first, then sign in again")
                        else -> Step(id, true, "git: $why → ${targets.size} repositor${if (targets.size == 1) "y" else "ies"} (${targets.joinToString { it.name }})")
                    }
                }
                "rclone" -> {
                    val (remotes, why) = rcloneRemotes(artifact, key)
                    if (remotes.isEmpty()) Step(id, false, "rclone: $why") else Step(id, true, "rclone: $why → ${remotes.joinToString { it.name }}")
                }
                else -> Step(id, false, "$id: declared in build.json::auth.applies but nothing here knows how to apply it")
            }
        }
        return Report(steps)
    }

    /** THE writer. Plans against the live registry, then writes every ok step. */
    fun apply(ctx: Context, artifact: JSONObject): Report {
        val registry = RepoRegistry(File(ctx.filesDir, GitSyncWorker.REGISTRY_FILE))
        val repos = registry.load()
        val report = plan(artifact, applies, repos)
        for (step in report.steps.filter { it.ok }) {
            when (step.section) {
                "git" -> {
                    val (token, _) = gitToken(artifact, applies.getValue("git"))
                    val store = GitCredentialStore(ctx)
                    repos.filter { it.authKind == "https" }.forEach { store.setSecret(it.id, token!!) }
                }
                "rclone" -> {
                    val (remotes, _) = rcloneRemotes(artifact, applies.getValue("rclone"))
                    // The same rclone.conf the engine's runner reads — its path, not a second copy of it.
                    val conf = RcloneRunner(ctx).configFile
                    remotes.forEach { RcloneConfig.upsert(conf, it) }
                }
            }
        }
        return report
    }
}
