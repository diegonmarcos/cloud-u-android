package com.diegonmarcos.cloudlib.sharedstore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.io.File

/**
 * cloud-shared-store's store-access layer: the ONE place a fleet app asks how it reaches the
 * store. DIRECT = this app runs as cld.termux's uid (android:sharedUserId cld.termux.shared,
 * same signing key) and the directory answers, so plain java.io.File works at native speed.
 * EXEC = the shared uid is NOT in effect (an older install, a debug build signed differently,
 * an exit-hatched install on API 33+): git/rclone run INSIDE cld.termux through CLOUD_EXEC
 * ([StoreExec]). NONE = cld.termux is absent or refuses us.
 */
object StoreAccess {

    val paths = StorePaths(BuildConfig.OWNER_PACKAGE, BuildConfig.STORE_DIR, BuildConfig.LEGACY_SHARED_ROOT, BuildConfig.COMPAT_LINK)

    fun userId(): Int = Process.myUid() / 100000

    /** The store's host path (never created here unless DIRECT). */
    fun root(): File = File(paths.hostRoot(userId()))

    fun gitRoot(): File = File(root(), BuildConfig.GIT_SUBDIR)

    fun ownerUid(ctx: Context): Int? = runCatching {
        ctx.packageManager.getApplicationInfo(BuildConfig.OWNER_PACKAGE, 0).uid
    }.getOrNull()

    fun mode(ctx: Context): StorePaths.Mode {
        val dir = root()
        val accessible = dir.isDirectory && dir.canRead() && dir.canWrite()
        val exec = ctx.checkSelfPermission("${BuildConfig.OWNER_PACKAGE}.permission.CLOUD_EXEC") == PackageManager.PERMISSION_GRANTED
        return StorePaths.decide(Process.myUid(), ownerUid(ctx), accessible, exec)
    }

    /** One line for /api/health and the Files hint. */
    fun describe(ctx: Context): String = "${mode(ctx).name.lowercase()} ${root().absolutePath}"
}
