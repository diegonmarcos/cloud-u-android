package com.diegonmarcos.clouddrive

import android.os.Environment
import java.io.File

/**
 * #575 THE ONE on-phone repo store, resolved at runtime from the ONE declaration
 * (build.json::storage.shared_root → BuildConfig.SHARED_ROOT). Plain shared
 * storage, so every fleet app that holds all-files access reads and writes what
 * lives here exactly as it reads /storage/emulated/0 — no Android/data sandbox,
 * no per-app clone. The absolute path is never written down anywhere: it is
 * derived here from the device's own shared-storage directory.
 */
object SharedStore {

    /** `<shared storage>/<shared_root>`, created on first use when the app may write there. */
    fun root(): File = File(Environment.getExternalStorageDirectory(), BuildConfig.SHARED_ROOT).apply { mkdirs() }

    /**
     * #606/#608 `<root>/<git_subdir>` — the ONE folder of the store that holds clones, from
     * the ONE declaration (build.json::storage.git_subdir → BuildConfig.GIT_SUBDIR). Plain
     * shared storage like the root itself, never an exec volume and never app-private: the
     * point of the store is that every fleet app with all-files access reads it.
     */
    fun gitRoot(): File = File(root(), BuildConfig.GIT_SUBDIR).apply { mkdirs() }

    /** Where a repository named [name] lives: `<root>/<git_subdir>/<name>`. */
    fun repoDir(name: String): File = File(gitRoot(), name)

    /**
     * An rclone-style path as the phone runs it: `remote:dir` and absolute paths
     * pass through; a relative local path is a leg of the shared store.
     */
    fun resolve(path: String): String =
        if (path.isBlank() || path.startsWith("/") || path.contains(':')) path
        else File(root(), path).absolutePath
}
