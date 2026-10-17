package com.diegonmarcos.clouddrive

import android.os.Environment
import com.diegonmarcos.cloudlib.sharedstore.StoreAccess
import java.io.File

/**
 * THE fleet store, cloud-shared-store (owner decision 2026-10-17, replacing #575's
 * <shared storage>/CloudDrive). It lives in cld.termux's private home
 * (`/data/user/<u>/cld.termux/files/home/cloud-shared-store`, ext4: exec, fast stats, real
 * symlinks) and this app reaches it DIRECTLY because it runs as the same Linux uid
 * (android:sharedUserId cld.termux.shared, one signing key). When that is not in effect
 * (StoreAccess.mode == EXEC) the File calls below fail and git runs inside cld.termux
 * instead (StoreGit). Every path is derived from libs:shared-store, which reads the ONE
 * declaration (ac_cloud-termux/build.json::shared_store + ::storage); nothing here writes
 * an absolute path down.
 */
object SharedStore {

    /** The store's root; created on first use when this app may write there (DIRECT). */
    fun root(): File = StoreAccess.root().apply { if (parentFile?.canWrite() == true) mkdirs() }

    /**
     * #606/#608 `<root>/<git_subdir>` — the ONE folder of the store that holds clones
     * (ac_cloud-termux/build.json::storage.git_subdir → BuildConfig.GIT_SUBDIR).
     */
    fun gitRoot(): File = File(root(), BuildConfig.GIT_SUBDIR).apply { if (root().canWrite()) mkdirs() }

    /** Where a repository named [name] lives: `<root>/<git_subdir>/<name>`. */
    fun repoDir(name: String): File = File(gitRoot(), name)

    /**
     * The LEGACY mirror (<shared storage>/<storage.shared_root>, "CloudDrive"): what Gallery and
     * Files can see, the export mirror's target and the migration drop. Not where anything syncs.
     */
    fun legacyRoot(): File = File(Environment.getExternalStorageDirectory(), BuildConfig.SHARED_ROOT)

    /**
     * An rclone-style path as the phone runs it: `remote:dir` passes through; an absolute path
     * that names the old location is rewritten to the store (JobPathMigration's rule); a
     * relative local path is a leg of the store.
     */
    fun resolve(path: String): String = when {
        path.isBlank() || path.contains(':') -> path
        path.startsWith("/") -> StoreAccess.paths.migrateLegacy(path, StoreAccess.userId()) ?: path
        else -> File(root(), path).absolutePath
    }
}
