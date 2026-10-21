package com.diegonmarcos.cloudlib.sharedstore

/**
 * cloud-shared-store, the pure half (no Android, unit-tested on a plain JVM).
 *
 * The store is `<owner's data dir>/files/home/<dir>`: `/data/user/<u>/<owner>/files/home/<dir>`.
 * Inside cld.termux's glibc root the same directory is `/root/<dir>` ($HOME is bound as /root).
 * Never an absolute path written down anywhere else: the user id changes under a second profile.
 */
class StorePaths(
    private val ownerPackage: String,
    private val dir: String,
    private val legacySharedRoot: String,
    private val compatLink: String,
) {
    /** The store on the host, for Android user [userId] (Process.myUid() / 100000). */
    fun hostRoot(userId: Int): String = "/data/user/$userId/$ownerPackage/files/home/$dir"

    /** [hostPath] (inside [hostRoot]) as cld.termux's guest shell sees it, or null if it is outside the store. */
    fun guestPath(hostPath: String, userId: Int): String? {
        val root = hostRoot(userId)
        val alt = "/data/data/$ownerPackage/files/home/$dir"
        val rel = when {
            hostPath == root || hostPath == alt -> return "/root/$dir"
            hostPath.startsWith("$root/") -> hostPath.removePrefix("$root/")
            hostPath.startsWith("$alt/") -> hostPath.removePrefix("$alt/")
            else -> return null
        }
        if (rel.split('/').any { it == ".." }) return null
        return "/root/$dir/$rel"
    }

    /**
     * The first-launch job-path migration: an old location rewritten to the store, or null when
     * [path] does not name one (idempotent: a path already in the store is returned unchanged
     * as null, so running the migration twice changes nothing the second time).
     *
     * Old locations: `<any shared storage view>/<legacySharedRoot>[/...]` and
     * `<anything>/<compatLink>[/...]` (the old terminal link name).
     */
    fun migrateLegacy(path: String, userId: Int): String? {
        val root = hostRoot(userId)
        val p = path.trimEnd('/')
        if (p == root || p.startsWith("$root/")) return null
        val views = listOf(
            "/storage/emulated/$userId", "/sdcard", "/storage/self/primary", "/data/media/$userId",
            "/mnt/pass_through/0/emulated/$userId", "/mnt/androidwritable/0/emulated/$userId",
        )
        for (v in views) {
            val old = "$v/$legacySharedRoot"
            if (p == old) return root
            if (p.startsWith("$old/")) return root + "/" + p.removePrefix("$old/")
        }
        val marker = "/$compatLink"
        val i = p.indexOf(marker)
        if (i >= 0) {
            val after = p.substring(i + marker.length)
            if (after.isEmpty()) return root
            if (after.startsWith("/")) return root + after
        }
        if (p == "~/$compatLink" || p.startsWith("~/$compatLink/")) return root + p.removePrefix("~/$compatLink")
        return null
    }

    /** True when [path] still names an old location (what the "no old path" checks ask). */
    fun isLegacy(path: String, userId: Int): Boolean = migrateLegacy(path, userId) != null

    /**
     * Where an ENGINE APK (rclone, git: their own uid, All-files access to shared storage only) can
     * meet the store's content: a scratch folder in the legacy shared root. The store itself is
     * cld.termux's private home, which no engine can open, whatever this app's own [Mode] is.
     */
    fun stagingRoot(userId: Int): String = "/storage/emulated/$userId/$legacySharedRoot/.store-stage"

    /** True when [path] is the store or inside it (no `..` escapes). */
    fun inStore(path: String, userId: Int): Boolean {
        val root = hostRoot(userId)
        val p = path.trimEnd('/')
        return (p == root || p.startsWith("$root/")) && p.split('/').none { it == ".." }
    }

    /** The staging twin of a store path (same relative tail, one folder per [tag]), or null outside the store. */
    fun stagePath(path: String, userId: Int, tag: String): String? {
        if (!inStore(path, userId)) return null
        val rel = path.trimEnd('/').removePrefix(hostRoot(userId)).trimStart('/')
        return stagingRoot(userId) + "/" + tag + (if (rel.isEmpty()) "" else "/$rel")
    }

    enum class Mode { DIRECT, EXEC, NONE }

    companion object {
        /**
         * An engine APK (JGit, rclone) runs in ITS OWN uid, so it cannot open the store even when
         * this app joined the shared uid (DIRECT): everything an engine would do on the store goes
         * through cld.termux (CLOUD_EXEC) whenever cld.termux answers at all.
         */
        fun viaOwner(mode: Mode): Boolean = mode != Mode.NONE

        /**
         * The runtime decision: DIRECT only when the shared uid is really in effect (our uid IS
         * the owner's uid) AND the store directory answers; EXEC when the owner is installed and
         * we may bind CLOUD_EXEC; else NONE. Pure so both answers are unit-tested.
         */
        fun decide(myUid: Int, ownerUid: Int?, storeAccessible: Boolean, execGranted: Boolean): Mode = when {
            ownerUid != null && ownerUid == myUid && storeAccessible -> Mode.DIRECT
            ownerUid != null && execGranted -> Mode.EXEC
            else -> Mode.NONE
        }
    }
}
