package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.cloudlib.sharedstore.StoreExec
import com.diegonmarcos.cloudlib.sharedstore.StoreExec.Companion.q
import com.diegonmarcos.cloudlib.sharedstore.StorePaths
import java.io.File

/**
 * cloud-shared-store's EXEC fallback for git: when this app is NOT in cld.termux's uid
 * (StoreAccess.mode == EXEC) the store is not a File it can open, so the clone / sync runs
 * INSIDE cld.termux's glibc root on the store (Debian git, /root/cloud-shared-store/...),
 * through the signature-level CLOUD_EXEC service. The token goes over stdin only.
 * DIRECT keeps the in-process JGit path; [useExec] is the one switch every caller asks.
 */
object StoreGit {

    fun useExec(ctx: Context): Boolean = StoreAccess.mode(ctx) == StorePaths.Mode.EXEC

    private fun guest(dir: File): String =
        StoreAccess.paths.guestPath(dir.absolutePath, StoreAccess.userId())
            ?: error("${dir.absolutePath} is not inside the store")

    /** `git <args>` with the token (if any) as an http.extraHeader, read from stdin. */
    internal fun authed(args: String): String =
        "T=\"\$(cat)\"; if [ -n \"\$T\" ]; then " +
            "H=\"Authorization: Basic \$(printf 'x-access-token:%s' \"\$T\" | base64 | tr -d '\\n')\"; " +
            "git -c http.extraHeader=\"\$H\" $args; else git $args; fi"

    fun cloneScript(url: String, guestDir: String, depth: Int?): String =
        "set -e; mkdir -p \"\$(dirname ${q(guestDir)})\"; " +
            authed("clone ${if (depth != null && depth > 0) "--depth $depth " else ""}${q(url)} ${q(guestDir)}")

    fun syncScript(guestDir: String, message: String, name: String, email: String, rebase: Boolean): String =
        "set -e; cd ${q(guestDir)}; git add -A; " +
            "git diff --cached --quiet || git -c user.name=${q(name)} -c user.email=${q(email)} commit -q -m ${q(message)}; " +
            authed(if (rebase) "pull --rebase" else "pull --no-rebase") + "; " + authed("push")

    fun clone(ctx: Context, url: String, dir: File, token: String, depth: Int? = null): GitOpResult {
        val r = StoreExec(ctx).job(cloneScript(url, guest(dir), depth), stdin = token)
        return GitOpResult(r.ok, if (r.ok) "cloned into ${dir.name} (in cld.termux)" else "clone in cld.termux failed (exit ${r.exit})", (r.out + "\n" + r.err).trim().takeLast(4000))
    }

    fun sync(ctx: Context, dir: File, message: String, name: String, email: String, rebase: Boolean, token: String): GitOpResult {
        val r = StoreExec(ctx).job(syncScript(guest(dir), message, name, email, rebase), stdin = token)
        return GitOpResult(r.ok, if (r.ok) "synced (in cld.termux)" else "sync in cld.termux failed (exit ${r.exit})", (r.out + "\n" + r.err).trim().takeLast(4000))
    }

    /** `git rev-parse` in the guest: is [dir] a repository (EXEC mode's GitEngine.isRepository). */
    fun isRepository(ctx: Context, dir: File): Boolean =
        runCatching { StoreExec(ctx).run("git -C ${q(guest(dir))} rev-parse --git-dir >/dev/null 2>&1", timeoutMs = 30_000).ok }.getOrDefault(false)
}
