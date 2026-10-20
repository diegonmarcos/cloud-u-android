package com.diegonmarcos.cloudlib.gitsync

import android.content.Context
import java.io.File

/**
 * The Android side of [GitEngine]'s construction (#871 move 11): the engine is found through a Context
 * ([GitLink] binds Cloud-Lib-Git-Sync-Engine.apk), and [GitEngine] itself stays free of android.* so the
 * JVM suite runs it against a fake transport. These keep every call site reading as it always did, with
 * the Context as the new first argument.
 */
fun GitEngine(context: Context, workTree: File): GitEngine = GitEngine(GitLink(context), workTree)

fun GitEngine.Companion.init(context: Context, dir: File): GitEngine = init(GitLink(context), dir)

fun GitEngine.Companion.clone(context: Context, url: String, dir: File, auth: GitAuth = GitAuth.None, depth: Int = 0): GitEngine =
    clone(GitLink(context), url, dir, auth, depth)
