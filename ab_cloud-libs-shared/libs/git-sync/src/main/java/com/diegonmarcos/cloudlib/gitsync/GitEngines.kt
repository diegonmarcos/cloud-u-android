package com.diegonmarcos.cloudlib.gitsync

import android.content.Context
import java.io.File

/**
 * The Android side of [GitEngine]'s construction (#871 move 11): the engine is found through a Context
 * ([GitLink] binds Cloud-Lib-Git-Sync-Engine.apk), and [GitEngine] itself stays free of android.* so the
 * JVM suite runs it against a fake transport. These keep every call site reading as it always did, with
 * the Context as the new first argument.
 */
/**
 * Where a repository's git calls go. The default is the engine APK ([GitLink]); the host app may route a
 * directory elsewhere (Cloud Drive sends repos in the shared store, which lives in cld.termux's private home
 * and which the engine's uid cannot open, through cld.termux). One seam for every screen and worker.
 */
object GitTransports {
    /** Null = use the engine APK for this directory. */
    @Volatile var router: ((Context, File) -> GitTransport?)? = null

    fun forDir(context: Context, dir: File): GitTransport = router?.invoke(context, dir) ?: GitLink(context)
}

fun GitEngine(context: Context, workTree: File): GitEngine = GitEngine(GitTransports.forDir(context, workTree), workTree)

fun GitEngine.Companion.init(context: Context, dir: File): GitEngine = init(GitTransports.forDir(context, dir), dir)

fun GitEngine.Companion.clone(context: Context, url: String, dir: File, auth: GitAuth = GitAuth.None, depth: Int = 0): GitEngine =
    clone(GitTransports.forDir(context, dir), url, dir, auth, depth)
