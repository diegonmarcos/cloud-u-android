package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.cloudlib.gitsync.GitAuth
import com.diegonmarcos.cloudlib.gitsync.GitEngine
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.GitTransports
import com.diegonmarcos.cloudlib.gitsync.GitWire
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File
import org.json.JSONObject

/**
 * #1138 THE COMPOUND GIT OPERATIONS of a repository's detail, composed from the existing engine verbs
 * plus four verbs only the terminal-root wire ([TermuxGitWire], git inside cld.termux) answers:
 * `merge_upstream`, `stash`, `force_push_lease` and `shrink`. They are sent through the SAME transport
 * the engine uses ([GitTransports]), so the shared-store repositories keep running git as the terminal
 * root; a directory the engine APK serves refuses them with its own sentence rather than guessing.
 *
 *  - sync-no-winner  : fetch + merge of the upstream; a conflict aborts the merge and reports, nothing changes.
 *  - sync-remote-wins: local changes (untracked included) are stashed first, then reset to the remote.
 *  - sync-local-wins : stage + commit, fetch (so the lease is current), then push --force-with-lease.
 *  - clean(depth)    : LOCAL ONLY: fetch --depth N, reflog expire, gc. The remote is never written.
 */
internal object RepoCompound {

    /** One verb the engine client has no method for, through the transport that serves [dir]. */
    fun raw(ctx: Context, dir: File, verb: String, args: JSONObject, auth: GitAuth): GitOpResult =
        runCatching {
            GitTransports.forDir(ctx, dir).session().use { s ->
                val a = s.git(GitWire.request(dir, verb, args.put("remote", "origin"), GitWire.authToJson(auth)))
                if (!a.optBoolean("ok", false)) GitOpResult(false, a.optString("error").ifBlank { "$verb refused" })
                else runCatching { GitWire.json.decodeFromString(GitOpResult.serializer(), a.optString("result")) }.getOrElse { GitOpResult(true, "$verb done") }
            }
        }.getOrElse { GitOpResult(false, it.message ?: it.toString()) }

    fun checkoutMain(ctx: Context, repo: ManagedRepo, auth: GitAuth): GitOpResult = raw(ctx, File(repo.path), "checkout_main", JSONObject(), auth)

    fun run(ctx: Context, repo: ManagedRepo, id: String, auth: GitAuth): GitOpResult {
        val dir = File(repo.path)
        val name = repo.authorName.ifBlank { GitSyncCoordinator.DEFAULT_AUTHOR }
        val email = repo.authorEmail.ifBlank { GitSyncCoordinator.DEFAULT_EMAIL }
        val who = JSONObject().put("name", name).put("email", email)
        return when {
            id == RepoActions.SYNC_NO_WINNER -> {
                val r = raw(ctx, dir, "merge_upstream", who, auth)
                if (r.ok) GitOpResult(true, "sync-no-winner: fetched and merged", r.details)
                else GitOpResult(false, "sync-no-winner: " + (if (r.details.contains("merge conflict")) "conflict, merge aborted, nothing changed" else "not merged"), r.details.ifBlank { r.summary })
            }
            id == RepoActions.SYNC_REMOTE_WINS -> {
                val dirty = GitEngine(ctx, dir).use { it.status().files.isNotEmpty() }
                if (dirty) {
                    val st = raw(ctx, dir, "stash", JSONObject().put("name", name).put("email", email).put("message", "cloud-drive: before remote-wins"), auth)
                    if (!st.ok) return GitOpResult(false, "sync-remote-wins: backup failed, nothing reset", st.details.ifBlank { st.summary })
                }
                val pulled = GitEngine(ctx, dir).use { it.forcePull(auth = auth) }
                GitOpResult(pulled.ok, "sync-remote-wins: " + (if (dirty) "local changes stashed, " else "") + pulled.summary, pulled.details)
            }
            id == RepoActions.SYNC_LOCAL_WINS -> {
                val steps = GitEngine(ctx, dir).use { e ->
                    e.stageAll()
                    val committed = if (e.status().staged.isNotEmpty()) "committed ${e.commit(repo.syncMessage.ifBlank { "cloud-drive sync" }, name, email).shortSha}, " else ""
                    val fetched = e.fetch(auth = auth)
                    if (fetched.ok) committed else return GitOpResult(false, "sync-local-wins: fetch failed, nothing pushed", fetched.details.ifBlank { fetched.summary })
                }
                val pushed = raw(ctx, dir, "force_push_lease", JSONObject(), auth)
                GitOpResult(pushed.ok, "sync-local-wins: " + steps + (if (pushed.ok) "pushed with lease" else "push refused"), pushed.details.ifBlank { pushed.summary })
            }
            id == RepoActions.STASH_PUSH -> {
                val dirty = GitEngine(ctx, dir).use { it.status().files.isNotEmpty() }
                if (dirty) {
                    val st = raw(ctx, dir, "stash", JSONObject().put("name", name).put("email", email).put("message", "cloud-drive: stash and push"), auth)
                    if (!st.ok) return GitOpResult(false, "stash-push: stash failed, nothing pushed", st.details.ifBlank { st.summary })
                }
                val pushed = GitEngine(ctx, dir).use { it.push(auth = auth) }
                GitOpResult(pushed.ok, "stash-push: " + (if (dirty) "local changes stashed, " else "") + pushed.summary, pushed.details)
            }
            RepoActions.cleanDepth(id) > 0 -> {
                val depth = RepoActions.cleanDepth(id)
                val r = raw(ctx, dir, "shrink", JSONObject().put("depth", depth), auth)
                GitOpResult(r.ok, if (r.ok) "repo clean: kept depth $depth locally (remote untouched)" else "repo clean failed (remote untouched)", r.details.ifBlank { r.summary })
            }
            else -> GitOpResult(false, "no such operation: $id")
        }
    }
}
