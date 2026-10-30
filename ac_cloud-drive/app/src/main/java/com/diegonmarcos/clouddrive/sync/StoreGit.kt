package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import com.diegonmarcos.cloudlib.sharedstore.StoreExec
import com.diegonmarcos.cloudlib.sharedstore.StoreExec.Companion.q
import com.diegonmarcos.cloudlib.sharedstore.StorePaths
import java.io.File

/**
 * cloud-shared-store's EXEC fallback for git: because the git engine APK (its own uid) cannot open
 * the store in either access mode, so the clone / sync runs
 * INSIDE cld.termux's glibc root on the store (Debian git, /root/cloud-shared-store/...),
 * through the signature-level CLOUD_EXEC service. The token goes over stdin only.
 * [useExec] is the one switch every caller asks.
 */
object StoreGit {

    /** The git engine APK is another uid and cannot open the store even when this app shares cld.termux's uid, so git runs in cld.termux whenever it answers. */
    fun useExec(ctx: Context): Boolean = StorePaths.viaOwner(StoreAccess.mode(ctx))

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

    /** #1282 the guest path of a store folder (the bare root), for scripts that build paths under it. */
    fun guestOf(dir: File): String = guest(dir)

    /** #1282 a bare mirror at [guestDir] (BareClones.path in guest form); [depth] 0 = full history. Never touches a working clone. */
    fun bareClone(ctx: Context, url: String, guestDir: String, token: String, depth: Int): GitOpResult {
        val r = StoreExec(ctx).job("set -e; mkdir -p \"\$(dirname ${q(guestDir)})\"; " + authed(BareClones.cloneArgs(url, guestDir, depth) { q(it) }), stdin = token)
        return GitOpResult(r.ok, if (r.ok) "bare mirror at $guestDir" else "bare clone in cld.termux failed (exit ${r.exit})", (r.out + "\n" + r.err).trim().takeLast(4000))
    }

    /** #1282 `git remote update --prune` of an existing mirror: the one-bare rule's update instead of a second clone. */
    fun bareUpdate(ctx: Context, guestDir: String, token: String): GitOpResult {
        val r = StoreExec(ctx).job("set -e; " + authed(BareClones.updateArgs(guestDir) { q(it) }), stdin = token)
        return GitOpResult(r.ok, if (r.ok) "bare mirror updated at $guestDir" else "bare update in cld.termux failed (exit ${r.exit})", (r.out + "\n" + r.err).trim().takeLast(4000))
    }

    /** #1313 the stale temp pack pass inside cld.termux (StalePackCleaner.script); null when the guest could not run it. */
    fun sweepStalePacks(ctx: Context, gitRoot: File, bareRoot: String, patterns: List<String>, minAgeHours: Int): StalePackCleaner.Result? = runCatching {
        val r = StoreExec(ctx).run(StalePackCleaner.script(guest(gitRoot), bareRoot, patterns, minAgeHours) { q(it) }, timeoutMs = 120_000)
        if (r.ok) StalePackCleaner.parse(r.out) else null
    }.getOrNull()

    /** #1282 every mirror under the bare root (two levels: <owner>/<repo>.git) with its origin, read in the guest. */
    fun listBare(ctx: Context, bareRoot: File): List<BareClones.Mirror> = runCatching {
        val g = guest(bareRoot)
        val r = StoreExec(ctx).run("for d in ${q(g)}/*/*.git; do [ -d \"\$d\" ] || continue; printf '%s\\t%s\\n' \"\$d\" \"\$(git --git-dir=\"\$d\" config remote.origin.url 2>/dev/null)\"; done", timeoutMs = 30_000)
        if (!r.ok) emptyList() else r.out.lineSequence().filter { it.isNotBlank() }.map { BareClones.Mirror(it.substringBefore('\t'), it.substringAfter('\t', "")) }.toList()
    }.getOrDefault(emptyList())

    /** `git rev-parse` in the guest: is [dir] a repository (EXEC mode's GitEngine.isRepository). */
    fun isRepository(ctx: Context, dir: File): Boolean =
        runCatching { StoreExec(ctx).run("git -C ${q(guest(dir))} rev-parse --git-dir >/dev/null 2>&1", timeoutMs = 30_000).ok }.getOrDefault(false)
}
